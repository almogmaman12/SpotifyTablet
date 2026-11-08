package com.almog.spotifytablet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * The cover as a poster: it fills its box, darkens towards the bottom so the controls stay readable, and its right
 * side fades out into the background (the picture itself turns transparent, so there is no visible edge).
 */
public class PosterImageView extends AppCompatImageView {
    private final Paint scrim = new Paint();
    private final Paint fade = new Paint();

    public PosterImageView(Context c) { this(c, null); }

    public PosterImageView(Context c, AttributeSet a) {
        super(c, a);
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        scrim.setShader(new LinearGradient(0, h * 0.5f, 0, h, 0x00000000, 0xB8000000, Shader.TileMode.CLAMP));
        fade.setShader(new LinearGradient(w * 0.45f, 0, w, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || getDrawable() == null) return;
        int layer = canvas.saveLayer(0, 0, w, h, null);
        super.onDraw(canvas);
        canvas.drawRect(0, 0, w, h, scrim);
        canvas.drawRect(0, 0, w, h, fade);
        canvas.restoreToCount(layer);
    }
}
