package app.ruisnow.muse;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Watchdog tick (armed by BridgeService.armWatchdog, ~every 10 min and 1 min
 * after an unexpected service death). Keeps its own alarm chain alive while
 * auto-connect is on, and resurrects BridgeService if it is gone — unless the
 * user stopped it by hand. A system force-stop removes our alarms too, which
 * Android intends and we do not fight.
 */
public class WatchdogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.autoConnect(context)) return;
        BridgeService.armWatchdog(context, BridgeService.WATCHDOG_INTERVAL_MS);
        if (Prefs.userStopped(context)) return;
        if (Prefs.token(context).length() == 0) return;
        // 0.6.8: do not skip when the service object is alive — its worker
        // thread can be dead (freeze-kill) while isRunning stays true.
        // onStartCommand revives a dead worker and no-ops on a live one.
        Intent svc = new Intent(context, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
        else context.startService(svc);
    }
}
