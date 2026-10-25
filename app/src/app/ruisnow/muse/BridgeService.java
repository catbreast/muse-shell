package app.ruisnow.muse;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public class BridgeService extends Service {
    private static final String CHANNEL = "muse_bridge";
    public static volatile boolean isRunning = false;
    private volatile boolean running = false;
    private Thread worker;
    // 0.6.9: liveness stamp of the last completed relay exchange, watched
    // by the supervisor thread (see startSupervisor).
    static volatile long lastPollOkMs = 0;
    // 0.6.10: true while a command is executing. loop() cannot poll during
    // a dispatch, so the supervisor must not read that silence as a wedge.
    static volatile boolean dispatching = false;
    private Thread supervisor;
    private PowerManager.WakeLock wakeLock;

    public static boolean isRunning() {
        return isRunning;
    }

    static final long WATCHDOG_INTERVAL_MS = 10 * 60 * 1000L;
    private static final int WATCHDOG_RC = 42;

    private static PendingIntent watchdogIntent(Context c) {
        Intent i = new Intent(c, WatchdogReceiver.class);
        return PendingIntent.getBroadcast(c, WATCHDOG_RC, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** (Re)arm the single watchdog alarm; repeated calls just replace it. */
    public static void armWatchdog(Context c, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + delayMs, watchdogIntent(c));
            }
        } catch (Exception ignored) {
        }
    }

    public static void disarmWatchdog(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(watchdogIntent(c));
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        Ledger.init(this);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Muse 壳连接", NotificationManager.IMPORTANCE_LOW));
        }
        startForeground(1, buildNotification("连接中…"));
        if (Prefs.autoConnect(this)) armWatchdog(this, WATCHDOG_INTERVAL_MS);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "muse:bridge");
        wakeLock.setReferenceCounted(false);
        // 0.6.8: hold the lock for the whole service lifetime (released in
        // onDestroy). Before, it was only taken while dispatching a command,
        // so with the screen off the CPU suspended between polls and the
        // relay looked dead for minutes at a time.
        wakeLock.acquire();
        lastPollOkMs = System.currentTimeMillis();
        startSupervisor();
        try {
            Ledger.log(this, "service_start", null);
        } catch (Exception ignored) {
        }
    }

    private static final String ACTION_TOGGLE_KILL = "app.ruisnow.muse.TOGGLE_KILL";

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        // VPN-style stop action in the persistent notification: the floating
        // ball is gone, so the kill switch lives here now. The label flips
        // with the current state; the notification is rebuilt on every post,
        // so the label stays truthful.
        boolean killed = Prefs.killSwitch(this);
        Intent toggle = new Intent(this, BridgeService.class);
        toggle.setAction(ACTION_TOGGLE_KILL);
        PendingIntent tpi = PendingIntent.getService(this, 1, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_lock_power_off,
                killed ? "解除急停" : "急停",
                tpi).build());
        return b.setContentTitle("Muse 壳")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_muse)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private volatile String lastNotifText = null;

    private void setNotificationText(String text) {
        if (!isRunning) return;
        if (text.equals(lastNotifText)) return;
        lastNotifText = text;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1, buildNotification(text));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TOGGLE_KILL.equals(intent.getAction())) {
            boolean on = !Prefs.killSwitch(this);
            Prefs.setKillSwitch(this, on);
            Toast.makeText(this, on ? "急停已开启" : "急停已关闭", Toast.LENGTH_SHORT).show();
            try {
                Ledger.log(this, on ? "kill_on" : "kill_off", null);
            } catch (Exception ignored) {}
            // Force a repost so the action label flips to the new state.
            String t = lastNotifText != null ? lastNotifText : "已连接";
            lastNotifText = null;
            setNotificationText(t);
            return START_STICKY;
        }
        // Liveness is judged by the thread itself, not the running flag: if the
        // worker died (Error, freeze-kill) while the flag stayed set, a plain
        // "start service" tap must still be able to revive it.
        if (worker == null || !worker.isAlive()) {
            running = true;
            worker = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        loop();
                    } catch (Throwable t) {
                        // 0.6.9: a dead worker used to be invisible until the
                        // 10-minute watchdog alarm revived it. Record the
                        // killer and ask for a fast comeback instead.
                        try {
                            JSONObject d = new JSONObject();
                            d.put("error", t.getClass().getName());
                            Ledger.log(BridgeService.this, "worker_died", d);
                        } catch (Exception ignored) {
                        }
                        armWatchdog(BridgeService.this, 60 * 1000L);
                    } finally {
                        running = false;
                    }
                }
            }, "muse-bridge");
            worker.setDaemon(true);
            worker.start();
        }
        return START_STICKY;
    }

    private void loop() {
        ToolDispatcher dispatcher = new ToolDispatcher(this);
        long backoff = 2000;
        while (running) {
            try {
                if (Prefs.killSwitch(this)) {
                    setNotificationText("急停已开启，服务待机");
                    Thread.sleep(3000);
                    continue;
                }
                String base = Prefs.baseUrl(this);
                String token = Prefs.token(this);
                String dev = Prefs.deviceId(this);
                HttpUtil.Resp r = HttpUtil.request("GET", base + "/poll", token, dev, null, 10000, 40000);
                lastPollOkMs = System.currentTimeMillis();
                if (r.code == 401) {
                    setNotificationText("令牌不对，检查配对设置");
                    Thread.sleep(10000);
                    continue;
                }
                if (r.code == 204 || r.body == null || r.body.length() == 0) {
                    backoff = 2000;
                    setNotificationText("已连接，待机");
                    continue;
                }
                if (r.code != 200) {
                    Thread.sleep(backoff);
                    backoff = Math.min(backoff * 2, 30000);
                    continue;
                }
                backoff = 2000;
                setNotificationText("已连接，执行命令中…");
                JSONObject cmd = new JSONObject(r.body);
                // The wake lock is held for the service lifetime (onCreate);
                // the old per-dispatch acquire/release would have dropped it.
                // 0.6.10 (agy review P0-2): a command may run for minutes and
                // loop() cannot poll meanwhile — stamp the receipt as proof
                // of life and flag the dispatch so the supervisor stands
                // down until it finishes; otherwise it kills the process
                // mid-task and the result never reaches the relay.
                dispatching = true;
                lastPollOkMs = System.currentTimeMillis();
                try {
                    JSONObject result = dispatcher.dispatch(cmd);
                    HttpUtil.request("POST", base + "/result", token, dev, result.toString(), 10000, 15000);
                } finally {
                    dispatching = false;
                }
                setNotificationText("已连接，待机");
            } catch (Exception e) {
                try {
                    setNotificationText("连接中断，重连中…");
                    Thread.sleep(backoff);
                    backoff = Math.min(backoff * 2, 30000);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    // 0.6.9: supervisor. The poll worker can die or wedge silently (its last
    // visible act is the "reconnecting" notification) while the process and
    // service live on; before, only the 10-minute watchdog alarm brought it
    // back, which is exactly how long screen-off outages lasted.
    // 0.6.10: two-stage heal, and never during a dispatch. Stage 1 cancels
    // the in-flight connection — a worker wedged in a socket read/connect
    // throws at once and loop() retries in-process, so the process does
    // not die at all. Only if the same stamp is still stale 60 s after the
    // cancel (wedged where a disconnect cannot reach, e.g. DNS) does stage
    // 2 restart the process for a fresh worker, sockets and DNS state.
    private void startSupervisor() {
        supervisor = new Thread(new Runnable() {
            private long cancelForStamp = -1;
            private long cancelAtMs = 0;

            @Override public void run() {
                while (isRunning) {
                    try {
                        Thread.sleep(30000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!isRunning) return;
                    try {
                        if (!Prefs.autoConnect(BridgeService.this)) continue;
                        if (Prefs.killSwitch(BridgeService.this)) continue;
                        if (dispatching) continue;
                        long last = lastPollOkMs;
                        long now = System.currentTimeMillis();
                        if (last <= 0 || now - last <= 150000) {
                            cancelForStamp = -1;
                            continue;
                        }
                        if (cancelForStamp != last) {
                            Ledger.log(BridgeService.this, "worker_stale_cancel", null);
                            HttpUtil.cancelInFlight();
                            cancelForStamp = last;
                            cancelAtMs = now;
                            continue;
                        }
                        if (now - cancelAtMs > 60000) {
                            Ledger.log(BridgeService.this, "worker_stale_exit", null);
                            armWatchdog(BridgeService.this, 30000);
                            android.os.Process.killProcess(android.os.Process.myPid());
                            return;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "muse-supervisor");
        supervisor.setDaemon(true);
        supervisor.start();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        running = false;
        if (worker != null) worker.interrupt();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        // Replace whatever live state was showing (often "连接中断，重连中…",
        // posted by the dying worker) with a final, dismissible stopped
        // notice; the isRunning guard keeps the worker from overwriting it.
        try {
            stopForeground(true);
            Intent open = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL)
                    : new Notification.Builder(this);
            Notification stopped = b.setContentTitle("Muse 壳")
                    .setContentText("已停止")
                    .setSmallIcon(R.drawable.ic_stat_muse)
                    .setContentIntent(pi)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .build();
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, stopped);
        } catch (Exception ignored) {}
        try { Ledger.log(this, "service_stop", null); } catch (Exception ignored) {}
        // Unexpected death (system kill): ask the watchdog for a fast comeback.
        // A deliberate stop sets userStopped first, so this stays quiet then;
        // the receiver re-checks the flags before resurrecting anything.
        if (Prefs.autoConnect(this) && !Prefs.userStopped(this)) armWatchdog(this, 60 * 1000L);
        super.onDestroy();
    }
}
