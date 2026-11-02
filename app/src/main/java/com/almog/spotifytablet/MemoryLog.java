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
    private static final long EVERY_MS = 60_000L;
    private static boolean started;

    public static synchronized void start(Context context) {
        if (started) return;
        started = true;
        final Context app = context.getApplicationContext();
        final Handler handler = new Handler(Looper.getMainLooper());
        final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        handler.post(new Runnable() {
            @Override public void run() {
                try {
                    write(app, sample(fmt));
                } catch (Throwable ignored) {
                    // Logging must never be the problem.
                }
                handler.postDelayed(this, EVERY_MS);
            }
        });
    }

    private static String sample(SimpleDateFormat fmt) {
        Runtime rt = Runtime.getRuntime();
        long javaUsed = (rt.totalMemory() - rt.freeMemory()) >> 20;
        long nativeHeap = Debug.getNativeHeapAllocatedSize() >> 20;
        Debug.MemoryInfo mi = new Debug.MemoryInfo();
        Debug.getMemoryInfo(mi);
        String gfx = mi.getMemoryStat("summary.graphics");
        String code = mi.getMemoryStat("summary.code");
        return fmt.format(new Date()) + "  java " + javaUsed + " MB  native " + nativeHeap + " MB  graphics "
                + kb(gfx) + " MB  code " + kb(code) + " MB  total " + (mi.getTotalPss() >> 10) + " MB";
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
