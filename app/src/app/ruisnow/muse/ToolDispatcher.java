package app.ruisnow.muse;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Comparator;

public class ToolDispatcher {
    private final Context ctx;
    private static final int MAX_READ = 256 * 1024;

    public ToolDispatcher(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public JSONObject dispatch(JSONObject cmd) {
        String id = cmd.optString("id", "");
        String tool = cmd.optString("tool", "");
        JSONObject args = cmd.optJSONObject("args");
        if (args == null) args = new JSONObject();
        JSONObject res = new JSONObject();
        try {
            ShellRunner.mode = Prefs.sp(ctx).getString("privilege_mode", "auto");
            shellTrustSession = Prefs.shellTrust(ctx);
            res.put("id", id);
            if (Prefs.killSwitch(ctx) && !tool.equals("ping") && !tool.equals("status")) {
                res.put("ok", false);
                res.put("error", "kill_switch_on");
                logCall(tool, args, false, "kill_switch_on");
                return res;
            }
            JSONObject data = run(tool, args, id);
            res.put("ok", true);
            res.put("result", data);
            logCall(tool, args, true, null);
        } catch (Exception e) {
            try {
                res.put("id", id);
                res.put("ok", false);
                res.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            } catch (Exception ignored) {
            }
            logCall(tool, args, false, e.getMessage());
        }
        return res;
    }

    private void logCall(String tool, JSONObject args, boolean ok, String err) {
        try {
            JSONObject d = new JSONObject();
            d.put("tool", tool);
            d.put("args", sanitizeArgs(args));
            d.put("ok", ok);
            if (err != null) d.put("error", err);
            Ledger.log(ctx, "tool_call", d);
        } catch (Exception ignored) {
        }
    }

    /** Mask secrets in logged args: anything typed via `input text` (e.g. a PIN)
     *  must never land in the ledger in plaintext. Returns a sanitized copy. */
    private static JSONObject sanitizeArgs(JSONObject args) {
        try {
            JSONObject copy = new JSONObject(args.toString());
            sanitizeObj(copy);
            return copy;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void sanitizeObj(JSONObject o) throws Exception {
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.get(k);
            if (v instanceof String) {
                o.put(k, ((String) v).replaceAll("(?i)input\\s+text\\s+\\S+", "input text ***"));
            } else if (v instanceof JSONObject) {
                sanitizeObj((JSONObject) v);
            } else if (v instanceof JSONArray) {
                JSONArray arr = (JSONArray) v;
                for (int i = 0; i < arr.length(); i++) {
                    Object e = arr.get(i);
                    if (e instanceof JSONObject) sanitizeObj((JSONObject) e);
                    else if (e instanceof String) {
                        arr.put(i, ((String) e).replaceAll("(?i)input\\s+text\\s+\\S+", "input text ***"));
                    }
                }
            }
        }
    }

    private JSONObject run(String tool, JSONObject a, String cmdId) throws Exception {
        if (tool.equals("ping")) {
            JSONObject o = new JSONObject();
            o.put("pong", true);
            o.put("time", System.currentTimeMillis());
            return o;
        }
        if (tool.equals("status")) return status();
        if (tool.equals("files.list")) return filesList(a);
        if (tool.equals("files.read")) return filesRead(a);
        if (tool.equals("files.write")) return filesWrite(a, cmdId);
        if (tool.equals("files.mkdir")) return filesMkdir(a);
        if (tool.equals("files.delete")) return filesDelete(a, cmdId);
        if (tool.equals("a11y.status")) {
            JSONObject o = new JSONObject();
            o.put("enabled", ShellA11yService.enabled());
            return o;
        }
        if (tool.equals("a11y.tree")) {
            ShellA11yService s = needA11y();
            return s.dumpTree(a.optInt("maxNodes", 400));
        }
        if (tool.equals("a11y.nodes")) {
            ShellA11yService s = needA11y();
            return s.dumpNodes(a.optInt("maxNodes", 600));
        }
        if (tool.equals("a11y.tap")) {
            ShellA11yService s = needA11y();
            if (a.has("text")) return s.tapByText(a.getString("text"));
            if (a.has("fx") && a.has("fy")) return s.tapFrac(a.getDouble("fx"), a.getDouble("fy"));
            if (a.has("x") && a.has("y")) return s.tapXY(a.getInt("x"), a.getInt("y"));
            throw new Exception("a11y.tap needs text, fx/fy or x/y");
        }
        if (tool.equals("a11y.type")) {
            ShellA11yService s = needA11y();
            return s.typeText(a.getString("text"));
        }
        if (tool.equals("a11y.scroll")) {
            ShellA11yService s = needA11y();
            return s.scroll(a.optString("dir", "down"));
        }
        if (tool.equals("a11y.back")) { ShellA11yService s = needA11y(); return bool("performed", s.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)); }
        if (tool.equals("a11y.home")) { ShellA11yService s = needA11y(); return bool("performed", s.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)); }
        if (tool.equals("a11y.see")) {
            ShellA11yService s = needA11y();
            int part = a.optInt("part", 0);
            if (part == 0) {
                shotCache = s.screenshotBase64(a.optInt("maxBytes", 24000));
                shotCacheW = s.shotW;
                shotCacheH = s.shotH;
            }
            if (shotCache == null) throw new Exception("no_cached_frame: 先以 part=0 取图");
            JSONObject o = sliceShot(shotCache, part);
            o.put("imgWidth", shotCacheW);
            o.put("imgHeight", shotCacheH);
            return o;
        }
        if (tool.equals("notify")) return notify(a);
        if (tool.equals("shell")) return shellTool(a, cmdId);
        if (tool.equals("lock.unlock")) return lockUnlock();
        if (tool.equals("shell.check")) return privilegeStatus();
        if (tool.equals("privilege.status")) return privilegeStatus();
        if (tool.equals("privilege.mode")) {
            if (a.has("mode")) {
                String m = a.getString("mode");
                if (!m.equals("auto") && !m.equals("su") && !m.equals("shizuku")) {
                    throw new Exception("bad_mode: " + m);
                }
                Prefs.sp(ctx).edit().putString("privilege_mode", m).apply();
                ShellRunner.mode = m;
            }
            return privilegeStatus();
        }
        if (tool.equals("privilege.request")) {
            JSONObject o = new JSONObject();
            o.put("requested", ShizukuPrivilege.request());
            o.put("shizuku", ShizukuPrivilege.state());
            return o;
        }
        if (tool.equals("android.launch")) return androidLaunch(a);
        if (tool.equals("android.packages")) return androidPackages();
        if (tool.equals("android.force_stop")) return androidSimple(a, cmdId, "am force-stop ", "强制停止应用", true);
        if (tool.equals("android.clear")) return androidSimple(a, cmdId, "pm clear ", "清除应用数据", true);
        if (tool.equals("android.uninstall")) return androidSimple(a, cmdId, "pm uninstall ", "卸载应用", true);
        if (tool.equals("android.install")) return androidInstall(a, cmdId);
        if (tool.equals("android.setting")) return androidSetting(a, cmdId);
        if (tool.equals("android.input")) return androidInput(a);
        if (tool.equals("browser.open")) return browserOpen(a);
        if (tool.equals("browser.snapshot")) return browserSnapshot(a);
        if (tool.equals("browser.find")) return browserFind(a);
        if (tool.equals("browser.click")) return browserClick(a, cmdId);
        if (tool.equals("browser.type")) return browserType(a, cmdId);
        if (tool.equals("browser.read")) return browserRead(a);
        if (tool.equals("browser.batch")) return browserBatch(a);
        if (tool.equals("vscreen.create")) return VScreenManager.get(ctx).create(a);
        if (tool.equals("vscreen.status")) return VScreenManager.get(ctx).status();
        if (tool.equals("vscreen.launch")) return VScreenManager.get(ctx).launch(a);
        if (tool.equals("vscreen.see")) return vscreenSee(a);
        if (tool.equals("linux.status")) return linuxStatus();
        if (tool.equals("linux.exec")) return linuxExec(a);
        if (tool.equals("vscreen.tap")) return VScreenManager.get(ctx).tap(a);
        if (tool.equals("vscreen.swipe")) return VScreenManager.get(ctx).swipe(a);
        if (tool.equals("vscreen.key")) return VScreenManager.get(ctx).key(a);
        if (tool.equals("vscreen.close")) return VScreenManager.get(ctx).closeDisplay();
        if (tool.equals("vscreen.preview")) return VScreenManager.get(ctx).preview(a);
        throw new Exception("unknown_tool: " + tool);
    }

    // ---- 截图切片（0.6.6）：中继通道对大响应随机掐流（今晚 >约5KB 即常断），
    // 帧在手机侧缓存、按 3400 字符切片返回，调用方拼回完整 base64。
    private static String shotCache;
    private static int shotCacheW, shotCacheH;
    private static String frameCache;
    private static JSONObject frameCacheMeta;

    private JSONObject sliceShot(String b64, int part) throws Exception {
        final int CH = 3400;
        int parts = (b64.length() + CH - 1) / CH;
        if (part < 0 || part >= parts) throw new Exception("bad_part: " + part + "/" + parts);
        int from = part * CH;
        int to = Math.min(b64.length(), from + CH);
        JSONObject o = new JSONObject();
        o.put("part", part);
        o.put("parts", parts);
        o.put("data", b64.substring(from, to));
        return o;
    }

    private JSONObject vscreenSee(JSONObject a) throws Exception {
        int part = a.optInt("part", 0);
        if (part == 0) {
            JSONObject full = VScreenManager.get(ctx).see(a);
            frameCache = full.optString("jpeg_base64", "");
            full.remove("jpeg_base64");
            frameCacheMeta = full;
        }
        if (frameCache == null || frameCacheMeta == null) {
            throw new Exception("no_cached_frame: 先以 part=0 取帧");
        }
        JSONObject o = sliceShot(frameCache, part);
        JSONArray names = frameCacheMeta.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String k = names.getString(i);
                o.put(k, frameCacheMeta.get(k));
            }
        }
        return o;
    }

    // ---- Linux 容器（0.6.7）：/data/local/muse-linux 下的 Debian rootfs，
    // enter.sh 负责建私有 mount/ipc/uts 命名空间、挂 proc/sys/dev 后 chroot。
    // 部署脚本与 enter.sh 的源文件在工程 server/ 目录，真机副本由部署时推送。
    private static final String LINUX_ENTER = "/data/local/muse-linux/enter.sh";

    private JSONObject linuxStatus() throws Exception {
        JSONObject r = ShellRunner.exec(
            "B=/data/local/muse-linux; if [ -d $B/rootfs/etc ]; then echo INSTALLED; "
            + "grep PRETTY_NAME $B/rootfs/etc/os-release; echo -n 'MB '; du -sm $B/rootfs | cut -f1; "
            + "test -f $B/enter.sh && echo ENTER_OK; else echo NOT_INSTALLED; fi", 30);
        String out = r.optString("stdout", "");
        JSONObject o = new JSONObject();
        o.put("installed", out.contains("INSTALLED") && !out.contains("NOT_INSTALLED"));
        o.put("detail", out.trim());
        return o;
    }

    private JSONObject linuxExec(JSONObject a) throws Exception {
        String cmd = a.getString("cmd");
        int timeout = Math.min(Math.max(a.optInt("timeout", 60), 1), 600);
        JSONObject r = ShellRunner.exec(
            "/system/bin/sh " + LINUX_ENTER + " /bin/bash -lc " + q(cmd), timeout);
        // 中继通道怕大响应：输出在工具侧再封顶，长输出应让命令自己写文件后分段读
        for (String k : new String[]{"stdout", "stderr"}) {
            String v = r.optString(k, "");
            if (v.length() > 4500) r.put(k, v.substring(0, 4500) + "\n…[truncated]");
        }
        return r;
    }

    private JSONObject browserOpen(JSONObject a) throws Exception {
        needBrowser();
        return BrowserHost.get(ctx).open(a.getString("url"));
    }

    private JSONObject browserSnapshot(JSONObject a) throws Exception {
        needBrowser();
        return BrowserHost.get(ctx).snapshot(a.optBoolean("text", false), a.optInt("offset", 0));
    }

    private JSONObject browserFind(JSONObject a) throws Exception {
        needBrowser();
        return BrowserHost.get(ctx).find(a.optString("q", ""), a.optString("role", ""));
    }

    private JSONObject browserClick(JSONObject a, String cmdId) throws Exception {
        needBrowser();
        // 用户 2026-10-22 定：浏览器点击/输入默认允许、不弹审批卡
        // （频繁弹窗会被 ColorOS 判恶意弹窗；控制台开关与急停仍可全拦）。
        return BrowserHost.get(ctx).click(a.getString("ref"));
    }

    private JSONObject browserType(JSONObject a, String cmdId) throws Exception {
        needBrowser();
        return BrowserHost.get(ctx).type(a.getString("ref"), a.getString("text"));
    }

    private JSONObject browserRead(JSONObject a) throws Exception {
        needBrowser();
        return BrowserHost.get(ctx).read(a.optString("ref", ""));
    }

    private JSONObject browserBatch(JSONObject a) throws Exception {
        needBrowser();
        // 与 click/type 同口径（2026-10-22 用户定）：默认允许、不弹审批卡
        return BrowserHost.get(ctx).batch(a);
    }

    private void needBrowser() throws Exception {
        if (!Prefs.browserEnabled(ctx)) {
            throw new Exception("browser_disabled: 请在控制台开启「AI 浏览器」");
        }
    }

    private JSONObject bool(String k, boolean v) throws Exception {
        JSONObject o = new JSONObject();
        o.put(k, v);
        return o;
    }

    private ShellA11yService needA11y() throws Exception {
        ShellA11yService s = ShellA11yService.instance;
        if (s == null) throw new Exception("a11y_not_enabled: 请在系统设置-无障碍里开启「Muse 壳屏幕助手」");
        return s;
    }

    private JSONObject privilegeStatus() throws Exception {
        JSONObject o = new JSONObject();
        o.put("mode", ShellRunner.mode);
        boolean root = ShellRunner.checkRoot();
        o.put("root", root);
        if (!root && ShellRunner.rootError.length() > 0) o.put("root_error", ShellRunner.rootError);
        o.put("shizuku", ShizukuPrivilege.state());
        o.put("active", ShellRunner.activeChannel());
        return o;
    }

    private JSONObject status() throws Exception {
        JSONObject o = new JSONObject();
        o.put("app", "muse-shell");
        String ver = "unknown";
        try {
            ver = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        o.put("version", ver);
        o.put("privilege", privilegeStatus());
        o.put("android", Build.VERSION.RELEASE);
        o.put("sdk", Build.VERSION.SDK_INT);
        o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        o.put("a11y_enabled", ShellA11yService.enabled());
        o.put("external_manager", Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager());
        o.put("kill_switch", Prefs.killSwitch(ctx));
        o.put("ledger_dir", String.valueOf(Ledger.dir(ctx)));
        o.put("time", System.currentTimeMillis());
        return o;
    }

    // ---------- files ----------

    private File guard(String path) throws Exception {
        File f = new File(path);
        String canon = f.getCanonicalPath();
        String sd = Environment.getExternalStorageDirectory().getCanonicalPath();
        String own = ctx.getFilesDir().getCanonicalPath();
        String ownExt = ctx.getExternalFilesDir(null) == null ? "/nonexistent" : ctx.getExternalFilesDir(null).getCanonicalPath();
        if (canon.startsWith(sd) || canon.startsWith(own) || canon.startsWith(ownExt)) return new File(canon);
        throw new Exception("path_not_allowed: " + canon);
    }

    private JSONObject filesList(JSONObject a) throws Exception {
        File dir = guard(a.getString("path"));
        if (!dir.isDirectory()) throw new Exception("not_a_directory: " + dir);
        File[] items = dir.listFiles();
        JSONArray arr = new JSONArray();
        if (items != null) {
            Arrays.sort(items, new Comparator<File>() {
                @Override public int compare(File x, File y) { return x.getName().compareTo(y.getName()); }
            });
            for (File f : items) {
                JSONObject e = new JSONObject();
                e.put("name", f.getName());
                e.put("type", f.isDirectory() ? "dir" : "file");
                e.put("size", f.length());
                e.put("mtime", f.lastModified());
                arr.put(e);
            }
        }
        JSONObject o = new JSONObject();
        o.put("path", dir.getPath());
        o.put("entries", arr);
        return o;
    }

    private JSONObject filesRead(JSONObject a) throws Exception {
        File f = guard(a.getString("path"));
        if (!f.isFile()) throw new Exception("not_a_file: " + f);
        int max = a.optInt("maxBytes", MAX_READ);
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[(int) Math.min(f.length() + 1, (long) max + 1)];
        int n = 0, r;
        while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
        in.close();
        JSONObject o = new JSONObject();
        o.put("path", f.getPath());
        o.put("size", f.length());
        o.put("truncated", f.length() > n);
        o.put("text", new String(buf, 0, n, "UTF-8"));
        return o;
    }

    private JSONObject filesWrite(JSONObject a, String cmdId) throws Exception {
        File f = guard(a.getString("path"));
        String text = a.getString("text");
        boolean append = a.optBoolean("append", false);
        if (f.exists() && !append) {
            String home = new File(Environment.getExternalStorageDirectory(), "MuseShell").getCanonicalPath();
            if (!f.getCanonicalPath().startsWith(home)) {
                boolean ok = ApprovalManager.request(ctx, cmdId,
                        "覆盖已有文件：\n" + f.getPath() + "\n（" + text.length() + " 字符）");
                if (!ok) throw new Exception("approval_denied");
            }
        }
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileOutputStream out = new FileOutputStream(f, append);
        out.write(text.getBytes("UTF-8"));
        out.close();
        JSONObject o = new JSONObject();
        o.put("path", f.getPath());
        o.put("size", f.length());
        o.put("written", true);
        return o;
    }

    private JSONObject filesMkdir(JSONObject a) throws Exception {
        File f = guard(a.getString("path"));
        boolean made = f.mkdirs();
        JSONObject o = new JSONObject();
        o.put("path", f.getPath());
        o.put("exists", f.isDirectory());
        o.put("created", made);
        return o;
    }

    private JSONObject filesDelete(JSONObject a, String cmdId) throws Exception {
        File f = guard(a.getString("path"));
        if (!f.exists()) throw new Exception("not_found: " + f);
        boolean ok = ApprovalManager.request(ctx, cmdId,
                "删除" + (f.isDirectory() ? "目录" : "文件") + "：\n" + f.getPath());
        if (!ok) throw new Exception("approval_denied");
        deleteRec(f);
        JSONObject o = new JSONObject();
        o.put("path", f.getPath());
        o.put("deleted", !f.exists());
        return o;
    }

    private void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        f.delete();
    }

  
// ---------- notify ----------

    private JSONObject notify(JSONObject a) throws Exception {
        String title = a.optString("title", "Muse");
        String body = a.optString("body", "");
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new android.app.NotificationChannel(
                    "muse_ai", "Muse 通知", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, "muse_ai")
                : new Notification.Builder(ctx);
        Notification n = b.setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setSmallIcon(R.drawable.ic_stat_muse)
                .setAutoCancel(true)
                .build();
        nm.notify((int) (System.currentTimeMillis() % 100000), n);
        JSONObject o = new JSONObject();
        o.put("posted", true);
        return o;
    }

    // ---------- shell (root) ----------

    static volatile boolean shellTrustSession = false;

    /**
     * Unlock the device with the PIN stored on-device (Prefs "unlock_pin",
     * set once by the user in the console). The call carries no arguments and
     * the PIN never appears in any command string that reaches the ledger:
     * digits are injected as key events by an internal shell run whose command
     * is not a tool call. Deliberately no per-call approval (an approval card
     * cannot be tapped on the lock screen); the kill switch still gates this
     * tool in dispatch(). One attempt only — no retry, to respect Gatekeeper
     * throttling on wrong PINs.
     */
    private JSONObject lockUnlock() throws Exception {
        String pin = Prefs.sp(ctx).getString("unlock_pin", "");
        if (pin == null || !pin.matches("\\d{4,8}")) throw new Exception("unlock_pin_not_set");
        KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        JSONObject o = new JSONObject();
        if (km != null && !km.isKeyguardLocked()) {
            o.put("unlocked", false);
            o.put("already", true);
            return o;
        }
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int x = dm.widthPixels / 2;
        int y1 = (int) (dm.heightPixels * 0.85);
        int y2 = (int) (dm.heightPixels * 0.35);
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < pin.length(); i++) {
            if (i > 0) digits.append("; ");
            digits.append("input keyevent ").append(7 + (pin.charAt(i) - '0'));
        }
        String cmd = "input keyevent 224; sleep 1; "
                + "input swipe " + x + " " + y1 + " " + x + " " + y2 + " 200; sleep 1; "
                + digits + "; sleep 1; input keyevent 66; sleep 2";
        ShellRunner.exec(cmd, 30);
        boolean locked = km != null && km.isKeyguardLocked();
        o.put("unlocked", !locked);
        return o;
    }

    private JSONObject shellTool(JSONObject a, String cmdId) throws Exception {
        String cmd = a.getString("cmd");
        int timeout = Math.min(Math.max(a.optInt("timeout", 30), 1), 180);
        if (!shellTrustSession) {
            String shown = cmd.length() > 400 ? cmd.substring(0, 400) + "…" : cmd;
            boolean ok = ApprovalManager.request(ctx, cmdId, "以 root 执行 shell 命令：\n" + shown);
            if (!ok) throw new Exception("approval_denied");
        }
        return ShellRunner.exec(cmd, timeout);
    }

    private String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void checkPkg(String pkg) throws Exception {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_.]+")) throw new Exception("bad_package_name");
    }

    private void approve(String cmdId, String desc) throws Exception {
        if (!ApprovalManager.request(ctx, cmdId, desc)) throw new Exception("approval_denied");
    }

    // ---------- android structured ops ----------

    private JSONObject androidLaunch(JSONObject a) throws Exception {
        String pkg = a.getString("package");
        checkPkg(pkg);
        return ShellRunner.exec("monkey -p " + q(pkg) + " -c android.intent.category.LAUNCHER 1", 25);
    }

    private JSONObject androidPackages() throws Exception {
        JSONObject r = ShellRunner.exec("pm list packages -3", 20);
        String[] lines = r.optString("stdout", "").split("\n");
        JSONArray arr = new JSONArray();
        for (String ln : lines) {
            ln = ln.trim();
            if (ln.startsWith("package:")) arr.put(ln.substring(8));
        }
        JSONObject o = new JSONObject();
        o.put("count", arr.length());
        o.put("packages", arr);
        return o;
    }

    private JSONObject androidSimple(JSONObject a, String cmdId, String prefix, String label, boolean needApproval) throws Exception {
        String pkg = a.getString("package");
        checkPkg(pkg);
        if (needApproval) approve(cmdId, label + "：\n" + pkg);
        return ShellRunner.exec(prefix + q(pkg), 30);
    }

    private JSONObject androidInstall(JSONObject a, String cmdId) throws Exception {
        File f = guard(a.getString("path"));
        if (!f.isFile()) throw new Exception("not_a_file: " + f);
        approve(cmdId, "安装应用：\n" + f.getPath());
        return ShellRunner.exec("pm install -r " + q(f.getPath()), 120);
    }

    private JSONObject androidSetting(JSONObject a, String cmdId) throws Exception {
        String action = a.optString("action", "get");
        String ns = a.getString("ns");
        if (!ns.equals("system") && !ns.equals("secure") && !ns.equals("global")) throw new Exception("bad_ns");
        String key = a.getString("key");
        if (!key.matches("[A-Za-z0-9_.]+")) throw new Exception("bad_key");
        JSONObject old = ShellRunner.exec("settings get " + ns + " " + q(key), 15);
        String oldVal = old.optString("stdout", "").trim();
        JSONObject o = new JSONObject();
        o.put("ns", ns);
        o.put("key", key);
        o.put("oldValue", oldVal);
        if (action.equals("get")) return o;
        String value = a.getString("value");
        approve(cmdId, "修改系统设置：\n" + ns + " " + key + "\n" + oldVal + " → " + value);
        JSONObject r = ShellRunner.exec("settings put " + ns + " " + q(key) + " " + q(value), 15);
        JSONObject after = ShellRunner.exec("settings get " + ns + " " + q(key), 15);
        o.put("newValue", after.optString("stdout", "").trim());
        o.put("exit", r.optInt("exit", -1));
        return o;
    }

    private JSONObject androidInput(JSONObject a) throws Exception {
        String kind = a.getString("kind");
        String cmd;
        if (kind.equals("tap")) {
            cmd = "input tap " + a.getInt("x") + " " + a.getInt("y");
        } else if (kind.equals("swipe")) {
            cmd = "input swipe " + a.getInt("x1") + " " + a.getInt("y1") + " "
                    + a.getInt("x2") + " " + a.getInt("y2") + " " + a.optInt("ms", 300);
        } else if (kind.equals("key")) {
            int code = a.getInt("code");
            cmd = "input keyevent " + code;
        } else if (kind.equals("text")) {
            String t = a.getString("text").replace(" ", "%s");
            cmd = "input text " + q(t);
        } else {
            throw new Exception("bad_input_kind");
        }
        return ShellRunner.exec(cmd, 15);
    }
}
