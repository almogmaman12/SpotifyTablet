package com.almog.spotifytablet;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Random;

/**
 * A very fine, still film grain: one small tile of faint white and black specks, repeated over the whole screen.
 * It breaks up the visible color bands that a soft blurred background gets on an older tablet screen. Nothing moves, so it costs
 * almost nothing to draw.
 */
public final class GrainDrawable extends Drawable {
    private static final int TILE = 128;
    private static Bitmap tile;

    private final Paint paint = new Paint();

    public GrainDrawable() {
        if (tile == null || tile.isRecycled()) {
            int[] px = new int[TILE * TILE];
            Random r = new Random(7);
            for (int i = 0; i < px.length; i++) {
                int a = 6 + r.nextInt(14); // up to about 8% opacity
                px[i] = (a << 24) | (r.nextBoolean() ? 0xFFFFFF : 0x000000);
            }
            tile = Bitmap.createBitmap(px, TILE, TILE, Bitmap.Config.ARGB_8888);
        }
        paint.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.drawRect(getBounds(), paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { paint.setColorFilter(colorFilter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
