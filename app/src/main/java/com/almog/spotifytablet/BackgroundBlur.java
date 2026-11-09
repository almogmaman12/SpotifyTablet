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

    /** Settings: how dark the edges of the background get, in percent (0 is off). Read on the blur thread. */
    public static volatile int vignettePct = 0;

    private BackgroundBlur() {}

    // Two small bitmaps used in turn (one is on screen while the other is drawn into), and one pixel buffer, so a new
    // cover allocates nothing. Only the blur thread touches them; the one on screen is never written to.
    private static Bitmap[] pair = new Bitmap[2];
    private static int next = 0;
    private static int[] pixels;

    /** [sigmaPx] is the blur strength in screen pixels (the old RenderEffect radius). Blocking; call off the main thread. */
    public static synchronized Bitmap render(Bitmap cover, int viewW, int viewH, float sigmaPx) {
        int tw = Math.max(16, viewW / DOWNSCALE), th = Math.max(16, viewH / DOWNSCALE);
        // Which part of the cover the screen shows with centerCrop.
        float scale = Math.max((float) tw / cover.getWidth(), (float) th / cover.getHeight());
        int srcW = Math.min(cover.getWidth(), Math.round(tw / scale));
        int srcH = Math.min(cover.getHeight(), Math.round(th / scale));
        int srcX = (cover.getWidth() - srcW) / 2, srcY = (cover.getHeight() - srcH) / 2;

        Bitmap small = pair[next];
        if (small == null || small.isRecycled() || small.getWidth() != tw || small.getHeight() != th) {
            small = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            pair[next] = small;
        }
        next = 1 - next;
        small.eraseColor(0);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        new Canvas(small).drawBitmap(cover, new Rect(srcX, srcY, srcX + srcW, srcY + srcH), new Rect(0, 0, tw, th), paint);

        if (pixels == null || pixels.length != tw * th) pixels = new int[tw * th];
        int[] px = pixels;
        small.getPixels(px, 0, tw, 0, 0, tw, th);
        BlurMath.gaussian(px, tw, th, sigmaPx / DOWNSCALE);
        small.setPixels(px, 0, tw, 0, 0, tw, th);
        int v = vignettePct;
        if (v > 0) {
            // Darkens the corners smoothly, inside the picture itself, so there is never an edge over the lyrics.
            float r = (float) Math.hypot(tw, th) / 2f;
            Paint vp = new Paint(Paint.ANTI_ALIAS_FLAG);
            vp.setShader(new android.graphics.RadialGradient(tw / 2f, th / 2f, r,
                    new int[]{0x00000000, 0x00000000, ((int) (Math.min(100, v) * 2.2f) << 24)},
                    new float[]{0f, 0.45f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            new Canvas(small).drawRect(0, 0, tw, th, vp);
        }
        return small;
    }
}
