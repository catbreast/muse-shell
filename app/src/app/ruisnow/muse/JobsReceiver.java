package app.ruisnow.muse;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 0.8.0 本地任务到期唤醒：优先把 BridgeService 拉起来执行（服务里有
 * 完整的派发/待发箱环境）；拉不起来（系统限制）就本进程内直接跑，
 * 结果先进 Outbox 落盘，链路恢复后由服务补发。
 */
public class JobsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        if (Prefs.killSwitch(context)) {
            Jobs.armNext(context);
            return;
        }
        boolean started = false;
        try {
            Intent svc = new Intent(context, BridgeService.class);
            svc.setAction(BridgeService.ACTION_JOBS_DUE);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
            else context.startService(svc);
            started = true;
        } catch (Exception ignored) {
        }
        if (!started) {
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        Jobs.runDue(context.getApplicationContext());
                    } catch (Exception ignored) {
                    } finally {
                        pr.finish();
                    }
                }
            }, "muse-jobs-fallback").start();
        }
    }
}
