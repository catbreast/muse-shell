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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

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
    // 0.7.12: WebSocket primary channel (relay Hibernation migration). While
    // wsOpen, commands arrive on the socket and the HTTP poll loop idles;
    // any socket failure drops back to the poll loop below, so an old relay
    // or a dead socket never breaks the command path.
    private volatile WebSocket relayWs = null;
    private volatile boolean wsOpen = false;
    private volatile long nextWsTryMs = 0;
    private volatile long lastTelePingMs = 0;
    // 0.7.14: multi-lane dispatch. Until 0.7.13 every command ran serially
    // on the worker/WS-callback thread, so one command that never returned
    // (e.g. a pm install held by the system) starved the whole channel
    // while heartbeats stayed green and the supervisor stood down. Now
    // each command runs on a lane thread and posts its own result; a
    // wedged lane costs one thread, never the channel. browser.* keeps a
    // single dedicated lane: its snapshot refs and page state assume
    // sequential execution. Note: commands from one batch may now finish
    // out of order; the relay matches results by call id.
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final ExecutorService lanes = Executors.newFixedThreadPool(4, laneFactory("muse-lane"));
    private final ExecutorService browserLane = Executors.newSingleThreadExecutor(laneFactory("muse-browser-lane"));

    private static ThreadFactory laneFactory(final String name) {
        return new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(0);

            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name + "-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }

    // Run one command on its lane; the result goes into the Outbox and the
    // outbox sender thread owns delivery (0.8.0: persist-first, retry until
    // the relay acks — the old single-shot sendResultWs silently dropped
    // results whenever the network hiccuped, which callers then read as a
    // timeout and retried, double-executing non-idempotent commands).
    // A command whose id already has a stored result is a redelivery:
    // never execute twice — just re-mark the stored result for resend.
    private void submit(final ToolDispatcher dispatcher, final WebSocket ws,
                        final String base, final String token, final String dev,
                        final JSONObject cmd) {
        final String cid = cmd.optString("id", "");
        if (cid.length() > 0) {
            JSONObject cached = Outbox.get(cid);
            if (cached != null) {
                Outbox.put(cached);
                return;
            }
        }
        inFlight.incrementAndGet();
        dispatching = true;
        lastPollOkMs = System.currentTimeMillis();
        String tool = cmd.optString("tool", "");
        ExecutorService lane = tool.startsWith("browser.") ? browserLane : lanes;
        try {
            lane.execute(new Runnable() {
                @Override public void run() {
                    try {
                        JSONObject result = dispatcher.dispatch(cmd);
                        Outbox.put(result);
                    } catch (Exception ignored) {
                    } finally {
                        if (inFlight.decrementAndGet() <= 0) dispatching = false;
                    }
                }
            });
        } catch (Exception e) {
            if (inFlight.decrementAndGet() <= 0) dispatching = false;
        }
    }

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

    public static final String ACTION_JOBS_DUE = "app.ruisnow.muse.JOBS_DUE";

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        Ledger.init(this);
        Outbox.init(this);
        Jobs.armNext(this);
        startOutboxSender();
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

    private static final String ACTION_START = "app.ruisnow.muse.START";
    private static final String ACTION_STOP = "app.ruisnow.muse.STOP";

    /** Start/stop intents for the notification actions, mirroring the
     *  console button's semantics exactly (Prefs.userStopped gates the
     *  watchdog, so a notification stop is not resurrected either). */
    private PendingIntent startPendingIntent(int requestCode) {
        Intent start = new Intent(this, BridgeService.class);
        start.setAction(ACTION_START);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        if (Build.VERSION.SDK_INT >= 26) {
            return PendingIntent.getForegroundService(this, requestCode, start, flags);
        }
        return PendingIntent.getService(this, requestCode, start, flags);
    }

    private PendingIntent stopPendingIntent(int requestCode) {
        Intent stop = new Intent(this, BridgeService.class);
        stop.setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, requestCode, stop, flags);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        // One state-flipping action: while running it reads 停止; the
        // stopped notice posted by onDestroy carries 启动 instead.
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_lock_power_off, "停止", stopPendingIntent(2)).build());
        return b.setContentText(text)
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
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // Same as the console stop: mark user-stopped so the watchdog
            // does not resurrect the service, then shut down. onDestroy
            // posts the stopped notice (which carries a 启动 action).
            Prefs.setUserStopped(this, true);
            Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show();
            stopSelf();
            return START_STICKY;
        }
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            Prefs.setUserStopped(this, false);
        }
        if (intent != null && ACTION_JOBS_DUE.equals(intent.getAction())) {
            // 本地任务到期：独立线程跑（可能含长命令），不阻塞服务启动路径
            new Thread(new Runnable() {
                @Override public void run() {
                    try { Jobs.runDue(BridgeService.this); } catch (Exception ignored) {}
                }
            }, "muse-jobs").start();
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

    // 0.7.12: WS listener. Runs on OkHttp dispatcher threads.
    // 0.7.14: onMessage only submits to the dispatch lanes (see submit());
    // the dispatching flag is maintained per in-flight command and keeps
    // the supervisor stood down exactly as in loop().
    private WebSocketListener wsListener(final ToolDispatcher dispatcher,
                                         final String base, final String token,
                                         final String dev) {
        return new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response resp) {
                wsOpen = true;
                lastPollOkMs = System.currentTimeMillis();
                try {
                    JSONObject h = new JSONObject();
                    h.put("type", "hello");
                    h.put("device", dev);
                    h.put("tele", teleJson());
                    ws.send(h.toString());
                } catch (Exception ignored) {
                }
                setNotificationText("已连接，待机");
            }

            @Override public void onMessage(WebSocket ws, String text) {
                lastPollOkMs = System.currentTimeMillis();
                final JSONObject obj;
                try {
                    obj = new JSONObject(text);
                } catch (Exception e) {
                    return;
                }
                if (obj.has("type")) {
                    // 0.8.0: 中继对结果的确认——待发箱据此删条目
                    if ("result_ack".equals(obj.optString("type", ""))) {
                        String ackId = obj.optString("id", "");
                        if (ackId.length() > 0) {
                            Outbox.markAcked(ackId);
                            lastAckMs = System.currentTimeMillis();
                            java.util.concurrent.CountDownLatch l = ackWaiters.remove(ackId);
                            if (l != null) l.countDown();
                        }
                    }
                    return; // hello_ack / pong 等其余控制消息
                }
                // 0.7.14: hand each command to a lane and return at once;
                // blocking this OkHttp callback on a dispatch was the old
                // serial bottleneck.
                try {
                    JSONArray cmds = obj.optJSONArray("cmds");
                    if (cmds != null) {
                        for (int i = 0; i < cmds.length(); i++) {
                            submit(dispatcher, ws, base, token, dev, cmds.getJSONObject(i));
                        }
                    } else if (obj.has("tool")) {
                        submit(dispatcher, ws, base, token, dev, obj);
                    }
                } catch (Exception ignored) {
                }
            }

            @Override public void onClosing(WebSocket ws, int code, String reason) {
                wsOpen = false;
                try { ws.close(1000, null); } catch (Exception ignored) {}
            }

            @Override public void onFailure(WebSocket ws, Throwable t, Response resp) {
                wsOpen = false;
                if (relayWs == ws) relayWs = null;
                nextWsTryMs = System.currentTimeMillis() + 30000;
            }
        };
    }

    // ---- 0.8.0 结果待发箱发送线程 ----
    static volatile long lastAckMs = 0;
    private final java.util.Map<String, java.util.concurrent.CountDownLatch> ackWaiters =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> nextTryMs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Integer> tryCount =
            new java.util.concurrent.ConcurrentHashMap<>();

    JSONObject teleJson() {
        JSONObject t = new JSONObject();
        try {
            t.put("app", "0.8.0");
            t.put("outbox_pending", Outbox.pendingCount());
            t.put("outbox_total", Outbox.totalCount());
            t.put("jobs_enabled", Jobs.enabledCount(this));
            long nj = Jobs.nextDueMs(this);
            if (nj > 0) t.put("jobs_next_ms", nj);
            if (lastAckMs > 0) {
                t.put("last_result_ack_ago_s",
                        (System.currentTimeMillis() - lastAckMs) / 1000);
            }
        } catch (Exception ignored) {
        }
        return t;
    }

    /** 一次投递尝试：先 WS（等 result_ack ≤4s），不行再 HTTP /result（200=确认）。 */
    private boolean deliverOnce(JSONObject envelope) {
        String id = envelope.optString("id", "");
        String base = Prefs.baseUrl(this);
        String token = Prefs.token(this);
        String dev = Prefs.deviceId(this);
        WebSocket ws = relayWs;
        if (wsOpen && ws != null && id.length() > 0) {
            java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            ackWaiters.put(id, latch);
            try {
                JSONObject wrap = new JSONObject();
                wrap.put("type", "result");
                wrap.put("result", envelope);
                if (ws.send(wrap.toString())) {
                    latch.await(4, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (Exception ignored) {
            } finally {
                ackWaiters.remove(id);
            }
            if (Outbox.isAcked(id)) return true;
        }
        try {
            HttpUtil.Resp r = HttpUtil.request("POST", base + "/result", token, dev,
                    envelope.toString(), 10000, 15000);
            return r.code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void startOutboxSender() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (isRunning) {
                    try {
                        java.util.List<String> pend = Outbox.pendingIds();
                        if (pend.isEmpty()) {
                            Thread.sleep(4000);
                            continue;
                        }
                        long now = System.currentTimeMillis();
                        boolean attempted = false;
                        for (String id : pend) {
                            if (!isRunning) return;
                            Long nt = nextTryMs.get(id);
                            if (nt != null && now < nt) continue;
                            JSONObject env = Outbox.get(id);
                            if (env == null) continue;
                            attempted = true;
                            if (deliverOnce(env)) {
                                Outbox.markAcked(id);
                                lastAckMs = System.currentTimeMillis();
                                nextTryMs.remove(id);
                                tryCount.remove(id);
                            } else {
                                int n = tryCount.containsKey(id) ? tryCount.get(id) + 1 : 1;
                                tryCount.put(id, n);
                                long delay = Math.min(60000L, 2000L << Math.min(n, 5));
                                nextTryMs.put(id, System.currentTimeMillis() + delay);
                            }
                        }
                        Thread.sleep(attempted ? 1200 : 3000);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Exception ignored) {
                        try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                    }
                }
            }
        }, "muse-outbox");
        t.setDaemon(true);
        t.start();
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
                // 0.7.12: WS 优先。连接存活时本循环只做存活打点（监管线程据此
                // 判活），不再发 HTTP 长轮询；断线则落回下面的轮询路径顶着，
                // 每 30 秒重试一次 WS。
                if (wsOpen) {
                    lastPollOkMs = System.currentTimeMillis();
                    // 0.8.0: 每 25 秒带遥测 ping 一次——/status 上的 tele
                    // （待发箱深度、任务下次到期、最近结果确认）靠它刷新
                    if (System.currentTimeMillis() - lastTelePingMs > 25000) {
                        lastTelePingMs = System.currentTimeMillis();
                        try {
                            WebSocket ws = relayWs;
                            if (ws != null) {
                                JSONObject p = new JSONObject();
                                p.put("type", "ping");
                                p.put("tele", teleJson());
                                ws.send(p.toString());
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    Thread.sleep(1000);
                    continue;
                }
                if (relayWs == null && System.currentTimeMillis() >= nextWsTryMs) {
                    try {
                        relayWs = HttpUtil.connectWs(base, token, dev,
                                wsListener(dispatcher, base, token, dev));
                        // 给握手最多 10 秒；连上后下一轮进入 WS 待机
                        for (int i = 0; i < 50 && running && !wsOpen; i++) {
                            Thread.sleep(200);
                        }
                        if (wsOpen) continue;
                        try { relayWs.close(1000, "handshake_timeout"); } catch (Exception ignored) {}
                        relayWs = null;
                        nextWsTryMs = System.currentTimeMillis() + 30000;
                    } catch (Exception e) {
                        relayWs = null;
                        nextWsTryMs = System.currentTimeMillis() + 30000;
                    }
                }
                // 0.7.10: ?n=8 让中继在有一批待办时一次返回多条（{cmds:[...]}），
                // 连环操作不用每条都等一轮长轮询；旧中继忽略该参数、仍返回单条。
                HttpUtil.Resp r = HttpUtil.request("GET", base + "/poll?n=8", token, dev, null, 10000, 40000);
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
                JSONObject first = new JSONObject(r.body);
                // The wake lock is held for the service lifetime (onCreate);
                // the old per-dispatch acquire/release would have dropped it.
                // 0.6.10 (agy review P0-2): a command may run for minutes —
                // stamp the receipt as proof of life and flag the dispatch
                // so the supervisor stands down; otherwise it kills the
                // process mid-task and the result never reaches the relay.
                // 0.7.14: submit() keeps that flag per in-flight command
                // (see inFlight) while this loop polls on immediately.
                try {
                    JSONArray cmds = first.optJSONArray("cmds");
                    if (cmds != null) {
                        // 0.7.10 批量：逐条提交到通道，各自完成时回结果
                        for (int i = 0; i < cmds.length(); i++) {
                            submit(dispatcher, null, base, token, dev, cmds.getJSONObject(i));
                        }
                    } else {
                        submit(dispatcher, null, base, token, dev, first);
                    }
                } catch (Exception ignored) {
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
        wsOpen = false;
        if (relayWs != null) {
            try { relayWs.close(1000, "service_destroy"); } catch (Exception ignored) {}
            relayWs = null;
        }
        if (worker != null) worker.interrupt();
        lanes.shutdownNow();
        browserLane.shutdownNow();
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
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_media_play, "启动", startPendingIntent(3)).build());
            Notification stopped = b.setContentText("已停止")
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
