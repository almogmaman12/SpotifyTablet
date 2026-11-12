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
    private static Context appContext;

    // The old deaths came when the total climbed about 4 MB a minute up to 200-240 MB. Since the fixes the total is flat
    // for hours (about 185-190 MB with 600 MB of the tablet free), so a fixed mark near 185 only restarted a healthy app
    // every few minutes. The app now restarts itself (at the next song change or quiet moment) only when memory is
    // really high (SOFT), or high AND still climbing, or the whole tablet is short of memory. It never restarts within
    // the first 20 minutes unless it is past HARD.
    public static final long RESTART_SOFT_MB = 235;
    private static final long RESTART_HARD_MB = 255;
    private static final long RESTART_CLIMBING_MB = 200;      // high enough to care about if it keeps growing
    private static final long RESTART_CLIMB_PER_15MIN_MB = 30; // growth that counts as "still climbing"
    private static final long RESTART_SYSTEM_TIGHT_MB = 170;  // with the tablet itself short of memory
    private static final long RESTART_MIN_UPTIME_MS = 20 * 60_000L; // never a restart loop
    private static final int HIST = 45;                       // 45 checks of 20 s = 15 minutes
    private static final long[] hist = new long[HIST];
    private static int histN;
    public static volatile boolean restartWanted;
    /** Last measured total (MB); the activity restarts at once, not at a quiet moment, past RESTART_CEILING_MB. */
    public static volatile long lastTotalMb;
    public static final long RESTART_CEILING_MB = 300;
    private static volatile boolean tight;
    private static volatile Runnable restartHook;

    /** True while memory is short: callers skip optional work (like pre-measuring lyrics variants). */
    public static boolean isTight() { return tight; }

    public static void setRestartHook(Runnable hook) { restartHook = hook; }

    /** Whether the app has run long enough, and is large enough, that it should restart now. */
    public static boolean shouldRestartNow() { return restartWanted && uptimeMs() >= RESTART_MIN_UPTIME_MS; }

    public static long uptimeMs() {
        return android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime();
    }

    /** Adds a line to the memory log (events like song changes, so jumps can be matched to what caused them). */
    public static void note(String text) {
        Context app = appContext;
        if (app == null) return;
        try {
            write(app, new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date()) + "  " + text);
        } catch (Throwable ignored) {
        }
    }

    // Lyrics drawing creates thousands of small native objects a minute (a gradient shader per lit word, per frame).
    // They are freed when Java's garbage collector reaches their Java handles, but the Java heap here is tiny, so it
    // runs only every 10 to 15 minutes and the native side balloons to 100 MB or more before then. This asks for a
    // collection sooner, off the main thread, so Android has no reason to kill the app for memory.
    private static final long NATIVE_LIMIT_BYTES = 70L << 20;

    public static synchronized void start(Context context) {
        if (started) return;
        started = true;
        final Context app = context.getApplicationContext();
        appContext = app;
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
    private static String guard(Context app, SimpleDateFormat fmt) throws Exception {
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
        tight = nativeHigh || appTight || systemTight;

        // The app's total (what Android counts against it), only measured once it is big enough to matter.
        if (mine >= 70 && uptimeMs() >= 3 * 60_000L) {
            Debug.MemoryInfo mi = new Debug.MemoryInfo();
            Debug.getMemoryInfo(mi);
            long total = mi.getTotalPss() >> 10;
            // Growth over the last 15 minutes (only once the history covers about that long).
            hist[histN % HIST] = total;
            histN++;
            long growth = histN >= HIST ? total - hist[histN % HIST] : 0;
            boolean want = total >= RESTART_SOFT_MB
                    || (total >= RESTART_CLIMBING_MB && growth >= RESTART_CLIMB_PER_15MIN_MB)
                    || (systemTight && total >= RESTART_SYSTEM_TIGHT_MB);
            if (uptimeMs() < RESTART_MIN_UPTIME_MS && total < RESTART_HARD_MB) want = false;
            restartWanted = want;
            lastTotalMb = total;
            Runnable hook = restartHook;
            // The hook decides when: it waits for a song change, a pause, the clock or a dark screen (unless past the ceiling).
            if (hook != null && (total >= RESTART_HARD_MB || (want && systemTight))) hook.run();
        } else {
            restartWanted = false;
        }

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

    /** Runs a garbage collection on a background thread and notes the native memory before and after. */
    public static void cleanUp(final Context app, final SimpleDateFormat fmt) {
        final long before = Debug.getNativeHeapAllocatedSize() >> 20;
        Thread t = new Thread(() -> {
            try {
                Runtime.getRuntime().gc();
                Thread.sleep(1500); // the native objects are released just after the collection
                long after = Debug.getNativeHeapAllocatedSize() >> 20;
                write(app, fmt.format(new Date()) + "  cleanup: native " + before + " MB -> " + after + " MB");
            } catch (Throwable ignored) {
            }
        }, "memory-cleanup");
        t.setDaemon(true);
        t.start();
    }

    /** For when Android says memory is short. */
    public static void cleanUpNow(Context context) {
        cleanUp(context.getApplicationContext(), new SimpleDateFormat("MM-dd HH:mm", Locale.US));
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
                + kb(gfx) + " MB  code " + kb(code) + " MB  total " + (mi.getTotalPss() >> 10) + " MB  tablet free " + avail + " MB  threads " + threadCount() + "  fds " + fdCount();
    }

    public static int threadCount() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/self/status"))) {
            String l;
            while ((l = r.readLine()) != null) if (l.startsWith("Threads:")) return Integer.parseInt(l.substring(8).trim());
        } catch (Throwable ignored) {
        }
        return -1;
    }

    public static int fdCount() {
        try {
            String[] f = new File("/proc/self/fd").list();
            return f == null ? -1 : f.length;
        } catch (Throwable t) {
            return -1;
        }
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
