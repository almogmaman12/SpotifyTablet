package com.almog.spotifytablet;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps the last crash (time, thread, stack trace) in a small file, so the debug page can show what
 * went wrong after the app restarts. The crash still goes on to Android's normal handling.
 */
public final class CrashReporter {
    private CrashReporter() {}

    private static final String FILE = "last_crash.txt";
    private static final int MAX_CHARS = 60_000;

    public static void install(Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            record(app, thread, error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** Saves a crash (time, thread, stack trace) for the debug page. Never throws. */
    public static void record(Context context, Thread thread, Throwable error) {
        try {
            final Context app = context.getApplicationContext();
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            String version = "?";
            try {
                version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
            } catch (Exception ignored) {
            }
            Runtime rt = Runtime.getRuntime();
            String text = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "\nThread: " + thread.getName()
                    + "\nMemory: " + ((rt.totalMemory() - rt.freeMemory()) >> 20) + " MB used of " + (rt.maxMemory() >> 20) + " MB"
                    + "\nApp " + version + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT
                    + "), " + Build.MANUFACTURER + " " + Build.MODEL + "\n\n" + trace;
            if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS);
            try (FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), FILE))) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // Never let reporting hide the real crash.
        }
    }

    /**
     * Why the app's recent runs ended, from Android's own record. This catches what the crash file cannot: the system
     * stopping the app for memory, the app not responding, or a crash in native code (none of them reach the Java handler).
     */
    public static String exitHistory(Context context) {
        if (Build.VERSION.SDK_INT < 30) return "Android 11 or newer is needed for the exit history.";
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            java.util.List<android.app.ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 8);
            if (list == null || list.isEmpty()) return "No earlier exits recorded.";
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            StringBuilder sb = new StringBuilder();
            for (android.app.ApplicationExitInfo e : list) {
                sb.append(fmt.format(new Date(e.getTimestamp()))).append("  ").append(reasonName(e.getReason()))
                        .append("  (status ").append(e.getStatus()).append(", ")
                        .append(e.getImportance() <= 125 ? "was on screen" : "was in the background")
                        .append(", memory ").append(e.getRss() >> 10).append(" MB)\n");
                if (e.getDescription() != null && !e.getDescription().isEmpty()) sb.append("    ").append(e.getDescription()).append('\n');
                int r = e.getReason();
                if (r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE || r == android.app.ApplicationExitInfo.REASON_ANR) {
                    try (java.io.InputStream in = e.getTraceInputStream()) {
                        if (in != null) {
                            byte[] buf = new byte[3000];
                            int n = in.read(buf);
                            if (n > 0) sb.append("    trace: ").append(new String(buf, 0, n, StandardCharsets.UTF_8).replace("\n", "\n    ")).append('\n');
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "Could not read the exit history: " + t;
        }
    }

    private static String reasonName(int r) {
        switch (r) {
            case android.app.ApplicationExitInfo.REASON_CRASH: return "CRASH (Java)";
            case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH (native)";
            case android.app.ApplicationExitInfo.REASON_ANR: return "NOT RESPONDING (ANR)";
            case android.app.ApplicationExitInfo.REASON_LOW_MEMORY: return "KILLED, LOW MEMORY";
            case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "KILLED, TOO MUCH RESOURCE USE";
            case android.app.ApplicationExitInfo.REASON_SIGNALED: return "KILLED BY SIGNAL";
            case android.app.ApplicationExitInfo.REASON_USER_REQUESTED: return "closed by you";
            case android.app.ApplicationExitInfo.REASON_USER_STOPPED: return "force-stopped";
            case android.app.ApplicationExitInfo.REASON_EXIT_SELF: return "app exited itself";
            case android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "a dependency died";
            case android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "permission changed";
            case android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "failed to start";
            case android.app.ApplicationExitInfo.REASON_OTHER: return "other";
            default: return "unknown (" + r + ")";
        }
    }

    /** The last crash's text, or null if there has been none. */
    public static String read(Context context) {
        File f = new File(context.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    public static void clear(Context context) {
        //noinspection ResultOfMethodCallIgnored
        new File(context.getFilesDir(), FILE).delete();
    }
}
