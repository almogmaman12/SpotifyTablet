package com.almog.spotifytablet;

import android.content.Context;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One line a minute about the app's memory, kept in a small file. Android can end the app without leaving any
 * trace of its own; these lines survive that and show what was growing in the minutes before.
 */
public final class MemoryLog {
    private MemoryLog() {}

    private static final String FILE = "memory_log.txt";
    private static final int KEEP = 240; // four hours
    private static final long EVERY_MS = 20_000L;      // memory check
    private static final int LOG_EVERY = 3;              // every third check also writes a line (once a minute)
    private static final long GUARD_COOLDOWN_MS = 60_000L;
    private static final long TIGHT_NATIVE_PLUS_JAVA_MB = 130;
    private static long lastGuard;
    private static int ticks;
    private static boolean started;

    // Lyrics drawing creates thousands of small native objects a minute (a gradient shader per lit word, per frame).
    // They are freed when Java's garbage collector reaches their Java handles, but the Java heap here is tiny, so it
    // runs only every 10 to 15 minutes and the native side balloons to 100 MB or more before then. This asks for a
    // collection sooner, off the main thread, so Android has no reason to kill the app for memory.
    private static final long NATIVE_LIMIT_BYTES = 70L << 20;

    public static synchronized void start(Context context) {
        if (started) return;
        started = true;
        final Context app = context.getApplicationContext();
        final Handler handler = new Handler(Looper.getMainLooper());
        final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        handler.post(new Runnable() {
            @Override public void run() {
                try {
                    ticks++;
                    String note = guard(app, fmt);
                    if (ticks % LOG_EVERY == 1 || !note.isEmpty()) write(app, sample(app, fmt) + note);
                } catch (Throwable ignored) {
                    // Logging must never be the problem.
                }
                handler.postDelayed(this, EVERY_MS);
            }
        });
    }

    /**
     * Acts before Android does: when the app or the whole tablet is short of memory, frees what can be fetched again
     * (old pictures, cached lyrics, text-layout garbage). Cheap to check, and it runs at most once a minute.
     */
    private static String guard(Context app, SimpleDateFormat fmt) {
        Runtime rt = Runtime.getRuntime();
        long mine = ((rt.totalMemory() - rt.freeMemory()) + Debug.getNativeHeapAllocatedSize()) >> 20;
        android.app.ActivityManager.MemoryInfo si = new android.app.ActivityManager.MemoryInfo();
        android.app.ActivityManager am = (android.app.ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        boolean systemTight = false;
        if (am != null) {
            am.getMemoryInfo(si);
            systemTight = si.lowMemory || si.availMem < si.threshold * 2L;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        boolean nativeHigh = Debug.getNativeHeapAllocatedSize() > NATIVE_LIMIT_BYTES;
        boolean appTight = mine >= TIGHT_NATIVE_PLUS_JAVA_MB;
        if ((!nativeHigh && !appTight && !systemTight) || now - lastGuard < GUARD_COOLDOWN_MS) return "";
        lastGuard = now;
        if (appTight || systemTight) {
            try {
                com.bumptech.glide.Glide.get(app).clearMemory();
            } catch (Throwable ignored) {
            }
            com.almog.spotifytablet.lyrics.repository.LyricsRepository.trimMemory();
        }
        cleanUp(app, fmt); // the collection itself, which notes native memory before and after in the log
        return systemTight ? "  [tablet short of memory]" : appTight ? "  [app memory high]" : "";
    }

    private static String sample(Context app, SimpleDateFormat fmt) {
        Runtime rt = Runtime.getRuntime();
        long javaUsed = (rt.totalMemory() - rt.freeMemory()) >> 20;
        long nativeHeap = Debug.getNativeHeapAllocatedSize() >> 20;
        Debug.MemoryInfo mi = new Debug.MemoryInfo();
        Debug.getMemoryInfo(mi);
        String gfx = mi.getMemoryStat("summary.graphics");
        String code = mi.getMemoryStat("summary.code");
        long avail = -1;
        android.app.ActivityManager am = (android.app.ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            android.app.ActivityManager.MemoryInfo si = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(si);
            avail = si.availMem >> 20;
        }
        return fmt.format(new Date()) + "  java " + javaUsed + " MB  native " + nativeHeap + " MB  graphics "
                + kb(gfx) + " MB  code " + kb(code) + " MB  total " + (mi.getTotalPss() >> 10) + " MB  tablet free " + avail + " MB";
    }

    private static String kb(String value) {
        try { return String.valueOf(Long.parseLong(value) >> 10); } catch (Exception e) { return "?"; }
    }

    private static synchronized void write(Context app, String line) throws Exception {
        List<String> lines = readLines(app);
        lines.add(line);
        while (lines.size() > KEEP) lines.remove(0);
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        try (FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), FILE))) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static List<String> readLines(Context app) {
        List<String> out = new ArrayList<>();
        File f = new File(app.getFilesDir(), FILE);
        if (!f.exists()) return out;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) out.add(l);
        } catch (Exception ignored) {
        }
        return out;
    }

    /** The newest [count] lines, oldest first, for the debug page. */
    public static synchronized String recent(Context context, int count) {
        List<String> lines = readLines(context.getApplicationContext());
        if (lines.isEmpty()) return "Nothing yet. A line is added every minute while the app runs.";
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.size() - count); i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
        return sb.toString();
    }
}
