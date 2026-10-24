package app.ruisnow.muse;

import android.app.Application;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Installs an uncaught-exception logger as early as possible: any fatal crash
 * (console, service, approval card) is appended to MuseShell/crash.log so the
 * next successful connection can read back exactly what died and why.
 */
public class ShellApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        final File logFile = pickLogFile();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    FileWriter w = new FileWriter(logFile, true);
                    w.write("==== ");
                    w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
                    w.write(" thread=" + t.getName() + " ====\n");
                    w.write(sw.toString());
                    w.write("\n");
                    w.close();
                } catch (Exception ignored) {
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    private File pickLogFile() {
        try {
            File ext = new File(Environment.getExternalStorageDirectory(), "MuseShell");
            if (!ext.exists()) ext.mkdirs();
            File probe = new File(ext, ".probe");
            FileWriter w = new FileWriter(probe);
            w.write("ok");
            w.close();
            probe.delete();
            return new File(ext, "crash.log");
        } catch (Exception e) {
            File d = getExternalFilesDir(null);
            if (d == null) d = getFilesDir();
            return new File(d, "crash.log");
        }
    }
}
