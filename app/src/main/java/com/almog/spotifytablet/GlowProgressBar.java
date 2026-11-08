package com.almog.spotifytablet;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.ProgressBar;

/**
 * A progress bar with a rounded track and a small glowing dot at the current position, in the bar's progress tint
 * (the cover's accent when "Cover colors" is on). It draws itself, so the progress and tint code is unchanged.
 */
public class GlowProgressBar extends ProgressBar {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public GlowProgressBar(Context c) { super(c); }
    public GlowProgressBar(Context c, AttributeSet a) { super(c, a); }
    public GlowProgressBar(Context c, AttributeSet a, int style) { super(c, a, style); }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float d = getResources().getDisplayMetrics().density;
        float barH = 5f * d;
        float dotR = 8f * d;
        float left = getPaddingLeft() + dotR;
        float right = getPaddingLeft() + w - dotR;
        if (right <= left) return;
        float cy = h / 2f;
        int accent = Color.WHITE;
        ColorStateList tint = getProgressTintList();
        if (tint != null) accent = tint.getDefaultColor();
        float frac = getMax() > 0 ? Math.max(0f, Math.min(1f, getProgress() / (float) getMax())) : 0f;
        float x = left + (right - left) * frac;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33FFFFFF);
        rect.set(left, cy - barH / 2, right, cy + barH / 2);
        canvas.drawRoundRect(rect, barH / 2, barH / 2, paint);
        paint.setColor(accent);
        rect.set(left, cy - barH / 2, Math.max(left + barH, x), cy + barH / 2);
        canvas.drawRoundRect(rect, barH / 2, barH / 2, paint);
        // Glow: two soft rings under the solid dot.
        paint.setColor((accent & 0x00FFFFFF) | 0x26000000);
        canvas.drawCircle(x, cy, dotR * 1.0f, paint);
        paint.setColor((accent & 0x00FFFFFF) | 0x40000000);
        canvas.drawCircle(x, cy, dotR * 0.72f, paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x, cy, dotR * 0.42f, paint);
    }
}
