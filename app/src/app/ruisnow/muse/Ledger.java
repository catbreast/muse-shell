package app.ruisnow.muse;

import android.content.Context;
import android.os.Environment;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Append-only JSONL ledger of every tool call / approval / lifecycle event. */
public class Ledger {
    private static final Object LOCK = new Object();
    private static File dir;

    public static synchronized void init(Context c) {
        File ext = new File(Environment.getExternalStorageDirectory(), "MuseShell");
        if (dir != null) {
            // Reprobe: if we fell back to the app dir at first init (perms not yet
            // granted) but /sdcard/MuseShell is writable now, switch to it.
            if (!dir.equals(ext) && extWritable(ext)) dir = ext;
            return;
        }
        File target = ext;
        try {
            if (!ext.exists()) ext.mkdirs();
            File probe = new File(ext, ".probe");
            FileWriter w = new FileWriter(probe);
            w.write("ok");
            w.close();
            probe.delete();
        } catch (Exception e) {
            target = c.getExternalFilesDir(null);
            if (target == null) target = c.getFilesDir();
        }
        dir = target;
    }

    private static boolean extWritable(File ext) {
        try {
            if (!ext.exists()) ext.mkdirs();
            File probe = new File(ext, ".probe");
            FileWriter w = new FileWriter(probe);
            w.write("ok");
            w.close();
            probe.delete();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static File dir(Context c) {
        init(c);
        return dir;
    }

    private static final long ROTATE_BYTES = 2L * 1024 * 1024;
    private static final int TAIL_BYTES = 64 * 1024;

    public static void log(Context c, String event, JSONObject details) {
        try {
            init(c);
            JSONObject row = new JSONObject();
            row.put("ts", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            row.put("event", event);
            if (details != null) row.put("details", details);
            synchronized (LOCK) {
                File f = new File(dir, "ledger.jsonl");
                if (f.length() > ROTATE_BYTES) {
                    File old = new File(dir, "ledger.1.jsonl");
                    if (old.exists()) old.delete();
                    f.renameTo(old);
                }
                FileWriter w = new FileWriter(f, true);
                w.write(row.toString());
                w.write("\n");
                w.close();
            }
        } catch (Exception ignored) {
        }
    }

    /** Delete the ledger (current + rotated). Returns true if a file was removed. */
    public static boolean clear(Context c) {
        try {
            init(c);
            synchronized (LOCK) {
                boolean any = false;
                File f = new File(dir, "ledger.jsonl");
                if (f.exists()) any = f.delete() || any;
                File old = new File(dir, "ledger.1.jsonl");
                if (old.exists()) any = old.delete() || any;
                return any;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static String recent(Context c, int n) {
        try {
            init(c);
            File f = new File(dir, "ledger.jsonl");
            if (!f.exists() || f.length() == 0) return "(暂无日志)";
            // Read only the tail of the file: the ledger grows unboundedly and the
            // console used to load all of it on the UI thread (visible jank).
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            long start = Math.max(0, len - TAIL_BYTES);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            raf.close();
            String text = new String(buf, "UTF-8");
            String[] lines = text.split("\n");
            StringBuilder sb = new StringBuilder();
            int from = Math.max(start > 0 ? 1 : 0, lines.length - n);
            for (int i = from; i < lines.length; i++) {
                if (lines[i].length() > 0) sb.append(lines[i]).append("\n");
            }
            return sb.length() == 0 ? "(暂无日志)" : sb.toString();
        } catch (Exception e) {
            return "(读取日志失败: " + e.getMessage() + ")";
        }
    }
}
