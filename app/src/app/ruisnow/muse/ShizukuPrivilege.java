package app.ruisnow.muse;

import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Shizuku channel: shell-uid (2000) command execution for devices without root.
 * The user installs the Shizuku app, starts its service (via wireless debugging
 * or root), and grants this app permission once in Shizuku's dialog.
 */
public class ShizukuPrivilege {
    private static final int MAX_OUT = 64 * 1024;
    public static final int REQUEST_CODE = 4242;

    /** unavailable (Shizuku not running) | denied | granted. Never throws. */
    public static String state() {
        try {
            if (!Shizuku.pingBinder()) return "unavailable";
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    ? "granted" : "denied";
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    public static boolean ready() {
        return "granted".equals(state());
    }

    /** Ask Shizuku to show its permission dialog (user taps Allow in Shizuku). */
    public static boolean request() {
        try {
            if (!Shizuku.pingBinder()) return false;
            if (ready()) return true;
            Shizuku.requestPermission(REQUEST_CODE);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Process newProcess(String cmd) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess",
                String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, new Object[]{
                new String[]{"sh", "-c", cmd}, null, null});
    }

    public static JSONObject exec(String cmd, int timeoutSec) throws Exception {
        if (!ready()) throw new Exception("shizuku_not_granted: " + state());
        final Process p = newProcess(cmd);
        final StringBuilder outBuf = new StringBuilder();
        final StringBuilder errBuf = new StringBuilder();
        Thread tOut = gobble(p.getInputStream(), outBuf);
        Thread tErr = gobble(p.getErrorStream(), errBuf);
        // NOTE: ShizukuRemoteProcess.waitFor(timeout, unit) polls exitValue()
        // across the binder and races ("process hasn't exited" leaks through).
        // Use the blocking waitFor() on our own thread and time it with join().
        final int[] exitBox = new int[1];
        final Throwable[] errBox = new Throwable[1];
        Thread waiter = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    exitBox[0] = p.waitFor();
                } catch (Throwable t) {
                    errBox[0] = t;
                }
            }
        });
        waiter.setDaemon(true);
        waiter.start();
        waiter.join(timeoutSec * 1000L);
        if (waiter.isAlive()) {
            p.destroyForcibly();
            throw new Exception("shell_timeout_" + timeoutSec + "s");
        }
        if (errBox[0] != null) throw new Exception("wait_failed: " + errBox[0].getMessage());
        tOut.join(1000);
        tErr.join(1000);
        JSONObject o = new JSONObject();
        o.put("exit", exitBox[0]);
        o.put("stdout", cap(outBuf));
        o.put("stderr", cap(errBuf));
        o.put("via", "shizuku");
        return o;
    }

    /** Fire-and-forget detached spawn through Shizuku (for the vscreen core). */
    public static void spawn(String cmd) throws Exception {
        if (!ready()) throw new Exception("shizuku_not_granted: " + state());
        Process p = newProcess(cmd);
        p.getInputStream().close();
        p.getOutputStream().close();
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
