package app.ruisnow.vscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.MessageDigest;

/**
 * Muse Shell virtual-screen core. Runs as a privileged app_process (root via su),
 * so the VirtualDisplay it creates can actually host other apps — an app-uid
 * MediaProjection display cannot. Speaks tiny JSON-over-HTTP on 127.0.0.1.
 * If the owning app stops polling (60s without any request) the core destroys
 * the display and exits, so no orphan screens survive.
 */
public class CoreMain {
    static final String BUILD = "muse-core-1";
    private static final long HEARTBEAT_TIMEOUT_MS = 60000L;

    private static int sPort = 8399;
    private static String sToken = "";
    private static String sDir = "/sdcard/MuseShell";
    private static volatile long sLastReqAt = System.currentTimeMillis();

    private static Context sCtx;
    private static VirtualDisplay sDisp;
    private static ImageReader sReader;
    private static HandlerThread sFrameThread;
    private static int sDisplayId = -1;
    private static int sW, sH;
    private static volatile byte[] sLastJpeg;
    private static volatile String sLastHash;
    private static volatile long sLastFrameAt;

    private static Object sInputManager;
    private static Method sInject;

    public static void main(String[] args) {
        Looper.prepareMainLooper();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i].equals("--port")) sPort = Integer.parseInt(args[i + 1]);
            else if (args[i].equals("--token")) sToken = args[i + 1];
            else if (args[i].equals("--dir")) sDir = args[i + 1];
        }
        log("core starting build=" + BUILD + " port=" + sPort + " uid=" + android.os.Process.myUid());
        try {
            sCtx = FakeContext.get();
        } catch (Throwable t) {
            log("FakeContext failed: " + t);
            return;
        }
        startWatchdog();
        startServer();
        log("core ready");
        Looper.loop();
    }

    private static void log(String msg) {
        String line = System.currentTimeMillis() + " " + msg + "\n";
        try {
            FileOutputStream f = new FileOutputStream(sDir + "/vscreen-core.log", true);
            f.write(line.getBytes("UTF-8"));
            f.close();
        } catch (Exception ignored) {
        }
        System.err.print(line);
    }

    private static void startWatchdog() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                    if (System.currentTimeMillis() - sLastReqAt > HEARTBEAT_TIMEOUT_MS) {
                        log("heartbeat timeout, exiting");
                        releaseDisplay();
                        System.exit(0);
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ---------- HTTP ----------

    private static void startServer() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ServerSocket ss = new ServerSocket(sPort, 16, InetAddress.getByName("127.0.0.1"));
                    while (true) {
                        final Socket s = ss.accept();
                        Thread h = new Thread(new Runnable() {
                            @Override public void run() { handle(s); }
                        });
                        h.setDaemon(true);
                        h.start();
                    }
                } catch (Exception e) {
                    log("server failed: " + e);
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void handle(Socket s) {
        try {
            s.setSoTimeout(20000);
            InputStream in = s.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String reqLine = br.readLine();
            if (reqLine == null) { s.close(); return; }
            String[] parts = reqLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            String token = "";
            int len = 0;
            String line;
            while ((line = br.readLine()) != null && line.length() > 0) {
                int c = line.indexOf(':');
                if (c > 0) {
                    String k = line.substring(0, c).trim().toLowerCase();
                    String v = line.substring(c + 1).trim();
                    if (k.equals("x-token")) token = v;
                    if (k.equals("content-length")) len = Integer.parseInt(v);
                }
            }
            StringBuilder body = new StringBuilder();
            char[] buf = new char[Math.max(len, 0)];
            int read = 0;
            while (read < len) {
                int n = br.read(buf, read, len - read);
                if (n < 0) break;
                read += n;
            }
            if (len > 0) body.append(buf, 0, read);

            sLastReqAt = System.currentTimeMillis();
            JSONObject out;
            int code = 200;
            if (sToken.length() > 0 && !sToken.equals(token)) {
                out = err("bad_token");
                code = 401;
            } else {
                try {
                    JSONObject args = body.length() > 0 ? new JSONObject(body.toString()) : new JSONObject();
                    out = route(path, args);
                } catch (Exception e) {
                    out = err(String.valueOf(e.getMessage() == null ? e : e.getMessage()));
                    code = 500;
                }
            }
            out.put("build", BUILD);
            byte[] resp = out.toString().getBytes("UTF-8");
            OutputStream os = s.getOutputStream();
            String head = "HTTP/1.0 " + code + " OK\r\nContent-Type: application/json\r\nContent-Length: "
                    + resp.length + "\r\nConnection: close\r\n\r\n";
            os.write(head.getBytes("UTF-8"));
            os.write(resp);
            os.flush();
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static JSONObject err(String msg) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("error", msg);
        return o;
    }

    private static JSONObject route(String path, JSONObject a) throws Exception {
        if (path.equals("/status")) return status();
        if (path.equals("/create")) return create(a);
        if (path.equals("/close")) { releaseDisplay(); JSONObject o = new JSONObject(); o.put("ok", true); o.put("closed", true); return o; }
        if (path.equals("/launch")) return launch(a);
        if (path.equals("/see")) return see(a);
        if (path.equals("/tap")) return tap(a);
        if (path.equals("/swipe")) return swipe(a);
        if (path.equals("/key")) return key(a);
        return err("unknown_path: " + path);
    }

    // ---------- display ----------

    private static synchronized JSONObject status() throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("active", sDisp != null);
        o.put("displayId", sDisplayId);
        if (sDisp != null) {
            o.put("width", sW);
            o.put("height", sH);
            o.put("frameAgeMs", sLastFrameAt == 0 ? -1 : (System.currentTimeMillis() - sLastFrameAt));
            o.put("hasFrame", sLastJpeg != null);
        }
        return o;
    }

    private static int flag(String name, int fallback) {
        try {
            java.lang.reflect.Field f = DisplayManager.class.getField(name);
            return f.getInt(null);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static synchronized JSONObject create(JSONObject a) throws Exception {
        if (sDisp != null) {
            JSONObject o = status();
            o.put("already", true);
            return o;
        }
        boolean landscape = "landscape".equals(a.optString("orientation", "portrait"));
        sW = a.optInt("width", landscape ? 1792 : 1008);
        sH = a.optInt("height", landscape ? 1008 : 1792);
        int dpi = a.optInt("dpi", 320);

        sFrameThread = new HandlerThread("muse-core-frame");
        sFrameThread.start();
        Handler handler = new Handler(sFrameThread.getLooper());
        sReader = ImageReader.newInstance(sW, sH, PixelFormat.RGBA_8888, 2);
        sReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader r) { grabFrame(r); }
        }, handler);

        int flags = flag("VIRTUAL_DISPLAY_FLAG_PUBLIC", 1)
                | flag("VIRTUAL_DISPLAY_FLAG_PRESENTATION", 2)
                | flag("VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY", 8)
                | flag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH", 64)
                | flag("VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT", 128)
                | flag("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL", 256)
                | flag("VIRTUAL_DISPLAY_FLAG_TRUSTED", 1024)
                | flag("VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED", 4096);
        DisplayManager dm = (DisplayManager) sCtx.getSystemService(Context.DISPLAY_SERVICE);
        sDisp = dm.createVirtualDisplay("MuseScreen", sW, sH, dpi, sReader.getSurface(), flags, null, handler);
        if (sDisp == null) throw new Exception("createVirtualDisplay_null");
        sDisplayId = sDisp.getDisplay().getDisplayId();

        long deadline = System.currentTimeMillis() + 3000;
        while (sLastJpeg == null && System.currentTimeMillis() < deadline) Thread.sleep(100);

        JSONObject o = status();
        o.put("width", sW);
        o.put("height", sH);
        o.put("dpi", dpi);
        o.put("firstFrame", sLastJpeg != null);
        log("display created id=" + sDisplayId + " " + sW + "x" + sH);
        return o;
    }

    private static synchronized void releaseDisplay() {
        if (sDisp != null) { try { sDisp.release(); } catch (Exception ignored) {} sDisp = null; }
        if (sReader != null) { try { sReader.close(); } catch (Exception ignored) {} sReader = null; }
        if (sFrameThread != null) { sFrameThread.quitSafely(); sFrameThread = null; }
        sDisplayId = -1;
        sLastJpeg = null;
        sLastHash = null;
        sLastFrameAt = 0;
    }

    private static void grabFrame(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            byte[] jpeg = imageToJpeg(img, sW, sH);
            if (jpeg != null) {
                sLastJpeg = jpeg;
                sLastHash = sha256(jpeg);
                sLastFrameAt = System.currentTimeMillis();
            }
        } catch (Exception ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    private static byte[] imageToJpeg(Image img, int w, int h) {
        Image.Plane[] planes = img.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * w;
        Bitmap bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buffer);
        if (rowPadding > 0) {
            Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, w, h);
            bmp.recycle();
            bmp = cropped;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, bos);
        bmp.recycle();
        return bos.toByteArray();
    }

    static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- actions ----------

    private static void needDisplay() throws Exception {
        if (sDisp == null || sDisplayId < 0) throw new Exception("vscreen_not_created");
    }

    private static JSONObject see(JSONObject a) throws Exception {
        needDisplay();
        long deadline = System.currentTimeMillis() + 2500;
        while (sLastJpeg == null && System.currentTimeMillis() < deadline) Thread.sleep(100);
        JSONObject o = new JSONObject();
        if (sLastJpeg == null) {
            o.put("ok", false);
            o.put("errorCode", "no_frame");
            return o;
        }
        String prev = a.optString("lastHash", "");
        o.put("ok", true);
        o.put("displayId", sDisplayId);
        o.put("frameAgeMs", System.currentTimeMillis() - sLastFrameAt);
        o.put("hash", sLastHash);
        o.put("screenChanged", prev.length() == 0 || !prev.equals(sLastHash));
        o.put("jpeg_base64", Base64.encodeToString(sLastJpeg, Base64.NO_WRAP));
        return o;
    }

    private static String exec(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        InputStream is = p.getInputStream();
        InputStream es = p.getErrorStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        while ((n = es.read(buf)) > 0) bos.write(buf, 0, n);
        p.waitFor();
        return bos.toString("UTF-8");
    }

    private static JSONObject launch(JSONObject a) throws Exception {
        needDisplay();
        String pkg = a.getString("package");
        if (!pkg.matches("[A-Za-z0-9_.]+")) throw new Exception("bad_package_name");
        String res = exec("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER '" + pkg + "'");
        String comp = null;
        for (String ln : res.split("\n")) {
            ln = ln.trim();
            if (ln.contains("/")) comp = ln;
        }
        if (comp == null) throw new Exception("launcher_activity_not_found: " + pkg);
        String before = sLastHash;
        String outTxt = exec("am start --display " + sDisplayId + " -n '" + comp + "'");
        Thread.sleep(900);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("component", comp);
        o.put("displayId", sDisplayId);
        o.put("stdout", outTxt.trim());
        o.put("screenChanged", before == null ? (sLastHash != null) : !before.equals(sLastHash));
        return o;
    }

    private static void inject(InputEvent ev) throws Exception {
        if (sInputManager == null) {
            Method getService = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
            Object binder = getService.invoke(null, "input");
            Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
            sInputManager = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            sInject = sInputManager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
        }
        try {
            InputEvent.class.getMethod("setDisplayId", int.class).invoke(ev, sDisplayId);
        } catch (Exception ignored) {
        }
        sInject.invoke(sInputManager, ev, 0);
    }

    private static JSONObject tap(JSONObject a) throws Exception {
        needDisplay();
        float x = (float) a.getDouble("x");
        float y = (float) a.getDouble("y");
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        inject(d);
        d.recycle();
        MotionEvent u = MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x, y, 0);
        inject(u);
        u.recycle();
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("x", x);
        o.put("y", y);
        o.put("displayId", sDisplayId);
        return o;
    }

    private static JSONObject swipe(JSONObject a) throws Exception {
        needDisplay();
        float x1 = (float) a.getDouble("x1"), y1 = (float) a.getDouble("y1");
        float x2 = (float) a.getDouble("x2"), y2 = (float) a.getDouble("y2");
        long ms = a.optLong("ms", 300);
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x1, y1, 0);
        inject(d);
        d.recycle();
        int steps = Math.max(2, (int) (ms / 16));
        for (int i = 1; i < steps; i++) {
            float f = (float) i / steps;
            MotionEvent m = MotionEvent.obtain(down, down + (long) (ms * f), MotionEvent.ACTION_MOVE,
                    x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, 0);
            inject(m);
            m.recycle();
        }
        MotionEvent u = MotionEvent.obtain(down, down + ms, MotionEvent.ACTION_UP, x2, y2, 0);
        inject(u);
        u.recycle();
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("displayId", sDisplayId);
        return o;
    }

    private static JSONObject key(JSONObject a) throws Exception {
        needDisplay();
        int code;
        if (a.has("code")) {
            code = a.getInt("code");
        } else {
            String name = a.optString("name", "").toUpperCase();
            if (name.equals("BACK")) code = 4;
            else if (name.equals("HOME")) code = 3;
            else if (name.equals("ENTER")) code = 66;
            else if (name.equals("DEL")) code = 67;
            else if (name.equals("TAB")) code = 61;
            else if (name.equals("ESCAPE")) code = 111;
            else if (name.equals("APP_SWITCH")) code = 187;
            else throw new Exception("unknown_key_name");
        }
        long down = SystemClock.uptimeMillis();
        KeyEvent kd = new KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0);
        inject(kd);
        KeyEvent ku = new KeyEvent(down, down + 50, KeyEvent.ACTION_UP, code, 0);
        inject(ku);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("code", code);
        return o;
    }
}
