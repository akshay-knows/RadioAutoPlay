package com.radioautoplay;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persistent diagnostics logger to trace stream lifecycle and failures.
 */
public class DiagnosticsLogger {

    private static final String TAG = "DiagnosticsLogger";
    private static final String LOG_NAME = "radio_diagnostics.log";
    private static final String LOG_OLD_NAME = "radio_diagnostics.log.1";
    private static final long MAX_LOG_BYTES = 1024L * 1024L;

    private final File logFile;
    private final File oldLogFile;
    private final SimpleDateFormat timestampFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    public DiagnosticsLogger(Context context) {
        File dir = new File(context.getFilesDir(), "logs");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create logs directory: " + dir.getAbsolutePath());
        }
        logFile = new File(dir, LOG_NAME);
        oldLogFile = new File(dir, LOG_OLD_NAME);
    }

    public synchronized void i(String tag, String message) {
        write("INFO", tag, message, null);
    }

    public synchronized void w(String tag, String message) {
        write("WARN", tag, message, null);
    }

    public synchronized void e(String tag, String message, Throwable throwable) {
        write("ERROR", tag, message, throwable);
    }

    public String getLogPath() {
        return logFile.getAbsolutePath();
    }

    private void rotateIfNeeded() {
        if (!logFile.exists()) return;
        if (logFile.length() < MAX_LOG_BYTES) return;
        if (oldLogFile.exists() && !oldLogFile.delete()) {
            Log.w(TAG, "Could not delete old log file");
        }
        if (!logFile.renameTo(oldLogFile)) {
            Log.w(TAG, "Could not rotate diagnostics log");
        }
    }

    private void write(String level, String tag, String message, Throwable throwable) {
        rotateIfNeeded();
        String ts = timestampFormat.format(new Date());
        String line = ts + " " + level + "/" + tag + " " + message;
        try (FileWriter writer = new FileWriter(logFile, true);
             PrintWriter out = new PrintWriter(writer)) {
            out.println(line);
            if (throwable != null) {
                throwable.printStackTrace(out);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not write diagnostics log", e);
        }
    }
}
