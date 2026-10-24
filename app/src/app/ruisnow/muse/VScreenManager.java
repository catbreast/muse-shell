package app.ruisnow.muse;

import android.content.Context;
import android.content.Intent;
import android.util.Base64;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;

/**
 * Virtual screen backed by the privileged core process (VScreenCore): the core
 * runs as root via app_process and owns the VirtualDisplay, so apps can really
 * live on it and no MediaProjection consent is ever needed. This class keeps the
 * old tool surface (create/status/launch/see/tap/swipe/key/close/preview) and a
 * frame pump that mirrors the core's latest frame for see() and the preview.
 */
public class VScreenManager {
    private static VScreenManager inst;

    public static synchronized VScreenManager get(Context ctx) {
        if (inst == null) inst = new VScreenManager(ctx.getApplicationContext());
        return inst;
    }

    /** Kept only so the now-unused VScreenConsentActivity still compiles. */
    static void onConsentResult(int resultCode, Intent data) {
    }

    private final Context ctx;
    private final VScreenCore core;
    private int displayId = -1;
    private int width, height, dpi = 320;
    private volatile byte[] lastJpeg;
    private volatile String lastHash;
    private volatile long lastFrameAt;
    private String lastSeeHash;
    private volatile boolean pumpOn;
    private Thread pumpThread;

    private VScreenManager(Context ctx) {
        this.ctx = ctx;
        this.core = new VScreenCore(ctx);
    }

    public synchronized JSONObject create(JSONObject a) throws Exception {
        if (displayId >= 0) {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("already", true);
            out.put("displayId", displayId);
            out.put("engine", "core");
            return out;
        }
        core.ensure();
        JSONObject req = new JSONObject();
        req.put("orientation", a.optString("orientation", "portrait"));
        if (a.has("width")) req.put("width", a.getInt("width"));
        if (a.has("height")) req.put("height", a.getInt("height"));
        req.put("dpi", a.optInt("dpi", 320));
        JSONObject r = core.call("/create", req);
        displayId = r.optInt("displayId", -1);
        width = r.optInt("width", 0);
        height = r.optInt("height", 0);
        dpi = r.optInt("dpi", 320);
        startPump();
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("engine", "core");
        out.put("displayId", displayId);
        out.put("width", width);
        out.put("height", height);
        out.put("dpi", dpi);
        out.put("firstFrame", r.optBoolean("firstFrame", false));
        Ledger.log(ctx, "vscreen_create", out);
        startPreview();
        return out;
    }

    public synchronized JSONObject status() throws Exception {
        JSONObject o = new JSONObject();
        o.put("engine", "core");
        o.put("coreRunning", core.probe() != null);
        o.put("active", displayId >= 0);
        o.put("displayId", displayId);
        if (displayId >= 0) {
            o.put("width", width);
            o.put("height", height);
            o.put("dpi", dpi);
            o.put("frameAgeMs", lastFrameAt == 0 ? -1 : (System.currentTimeMillis() - lastFrameAt));
            o.put("hasFrame", lastJpeg != null);
        }
        return o;
    }

    public synchronized JSONObject closeDisplay() throws Exception {
        if (displayId >= 0) {
            try { core.call("/close", new JSONObject()); } catch (Exception ignored) {}
        }
        stopPump();
        stopPreview();
        displayId = -1;
        lastJpeg = null;
        lastHash = null;
        lastSeeHash = null;
        JSONObject o = new JSONObject();
        o.put("closed", true);
        Ledger.log(ctx, "vscreen_close", o);
        return o;
    }

    private void needDisplay() throws Exception {
        if (displayId < 0) throw new Exception("vscreen_not_created");
    }

    public JSONObject launch(JSONObject a) throws Exception {
        needDisplay();
        JSONObject req = new JSONObject();
        req.put("package", a.getString("package"));
        JSONObject r = core.call("/launch", req);
        JSONObject o = new JSONObject();
        o.put("component", r.optString("component", ""));
        o.put("displayId", displayId);
        o.put("stdout", r.optString("stdout", ""));
        o.put("screenChanged", r.optBoolean("screenChanged", false));
        return o;
    }

    public JSONObject see() throws Exception {
        return see(null);
    }

    /**
     * 中继通道对大响应随机掐流（实测 >约35KB 经常 IncompleteRead）：
     * see 支持 maxBytes 预算（默认 16000 原始字节），按阶梯缩放 JPEG 到预算内，
     * 并回 imgWidth/imgHeight/scale 供调用方把图上坐标映射回屏幕坐标。
     */
    public JSONObject see(JSONObject a) throws Exception {
        needDisplay();
        long deadline = System.currentTimeMillis() + 2500;
        while (lastJpeg == null && System.currentTimeMillis() < deadline) Thread.sleep(100);
        JSONObject o = new JSONObject();
        if (lastJpeg == null) {
            o.put("ok", false);
            o.put("errorCode", "no_frame");
            return o;
        }
        boolean changed = lastSeeHash == null || !lastSeeHash.equals(lastHash);
        lastSeeHash = lastHash;
        int maxBytes = a == null ? 16000 : a.optInt("maxBytes", 16000);
        byte[] out = lastJpeg;
        int imgW = width, imgH = height;
        double scale = 1.0;
        if (maxBytes > 0 && lastJpeg.length > maxBytes) {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(lastJpeg, 0, lastJpeg.length);
            if (bmp != null) {
                double[] steps = {0.7, 0.5, 0.35, 0.25, 0.18};
                for (double s : steps) {
                    int w = Math.max(1, (int) (bmp.getWidth() * s));
                    int h = Math.max(1, (int) (bmp.getHeight() * s));
                    android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true);
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 65, bos);
                    out = bos.toByteArray();
                    imgW = w;
                    imgH = h;
                    scale = s;
                    if (small != bmp) small.recycle();
                    if (out.length <= maxBytes) break;
                }
                bmp.recycle();
            }
        }
        o.put("ok", true);
        o.put("displayId", displayId);
        o.put("frameAgeMs", System.currentTimeMillis() - lastFrameAt);
        o.put("screenChanged", changed);
        o.put("screenWidth", width);
        o.put("screenHeight", height);
        o.put("imgWidth", imgW);
        o.put("imgHeight", imgH);
        o.put("scale", scale);
        o.put("jpeg_base64", Base64.encodeToString(out, Base64.NO_WRAP));
        return o;
    }

    public JSONObject tap(JSONObject a) throws Exception {
        needDisplay();
        JSONObject req = new JSONObject();
        req.put("x", a.getInt("x"));
        req.put("y", a.getInt("y"));
        core.call("/tap", req);
        JSONObject o = new JSONObject();
        o.put("x", a.getInt("x"));
        o.put("y", a.getInt("y"));
        o.put("displayId", displayId);
        return o;
    }

    public JSONObject swipe(JSONObject a) throws Exception {
        needDisplay();
        JSONObject req = new JSONObject();
        req.put("x1", a.getInt("x1"));
        req.put("y1", a.getInt("y1"));
        req.put("x2", a.getInt("x2"));
        req.put("y2", a.getInt("y2"));
        req.put("ms", a.optInt("ms", 300));
        core.call("/swipe", req);
        JSONObject o = new JSONObject();
        o.put("displayId", displayId);
        return o;
    }

    public JSONObject key(JSONObject a) throws Exception {
        needDisplay();
        JSONObject req = new JSONObject();
        if (a.has("code")) req.put("code", a.getInt("code"));
        if (a.has("name")) req.put("name", a.getString("name"));
        JSONObject r = core.call("/key", req);
        JSONObject o = new JSONObject();
        o.put("code", r.optInt("code", -1));
        return o;
    }

    // ---------- frame pump (core -> local cache for see/preview) ----------

    private synchronized void startPump() {
        if (pumpOn) return;
        pumpOn = true;
        pumpThread = new Thread(new Runnable() {
            @Override public void run() {
                while (pumpOn) {
                    try {
                        JSONObject req = new JSONObject();
                        req.put("lastHash", lastHash == null ? "" : lastHash);
                        JSONObject r = core.call("/see", req);
                        if (r.optBoolean("screenChanged", true) && r.has("jpeg_base64")) {
                            lastJpeg = Base64.decode(r.getString("jpeg_base64"), Base64.DEFAULT);
                            lastHash = r.optString("hash", VScreenManager.sha256(lastJpeg));
                            lastFrameAt = System.currentTimeMillis();
                        }
                    } catch (Exception ignored) {
                    }
                    try { Thread.sleep(350); } catch (InterruptedException e) { return; }
                }
            }
        });
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    private synchronized void stopPump() {
        pumpOn = false;
        if (pumpThread != null) { pumpThread.interrupt(); pumpThread = null; }
    }

    static String sha256(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- floating preview ----------

    private android.view.WindowManager previewWm;
    private android.widget.ImageView previewView;
    private volatile boolean previewOn;
    private Thread previewThread;

    public JSONObject preview(JSONObject a) throws Exception {
        boolean on = a.optBoolean("on", true);
        if (on) startPreview();
        else stopPreview();
        JSONObject o = new JSONObject();
        o.put("preview", previewOn);
        return o;
    }

    synchronized void startPreview() {
        if (previewOn || displayId < 0) return;
        if (android.os.Build.VERSION.SDK_INT >= 23
                && !android.provider.Settings.canDrawOverlays(ctx)) return;
        try {
            previewWm = (android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            previewView = new android.widget.ImageView(ctx);
            previewView.setBackgroundColor(0xAA000000);
            previewView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            int pw = 420, ph = 746;
            final android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                    pw, ph,
                    android.os.Build.VERSION.SDK_INT >= 26
                            ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : android.view.WindowManager.LayoutParams.TYPE_PHONE,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
            lp.x = 16;
            lp.y = 420;
            previewWm.addView(previewView, lp);
            previewOn = true;
            final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            previewThread = new Thread(new Runnable() {
                @Override public void run() {
                    while (previewOn) {
                        final byte[] frame = lastJpeg;
                        if (frame != null) {
                            main.post(new Runnable() {
                                @Override public void run() {
                                    if (previewView != null) {
                                        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(frame, 0, frame.length);
                                        if (b != null) previewView.setImageBitmap(b);
                                    }
                                }
                            });
                        }
                        try { Thread.sleep(800); } catch (InterruptedException e) { return; }
                    }
                }
            });
            previewThread.setDaemon(true);
            previewThread.start();
        } catch (Exception ignored) {
            previewOn = false;
        }
    }

    synchronized void stopPreview() {
        previewOn = false;
        if (previewThread != null) { previewThread.interrupt(); previewThread = null; }
        if (previewWm != null && previewView != null) {
            try { previewWm.removeView(previewView); } catch (Exception ignored) {}
            previewView = null;
        }
    }

    // referenced by the consent stub so old imports stay harmless
    @SuppressWarnings("unused")
    private static CountDownLatch unusedLatch;
}
