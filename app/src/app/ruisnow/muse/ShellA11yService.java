package app.ruisnow.muse;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.accessibilityservice.AccessibilityServiceInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ShellA11yService extends AccessibilityService {
    public static volatile ShellA11yService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        // Android 14+ 允许无障碍服务被动观察触摸事件（不拦截、不影响原应用），
        // 用于「按住屏幕 0.5 秒打开 Muse」。低版本无此能力，静默跳过。
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                AccessibilityServiceInfo info = getServiceInfo();
                if (info != null) {
                    info.setMotionEventSources(InputDevice.SOURCE_TOUCHSCREEN);
                    setServiceInfo(info);
                }
            } catch (Exception ignored) {}
        }
    }

    // ---------- 屏幕长按 0.5 秒打开 Muse ----------
    // 状态机：DOWN 记起点并排 500ms 任务；MOVE 超过 24dp 视为滑动取消；
    // UP/CANCEL/第二指按下取消；任务触发时手指仍按住原地 → 启动 aura，一次按下只触发一次。

    private final Handler pressHandler = new Handler(Looper.getMainLooper());
    private float pressX, pressY;
    private boolean pressFired;
    private final Runnable pressLaunch = new Runnable() {
        @Override
        public void run() {
            pressFired = true;
            try {
                Intent open = getPackageManager().getLaunchIntentForPackage("com.facebook.aura");
                if (open != null) {
                    open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(open);
                }
            } catch (Exception ignored) {}
        }
    };

    private void cancelPress() {
        pressHandler.removeCallbacks(pressLaunch);
    }

    @Override
    public void onMotionEvent(MotionEvent event) {
        if (!Prefs.longPressMuse(this) || Prefs.killSwitch(this)) {
            cancelPress();
            return;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressX = event.getRawX();
                pressY = event.getRawY();
                pressFired = false;
                cancelPress();
                pressHandler.postDelayed(pressLaunch, 500);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!pressFired) {
                    float slop = 24f * getResources().getDisplayMetrics().density;
                    if (Math.abs(event.getRawX() - pressX) > slop
                            || Math.abs(event.getRawY() - pressY) > slop) {
                        cancelPress();
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelPress();
                break;
            default:
                break;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        cancelPress();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean enabled() {
        return instance != null;
    }

    private AccessibilityNodeInfo root() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        return r;
    }

    // ---------- tree dump ----------

    public JSONObject dumpTree(int maxNodes) throws Exception {
        JSONObject out = new JSONObject();
        AccessibilityNodeInfo r = root();
        if (r == null) {
            out.put("ok", false);
            out.put("error", "no_active_window");
            return out;
        }
        StringBuilder sb = new StringBuilder();
        int[] count = new int[]{0};
        dumpNode(r, 0, sb, count, maxNodes);
        out.put("ok", true);
        out.put("package", String.valueOf(r.getPackageName()));
        out.put("nodes", count[0]);
        out.put("truncated", count[0] >= maxNodes);
        out.put("tree", sb.toString());
        return out;
    }

    private void dumpNode(AccessibilityNodeInfo n, int depth, StringBuilder sb, int[] count, int max) {
        if (n == null || count[0] >= max) return;
        count[0]++;
        for (int i = 0; i < depth; i++) sb.append("  ");
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        sb.append("[").append(shortClass(n.getClassName())).append("]");
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) sb.append(" text=\"").append(esc(t)).append("\"");
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 0) sb.append(" desc=\"").append(esc(d)).append("\"");
        if (n.getViewIdResourceName() != null) sb.append(" id=").append(n.getViewIdResourceName());
        if (n.isClickable()) sb.append(" clickable");
        if (n.isEditable()) sb.append(" editable");
        if (n.isScrollable()) sb.append(" scrollable");
        if (n.isFocused()) sb.append(" focused");
        sb.append(" bounds=[").append(b.left).append(",").append(b.top).append(",")
          .append(b.right).append(",").append(b.bottom).append("]\n");
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            if (ch != null) {
                dumpNode(ch, depth + 1, sb, count, max);
                ch.recycle();
            }
        }
    }

    private String shortClass(CharSequence cs) {
        if (cs == null) return "?";
        String s = cs.toString();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    private String esc(CharSequence cs) {
        return cs.toString().replace("\"", "'").replace("\n", " ");
    }

    // ---------- find helpers ----------

    private AccessibilityNodeInfo findByText(AccessibilityNodeInfo n, String text, boolean contains) {
        if (n == null) return null;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        boolean hit = (t != null && (contains ? t.toString().contains(text) : t.toString().equals(text)))
                || (d != null && (contains ? d.toString().contains(text) : d.toString().equals(text)));
        if (hit) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            AccessibilityNodeInfo found = findByText(ch, text, contains);
            if (found != null) return found;
            if (ch != null) ch.recycle();
        }
        return null;
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        while (cur != null) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    // ---------- screen key / post-action verification ----------

    /** Cheap fingerprint of the visible tree: package + every node's text/desc/bounds. */
    public String screenKey() {
        try {
            AccessibilityNodeInfo r = root();
            if (r == null) return "none";
            StringBuilder sb = new StringBuilder();
            sb.append(String.valueOf(r.getPackageName()));
            collectKey(r, sb, new int[]{0});
            return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
        } catch (Exception e) {
            return "err";
        }
    }

    private void collectKey(AccessibilityNodeInfo n, StringBuilder sb, int[] count) {
        if (n == null || count[0] > 1500) return;
        count[0]++;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        if (t != null) sb.append(t);
        if (d != null) sb.append(d);
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        sb.append(b.left).append(',').append(b.top).append(',').append(b.right).append(',').append(b.bottom).append(';');
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            if (ch != null) {
                collectKey(ch, sb, count);
                ch.recycle();
            }
        }
    }

    /** First visible label on screen (rough page title). */
    public String firstLabel() {
        try {
            AccessibilityNodeInfo r = root();
            if (r == null) return "";
            String s = firstLabel(r, new int[]{0});
            return s == null ? "" : s;
        } catch (Exception e) {
            return "";
        }
    }

    private String firstLabel(AccessibilityNodeInfo n, int[] budget) {
        if (n == null || budget[0]++ > 800) return null;
        CharSequence t = n.getText();
        if (t != null && t.toString().trim().length() > 0) return t.toString().trim();
        CharSequence d = n.getContentDescription();
        if (d != null && d.toString().trim().length() > 0) return d.toString().trim();
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            String s = ch == null ? null : firstLabel(ch, budget);
            if (ch != null) ch.recycle();
            if (s != null) return s;
        }
        return null;
    }

    /** Wait briefly, re-fingerprint, and tell the caller whether the tap actually changed the page. */
    private void enrichAfter(JSONObject out, String preKey) {
        try {
            Thread.sleep(450);
            String postKey = screenKey();
            out.put("pageChanged", !postKey.equals(preKey));
            out.put("newTitle", firstLabel());
        } catch (Exception ignored) {
        }
    }

    // ---------- flat nodes ----------

    public JSONObject dumpNodes(int maxNodes) throws Exception {
        JSONObject out = new JSONObject();
        AccessibilityNodeInfo r = root();
        if (r == null) {
            out.put("ok", false);
            out.put("error", "no_active_window");
            return out;
        }
        JSONArray arr = new JSONArray();
        int[] count = new int[]{0};
        collectNodes(r, 0, arr, count, maxNodes);
        out.put("ok", true);
        out.put("package", String.valueOf(r.getPackageName()));
        out.put("count", count[0]);
        out.put("truncated", count[0] >= maxNodes);
        out.put("nodes", arr);
        return out;
    }

    private void collectNodes(AccessibilityNodeInfo n, int depth, JSONArray arr, int[] count, int max) throws Exception {
        if (n == null || count[0] >= max) return;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        JSONObject o = new JSONObject();
        o.put("index", count[0]);
        o.put("text", n.getText() == null ? "" : n.getText().toString());
        o.put("desc", n.getContentDescription() == null ? "" : n.getContentDescription().toString());
        o.put("cls", shortClass(n.getClassName()));
        o.put("cx", b.centerX());
        o.put("cy", b.centerY());
        o.put("w", b.width());
        o.put("h", b.height());
        o.put("clickable", n.isClickable());
        o.put("editable", n.isEditable());
        o.put("scrollable", n.isScrollable());
        o.put("depth", depth);
        arr.put(o);
        count[0]++;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            if (ch != null) {
                collectNodes(ch, depth + 1, arr, count, max);
                ch.recycle();
            }
        }
    }

    // ---------- actions ----------

    public JSONObject tapByText(String text) throws Exception {
        JSONObject out = new JSONObject();
        String preKey = screenKey();
        AccessibilityNodeInfo r = root();
        if (r == null) { out.put("ok", false); out.put("error", "no_active_window"); return out; }
        AccessibilityNodeInfo node = findByText(r, text, true);
        if (node == null) { out.put("ok", false); out.put("error", "text_not_found: " + text); return out; }
        String matched = node.getText() != null ? node.getText().toString()
                : (node.getContentDescription() != null ? node.getContentDescription().toString() : "");
        AccessibilityNodeInfo target = clickableAncestor(node);
        boolean viaNode = false;
        if (target != null) {
            viaNode = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        boolean viaGesture = false;
        if (!viaNode) {
            viaGesture = gestureTap(b.centerX(), b.centerY());
        }
        out.put("ok", viaNode || viaGesture);
        out.put("via", viaNode ? "node" : "gesture");
        out.put("matchedText", matched);
        out.put("bounds", b.left + "," + b.top + "," + b.right + "," + b.bottom);
        enrichAfter(out, preKey);
        return out;
    }

    public JSONObject tapXY(int x, int y) throws Exception {
        JSONObject out = new JSONObject();
        String preKey = screenKey();
        boolean ok = gestureTap(x, y);
        out.put("ok", ok);
        out.put("via", "gesture");
        out.put("x", x);
        out.put("y", y);
        enrichAfter(out, preKey);
        return out;
    }

    /** Fraction coordinates (0..1 of real display size): immune to screenshot scaling. */
    public JSONObject tapFrac(double fx, double fy) throws Exception {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        android.view.WindowManager wm = (android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        int x = (int) Math.round(fx * dm.widthPixels);
        int y = (int) Math.round(fy * dm.heightPixels);
        JSONObject out = tapXY(x, y);
        out.put("fx", fx);
        out.put("fy", fy);
        out.put("screenW", dm.widthPixels);
        out.put("screenH", dm.heightPixels);
        return out;
    }

    private boolean gestureTap(int x, int y) throws Exception {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 80))
                .build();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Boolean> res = new AtomicReference<Boolean>(Boolean.FALSE);
        dispatchGesture(gd, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { res.set(Boolean.TRUE); latch.countDown(); }
            @Override public void onCancelled(GestureDescription g) { latch.countDown(); }
        }, null);
        latch.await(3, TimeUnit.SECONDS);
        return res.get();
    }

    private boolean gestureSwipe(int x1, int y1, int x2, int y2, long ms) throws Exception {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, ms))
                .build();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Boolean> res = new AtomicReference<Boolean>(Boolean.FALSE);
        dispatchGesture(gd, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { res.set(Boolean.TRUE); latch.countDown(); }
            @Override public void onCancelled(GestureDescription g) { latch.countDown(); }
        }, null);
        latch.await(3, TimeUnit.SECONDS);
        return res.get();
    }

    public JSONObject typeText(String text) throws Exception {
        JSONObject out = new JSONObject();
        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null) {
            AccessibilityNodeInfo r = root();
            focus = findEditable(r);
        }
        if (focus == null) { out.put("ok", false); out.put("error", "no_editable_focus"); return out; }
        focus.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        String via = "set_text";
        if (!ok) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("muse", text));
            ok = focus.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            via = "paste";
        }
        out.put("ok", ok);
        out.put("via", via);
        return out;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isEditable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            AccessibilityNodeInfo f = findEditable(ch);
            if (f != null) return f;
            if (ch != null) ch.recycle();
        }
        return null;
    }

    public JSONObject scroll(String dir) throws Exception {
        JSONObject out = new JSONObject();
        AccessibilityNodeInfo r = root();
        if (r == null) { out.put("ok", false); out.put("error", "no_active_window"); return out; }
        AccessibilityNodeInfo sc = findScrollable(r);
        boolean ok = false;
        if (sc != null) {
            int action = ("up".equals(dir) || "left".equals(dir))
                    ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            ok = sc.performAction(action);
            out.put("via", "node");
        } else {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int cx = dm.widthPixels / 2;
            int cy = dm.heightPixels / 2;
            int dy = dm.heightPixels / 4;
            if ("down".equals(dir)) ok = gestureSwipe(cx, cy + dy, cx, cy - dy, 300);
            else if ("up".equals(dir)) ok = gestureSwipe(cx, cy - dy, cx, cy + dy, 300);
            else if ("left".equals(dir)) ok = gestureSwipe(cx + dy, cy, cx - dy, cy, 300);
            else ok = gestureSwipe(cx - dy, cy, cx + dy, cy, 300);
            out.put("via", "gesture");
        }
        out.put("ok", ok);
        out.put("dir", dir);
        return out;
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isScrollable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            AccessibilityNodeInfo f = findScrollable(ch);
            if (f != null) return f;
            if (ch != null) ch.recycle();
        }
        return null;
    }

    public boolean global(int action) {
        return performGlobalAction(action);
    }

    public volatile int shotW, shotH;

    public String screenshotBase64() throws Exception {
        return screenshotBase64(0);
    }

    /**
     * maxBytes>0 时按阶梯把截图压进预算（中继通道大响应易被掐流），
     * shotW/shotH 回传最终图片尺寸供坐标映射。
     */
    public String screenshotBase64(int maxBytes) throws Exception {
        if (Build.VERSION.SDK_INT < 30) throw new Exception("screenshot needs Android 11+");
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Bitmap> bmp = new AtomicReference<Bitmap>();
        final AtomicReference<String> err = new AtomicReference<String>();
        takeScreenshot(0, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                try {
                    Bitmap b = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                    bmp.set(b == null ? null : b.copy(Bitmap.Config.ARGB_8888, false));
                    if (result.getHardwareBuffer() != null) result.getHardwareBuffer().close();
                } catch (Exception e) {
                    err.set(e.getMessage());
                }
                latch.countDown();
            }
            @Override public void onFailure(int errorCode) {
                err.set("screenshot_failed_" + errorCode);
                latch.countDown();
            }
        });
        if (!latch.await(8, TimeUnit.SECONDS)) throw new Exception("screenshot_timeout");
        if (bmp.get() == null) throw new Exception(err.get() == null ? "screenshot_empty" : err.get());
        Bitmap src = bmp.get();
        int w = src.getWidth(), h = src.getHeight();
        float baseScale = w > 1080 ? 1080f / w : 1f;
        Bitmap b = baseScale < 1f
                ? Bitmap.createScaledBitmap(src, 1080, (int) (h * baseScale), true) : src;
        int quality = 65;
        if (maxBytes > 0) {
            double[] steps = {1.0, 0.7, 0.5, 0.35, 0.25, 0.18, 0.12};
            for (double s : steps) {
                Bitmap cand = s == 1.0 ? b : Bitmap.createScaledBitmap(src,
                        Math.max(1, (int) (w * baseScale * s)),
                        Math.max(1, (int) (h * baseScale * s)), true);
                ByteArrayOutputStream test = new ByteArrayOutputStream();
                cand.compress(Bitmap.CompressFormat.JPEG, quality, test);
                b = cand;
                if (test.size() <= maxBytes) break;
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, quality, bos);
        shotW = b.getWidth();
        shotH = b.getHeight();
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }
}
