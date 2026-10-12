package com.almog.spotifytablet;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/** A small pill at the top of the screen that shows the most important [AppStatus] message. */
public final class StatusBanner {
    private StatusBanner() {}

    public static void attach(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        float d = activity.getResources().getDisplayMetrics().density;

        TextView banner = new TextView(activity);
        banner.setTextColor(Color.WHITE);
        banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding((int) (18 * d), (int) (9 * d), (int) (18 * d), (int) (9 * d));
        banner.setElevation(24 * d);
        banner.setAlpha(0f);
        banner.setVisibility(android.view.View.GONE);
        banner.setOnClickListener(v -> AppStatus.dismissCurrent());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = (int) (14 * d);
        content.addView(banner, lp);

        AppStatus.setListener((message, severity) -> {
            if (message == null) {
                banner.animate().alpha(0f).setDuration(200)
                        .withEndAction(() -> banner.setVisibility(android.view.View.GONE)).start();
                return;
            }
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(24 * d);
            bg.setColor(severity >= AppStatus.ERROR ? Color.parseColor("#C0392B")
                    : severity == AppStatus.WARN ? Color.parseColor("#B9770E")
                    : Color.parseColor("#34495E"));
            banner.setBackground(bg);
            banner.setText(message);
            banner.setVisibility(android.view.View.VISIBLE);
            banner.animate().cancel();
            banner.animate().alpha(1f).setDuration(200).start();
        });
    }

    public static void detach() {
        AppStatus.setListener(null);
    }
}
