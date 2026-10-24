package app.ruisnow.vscreen;

import android.content.Context;
import android.content.ContextWrapper;

import java.lang.reflect.Method;

/**
 * Minimal system context for an app_process process (no Application exists here).
 * Built by reflecting ActivityThread's system context, the same trick scrcpy uses.
 */
public class FakeContext extends ContextWrapper {

    private FakeContext(Context base) {
        super(base);
    }

    public static FakeContext get() throws Exception {
        Class<?> atCls = Class.forName("android.app.ActivityThread");
        Object thread = null;
        try {
            Method sm = atCls.getMethod("systemMain");
            thread = sm.invoke(null);
        } catch (Exception ignored) {
        }
        if (thread == null) {
            Method cur = atCls.getMethod("currentActivityThread");
            thread = cur.invoke(null);
        }
        if (thread == null) throw new Exception("no_activity_thread");
        Method gsc = atCls.getMethod("getSystemContext");
        Context sys = (Context) gsc.invoke(thread);
        if (sys == null) throw new Exception("no_system_context");
        return new FakeContext(sys);
    }

    @Override
    public String getPackageName() {
        return "com.android.shell";
    }

    @Override
    public String getOpPackageName() {
        return "com.android.shell";
    }
}
