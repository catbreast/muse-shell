package app.ruisnow.muse;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Runs commands through the active privilege channel: root via su, or Shizuku
 * (shell uid) on devices without root. Channel mode is injected per call by
 * ToolDispatcher from Prefs: auto | su | shizuku. Env sanitized, output capped.
 */
public class ShellRunner {
    private static final int MAX_OUT = 64 * 1024;
    private static Boolean rootOk;
    private static long rootCheckedAt = 0;
    /** Why the last root probe failed (exit code / stderr / exception). For status. */
    public static volatile String rootError = "";
    public static volatile String mode = "auto";

    public static boolean checkRoot() {
        // A positive result is cached for the process lifetime; a negative one
        // only for 20s — grants can appear later (manager toggle, reboot) and a
        // permanently cached "no root" once stranded the shell for a whole night.
        long now = System.currentTimeMillis();
        if (rootOk != null && (rootOk || now - rootCheckedAt < 20000)) return rootOk;
        rootCheckedAt = now;
        try {
            JSONObject r = execRaw("id", 8);
            int exit = r.optInt("exit", -1);
            String out = r.optString("stdout", "");
            rootOk = exit == 0 && out.contains("uid=0");
            if (rootOk) {
                rootError = "";
            } else {
                String err = r.optString("stderr", "").trim();
                rootError = "exit=" + exit + " stdout=" + abbrev(out.trim())
                        + (err.length() > 0 ? " stderr=" + abbrev(err) : "");
            }
        } catch (Exception e) {
            rootOk = Boolean.FALSE;
            rootError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return rootOk;
    }

    private static String abbrev(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    /** The channel exec() would use right now: su | shizuku | none. */
    public static String activeChannel() {
        if ("su".equals(mode)) return checkRoot() ? "su" : "none";
        if ("shizuku".equals(mode)) return ShizukuPrivilege.ready() ? "shizuku" : "none";
        if (checkRoot()) return "su";
        if (ShizukuPrivilege.ready()) return "shizuku";
        return "none";
    }

    public static JSONObject exec(String cmd, int timeoutSec) throws Exception {
        String ch = activeChannel();
        if ("su".equals(ch)) {
            JSONObject o = execRaw(cmd, timeoutSec);
            o.put("via", "su");
            return o;
        }
        if ("shizuku".equals(ch)) return ShizukuPrivilege.exec(cmd, timeoutSec);
        throw new Exception("no_privilege_channel: root 与 Shizuku 均不可用（mode=" + mode
                + "，shizuku=" + ShizukuPrivilege.state() + "）");
    }

    /** Fire-and-forget detached spawn (for the vscreen server). */
    public static void spawnDetached(String cmd) throws Exception {
        String ch = activeChannel();
        if ("shizuku".equals(ch)) {
            ShizukuPrivilege.spawn(cmd);
            return;
        }
        if (!"su".equals(ch)) throw new Exception("no_privilege_channel");
        ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
        sanitize(pb);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().close();
        p.getOutputStream().close();
    }

    private static void sanitize(ProcessBuilder pb) {
        pb.environment().remove("LD_LIBRARY_PATH");
        pb.environment().remove("LD_PRELOAD");
        pb.environment().remove("LD_DEBUG");
    }

    private static JSONObject execRaw(String cmd, int timeoutSec) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
        sanitize(pb);
        final Process p = pb.start();
        final StringBuilder outBuf = new StringBuilder();
        final StringBuilder errBuf = new StringBuilder();
        Thread tOut = gobble(p.getInputStream(), outBuf);
        Thread tErr = gobble(p.getErrorStream(), errBuf);
        boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new Exception("shell_timeout_" + timeoutSec + "s");
        }
        tOut.join(1000);
        tErr.join(1000);
        JSONObject o = new JSONObject();
        o.put("exit", p.exitValue());
        o.put("stdout", cap(outBuf));
        o.put("stderr", cap(errBuf));
        return o;
    }

    private static String cap(StringBuilder sb) {
        synchronized (sb) {
            String s = sb.toString();
            if (s.length() > MAX_OUT) return s.substring(0, MAX_OUT) + "\n...(truncated)";
            return s;
        }
    }

    private static Thread gobble(final InputStream is, final StringBuilder sb) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    char[] buf = new char[4096];
                    int n;
                    while ((n = r.read(buf)) > 0) {
                        synchronized (sb) {
                            if (sb.length() < MAX_OUT * 2) sb.append(buf, 0, n);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}
