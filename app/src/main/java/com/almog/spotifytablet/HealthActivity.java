package com.almog.spotifytablet;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One page answering "is the app healthy": memory, uptime, restarts in the last 24 hours, why it last restarted and whether
 * the browser extension is connected. It updates itself every 2 seconds while open.
 */
public class HealthActivity extends AppCompatActivity {
    private static final int BG = 0xFF121212, CARD = 0xFF1E1E1E, TEXT = 0xFFFFFFFF, MUTED = 0xFF9AA0A6;
    private static final int OK = 0xFF1DB954, WARN = 0xFFFFB020, BAD = 0xFFFF5A4F;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private float density;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        density = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(12), dp(20), dp(12));
        TextView back = text("‹ Back", 16, TEXT);
        back.setPadding(dp(14), dp(8), dp(14), dp(8));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = text("Health", 22, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(16), 0, 0, 0);
        bar.addView(title);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(20), dp(4), dp(20), dp(32));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(refresh); }

    private void render() {
        list.removeAllViews();
        Context app = getApplicationContext();

        // ---- Memory ----
        header("Memory");
        Debug.MemoryInfo mi = new Debug.MemoryInfo();
        Debug.getMemoryInfo(mi);
        long total = mi.getTotalPss() >> 10;
        row("App memory", total + " MB", total >= MemoryLog.RESTART_SOFT_MB ? BAD : total >= 150 ? WARN : OK,
                "What Android counts against the app. The app restarts itself near " + MemoryLog.RESTART_SOFT_MB + " MB, before Android ends it.");
        Runtime rt = Runtime.getRuntime();
        row("Java / native / graphics",
                ((rt.totalMemory() - rt.freeMemory()) >> 20) + " / " + (Debug.getNativeHeapAllocatedSize() >> 20) + " / "
                        + (parse(mi.getMemoryStat("summary.graphics")) >> 10) + " MB", MUTED, null);
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo sys = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(sys);
        row("Tablet free memory", (sys.availMem >> 20) + " MB", sys.lowMemory ? BAD : (sys.availMem < sys.threshold * 2 ? WARN : OK),
                sys.lowMemory ? "Android is short of memory right now." : null);
        int threads = MemoryLog.threadCount();
        row("Threads / open files", threads + " / " + MemoryLog.fdCount(), threads >= 60 ? WARN : MUTED,
                threads >= 60 ? "Many threads: something may be leaking them." : null);
        int cool = PowerSettings.coolDownLevel();
        row("Heat protection", cool == 0 ? "normal" : cool == 1 ? "30 fps (hot or Battery Saver)" : "24 fps (very hot)",
                cool == 0 ? OK : cool == 1 ? WARN : BAD, null);

        // ---- Running ----
        header("Running");
        long up = MemoryLog.uptimeMs();
        row("Uptime", duration(up), MUTED, "Time since the app last started.");
        addExitRows();

        // ---- Browser extension ----
        header("Browser extension");
        if (!BrowserMedia.isEnabled(app)) {
            row("Status", "off", MUTED, "Turn on the browser source in Settings.");
        } else if (!BrowserMedia.isRunning()) {
            row("Status", "on, receiver not running", WARN, "Open the player screen once so it starts.");
        } else {
            long age = BrowserMedia.lastReportAgeMs();
            if (age < 0) {
                row("Status", "waiting for the extension", WARN, "Nothing has arrived since the app started. Address: " + BrowserMedia.address());
            } else if (age > 30_000) {
                row("Status", "connected earlier, quiet for " + duration(age), WARN, "No page is playing, or the browser is closed.");
            } else {
                row("Status", "connected", OK, "Last report " + duration(age) + " ago");
                String t = BrowserMedia.lastReportTitle();
                if (!t.isEmpty()) row("Now reporting", t, MUTED, null);
            }
        }
    }

    private static long MemoryLog.RESTART_SOFT_MB { return 185; }

    /** Restarts in the last 24 hours and why the last one happened, from Android's exit record and the app's own memory log. */
    private void addExitRows() {
        if (Build.VERSION.SDK_INT < 30) {
            row("Restarts", "unknown", MUTED, "Needs Android 11 or newer.");
            return;
        }
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(getPackageName(), 0, 30);
            long now = System.currentTimeMillis();
            Map<String, Integer> counts = new LinkedHashMap<>();
            int n = 0;
            ApplicationExitInfo last = null;
            if (exits != null) {
                for (ApplicationExitInfo e : exits) {
                    if (last == null) last = e;
                    if (now - e.getTimestamp() > DAY_MS) continue;
                    n++;
                    String name = CrashReporter.reasonName(e.getReason());
                    Integer c = counts.get(name);
                    counts.put(name, c == null ? 1 : c + 1);
                }
            }
            StringBuilder why = new StringBuilder();
            for (Map.Entry<String, Integer> en : counts.entrySet()) {
                if (why.length() > 0) why.append(", ");
                why.append(en.getValue()).append("× ").append(en.getKey());
            }
            row("Restarts in 24 h", String.valueOf(n), n == 0 ? OK : n <= 3 ? WARN : BAD, why.length() > 0 ? why.toString() : null);
            if (last == null) {
                row("Last restart", "none recorded", MUTED, null);
            } else {
                String when = new SimpleDateFormat("EEE HH:mm", Locale.US).format(new Date(last.getTimestamp()));
                row("Last restart", CrashReporter.reasonName(last.getReason()), MUTED,
                        when + " (" + duration(now - last.getTimestamp()) + " ago), "
                                + (last.getImportance() <= 125 ? "was on screen" : "was in the background")
                                + ", memory " + (last.getRss() >> 10) + " MB");
            }
        } catch (Throwable t) {
            row("Restarts", "could not read", MUTED, String.valueOf(t));
        }
        // The app's own deliberate restarts (the memory valve) are in its memory log.
        String log = MemoryLog.recent(getApplicationContext(), 240);
        String lastValve = null;
        for (String line : log.split("\n")) if (line.contains("restarting to free memory")) lastValve = line.trim();
        row("Last memory restart", lastValve == null ? "none" : "yes", lastValve == null ? OK : WARN, lastValve);
    }

    // ---------- small view helpers ----------
    private void header(String s) {
        TextView t = text(s.toUpperCase(Locale.ROOT), 12, MUTED);
        t.setTypeface(null, Typeface.BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), dp(20), 0, dp(8));
        list.addView(t);
    }

    private void row(String label, String value, int valueColor, String hint) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(CARD);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = text(label, 16, TEXT);
        line.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(value, 16, valueColor);
        v.setTypeface(null, Typeface.BOLD);
        v.setGravity(Gravity.END);
        line.addView(v);
        card.addView(line);
        if (hint != null && !hint.isEmpty()) {
            TextView h = text(hint, 13, MUTED);
            h.setPadding(0, dp(4), 0, 0);
            card.addView(h);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(2);
        list.addView(card, lp);
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(int v) { return Math.round(v * density); }

    private static long parse(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }

    private static String duration(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + " s";
        long m = s / 60;
        if (m < 60) return m + " min";
        long h = m / 60;
        if (h < 48) return h + " h " + (m % 60) + " min";
        return (h / 24) + " d " + (h % 24) + " h";
    }
}
