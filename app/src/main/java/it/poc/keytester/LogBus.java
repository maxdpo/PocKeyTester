package it.poc.keytester;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Registro centrale: scrive su schermo, su file e su logcat (tag POCKEYS). */
public final class LogBus {

    public interface Listener {
        void onLine(String line);
    }

    public static final String TAG = "POCKEYS";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static File file;
    private static volatile Listener listener;

    private LogBus() {
    }

    public static synchronized void init(Context c) {
        if (file != null) return;
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        file = new File(dir, "poc_keylog.txt");
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    public static synchronized String path() {
        return file == null ? "?" : file.getAbsolutePath();
    }

    public static void add(String msg) {
        final String line;
        synchronized (FMT) {
            line = FMT.format(new Date()) + " " + msg;
        }
        Log.i(TAG, line);
        synchronized (LogBus.class) {
            if (file != null) {
                try (FileWriter w = new FileWriter(file, true)) {
                    w.write(line);
                    w.write('\n');
                } catch (IOException ignored) {
                }
            }
        }
        MAIN.post(() -> {
            Listener l = listener;
            if (l != null) l.onLine(line);
        });
    }
}
