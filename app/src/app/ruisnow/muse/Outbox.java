package app.ruisnow.muse;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 0.8.0 结果待发箱 + 幂等结果缓存（二合一）。
 *
 * 旧协议（≤0.7.15）的致命伤：结果回传是单次 fire-and-forget——WS 发一次、
 * HTTP 补一次，再失败就静默丢弃。命令明明执行了，结果却永远到不了中继，
 * 调用方只看到超时，一重试非幂等命令就被执行第二遍。
 *
 * 本类把每个结果先落盘（filesDir/outbox/<id>.json），由 BridgeService 的
 * 发送线程反复投递，直到中继确认（WS result_ack 或 HTTP /result 200）才
 * 标记 acked。acked 条目保留 24 小时作幂等缓存：同 id 命令被重复投递时
 * 直接重发缓存结果、不再执行。进程被杀/重启不丢（onCreate 时续发）。
 */
public class Outbox {
    private static File dir;
    private static final long KEEP_ACKED_MS = 24L * 3600 * 1000;
    private static final int MAX_ENTRIES = 400;

    public static synchronized void init(Context c) {
        if (dir != null) return;
        dir = new File(c.getFilesDir(), "outbox");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        prune();
    }

    private static File file(String id) {
        return new File(dir, id.replaceAll("[^a-zA-Z0-9-]", "_") + ".json");
    }

    private static String read(File f) throws Exception {
        BufferedReader r = new BufferedReader(new FileReader(f));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }

    private static void write(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        FileWriter w = new FileWriter(tmp);
        w.write(s);
        w.close();
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    /** 存入一个结果信封（{id, ok, result|error}）。已存在同 id 则不覆盖。 */
    public static synchronized void put(JSONObject envelope) {
        if (dir == null) return;
        String id = envelope.optString("id", "");
        if (id.length() == 0) return;
        try {
            File f = file(id);
            if (f.exists()) {
                // 已 acked 的缓存条目被重新 put（重复投递路径）：重新标为
                // 待发，逼发送线程再投一次——内容不变。
                JSONObject e = new JSONObject(read(f));
                if (e.optBoolean("acked", false)) {
                    e.put("acked", false);
                    write(f, e.toString());
                }
                return;
            }
            JSONObject e = new JSONObject();
            e.put("result", envelope.toString());
            e.put("ts", System.currentTimeMillis());
            e.put("acked", false);
            write(f, e.toString());
            prune();
        } catch (Exception ignored) {
        }
    }

    /** 取缓存的结果信封（待发或已确认都算命中）；无则 null。 */
    public static synchronized JSONObject get(String id) {
        if (dir == null || id == null || id.length() == 0) return null;
        try {
            File f = file(id);
            if (!f.exists()) return null;
            JSONObject e = new JSONObject(read(f));
            return new JSONObject(e.optString("result", "null"));
        } catch (Exception e) {
            return null;
        }
    }

    public static synchronized boolean isAcked(String id) {
        if (dir == null || id == null) return false;
        try {
            File f = file(id);
            if (!f.exists()) return false;
            return new JSONObject(read(f)).optBoolean("acked", false);
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized void markAcked(String id) {
        if (dir == null || id == null) return;
        try {
            File f = file(id);
            if (!f.exists()) return;
            JSONObject e = new JSONObject(read(f));
            e.put("acked", true);
            e.put("acked_ts", System.currentTimeMillis());
            write(f, e.toString());
        } catch (Exception ignored) {
        }
    }

    /** 待发条目 id 列表（旧→新）。 */
    public static synchronized List<String> pendingIds() {
        List<String> out = new ArrayList<>();
        if (dir == null) return out;
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        Arrays.sort(fs, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : fs) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                JSONObject e = new JSONObject(read(f));
                if (!e.optBoolean("acked", false)) {
                    out.add(f.getName().substring(0, f.getName().length() - 5));
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static synchronized int pendingCount() {
        return pendingIds().size();
    }

    public static synchronized int totalCount() {
        if (dir == null) return 0;
        File[] fs = dir.listFiles();
        int n = 0;
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".json")) n++;
        return n;
    }

    /** acked 超 24h 删除；总量超上限先删最旧 acked、再删最旧待发。 */
    public static synchronized void prune() {
        if (dir == null) return;
        try {
            File[] fs = dir.listFiles();
            if (fs == null) return;
            Arrays.sort(fs, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            long now = System.currentTimeMillis();
            List<File> alive = new ArrayList<>();
            for (File f : fs) {
                if (!f.getName().endsWith(".json")) continue;
                boolean drop = false;
                try {
                    JSONObject e = new JSONObject(read(f));
                    if (e.optBoolean("acked", false)
                            && now - e.optLong("acked_ts", e.optLong("ts", 0)) > KEEP_ACKED_MS) {
                        drop = true;
                    }
                } catch (Exception ignored) {
                }
                if (drop) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                } else {
                    alive.add(f);
                }
            }
            int excess = alive.size() - MAX_ENTRIES;
            for (int i = 0; excess > 0 && i < alive.size(); i++, excess--) {
                //noinspection ResultOfMethodCallIgnored
                alive.get(i).delete();
            }
        } catch (Exception ignored) {
        }
    }
}
