package com.almog.spotifytablet;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Debug page: which source answered each lyrics lookup and how long it took, recent network calls
 * (failures in red), and the app's recent log lines. Everything is read from [DiagLog] in memory.
 */
public class DebugActivity extends AppCompatActivity {

    private static final int BG = Color.parseColor("#0A0B0E");
    private static final int TEXT = Color.WHITE;
    private static final int DIM = Color.parseColor("#9AA3B2");
    private static final int GOOD = Color.parseColor("#1DB954");
    private static final int BAD = Color.parseColor("#FF6B6B");
    private static final int WARN = Color.parseColor("#F5B041");

    private static final String[] TABS = {"Lyrics", "Network", "Log", "Crash"};
    private int tab = 0;
    private float density;
    private LinearLayout list;
    private ScrollView scroll;
    private final TextView[] tabViews = new TextView[TABS.length];
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        density = getResources().getDisplayMetrics().density;
        if (savedInstanceState != null) tab = savedInstanceState.getInt("tab", 0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(12), dp(20), dp(12));
        bar.addView(button("‹ Back", v -> finish()));
        TextView title = text("Debug", 22, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(16), 0, dp(24), 0);
        bar.addView(title);
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            TextView t = button(TABS[i], v -> { tab = index; render(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            bar.addView(t, lp);
            tabViews[i] = t;
        }
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(button("Refresh", v -> render()));
        TextView copy = button("Copy", v -> copyAll());
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyLp.setMarginStart(dp(8));
        bar.addView(copy, copyLp);
        TextView clear = button("Clear", v -> { if (tab == 3) CrashReporter.clear(this); else DiagLog.clearAll(); render(); });
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clearLp.setMarginStart(dp(8));
        bar.addView(clear, clearLp);
        root.addView(bar);

        scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(20), dp(4), dp(20), dp(32));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    private void render() {
        for (int i = 0; i < tabViews.length; i++) {
            boolean on = i == tab;
            tabViews[i].setBackgroundResource(on ? R.drawable.bg_pill_selected : R.drawable.bg_pill);
            tabViews[i].setTextColor(on ? Color.BLACK : TEXT);
        }
        list.removeAllViews();
        if (tab == 0) renderFetches();
        else if (tab == 1) renderNet();
        else if (tab == 2) renderLog();
        else renderCrash();
        scroll.scrollTo(0, 0);
    }

    private void renderFetches() {
        List<DiagLog.Fetch> items = DiagLog.fetches();
        if (items.isEmpty()) { empty("No lyric lookups yet. Play a song."); return; }
        for (DiagLog.Fetch f : items) {
            boolean none = "none".equals(f.result);
            int color = none ? BAD : f.ms > 4000 ? WARN : GOOD;
            LinearLayout row = card();
            row.addView(text(f.song, 16, TEXT));
            TextView line = text(clock.format(new Date(f.time)) + "   " + f.ms + " ms   "
                    + (none ? "no lyrics" : f.result), 13, color);
            line.setPadding(0, dp(2), 0, 0);
            row.addView(line);
            if (!f.detail.isEmpty()) {
                TextView d = text(f.detail, 12, DIM);
                d.setPadding(0, dp(2), 0, 0);
                row.addView(d);
            }
            list.addView(row);
        }
    }

    private void renderNet() {
        List<DiagLog.Net> items = DiagLog.nets();
        if (items.isEmpty()) { empty("No network calls recorded yet."); return; }
        for (DiagLog.Net n : items) {
            LinearLayout row = card();
            String status = n.error != null ? "FAILED" : String.valueOf(n.code);
            TextView head = text(clock.format(new Date(n.time)) + "   " + status + "   " + n.ms + " ms", 13,
                    n.failed() ? BAD : n.ms > 2000 ? WARN : GOOD);
            row.addView(head);
            TextView where = text(n.host + n.path, 13, TEXT);
            where.setPadding(0, dp(2), 0, 0);
            row.addView(where);
            if (n.error != null) {
                TextView e = text(n.error, 12, DIM);
                e.setPadding(0, dp(2), 0, 0);
                row.addView(e);
            }
            list.addView(row);
        }
    }

    private void renderCrash() {
        String crash = CrashReporter.read(this);
        if (crash == null) { empty("No crash recorded. Good."); return; }
        TextView t = text(crash, 12, BAD);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(0, dp(8), 0, dp(8));
        list.addView(t);
    }

    private void renderLog() {
        List<DiagLog.Entry> items = DiagLog.logs();
        if (items.isEmpty()) { empty("Nothing logged yet."); return; }
        for (DiagLog.Entry e : items) {
            int color = e.level == 'E' ? BAD : e.level == 'W' ? WARN : TEXT;
            TextView t = text(clock.format(new Date(e.time)) + "  " + e.level + "  " + e.tag + ": " + e.msg, 12, color);
            t.setTypeface(Typeface.MONOSPACE);
            t.setPadding(0, dp(3), 0, dp(3));
            list.addView(t);
        }
    }

    private void copyAll() {
        StringBuilder sb = new StringBuilder();
        if (tab == 0) {
            for (DiagLog.Fetch f : DiagLog.fetches()) {
                sb.append(clock.format(new Date(f.time))).append(' ').append(f.song).append(" | ")
                        .append(f.result).append(" | ").append(f.ms).append(" ms | ").append(f.detail).append('\n');
            }
        } else if (tab == 1) {
            for (DiagLog.Net n : DiagLog.nets()) {
                sb.append(clock.format(new Date(n.time))).append(' ').append(n.error != null ? "FAILED" : n.code)
                        .append(' ').append(n.ms).append(" ms ").append(n.host).append(n.path)
                        .append(n.error != null ? " " + n.error : "").append('\n');
            }
        } else if (tab == 3) {
            String crash = CrashReporter.read(this);
            if (crash != null) sb.append(crash);
        } else {
            for (DiagLog.Entry e : DiagLog.logs()) {
                sb.append(clock.format(new Date(e.time))).append(' ').append(e.level).append(' ')
                        .append(e.tag).append(": ").append(e.msg).append('\n');
            }
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("debug", sb.toString()));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    private void empty(String message) {
        TextView t = text(message, 15, DIM);
        t.setPadding(dp(4), dp(24), 0, 0);
        list.addView(t);
    }

    private LinearLayout card() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_settings_card);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView t = text(label, 14, TEXT);
        t.setBackgroundResource(R.drawable.bg_btn);
        t.setPadding(dp(16), dp(9), dp(16), dp(9));
        t.setOnClickListener(l);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
