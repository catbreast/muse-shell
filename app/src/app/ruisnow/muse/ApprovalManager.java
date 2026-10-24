package app.ruisnow.muse;

import android.content.Context;
import android.content.Intent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Blocks the calling tool thread until the user taps allow/deny (or 90s timeout = deny). */
public class ApprovalManager {
    static class Pending {
        String desc;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean allowed = new AtomicBoolean(false);
    }

    static final ConcurrentHashMap<String, Pending> PENDING = new ConcurrentHashMap<String, Pending>();

    public static boolean request(Context ctx, String id, String desc) {
        Pending p = new Pending();
        p.desc = desc;
        PENDING.put(id, p);
        Intent i = new Intent(ctx, ApprovalActivity.class);
        i.putExtra("id", id);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        try {
            p.latch.await(90, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        PENDING.remove(id);
        return p.allowed.get();
    }

    static void resolve(String id, boolean allowed) {
        Pending p = PENDING.get(id);
        if (p != null) {
            p.allowed.set(allowed);
            p.latch.countDown();
        }
    }
}
