# 代码审查请求：Android 前台服务保活逻辑（MuseShell 0.6.8/0.6.9 改动）

## 背景与实证（均已在真机验证）
- App 是个人 Android 代理壳（root 一加 13，ColorOS），BridgeService 前台服务用长轮询连自建中继（CF Worker + Durable Object，DO 侧 /poll 攥 25 秒，命令到达即交付）。
- 故障：息屏后数分钟内中继侧 last_seen 归零联系（最长 14 分钟），进程不死、服务不重启（账本实证），只有应用内 10 分钟看门狗闹钟能恢复。
- 定性：轮询线程会静默僵死（无 Throwable 抛出），疑与 ColorOS Hans 的 cgroup v2 冻结/解冻周期有关；通知永远停在线程死前最后设的「连接中断，重连中」。
- 已有防线：Doze 白名单、待机豁免、耗电管理全开。

## 本次改动
1. 0.6.8：BridgeService 在 onCreate 全程持有 PARTIAL_WAKE_LOCK（此前仅执行命令时持 120 秒）；WatchdogReceiver 删除 `if (isRunning) return;`（服务对象活着但线程死了它原先看不出来），改为无条件 startForegroundService 由 onStartCommand 的线程存活检查决定是否拉活。
2. 0.6.9：加监工线程 startSupervisor()：每 30 秒查 lastPollOkMs（loop() 每次完成 /poll 请求后更新），>150 秒无成功交换则记账本 worker_stale_exit、预置 30 秒看门狗闹钟、killProcess 自重启；worker 的 Runnable catch(Throwable) 记 worker_died（异常类名）并预置 60 秒闹钟。实测：锁屏 8 分钟内监工触发 2 次，进程 3–7 秒重启回来，断联上限约 3 分钟。
3. 设备侧 root 解冻看门狗 muse-thaw.sh（KernelSU service.d）：每 5 秒巡逻目标 uid 的 cgroup.freeze 写 0、进程 State=T 时 SIGCONT。

## 请审查
- 上述逻辑的竞态、误杀（如网络真断 150 秒时 killProcess 循环）、与 Doze/闹钟配额的相互作用、START_STICKY 与 killProcess 的语义、线程僵死根因的其他可能与更优解法。
- 只做审查，不要改文件。输出：确认的问题（按严重度）/ 存疑点 / 改进建议，每条注明依据。

===== BridgeService.java =====
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
    private Thread supervisor;
    private WindowManager wm;
    private View ball;
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
        attachBall();
        try {
            Ledger.log(this, "service_start", null);
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("Muse 壳")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private volatile String lastNotifText = null;

    private void setNotificationText(String text) {
        if (text.equals(lastNotifText)) return;
        lastNotifText = text;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1, buildNotification(text));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
                JSONObject result = dispatcher.dispatch(cmd);
                HttpUtil.request("POST", base + "/result", token, dev, result.toString(), 10000, 15000);
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
    // back, which is exactly how long screen-off outages lasted. If no relay
    // exchange completes for 150 s, log it and restart the process — a fresh
    // process gets a fresh worker, fresh sockets and a fresh DNS state.
    // A live task implies fresh polls, so this never fires mid-command.
    private void startSupervisor() {
        supervisor = new Thread(new Runnable() {
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
                        long last = lastPollOkMs;
                        if (last > 0 && System.currentTimeMillis() - last > 150000) {
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

    // ---------- floating ball: tap opens console, long-press toggles kill switch ----------

    private void attachBall() {
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            final TextView tv = new TextView(this);
            tv.setText("M");
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(18);
            tv.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gd.setColor(0xCC2962FF);
            tv.setBackground(gd);
            int size = 120;
            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    size, size,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = 24;
            lp.y = 240;
            tv.setOnTouchListener(new View.OnTouchListener() {
                private int startX, startY;
                private float touchX, touchY;
                private long downAt;
                private boolean longFired;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            startX = lp.x;
                            startY = lp.y;
                            touchX = e.getRawX();
                            touchY = e.getRawY();
                            downAt = System.currentTimeMillis();
                            break;
                        case MotionEvent.ACTION_MOVE:
                            lp.x = startX - (int) (e.getRawX() - touchX);
                            lp.y = startY + (int) (e.getRawY() - touchY);
                            try { wm.updateViewLayout(tv, lp); } catch (Exception ignored) {}
                            break;
                        case MotionEvent.ACTION_UP:
                            long held = System.currentTimeMillis() - downAt;
                            int moved = Math.abs((int) (e.getRawX() - touchX))
                                    + Math.abs((int) (e.getRawY() - touchY));
                            if (held >= 800 && moved < 20) {
                                boolean on = !Prefs.killSwitch(BridgeService.this);
                                Prefs.setKillSwitch(BridgeService.this, on);
                                Toast.makeText(BridgeService.this,
                                        on ? "急停已开启" : "急停已关闭", Toast.LENGTH_SHORT).show();
                            } else if (moved < 20) {
                                Intent open = new Intent(BridgeService.this, MainActivity.class);
                                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(open);
                            }
                            break;
                    }
                    return true;
                }
            });
            ball = tv;
            wm.addView(tv, lp);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        running = false;
        if (worker != null) worker.interrupt();
        if (wm != null && ball != null) {
            try { wm.removeView(ball); } catch (Exception ignored) {}
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        try { Ledger.log(this, "service_stop", null); } catch (Exception ignored) {}
        // Unexpected death (system kill): ask the watchdog for a fast comeback.
        // A deliberate stop sets userStopped first, so this stays quiet then;
        // the receiver re-checks the flags before resurrecting anything.
        if (Prefs.autoConnect(this) && !Prefs.userStopped(this)) armWatchdog(this, 60 * 1000L);
        super.onDestroy();
    }
}
===== WatchdogReceiver.java =====
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
===== muse-thaw.sh（现行版在手机 /data/adb/service.d/，逻辑如下） =====
(见工程 server/ 目录同名脚本要点：5 秒巡逻 uid_10381 的 cgroup.freeze 写 0 + State=T 时 kill -CONT，pidfile 防重复)
