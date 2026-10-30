package app.ruisnow.muse;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 0.8.0 本地调度器：手机本地持久化的定时任务（jobs.json）。
 *
 * 教训来源：瘦终端架构下，定时、等待全靠云端逐条命令喂——链路在临点
 * 抖一下，零点抢券整场就没了（2026-10-31 实证）。本地任务由
 * AlarmManager 准点唤醒（RTC_WAKEUP，息屏/Doze 也能拉起进程），在本机
 * 经 ToolDispatcher 直接执行，结果进 Outbox 待发箱：链路活着就同步到
 * 中继，断着就攒着，恢复后补发。云端只负责白天下达/修改任务，不再
 * 临点送启动命令。
 *
 * 任务形态：{id, name, tool, args, kind: daily|at|every, time:"23:55",
 * at_ms, every_min, enabled, last_run_ms, last_ok}
 * browser.* 不可入任务（依赖前台 Activity）；需要交互审批的工具在
 * jobs.add 时一次性审批授权，运行时不再弹窗。
 */
public class Jobs {
    private static final int RC = 77;
    private static final String ACTION = "app.ruisnow.muse.JOBS_DUE";
    private static File storeFile;

    private static synchronized File file(Context c) {
        if (storeFile == null) storeFile = new File(c.getFilesDir(), "jobs.json");
        return storeFile;
    }

    public static synchronized JSONArray load(Context c) {
        try {
            File f = file(c);
            if (!f.exists()) return new JSONArray();
            BufferedReader r = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return new JSONArray(sb.toString());
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static synchronized void save(Context c, JSONArray jobs) {
        try {
            File f = file(c);
            File tmp = new File(f.getPath() + ".tmp");
            FileWriter w = new FileWriter(tmp);
            w.write(jobs.toString());
            w.close();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        } catch (Exception ignored) {
        }
    }

    static long computeNext(JSONObject job, long now) {
        if (!job.optBoolean("enabled", true)) return -1;
        String kind = job.optString("kind", "");
        try {
            if (kind.equals("daily")) {
                String t = job.optString("time", "00:00");
                String[] p = t.split(":");
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(p[0].trim()));
                cal.set(Calendar.MINUTE, Integer.parseInt(p[1].trim()));
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                if (cal.getTimeInMillis() <= now) cal.add(Calendar.DAY_OF_YEAR, 1);
                return cal.getTimeInMillis();
            }
            if (kind.equals("at")) {
                long at = job.optLong("at_ms", -1);
                if (at < 0) return -1;
                if (job.optLong("last_run_ms", 0) >= at) return -1; // 已跑过
                return at;
            }
            if (kind.equals("every")) {
                long step = Math.max(1, job.optLong("every_min", 60)) * 60000L;
                long base = job.optLong("last_run_ms", 0);
                if (base <= 0) base = job.optLong("created_ms", now);
                long next = base + step;
                return next <= now ? now + 5000 : next; // 过期只补一次，不追赶
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    public static synchronized long nextDueMs(Context c) {
        long now = System.currentTimeMillis();
        long best = -1;
        JSONArray jobs = load(c);
        for (int i = 0; i < jobs.length(); i++) {
            long n = computeNext(jobs.optJSONObject(i), now);
            if (n > 0 && (best < 0 || n < best)) best = n;
        }
        return best;
    }

    public static synchronized int enabledCount(Context c) {
        int n = 0;
        JSONArray jobs = load(c);
        for (int i = 0; i < jobs.length(); i++) {
            if (jobs.optJSONObject(i).optBoolean("enabled", true)) n++;
        }
        return n;
    }

    private static PendingIntent dueIntent(Context c) {
        Intent i = new Intent(c, JobsReceiver.class);
        i.setAction(ACTION);
        return PendingIntent.getBroadcast(c, RC, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 按最早到期时间重设那一颗闹钟；无任务则取消。 */
    public static synchronized void armNext(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long next = nextDueMs(c);
            if (next < 0) {
                am.cancel(dueIntent(c));
                return;
            }
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, dueIntent(c));
                    return;
                } catch (SecurityException se) {
                    // 无准点权限时退化为允许怠速的普通闹钟（系统应用一般有权限）
                }
            }
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, dueIntent(c));
        } catch (Exception ignored) {
        }
    }

    /** 执行所有已到期任务（由 JobsReceiver / BridgeService 调用）。 */
    public static void runDue(Context c) {
        JSONArray jobs;
        synchronized (Jobs.class) {
            jobs = load(c);
        }
        long now = System.currentTimeMillis();
        ToolDispatcher dispatcher = new ToolDispatcher(c);
        boolean changed = false;
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.optJSONObject(i);
            if (job == null || !job.optBoolean("enabled", true)) continue;
            long next = computeNext(job, now);
            // 到期判定：next 已算到未来一档时，用「上一档时间 <= now」判——
            // daily/every 的 computeNext 恒在未来，故改用 last 计算：
            if (!isDue(job, now)) continue;
            String runId = "job-" + job.optString("id", "x") + "-"
                    + String.format(Locale.US, "%d", now / 1000);
            JSONObject envelope;
            try {
                JSONObject cmd = new JSONObject();
                cmd.put("id", runId);
                cmd.put("tool", job.optString("tool", ""));
                cmd.put("args", job.optJSONObject("args") != null
                        ? job.optJSONObject("args") : new JSONObject());
                envelope = dispatcher.dispatch(cmd);
            } catch (Exception e) {
                envelope = new JSONObject();
                try {
                    envelope.put("id", runId);
                    envelope.put("ok", false);
                    envelope.put("error", String.valueOf(e.getMessage()));
                } catch (Exception ignored) {
                }
            }
            Outbox.put(envelope);
            try {
                job.put("last_run_ms", System.currentTimeMillis());
                job.put("last_ok", envelope.optBoolean("ok", false));
                if (job.optString("kind", "").equals("at")) job.put("enabled", false);
                JSONObject d = new JSONObject();
                d.put("job", job.optString("id", ""));
                d.put("tool", job.optString("tool", ""));
                d.put("ok", envelope.optBoolean("ok", false));
                Ledger.log(c, "job_run", d);
            } catch (Exception ignored) {
            }
            changed = true;
        }
        if (changed) {
            synchronized (Jobs.class) {
                // 与磁盘上的最新状态合并 last_* 字段，避免覆盖期间的新增任务
                JSONArray fresh = load(c);
                for (int i = 0; i < fresh.length(); i++) {
                    JSONObject fj = fresh.optJSONObject(i);
                    for (int j = 0; j < jobs.length(); j++) {
                        JSONObject oj = jobs.optJSONObject(j);
                        if (oj.optString("id", "").equals(fj.optString("id", ""))) {
                            try {
                                fj.put("last_run_ms", oj.optLong("last_run_ms", 0));
                                fj.put("last_ok", oj.optBoolean("last_ok", false));
                                fj.put("enabled", oj.optBoolean("enabled", true));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
                save(c, fresh);
            }
        }
        armNext(c);
    }

    /** job 在 now 时刻是否到期（上一档计划时间 <= now 且未在该档跑过）。 */
    static boolean isDue(JSONObject job, long now) {
        String kind = job.optString("kind", "");
        long last = job.optLong("last_run_ms", 0);
        try {
            if (kind.equals("daily")) {
                String t = job.optString("time", "00:00");
                String[] p = t.split(":");
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(p[0].trim()));
                cal.set(Calendar.MINUTE, Integer.parseInt(p[1].trim()));
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                if (cal.getTimeInMillis() > now) cal.add(Calendar.DAY_OF_YEAR, -1);
                long sched = cal.getTimeInMillis();
                return sched <= now && last < sched;
            }
            if (kind.equals("at")) {
                long at = job.optLong("at_ms", -1);
                return at > 0 && at <= now && last < at;
            }
            if (kind.equals("every")) {
                long step = Math.max(1, job.optLong("every_min", 60)) * 60000L;
                long base = last > 0 ? last : job.optLong("created_ms", now);
                return base + step <= now;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ---- CRUD（ToolDispatcher 调用） ----

    public static synchronized JSONObject add(Context c, JSONObject a, String cmdId) throws Exception {
        String tool = a.optString("tool", "");
        if (tool.length() == 0) throw new Exception("jobs.add needs tool");
        if (tool.startsWith("browser.")) throw new Exception("jobs: browser.* 依赖前台页面，不能入本地任务");
        if (tool.startsWith("jobs.")) throw new Exception("jobs: 不能嵌套 jobs.*");
        JSONObject job = new JSONObject();
        String id = a.optString("id", "");
        if (!id.matches("[a-zA-Z0-9-]{1,32}")) {
            id = "j" + Long.toHexString(System.currentTimeMillis()).substring(3)
                    + Integer.toHexString((int) (Math.random() * 65535));
        }
        JSONArray jobs = load(c);
        List<JSONObject> kept = new ArrayList<>();
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject oj = jobs.optJSONObject(i);
            if (!oj.optString("id", "").equals(id)) kept.add(oj);
        }
        job.put("id", id);
        job.put("name", a.optString("name", tool));
        job.put("tool", tool);
        job.put("args", a.optJSONObject("args") != null ? a.optJSONObject("args") : new JSONObject());
        job.put("created_ms", System.currentTimeMillis());
        job.put("enabled", a.optBoolean("enabled", true));
        if (a.has("daily")) {
            job.put("kind", "daily");
            job.put("time", a.optString("daily", "00:00"));
        } else if (a.has("at_ms")) {
            job.put("kind", "at");
            job.put("at_ms", a.optLong("at_ms", 0));
        } else if (a.has("every_min")) {
            job.put("kind", "every");
            job.put("every_min", a.optLong("every_min", 60));
        } else {
            throw new Exception("jobs.add needs one of daily / at_ms / every_min");
        }
        JSONArray out = new JSONArray();
        for (JSONObject oj : kept) out.put(oj);
        out.put(job);
        save(c, out);
        armNext(c);
        JSONObject o = new JSONObject(job.toString());
        o.put("next_run_ms", computeNext(job, System.currentTimeMillis()));
        return o;
    }

    public static synchronized JSONObject list(Context c) throws Exception {
        JSONArray jobs = load(c);
        long now = System.currentTimeMillis();
        JSONArray arr = new JSONArray();
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject j = new JSONObject(jobs.optJSONObject(i).toString());
            j.put("next_run_ms", computeNext(j, now));
            arr.put(j);
        }
        JSONObject o = new JSONObject();
        o.put("jobs", arr);
        o.put("count", arr.length());
        return o;
    }

    public static synchronized JSONObject remove(Context c, JSONObject a) throws Exception {
        String id = a.optString("id", "");
        JSONArray jobs = load(c);
        JSONArray out = new JSONArray();
        boolean found = false;
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject oj = jobs.optJSONObject(i);
            if (oj.optString("id", "").equals(id)) found = true;
            else out.put(oj);
        }
        if (!found) throw new Exception("jobs: no such id " + id);
        save(c, out);
        armNext(c);
        JSONObject o = new JSONObject();
        o.put("removed", id);
        return o;
    }
}
