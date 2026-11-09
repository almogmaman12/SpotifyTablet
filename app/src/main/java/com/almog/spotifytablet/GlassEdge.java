package com.almog.spotifytablet;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A thin light along the top and left edge of a glass card that fades to nothing, and returns faintly at the
 * bottom right: the detail that makes frosted glass look real. It is drawn in the card's overlay, so the card's own
 * background and touch handling are untouched.
 */
public final class GlassEdge extends Drawable {
    private static final List<GlassEdge> ALL = new ArrayList<>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius;
    private final float stroke;
    private boolean shown = true;

    private GlassEdge(float radiusPx, float strokePx) {
        radius = radiusPx;
        stroke = strokePx;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokePx);
    }

    /** Puts an edge light on [v] (a card with this corner radius, in dp). Returns it so it can be shown or hidden. */
    public static GlassEdge attach(View v, float radiusDp) {
        float d = v.getResources().getDisplayMetrics().density;
        final GlassEdge edge = new GlassEdge(radiusDp * d, Math.max(1.5f, 1.5f * d));
        v.getOverlay().add(edge);
        edge.setBounds(0, 0, v.getWidth(), v.getHeight());
        v.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> edge.setBounds(0, 0, r - l, b - t));
        synchronized (ALL) { ALL.add(edge); }
        return edge;
    }

    /** The settings switch: shows or hides every edge light. */
    public static void setAllShown(boolean shown) {
        synchronized (ALL) { for (GlassEdge e : ALL) e.setShown(shown); }
    }

    public void setShown(boolean shown) {
        if (this.shown == shown) return;
        this.shown = shown;
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect b) {
        super.onBoundsChange(b);
        paint.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{0x8CFFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x33FFFFFF},
                new float[]{0f, 0.38f, 0.72f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (!shown) return;
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        float inset = stroke / 2f;
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { paint.setColorFilter(colorFilter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
