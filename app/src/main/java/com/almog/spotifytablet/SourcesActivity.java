package com.almog.spotifytablet;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.almog.spotifytablet.lyrics.mobile.MobileLyricsSources;
import com.almog.spotifytablet.lyrics.mobile.MobileLyricsSources.BlendInfo;
import com.almog.spotifytablet.lyrics.mobile.MobileLyricsSources.SourceInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Full lyric Sources screen: ranked cards (drag to reorder, switch each on or off, what each can
 * return, a first-time consent for sources that send the song elsewhere), blends, and the person's
 * own Spicy Lyrics key.
 */
public class SourcesActivity extends AppCompatActivity {
    private static final int BG = Color.parseColor("#0E1116");
    private static final int CARD = Color.parseColor("#1A1F29");
    private static final int ACCENT = Color.parseColor("#1DB954");
    private static final int TEXT_DIM = Color.parseColor("#9AA3B2");

    private static final int T_HEADER = 0, T_SOURCE = 1, T_BLEND = 2, T_KEY = 3;

    private final List<Object> items = new ArrayList<>();
    private RecyclerView list;
    private Adapter adapter;
    private ItemTouchHelper touch;

    private static final class Header {
        final String title, subtitle;
        Header(String t, String s) { title = t; subtitle = s; }
    }

    private static final class KeyRow { }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        MobileLyricsSources.init(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        TextView title = new TextView(this);
        title.setText("Lyric sources");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(dp(20), dp(24), dp(20), dp(8));
        root.addView(title);

        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setClipToPadding(false);
        list.setPadding(dp(16), dp(4), dp(16), dp(32));
        adapter = new Adapter();
        list.setAdapter(adapter);
        touch = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                return vh.getItemViewType() == T_SOURCE ? makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) : 0;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
                if (to.getItemViewType() != T_SOURCE) return false;
                int a = from.getBindingAdapterPosition(), c = to.getBindingAdapterPosition();
                Collections.swap(items, a, c);
                adapter.notifyItemMoved(a, c);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) { }

            @Override
            public boolean isLongPressDragEnabled() { return false; }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                saveOrder();
                // Rank numbers follow the new order.
                rv.post(() -> adapter.notifyDataSetChanged());
            }
        });
        touch.attachToRecyclerView(list);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        reload();
    }

    private void reload() {
        items.clear();
        items.add(new Header("Sources",
                "Asked in this order; the best result wins, word-synced beats line-synced. Drag the handle to rank a source higher."));
        items.addAll(MobileLyricsSources.sources());
        List<BlendInfo> blends = MobileLyricsSources.blends();
        if (!blends.isEmpty()) {
            items.add(new Header("Blends",
                    "Combine one source's lines with another's word timing. A blend only works while the sources it needs are on."));
            items.addAll(blends);
        }
        items.add(new Header("Spicy Lyrics key",
                "Optional. Use your own client key instead of the built-in one."));
        items.add(new KeyRow());
        adapter.notifyDataSetChanged();
    }

    private void saveOrder() {
        List<String> ids = new ArrayList<>();
        for (Object o : items) if (o instanceof SourceInfo) ids.add(((SourceInfo) o).getId());
        MobileLyricsSources.setOrder(ids);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private void tint(Switch sw) {
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        sw.setTrackTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#3A4A3F")));
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void enableSource(SourceInfo s, boolean on, Runnable undo) {
        if (on && s.getNeedsConsent()) {
            new AlertDialog.Builder(this)
                    .setTitle("Turn on " + s.getName() + "?")
                    .setMessage(s.getNote() != null ? s.getNote() : "This source is asked for lyrics each time.")
                    .setPositiveButton("Turn on", (d, w) -> {
                        MobileLyricsSources.markDisclosed(s.getId());
                        MobileLyricsSources.setSourceEnabled(s.getId(), true);
                        reload();
                    })
                    .setNegativeButton("Cancel", (d, w) -> undo.run())
                    .setOnCancelListener(d -> undo.run())
                    .show();
            return;
        }
        MobileLyricsSources.setSourceEnabled(s.getId(), on);
        reload();
    }

    private void editKey() {
        EditText et = new EditText(this);
        et.setHint("sl_pk_…");
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Your Spicy Lyrics key")
                .setMessage("Add Spicy Lyrics Mobile at " + "developers.spicylyrics.org/catalog/spicy-player"
                        + " and paste the client key here (starts with sl_pk_). Leave empty to use the built-in key.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String err = MobileLyricsSources.setPersonalKey(et.getText().toString());
                    if (err != null) toast(err); else { toast("Saved"); reload(); }
                })
                .setNeutralButton("Use built-in", (d, w) -> { MobileLyricsSources.setPersonalKey(""); reload(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private final class VH extends RecyclerView.ViewHolder {
        VH(View v) { super(v); }
    }

    private final class Adapter extends RecyclerView.Adapter<VH> {
        @Override public int getItemCount() { return items.size(); }

        @Override
        public int getItemViewType(int pos) {
            Object o = items.get(pos);
            if (o instanceof Header) return T_HEADER;
            if (o instanceof SourceInfo) return T_SOURCE;
            if (o instanceof BlendInfo) return T_BLEND;
            return T_KEY;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LinearLayout v = new LinearLayout(SourcesActivity.this);
            v.setOrientation(type == T_HEADER ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            v.setGravity(Gravity.CENTER_VERTICAL);
            v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH vh, int pos) {
            LinearLayout v = (LinearLayout) vh.itemView;
            v.removeAllViews();
            v.setOnClickListener(null);
            Object o = items.get(pos);
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) v.getLayoutParams();
            lp.topMargin = 0; lp.bottomMargin = dp(8);
            if (o instanceof Header) {
                Header h = (Header) o;
                v.setBackground(null);
                v.setPadding(dp(4), dp(16), dp(4), dp(8));
                TextView t = text(h.title, 18, Color.WHITE);
                t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
                v.addView(t);
                v.addView(text(h.subtitle, 13, TEXT_DIM));
                return;
            }
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            bg.setCornerRadius(dp(14));
            v.setBackground(bg);
            v.setPadding(dp(14), dp(12), dp(14), dp(12));
            if (o instanceof SourceInfo) bindSource(v, (SourceInfo) o, pos, vh);
            else if (o instanceof BlendInfo) bindBlend(v, (BlendInfo) o);
            else bindKey(v);
        }

        private LinearLayout info(String name, String detail) {
            LinearLayout col = new LinearLayout(SourcesActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            col.addView(text(name, 16, Color.WHITE));
            if (detail != null && !detail.isEmpty()) {
                TextView d = text(detail, 12, TEXT_DIM);
                d.setPadding(0, dp(2), 0, 0);
                col.addView(d);
            }
            return col;
        }

        private void bindSource(LinearLayout v, SourceInfo s, int pos, VH vh) {
            int rank = 0;
            for (int i = 0; i <= pos; i++) if (items.get(i) instanceof SourceInfo) rank++;
            TextView r = text(String.valueOf(rank), 15, ACCENT);
            r.setGravity(Gravity.CENTER);
            r.setMinWidth(dp(28));
            v.addView(r);
            TextView handle = text("≡", 26, TEXT_DIM);
            handle.setPadding(dp(6), 0, dp(12), 0);
            handle.setOnTouchListener((h, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) touch.startDrag(vh);
                return false;
            });
            v.addView(handle);
            StringBuilder detail = new StringBuilder();
            for (String t : s.getTypes()) { if (detail.length() > 0) detail.append(" · "); detail.append(t); }
            if (s.getGroup() != null) {
                if (detail.length() > 0) detail.append("  |  ");
                detail.append("via ").append(s.getGroup().replace('_', ' '));
            }
            if (s.getNeedsConsent()) detail.append(detail.length() > 0 ? "  |  asks first" : "asks first");
            LinearLayout col = info(s.getName(), detail.toString());
            v.addView(col);
            Switch sw = new Switch(SourcesActivity.this);
            tint(sw);
            sw.setChecked(s.getEnabled());
            sw.setOnCheckedChangeListener((b, on) -> {
                if (on == s.getEnabled()) return;
                enableSource(s, on, () -> sw.setChecked(false));
            });
            v.addView(sw);
            if (s.getNote() != null) {
                col.setOnClickListener(x -> new AlertDialog.Builder(SourcesActivity.this)
                        .setTitle(s.getName()).setMessage(s.getNote()).setPositiveButton("OK", null).show());
            }
        }

        private void bindBlend(LinearLayout v, BlendInfo bl) {
            v.addView(info(bl.getName(), "Needs " + bl.getNeeds() + (bl.getEnabled() && !bl.getUsable() ? " (switch those on)" : "")));
            Switch sw = new Switch(SourcesActivity.this);
            tint(sw);
            sw.setChecked(bl.getEnabled());
            sw.setOnCheckedChangeListener((b, on) -> {
                if (on == bl.getEnabled()) return;
                MobileLyricsSources.setBlendEnabled(bl.getId(), on);
                reload();
            });
            v.addView(sw);
        }

        private void bindKey(LinearLayout v) {
            String hint = MobileLyricsSources.personalKeyHint();
            v.addView(info(hint != null ? "Using your key" : "Using the built-in key", hint != null ? hint : "Tap to add your own"));
            v.addView(text("›", 22, ACCENT));
            v.setOnClickListener(x -> editKey());
        }
    }
}
