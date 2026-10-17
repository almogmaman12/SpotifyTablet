package com.almog.spotifytablet;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * Makes the blurred cover for the player background once, on a small copy, so the GPU doesn't blur a
 * full-screen image on every frame. The picture is cropped to the screen's shape (like the ImageView's
 * centerCrop), blurred with the same strength as before (scaled down with the image), and the
 * ImageView stretches it back up: the blur is so soft that the small size is not visible.
 */
public final class BackgroundBlur {
    /** The blurred copy is this many times smaller than the screen. */
    private static final int DOWNSCALE = 8;

    private BackgroundBlur() {}

    /** [sigmaPx] is the blur strength in screen pixels (the old RenderEffect radius). Blocking; call off the main thread. */
    public static Bitmap render(Bitmap cover, int viewW, int viewH, float sigmaPx) {
        int tw = Math.max(16, viewW / DOWNSCALE), th = Math.max(16, viewH / DOWNSCALE);
        // Which part of the cover the screen shows with centerCrop.
        float scale = Math.max((float) tw / cover.getWidth(), (float) th / cover.getHeight());
        int srcW = Math.min(cover.getWidth(), Math.round(tw / scale));
        int srcH = Math.min(cover.getHeight(), Math.round(th / scale));
        int srcX = (cover.getWidth() - srcW) / 2, srcY = (cover.getHeight() - srcH) / 2;

        Bitmap small = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        new Canvas(small).drawBitmap(cover, new Rect(srcX, srcY, srcX + srcW, srcY + srcH), new Rect(0, 0, tw, th), paint);

        int[] px = new int[tw * th];
        small.getPixels(px, 0, tw, 0, 0, tw, th);
        BlurMath.gaussian(px, tw, th, sigmaPx / DOWNSCALE);
        small.setPixels(px, 0, tw, 0, 0, tw, th);
        return small;
    }
}
