package app.ruisnow.muse;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!Prefs.autoConnect(context)) return; // auto-connect checkbox off
        if (Prefs.token(context).length() == 0) return; // not paired yet
        Prefs.setUserStopped(context, false); // a fresh boot re-arms auto behavior
        BridgeService.armWatchdog(context, BridgeService.WATCHDOG_INTERVAL_MS);
        Intent svc = new Intent(context, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
        else context.startService(svc);
    }
}
