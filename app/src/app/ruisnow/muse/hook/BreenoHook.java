package app.ruisnow.muse.hook;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Breeno (com.heytap.speechassist) takeover hook for the Muse shell, modeled on
 * the publicly documented Eta approach.
 *
 * P1 (recon): structurally locate the AI-chat dispatch point and the speech
 * engine outbound method; log every request/response bean, never interfere.
 *
 * P2 (takeover, this version): when the user speaks/types a question into
 * Breeno's AI chat (dispatch bean chatType=1 with content), this hook calls
 * muse2api directly (its fixed side chat "小布专线" answers), cleans the
 * reply, injects it into Breeno as a native StreamTextCard directive
 * (spoken + displayed by Breeno itself), then persists the Q/A pair into
 * Breeno's chat history.
 *
 * P2-4 (suppression, 0.5.2+): rooms where a query was submitted are claimed
 * for 10 minutes; native answer beans (chatType=2) dispatched into a claimed
 * room are blocked unless they carry our marker or match a card we injected
 * ourselves (Eta uses the same claimed-room design). 0.5.3 adds Eta's second
 * layer: inbound cloud messages for a claimed room get their native answer
 * directives (StreamTextCard, SpeechSynthesizer, ...) filtered out before
 * the app processes them, so the native answer cannot surface later from
 * local state either. No placeholder card is injected anymore (user's
 * choice): the user hears nothing until Muse's answer card arrives.
 * 0.5.4 adds layer 3: Breeno pushes the native answer as a system
 * notification (channel "todo_messages"); NotificationManager.notify is
 * hooked and such notifications are dropped while a claim is fresh,
 * unless their text matches a card we injected.
 * Everything the hook does is wrapped so a failure can never break Breeno.
 *
 * Takeover can be switched off from the Muse shell console, which writes
 * /sdcard/MuseShell/breeno_takeover ("0" = off; absent or "1" = on).
 */
public class BreenoHook extends XposedModule {
    private static final String TAG = "MuseHook";
    private static final String TARGET_PKG = "com.heytap.speechassist";
    private static final String SPEECH_SERVICE = "com.heytap.speechassist.core.SpeechService";
    private static final String DATA_CENTER = "com.heytap.speechassist.aichat.AIChatDataCenter";
    private static final String VIEW_BEAN = "com.heytap.speechassist.aichat.bean.AIChatViewBean";
    private static final String MQM = "com.heytap.speech.engine.connect.core.manager.MessageQueueManager";
    private static final String MESSAGE = "com.heytap.speech.engine.protocol.event.Message";
    private static final String ENGINE = "com.heytap.speech.engine.HeytapSpeechEngine";
    private static final String DIRECTIVE = "com.heytap.speech.engine.protocol.directive.Directive";
    private static final String DIRECTIVE_HEADER = "com.heytap.speech.engine.protocol.directive.DirectiveHeader";
    private static final String STREAM_CARD = "com.heytap.speech.engine.protocol.directive.myai.StreamTextCard";
    private static final String REPOSITORY = "com.heytap.speechassist.aichat.repository.AIChatRepository";
    private static final String INSERT_RECORD = "com.heytap.speechassist.aichat.repository.api.InsertRecord";
    private static final String MARKER_KEY = "museAgent";
    private static final String FAILURE_CARD = "这条我暂时没处理成功，你可以在主聊天里再跟我说一次。";

    private volatile ClassLoader cl;
    private volatile boolean installedLineWritten = false;

    // ---- takeover state ----
    private static final class Pending {
        String text, roomId, recordId, beanJson;
        long ts;
    }
    private static final class Awaiting {
        String id, text, roomId, recordId, sessionId, originalRecordId;
        long submittedTs;
    }
    private final List<Pending> pending = new ArrayList<>();
    private final Map<String, Long> submittedKeys = new ConcurrentHashMap<>();
    // P2-4: rooms claimed by a takeover submit (roomId -> claim ts), and the
    // cards we injected ourselves (roomId + "\n" + content -> ts) so native
    // suppression never eats our own placeholder/answer cards.
    private static final long CLAIM_TTL_MS = 10 * 60 * 1000;
    private final Map<String, Long> claimedRooms = new ConcurrentHashMap<>();
    private final Map<String, Long> injectedContents = new ConcurrentHashMap<>();
    private volatile String lastText = "";
    private volatile long lastTextTs = 0;
    private volatile long takeoverCacheTs = 0;
    private volatile boolean takeoverCache = true;
    private volatile long powerCacheTs = 0;
    private volatile boolean powerCache = true;
    private volatile boolean housekeeperStarted = false;

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) return;
        cl = param.getClassLoader();
        int installed = 0;
        try {
            installed += hookDispatch(cl);
        } catch (Throwable t) {
            xlog("dispatch install failed: " + t);
        }
        try {
            installed += hookOutbound(cl);
        } catch (Throwable t) {
            xlog("outbound install failed: " + t);
        }
        try {
            installed += hookInbound(cl);
        } catch (Throwable t) {
            xlog("inbound install failed: " + t);
        }
        try {
            installed += hookNotifications(cl);
        } catch (Throwable t) {
            xlog("notification install failed: " + t);
        }
        try {
            installed += hookPowerKey(cl);
        } catch (Throwable t) {
            xlog("powerkey install failed: " + t);
        }
        xlog("hooks installed=" + installed + " in " + param.getPackageName());
        startHousekeeper();
    }

    // ================= hooks =================

    // ---- power key redirect (diagnostic phase: log SpeechService starts) ----
    // ColorOS wakes Breeno on power long-press by starting SpeechService.
    // Phase 1 records every start intent so the power-key signature can be
    // identified from the module log; the redirect lands once it is known.
    // Switch: /sdcard/MuseShell/powerkey_muse ("0" = off; absent or "1" = on).
    private int hookPowerKey(ClassLoader cl) throws Exception {
        Class<?> svc = Class.forName(SPEECH_SERVICE, false, cl);
        int n = 0;
        for (final Method m : svc.getDeclaredMethods()) {
            if (!"onStartCommand".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() != int.class || p.length != 3
                    || p[0] != Intent.class || p[1] != int.class || p[2] != int.class) continue;
            hook(m).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        if (powerKeyOn()) {
                            List<Object> a = chain.getArgs();
                            Intent in = a.isEmpty() ? null : (Intent) a.get(0);
                            appendFile("{\"event\":\"powerkey_start\",\"intent\":\""
                                    + esc(describeIntent(in)) + "\"}");
                        }
                    } catch (Throwable ignored) {}
                    return chain.proceed();
                }
            });
            n++;
        }
        return n;
    }

    private static String describeIntent(Intent in) {
        if (in == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append("action=").append(in.getAction());
        sb.append(" cmp=").append(in.getComponent() == null
                ? "-" : in.getComponent().flattenToShortString());
        sb.append(" flags=0x").append(Integer.toHexString(in.getFlags()));
        Bundle b = in.getExtras();
        if (b != null) {
            for (String k : b.keySet()) {
                Object v = null;
                try { v = b.get(k); } catch (Throwable ignored) {}
                sb.append(" | ").append(k).append("=");
                if (v == null) {
                    sb.append("null");
                } else if (v instanceof String || v instanceof Number || v instanceof Boolean) {
                    String s = String.valueOf(v);
                    sb.append(s.length() > 120 ? s.substring(0, 120) : s);
                } else {
                    sb.append("<").append(v.getClass().getSimpleName()).append(">");
                }
            }
        }
        return sb.toString();
    }

    private boolean powerKeyOn() {
        long now = System.currentTimeMillis();
        if (now - powerCacheTs < 5000) return powerCache;
        powerCacheTs = now;
        boolean on = true;
        try {
            File f = new File(Environment.getExternalStorageDirectory(), "MuseShell/powerkey_muse");
            if (f.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(f));
                String v = r.readLine();
                r.close();
                on = v == null || !"0".equals(v.trim());
            }
        } catch (Throwable ignored) {
            on = true;
        }
        powerCache = on;
        return on;
    }

    private int hookDispatch(ClassLoader cl) throws Exception {
        Class<?> dc = Class.forName(DATA_CENTER, false, cl);
        Class<?> bean = Class.forName(VIEW_BEAN, false, cl);
        int n = 0;
        for (final Method m : dc.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() != void.class || p.length < 1 || p[0] != bean
                    || Modifier.isStatic(m.getModifiers())) continue;
            hook(m).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object arg0 = null;
                    try {
                        List<Object> a = chain.getArgs();
                        arg0 = a.isEmpty() ? null : a.get(0);
                        appendFile("{\"event\":\"dispatch\",\"method\":\"" + m.getName()
                                + "\",\"bean\":" + dumpJson(arg0) + "}");
                        onDispatchBean(arg0);
                        if (shouldSuppressNativeAnswer(arg0)) {
                            appendFile("{\"event\":\"native_answer_blocked\",\"method\":\""
                                    + m.getName() + "\",\"roomId\":\""
                                    + esc(nullToEmpty(strField(arg0, "roomId")))
                                    + "\",\"content\":\""
                                    + esc(nullToEmpty(strField(arg0, "content"))) + "\"}");
                            return null;
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                }
            });
            n++;
            xlog("hooked dispatch " + m.getName());
        }
        return n;
    }

    private int hookOutbound(ClassLoader cl) throws Exception {
        Class<?> mqm = Class.forName(MQM, false, cl);
        Class<?> msg = Class.forName(MESSAGE, false, cl);
        int n = 0;
        for (final Method m : mqm.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() != void.class || p.length != 4 || p[0] != msg
                    || p[1] != boolean.class || p[2] != Integer.class || p[3] != boolean.class) continue;
            hook(m).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        List<Object> a = chain.getArgs();
                        Object message = a.isEmpty() ? null : a.get(0);
                        appendFile("{\"event\":\"outbound\",\"method\":\"" + m.getName()
                                + "\",\"message\":" + dumpJson(message) + "}");
                        onOutbound(message);
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                }
            });
            n++;
            xlog("hooked outbound " + m.getName());
        }
        return n;
    }

    /** P2-4 layer 2: hook the cloud->phone inbound message processor and
     *  strip native answer directives for claimed rooms before the app sees
     *  them (Eta's design). The method has no stable obfuscated name, so all
     *  void(String, String) instance methods in the connect manager package
     *  are hooked; the filter self-selects on directive-JSON content and
     *  passes everything else through untouched. */
    private int hookInbound(ClassLoader cl) {
        int n = 0;
        List<String> classNames = enumeratePackageClasses(
                cl, "com.heytap.speech.engine.connect.core.manager");
        for (String cn : classNames) {
            Class<?> k;
            try {
                k = Class.forName(cn, false, cl);
            } catch (Throwable t) {
                continue;
            }
            for (final Method m : k.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() != void.class || p.length != 2
                        || p[0] != String.class || p[1] != String.class
                        || Modifier.isStatic(m.getModifiers())) continue;
                hook(m).intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        try {
                            List<Object> a = chain.getArgs();
                            Object contentObj = a.size() > 1 ? a.get(1) : null;
                            if (contentObj instanceof String) {
                                String content = (String) contentObj;
                                String filtered = filterNativeDirectives(content);
                                if (filtered == null) {
                                    appendFile("{\"event\":\"inbound_suppressed\",\"method\":\""
                                            + m.getName() + "\"}");
                                    return null;
                                }
                                if (!filtered.equals(content)) {
                                    Object[] newArgs = a.toArray();
                                    newArgs[1] = filtered;
                                    return chain.proceed(newArgs);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    }
                });
                n++;
                xlog("hooked inbound candidate " + cn + "#" + m.getName());
            }
        }
        return n;
    }

    private List<String> enumeratePackageClasses(ClassLoader cl, String pkg) {
        List<String> out = new ArrayList<>();
        try {
            Field pathListF = findFieldInHierarchy(cl.getClass(), "pathList");
            Object pathList = pathListF.get(cl);
            Field elementsF = findFieldInHierarchy(pathList.getClass(), "dexElements");
            Object[] elements = (Object[]) elementsF.get(pathList);
            for (Object el : elements) {
                if (el == null) continue;
                Field dexF = findFieldInHierarchy(el.getClass(), "dexFile");
                Object dex = dexF.get(el);
                if (dex == null) continue;
                Method entriesM = dex.getClass().getMethod("entries");
                Enumeration<String> en = (Enumeration<String>) entriesM.invoke(dex);
                while (en.hasMoreElements()) {
                    String name = en.nextElement();
                    if (name.startsWith(pkg + ".")) out.add(name);
                }
            }
        } catch (Throwable t) {
            xlog("enumerate failed: " + t);
        }
        return out;
    }

    private static Field findFieldInHierarchy(Class<?> cls, String name) throws Exception {
        for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** Returns the message with native answer directives removed, null when
     *  nothing worth delivering remains, or the input unchanged when the
     *  message is not a native answer for a claimed room. */
    private String filterNativeDirectives(String content) {
        if (content == null || content.length() < 2 || content.charAt(0) != '{') return content;
        if (claimedRooms.isEmpty()) return content;
        try {
            JSONObject json = new JSONObject(content);
            String roomId = json.optString("roomId", "");
            if (roomId.isEmpty()) return content;
            Long claimed = claimedRooms.get(roomCore(roomId));
            if (claimed == null || System.currentTimeMillis() - claimed > CLAIM_TTL_MS) return content;
            JSONObject extend = json.optJSONObject("extend");
            if (extend != null && "true".equals(extend.optString(MARKER_KEY, ""))) return content;
            JSONArray directives = json.optJSONArray("directives");
            if (directives == null) return content;
            JSONArray kept = new JSONArray();
            int removed = 0;
            for (int i = 0; i < directives.length(); i++) {
                JSONObject d = directives.optJSONObject(i);
                if (d == null) {
                    kept.put(directives.opt(i));
                    continue;
                }
                JSONObject header = d.optJSONObject("header");
                String ns = header == null ? "" : header.optString("namespace", "");
                String name = header == null ? "" : header.optString("name", "");
                if (suppressDirective(ns, name)) {
                    removed++;
                } else {
                    kept.put(d);
                }
            }
            if (removed == 0) return content;
            appendFile("{\"event\":\"inbound_filtered\",\"removed\":" + removed
                    + ",\"kept\":" + kept.length() + "}");
            if (kept.length() == 0) return null;
            json.put("directives", kept);
            return json.toString();
        } catch (Throwable t) {
            return content;
        }
    }

    /** Eta's native-directive suppress table. */
    private static boolean suppressDirective(String ns, String name) {
        switch (ns) {
            case "MyAI":
                return "LoadingStateCard".equals(name) || "StreamTextCard".equals(name);
            case "App":
            case "AnalogClick":
            case "System":
            case "SystemScreen":
            case "Sms":
            case "PhoneCall":
            case "Ocr":
            case "SpeechSynthesizer":
            case "Recommend":
                return true;
            case "Tracking":
                return "BreenoFeedback".equals(name);
            case "SpeechRecognizer":
                return "ExpectSpeech".equals(name);
            default:
                return false;
        }
    }

    /** P2-4 layer 3 (0.5.4): Breeno also pushes its native answer as a system
     *  notification (channel "todo_messages", fixed id -479, observed in the
     *  wild at submit+5s). While a room claim is fresh, drop notifications on
     *  that channel whose text is not a card we injected ourselves; log every
     *  notification posted during a claim so other channels stay auditable. */
    private int hookNotifications(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> nm = Class.forName("android.app.NotificationManager", false, cl);
            for (final Method m : nm.getDeclaredMethods()) {
                if (!"notify".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 0 || p[p.length - 1] != Notification.class) continue;
                hook(m).intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        try {
                            List<Object> a = chain.getArgs();
                            Object last = a.isEmpty() ? null : a.get(a.size() - 1);
                            if (last instanceof Notification && takeoverOn() && hasFreshClaim()) {
                                Notification notif = (Notification) last;
                                String channel = notif.getChannelId();
                                String text = notifText(notif);
                                String shown = text.length() > 60 ? text.substring(0, 60) : text;
                                appendFile("{\"event\":\"notification_seen\",\"channel\":\""
                                        + esc(channel) + "\",\"text\":\"" + esc(shown) + "\"}");
                                if ("todo_messages".equals(channel) && !isOurContent(text)) {
                                    appendFile("{\"event\":\"notification_blocked\",\"channel\":\""
                                            + esc(channel) + "\"}");
                                    return null;
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    }
                });
                n++;
            }
        } catch (Throwable t) {
            xlog("notification hooks failed: " + t);
        }
        return n;
    }

    private boolean hasFreshClaim() {
        long now = System.currentTimeMillis();
        for (Long ts : claimedRooms.values()) {
            if (ts != null && now - ts < CLAIM_TTL_MS) return true;
        }
        return false;
    }

    private boolean isOurContent(String text) {
        if (text == null || text.isEmpty()) return false;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : injectedContents.entrySet()) {
            if (now - e.getValue() >= CLAIM_TTL_MS) continue;
            String key = e.getKey();
            int nl = key.indexOf('\n');
            String content = nl >= 0 ? key.substring(nl + 1) : key;
            if (content.isEmpty()) continue;
            if (text.contains(content)) return true;
            if (text.length() >= 6 && content.contains(text)) return true;
        }
        return false;
    }

    private static String notifText(Notification notif) {
        try {
            Bundle ex = notif.extras;
            if (ex == null) return "";
            StringBuilder sb = new StringBuilder();
            CharSequence[] keys = new CharSequence[] {
                    ex.getCharSequence(Notification.EXTRA_TITLE),
                    ex.getCharSequence(Notification.EXTRA_TEXT),
                    ex.getCharSequence(Notification.EXTRA_BIG_TEXT),
                    ex.getCharSequence(Notification.EXTRA_SUB_TEXT)};
            for (CharSequence cs : keys) {
                if (cs != null && cs.length() > 0) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(cs);
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ================= intake =================

    /** A chatType=1 bean with content is the user's question entering the chat. */
    private void onDispatchBean(Object bean) {
        if (bean == null) return;
        String chatType = strField(bean, "chatType");
        if (!"1".equals(chatType)) return;
        String content = strField(bean, "content");
        if (content == null || content.trim().isEmpty()) return;
        Pending p = new Pending();
        p.text = content.trim();
        p.roomId = strField(bean, "roomId");
        p.recordId = strField(bean, "recordId");
        p.ts = System.currentTimeMillis();
        synchronized (pending) {
            pending.add(p);
            while (pending.size() > 8) pending.remove(0);
        }
        appendFile("{\"event\":\"query_candidate\",\"text\":\"" + esc(p.text)
                + "\",\"roomId\":\"" + esc(nullToEmpty(p.roomId))
                + "\",\"recordId\":\"" + esc(nullToEmpty(p.recordId)) + "\"}");
    }

    /** The engine outbound send confirms the question was really sent (not a
     *  history re-render): submit the freshest matching pending query now. */
    private void onOutbound(Object message) {
        if (message == null) return;
        String roomId = strField(message, "roomId");
        String sessionId = strField(message, "sessionId");
        String recordId = strField(message, "recordId");
        String originalRecordId = strField(message, "originalRecordId");
        Pending best = null;
        synchronized (pending) {
            for (Pending p : pending) {
                if (System.currentTimeMillis() - p.ts > 8000) continue;
                if (roomId != null && p.roomId != null && !roomId.equals(p.roomId)) continue;
                if (best == null || p.ts > best.ts) best = p;
            }
            if (best != null) pending.remove(best);
        }
        if (best != null) {
            submit(best, roomId, sessionId, recordId, originalRecordId);
        }
    }

    private void startHousekeeper() {
        if (housekeeperStarted) return;
        housekeeperStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    try {
                        Thread.sleep(2000);
                        housekeep();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "muse-hook-housekeeper");
        t.setDaemon(true);
        t.start();
    }

    private void housekeep() {
        // Flush pending queries that never saw an outbound send (typed-input
        // edge cases): older than 8s they are submitted as-is.
        List<Pending> flush = new ArrayList<>();
        synchronized (pending) {
            for (int i = pending.size() - 1; i >= 0; i--) {
                Pending p = pending.get(i);
                if (System.currentTimeMillis() - p.ts > 8000) {
                    flush.add(p);
                    pending.remove(i);
                }
            }
        }
        for (Pending p : flush) {
            submit(p, p.roomId, "", p.recordId, "");
        }
    }

    private void submit(Pending p, String roomId, String sessionId, String recordId, String originalRecordId) {
        if (!takeoverOn()) {
            appendFile("{\"event\":\"submit_skipped_takeover_off\",\"text\":\"" + esc(p.text) + "\"}");
            return;
        }
        if (RelaySecret.MUSE2API_KEY.isEmpty()) {
            appendFile("{\"event\":\"submit_failed_no_key\"}");
            return;
        }
        String rid = roomId != null && !roomId.isEmpty() ? roomId : nullToEmpty(p.roomId);
        String key = roomCore(rid) + "|" + normText(p.text);
        long now = System.currentTimeMillis();
        Long seen = submittedKeys.get(key);
        if (seen != null && now - seen < 10 * 60 * 1000) {
            appendFile("{\"event\":\"submit_skipped_dup\",\"text\":\"" + esc(p.text) + "\"}");
            return;
        }
        if (normText(p.text).equals(lastText) && now - lastTextTs < 30 * 1000) {
            appendFile("{\"event\":\"submit_skipped_dup_global\",\"text\":\"" + esc(p.text) + "\"}");
            return;
        }
        submittedKeys.put(key, now);
        lastText = normText(p.text);
        lastTextTs = now;
        if (!rid.isEmpty()) claimedRooms.put(roomCore(rid), now);

        final Awaiting a = new Awaiting();
        a.text = p.text;
        a.roomId = rid;
        a.recordId = recordId != null && !recordId.isEmpty() ? recordId : nullToEmpty(p.recordId);
        a.sessionId = nullToEmpty(sessionId);
        a.originalRecordId = nullToEmpty(originalRecordId);
        a.submittedTs = now;
        a.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                appendFile("{\"event\":\"submitted\",\"id\":\"" + a.id
                        + "\",\"text\":\"" + esc(a.text) + "\"}");
                String answer = callMuse(a.text);
                if (answer == null || answer.isEmpty()) {
                    appendFile("{\"event\":\"answer_failed\",\"id\":\"" + a.id + "\"}");
                    injectCard(FAILURE_CARD, a);
                    return;
                }
                appendFile("{\"event\":\"reply_received\",\"id\":\"" + a.id
                        + "\",\"answer\":\"" + esc(answer) + "\"}");
                injectCard(answer, a);
                persistHistory(a, answer);
            }
        }, "muse-hook-muse2api");
        t.setDaemon(true);
        t.start();
    }

    // ================= injection (Eta recipe, plain-Java reflection) =================

    /** P2-4: a native answer bean (chatType=2) into a claimed room is
     *  suppressed, unless it is one of our own injected cards (marker in the
     *  payload, or content we registered in injectCard). */
    private boolean shouldSuppressNativeAnswer(Object bean) {
        if (bean == null || !takeoverOn()) return false;
        if (!"2".equals(strField(bean, "chatType"))) return false;
        String roomId = strField(bean, "roomId");
        if (roomId == null || roomId.isEmpty()) return false;
        Long claimed = claimedRooms.get(roomCore(roomId));
        if (claimed == null || System.currentTimeMillis() - claimed > CLAIM_TTL_MS) return false;
        String payload = strField(bean, "payload");
        if (payload != null && payload.contains(MARKER_KEY)) return false;
        String content = strField(bean, "content");
        if (content != null && !content.isEmpty()) {
            Long inj = injectedContents.get(roomCore(roomId) + "\n" + content);
            if (inj != null && System.currentTimeMillis() - inj < CLAIM_TTL_MS) return false;
        }
        return true;
    }

    /** Room ids come in two forms ("<hex>" and "<hex>-<ts>-default"); the
     *  stable identity is the part before the first dash. */
    private static String roomCore(String roomId) {
        if (roomId == null) return "";
        int dash = roomId.indexOf('-');
        return dash > 0 ? roomId.substring(0, dash) : roomId;
    }

    /** Dedupe key form of a query: trailing punctuation/ASR variants of the
     *  same sentence ("你是谁" vs "你是谁？") must collapse into one submit. */
    private static String normText(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (!t.isEmpty() && "？?。.！!，,、；;：:~～…".indexOf(t.charAt(t.length() - 1)) >= 0) {
            t = t.substring(0, t.length() - 1).trim();
        }
        return t;
    }

    /** Inject one final StreamTextCard answer into Breeno's live chat. */
    private void injectCard(String content, Awaiting a) {
        // Register before dispatching: our own card may synchronously come
        // back through the data-center dispatch as a chatType=2 bean.
        injectedContents.put(roomCore(a.roomId) + "\n" + content, System.currentTimeMillis());
        try {
            ClassLoader cl = this.cl;
            if (cl == null) return;
            String uniqueId = String.valueOf(System.currentTimeMillis());
            String headerId = UUID.randomUUID().toString().replace("-", "");

            JSONObject payloadJson = new JSONObject();
            payloadJson.put("content", content);
            payloadJson.put("roomId", a.roomId);
            payloadJson.put("isFinal", true);
            payloadJson.put("type", 2);
            payloadJson.put("query", a.text);
            payloadJson.put("isHtml", false);
            payloadJson.put("charPerSec", 50);
            JSONObject headerJson = new JSONObject();
            headerJson.put("id", headerId);
            headerJson.put("namespace", "MyAI");
            headerJson.put("name", "StreamTextCard");
            headerJson.put("namespaceVersion", "2.0.0");
            headerJson.put("version", "3.2");
            JSONObject directiveJson = new JSONObject();
            directiveJson.put("header", headerJson);
            directiveJson.put("payload", payloadJson);

            JSONObject origin = new JSONObject();
            origin.put("version", "3.0");
            origin.put("originalRecordId", a.originalRecordId.isEmpty() ? a.recordId : a.originalRecordId);
            origin.put("recordId", a.recordId);
            origin.put("sessionId", a.sessionId);
            origin.put("roomId", a.roomId);
            origin.put("uniqueId", uniqueId);
            origin.put("sequenceId", 0);
            origin.put("extend", new JSONObject().put(MARKER_KEY, "true"));
            origin.put("directives", new JSONArray().put(directiveJson));

            Class<?> headerCls = Class.forName(DIRECTIVE_HEADER, false, cl);
            Object header = headerCls.getDeclaredConstructor().newInstance();
            setCoerced(header, "setId", headerId);
            setCoerced(header, "setNamespace", "MyAI");
            setCoerced(header, "setName", "StreamTextCard");
            setCoerced(header, "setNamespaceVersion", "2.0.0");
            setCoerced(header, "setVersion", "3.2");

            Class<?> cardCls = Class.forName(STREAM_CARD, false, cl);
            Object payload = cardCls.getDeclaredConstructor().newInstance();
            setCoerced(payload, "setContent", content);
            setCoerced(payload, "setRoomId", a.roomId);
            setCoerced(payload, "setFinal", Boolean.TRUE);
            setCoerced(payload, "setType", 2);
            setCoerced(payload, "setQuery", a.text);
            setCoerced(payload, "setHtml", Boolean.FALSE);
            setCoerced(payload, "setCharPerSec", 50);

            Class<?> directiveCls = Class.forName(DIRECTIVE, false, cl);
            Object directive = directiveCls.getDeclaredConstructor().newInstance();
            setCoerced(directive, "setHeader", header);
            setCoerced(directive, "setPayload", payload);

            Object agent = resolveAgent(cl);
            if (agent == null) {
                appendFile("{\"event\":\"inject_failed\",\"why\":\"agent_null\",\"content\":\""
                        + esc(content) + "\"}");
                return;
            }
            Method dispatch = findInstanceMethod(agent.getClass(),
                    void.class, new String[]{"java.util.List", "java.lang.String"});
            if (dispatch == null) {
                appendFile("{\"event\":\"inject_failed\",\"why\":\"dispatch_method_missing\"}");
                return;
            }
            List<Object> directives = new ArrayList<>();
            directives.add(directive);
            dispatch.setAccessible(true);
            dispatch.invoke(agent, directives, origin.toString());
            appendFile("{\"event\":\"injected\",\"method\":\"" + dispatch.getName()
                    + "\",\"content\":\"" + esc(content) + "\"}");
        } catch (Throwable e) {
            appendFile("{\"event\":\"inject_error\",\"err\":\"" + esc(String.valueOf(e)) + "\"}");
        }
    }

    private Object resolveAgent(ClassLoader cl) {
        try {
            Class<?> engineCls = Class.forName(ENGINE, false, cl);
            Object engine = null;
            try {
                Method getInstance = engineCls.getDeclaredMethod("getInstance");
                if (Modifier.isStatic(getInstance.getModifiers())) {
                    getInstance.setAccessible(true);
                    engine = getInstance.invoke(null);
                }
            } catch (Throwable ignored) {
            }
            if (engine == null) {
                for (String fieldName : new String[]{"Companion", "INSTANCE"}) {
                    try {
                        Field f = engineCls.getDeclaredField(fieldName);
                        f.setAccessible(true);
                        Object companion = f.get(null);
                        if (companion != null) {
                            engine = invokeNoArg(companion, "getInstance");
                            if (engine != null) break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (engine == null) return null;
            Object agent = invokeNoArg(engine, "getMAgent");
            if (agent == null) agent = invokeNoArg(engine, "getAgent");
            if (agent == null) {
                try {
                    Field f = engine.getClass().getDeclaredField("mAgent");
                    f.setAccessible(true);
                    agent = f.get(engine);
                } catch (Throwable ignored) {
                }
            }
            return agent;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Persist the Q/A pair into Breeno history (best effort; live card first). */
    private void persistHistory(Awaiting a, String answer) {
        try {
            ClassLoader cl = this.cl;
            if (cl == null) return;
            Class<?> repoCls = Class.forName(REPOSITORY, false, cl);
            Object repo = singletonOf(repoCls);
            if (repo == null) {
                appendFile("{\"event\":\"history_failed\",\"why\":\"repo_null\"}");
                return;
            }
            Class<?> recordCls = Class.forName(INSERT_RECORD, false, cl);
            Class<?> fn1Cls = Class.forName("kotlin.jvm.functions.Function1", false, cl);
            Method insert = findInstanceMethod(repoCls, void.class,
                    new String[]{"java.lang.String", "java.lang.String", INSERT_RECORD, "kotlin.jvm.functions.Function1"});
            if (insert == null) {
                appendFile("{\"event\":\"history_failed\",\"why\":\"insert_method_missing\"}");
                return;
            }
            String payload = new JSONObject()
                    .put("uiDirectives", new JSONArray().put(new JSONObject()
                            .put("header", new JSONObject()
                                    .put("id", UUID.randomUUID().toString().replace("-", ""))
                                    .put("namespace", "MyAI")
                                    .put("name", "StreamTextCard")
                                    .put("namespaceVersion", "2.0.0")
                                    .put("version", "3.2"))
                            .put("payload", new JSONObject()
                                    .put("content", answer)
                                    .put("roomId", a.roomId)
                                    .put("isFinal", true)
                                    .put("type", 2)
                                    .put("query", a.text)
                                    .put("isHtml", false)
                                    .put("charPerSec", 50))))
                    .toString();
            Object queryRecord = newRecord(recordCls, a.recordId, a.text, "Q", null);
            Object answerRecord = newRecord(recordCls, a.recordId, answer, "A", payload);
            Object callback = newCallback(cl, fn1Cls);
            insert.setAccessible(true);
            insert.invoke(repo, a.roomId, "default", queryRecord, callback);
            insert.invoke(repo, a.roomId, "default", answerRecord, callback);
            appendFile("{\"event\":\"history_saved\",\"recordId\":\"" + esc(a.recordId) + "\"}");
        } catch (Throwable e) {
            appendFile("{\"event\":\"history_error\",\"err\":\"" + esc(String.valueOf(e)) + "\"}");
        }
    }

    private Object newRecord(Class<?> recordCls, String recordId, String content, String type, String payload) throws Exception {
        Object record = recordCls.getDeclaredConstructor().newInstance();
        setCoerced(record, "setRecordId", recordId);
        setCoerced(record, "setOriginRecordId", recordId);
        setCoerced(record, "setContent", content);
        setCoerced(record, "setType", type);
        setCoerced(record, "setCancelFlag", Boolean.FALSE);
        if (payload != null) setCoerced(record, "setPayload", payload);
        return record;
    }

    private Object newCallback(ClassLoader cl, Class<?> fn1Cls) {
        Object unit = null;
        try {
            Class<?> unitCls = Class.forName("kotlin.Unit", false, cl);
            Field f = unitCls.getDeclaredField("INSTANCE");
            f.setAccessible(true);
            unit = f.get(null);
        } catch (Throwable ignored) {
        }
        final Object u = unit;
        return Proxy.newProxyInstance(cl, new Class<?>[]{fn1Cls}, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                return u;
            }
        });
    }

    // ================= muse2api direct call =================

    /** Send the user's words to muse2api (fixed side chat "小布专线") and
     *  return the reply text cleaned for readback, or null on any failure. */
    private String callMuse(String text) {
        HttpURLConnection c = null;
        try {
            JSONObject req = new JSONObject();
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "user").put("content", text));
            req.put("messages", msgs);
            c = (HttpURLConnection) new URL(RelaySecret.MUSE2API_URL).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(300000);
            c.setRequestProperty("Authorization", "Bearer " + RelaySecret.MUSE2API_KEY);
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            OutputStream os = c.getOutputStream();
            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = c.getResponseCode();
            if (code != 200) {
                appendFile("{\"event\":\"muse2api_http\",\"code\":" + code + "}");
                return null;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null && sb.length() < 262144) sb.append(line);
            r.close();
            String content = new JSONObject(sb.toString())
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "");
            int cut = content.indexOf("[usage]");
            if (cut >= 0) content = content.substring(0, cut);
            return content.trim();
        } catch (Throwable e) {
            appendFile("{\"event\":\"muse2api_error\",\"err\":\"" + esc(String.valueOf(e)) + "\"}");
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ================= takeover switch =================

    private boolean takeoverOn() {
        long now = System.currentTimeMillis();
        if (now - takeoverCacheTs < 5000) return takeoverCache;
        takeoverCacheTs = now;
        boolean on = true;
        try {
            File f = new File(Environment.getExternalStorageDirectory(), "MuseShell/breeno_takeover");
            if (f.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(f));
                String v = r.readLine();
                r.close();
                on = v == null || !"0".equals(v.trim());
            }
        } catch (Throwable ignored) {
            on = true;
        }
        takeoverCache = on;
        return on;
    }

    // ================= reflection helpers =================

    private static Object getFieldDeep(Object target, String name) {
        for (Class<?> k = target.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String strField(Object target, String name) {
        Object v = getFieldDeep(target, name);
        return v == null ? null : String.valueOf(v);
    }

    private static Object invokeNoArg(Object target, String name) {
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable e) {
            try {
                Method m = target.getClass().getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable e2) {
                return null;
            }
        }
    }

    private static Object singletonOf(Class<?> cls) {
        for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v != null && cls.isInstance(v)) return v;
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            Method m = cls.getDeclaredMethod("getInstance");
            if (Modifier.isStatic(m.getModifiers())) {
                m.setAccessible(true);
                return m.invoke(null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Find an instance method by exact return + parameter type names, walking
     *  the class hierarchy. Mirrors Eta's structural matching. */
    private static Method findInstanceMethod(Class<?> cls, Class<?> returnType, String[] paramTypeNames) {
        for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != returnType) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != paramTypeNames.length) continue;
                boolean ok = true;
                for (int i = 0; i < p.length; i++) {
                    if (!p[i].getName().equals(paramTypeNames[i])) { ok = false; break; }
                }
                if (ok) return m;
            }
        }
        return null;
    }

    /** Eta-style compatible setter call: find a 1-arg method by name whose
     *  parameter accepts the value (with primitive/wrapper coercion). */
    private static boolean setCoerced(Object target, String name, Object value) {
        for (Class<?> k = target.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1) continue;
                if (!accepts(p[0], value)) continue;
                try {
                    m.setAccessible(true);
                    m.invoke(target, coerce(p[0], value));
                    return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private static boolean accepts(Class<?> param, Object value) {
        if (value == null) return !param.isPrimitive();
        Class<?> vc = value.getClass();
        if (param.isAssignableFrom(vc)) return true;
        if (param == boolean.class && vc == Boolean.class) return true;
        if (param == int.class && vc == Integer.class) return true;
        if (param == long.class && (vc == Long.class || vc == Integer.class)) return true;
        if (param == float.class && (vc == Float.class || vc == Integer.class)) return true;
        if (param == double.class && (vc == Double.class || vc == Integer.class)) return true;
        return false;
    }

    private static Object coerce(Class<?> param, Object value) {
        if (value instanceof Number) {
            Number n = (Number) value;
            if (param == int.class) return n.intValue();
            if (param == long.class) return n.longValue();
            if (param == float.class) return n.floatValue();
            if (param == double.class) return n.doubleValue();
        }
        return value;
    }

    // ================= capture plumbing (P1, kept) =================

    private void xlog(String s) {
        try {
            log(Log.INFO, TAG, s);
        } catch (Throwable ignored) {
        }
    }

    private synchronized void appendFile(String line) {
        try {
            File f = hookLogFile();
            if (f == null) return;
            FileWriter w = new FileWriter(f, true);
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            if (!installedLineWritten) {
                installedLineWritten = true;
                w.write("{\"ts\":\"" + ts + "\",\"event\":\"hooks_installed\",\"version\":\"0.6.0\"}\n");
            }
            if (line.startsWith("{")) {
                w.write("{\"ts\":\"" + ts + "\"," + line.substring(1));
            } else {
                w.write("{\"ts\":\"" + ts + "\",\"raw\":\"" + esc(line) + "\"}");
            }
            w.write("\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static File hookLogFile() {
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app == null) return null;
            File dir = (File) app.getClass().getMethod("getFilesDir").invoke(app);
            return new File(dir, "muse_hook.jsonl");
        } catch (Throwable t) {
            return null;
        }
    }

    static String dumpJson(Object o) {
        StringBuilder sb = new StringBuilder();
        dump(o, 0, sb);
        return sb.toString();
    }

    private static void dump(Object o, int depth, StringBuilder sb) {
        if (o == null) {
            sb.append("null");
            return;
        }
        Class<?> c = o.getClass();
        if (o instanceof String || o instanceof Number || o instanceof Boolean || c.isEnum()) {
            sb.append('"').append(esc(String.valueOf(o))).append('"');
            return;
        }
        if (o instanceof List) {
            List<?> list = (List<?>) o;
            sb.append('[');
            int n = Math.min(list.size(), 6);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                if (depth >= 3) {
                    sb.append("\"<item>\"");
                } else {
                    dump(list.get(i), depth + 1, sb);
                }
            }
            if (list.size() > n) sb.append(",\"+").append(list.size() - n).append(" more\"");
            sb.append(']');
            return;
        }
        if (o instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) o;
            sb.append('{');
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (i >= 8) { sb.append(",\"_more\":true"); break; }
                if (i > 0) sb.append(',');
                sb.append('"').append(esc(String.valueOf(e.getKey()))).append("\":");
                if (depth >= 3) sb.append("\"<v>\"");
                else dump(e.getValue(), depth + 1, sb);
                i++;
            }
            sb.append('}');
            return;
        }
        if (c.isArray()) {
            sb.append('[');
            int n = Math.min(Array.getLength(o), 6);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                if (depth >= 3) sb.append("\"<item>\"");
                else dump(Array.get(o, i), depth + 1, sb);
            }
            sb.append(']');
            return;
        }
        if (depth >= 3 || !c.getName().startsWith("com.heytap.")) {
            sb.append('"').append('<').append(c.getSimpleName()).append('>').append('"');
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Object v;
                try {
                    f.setAccessible(true);
                    v = f.get(o);
                } catch (Throwable t) {
                    continue;
                }
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(f.getName()).append("\":");
                dump(v, depth + 1, sb);
                if (sb.length() > 9000) {
                    sb.append(",\"_truncated\":true}");
                    return;
                }
            }
        }
        sb.append('}');
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        String t = s.length() > 800 ? s.substring(0, 800) + "…" : s;
        StringBuilder sb = new StringBuilder(t.length() + 8);
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(' ');
                    else sb.append(ch);
            }
        }
        return sb.toString();
    }
}
