package app.ruisnow.muse;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

/**
 * Client for the privileged vscreen core (separate dex run via app_process as root).
 * The core does the actual VirtualDisplay work; this class only deploys its dex,
 * (re)starts it when its build fingerprint is stale, and forwards JSON calls.
 */
public class VScreenCore {
    static final String EXPECTED_BUILD = "muse-core-1";
    private static final int PORT = 8399;
    private static final String CORE_MAIN = "app.ruisnow.vscreen.CoreMain";

    private final Context ctx;

    public VScreenCore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private String token() {
        String t = Prefs.sp(ctx).getString("core_token", null);
        if (t == null) {
            t = UUID.randomUUID().toString();
            Prefs.sp(ctx).edit().putString("core_token", t).apply();
        }
        return t;
    }

    private File dexFile() {
        // Must live somewhere the shell uid can read too (Shizuku channel):
        // the app-private files dir is off limits to uid 2000.
        return new File("/sdcard/MuseShell", "core.dex");
    }

    private void deployDex() throws Exception {
        File dst = dexFile();
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        InputStream in = ctx.getAssets().open("core.dex");
        FileOutputStream out = new FileOutputStream(dst, false);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
        dst.setReadable(true, false);
    }

    public JSONObject call(String path, JSONObject body) throws Exception {
        HttpUtil.Resp r = HttpUtil.request("POST", "http://127.0.0.1:" + PORT + path,
                token(), null, body == null ? "{}" : body.toString(), 3000, 20000);
        JSONObject o = new JSONObject(r.body);
        if (r.code != 200 || !o.optBoolean("ok", false)) {
            throw new Exception("core" + path + ": " + o.optString("error", "http_" + r.code));
        }
        return o;
    }

    /** Status without throwing; null when the core is not reachable. */
    public JSONObject probe() {
        try {
            HttpUtil.Resp r = HttpUtil.request("POST", "http://127.0.0.1:" + PORT + "/status",
                    token(), null, "{}", 1500, 3000);
            if (r.code != 200) return null;
            return new JSONObject(r.body);
        } catch (Exception e) {
            return null;
        }
    }

    /** Make sure a core with the expected build is running. */
    public synchronized JSONObject ensure() throws Exception {
        JSONObject st = probe();
        if (st != null && EXPECTED_BUILD.equals(st.optString("build"))) return st;
        deployDex();
        ShellRunner.exec("pkill -f " + CORE_MAIN + " 2>/dev/null; sleep 1", 10);
        String cmd = "setsid env CLASSPATH='" + dexFile().getAbsolutePath()
                + "' app_process /system/bin " + CORE_MAIN
                + " --port " + PORT + " --token " + token() + " --dir /sdcard/MuseShell"
                + " >>/sdcard/MuseShell/vscreen-core.out 2>&1 </dev/null &";
        ShellRunner.exec(cmd, 10);
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(300);
            st = probe();
            if (st != null && EXPECTED_BUILD.equals(st.optString("build"))) return st;
        }
        throw new Exception("core_start_timeout: 见 /sdcard/MuseShell/vscreen-core.out");
    }
}
