package com.almog.spotifytablet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * A faint mirror image of the cover's bottom edge, fading out downwards. It draws the same picture as the cover
 * (same Glide request, so no extra decoding), flipped, and then erases most of it with a gradient.
 */
public class ReflectionImageView extends AppCompatImageView {
    private final Paint fade = new Paint();

    public ReflectionImageView(Context c) { this(c, null); }

    public ReflectionImageView(Context c, AttributeSet a) {
        super(c, a);
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Top: 72% erased (so 28% shows), bottom: fully erased.
        fade.setShader(new LinearGradient(0, 0, 0, h, 0xB8000000, 0xFF000000, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable d = getDrawable();
        int w = getWidth(), h = getHeight();
        if (d == null || w <= 0 || h <= 0 || d.getIntrinsicWidth() <= 0) return;
        int scaledH = Math.round(d.getIntrinsicHeight() * (w / (float) d.getIntrinsicWidth()));
        int layer = canvas.saveLayer(0, 0, w, h, null);
        canvas.save();
        canvas.scale(1f, -1f);           // flip: the cover's bottom edge ends up at the top of this view
        d.setBounds(0, -scaledH, w, 0);
        d.draw(canvas);
        canvas.restore();
        canvas.drawRect(0, 0, w, h, fade);
        canvas.restoreToCount(layer);
    }
}
