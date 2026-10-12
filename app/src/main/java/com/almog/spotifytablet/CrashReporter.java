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
            try {
                StringWriter trace = new StringWriter();
                error.printStackTrace(new PrintWriter(trace));
                String version = "?";
                try {
                    version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
                } catch (Exception ignored) {
                }
                String text = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                        + "\nThread: " + thread.getName()
                        + "\nApp " + version + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT
                        + "), " + Build.MANUFACTURER + " " + Build.MODEL + "\n\n" + trace;
                if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS);
                try (FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), FILE))) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
                // Never let reporting hide the real crash.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
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
