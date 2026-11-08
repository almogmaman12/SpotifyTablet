package com.almog.spotifytablet;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;

/**
 * Picks the accent colors of a cover. Runs on the blur worker thread (a small bitmap, a few milliseconds).
 * The colors are adjusted to stay readable on the dark background: never too dark, never dull unless the cover itself is gray.
 */
public final class CoverTheme {
    public static final String PREF = "cover_theme";

    private CoverTheme() {}

    /** [0] main accent, [1] a second, softer color (glow and the clock), [2] the second singer's color. Null if nothing usable was found. */
    public static int[] extract(Bitmap cover) {
        if (cover == null || cover.isRecycled()) return null;
        Palette p = Palette.from(cover).maximumColorCount(12).generate();
        int main = pick(p.getVibrantSwatch(),
                p.getLightVibrantSwatch(), p.getDominantSwatch(), p.getMutedSwatch());
        if (main == 0) return null;
        int second = pick(p.getLightMutedSwatch(), p.getLightVibrantSwatch(), p.getMutedSwatch(), p.getDominantSwatch());
        if (second == 0) second = main;
        // [2]: the color of a duet's second singer. It must look different from the main accent, so if the cover's
        // second color has nearly the same hue, the main hue is turned a third of the way round the color wheel instead.
        float[] hm = new float[3], hs = new float[3];
        ColorUtils.colorToHSL(main, hm);
        ColorUtils.colorToHSL(second, hs);
        float diff = Math.abs(hm[0] - hs[0]);
        diff = Math.min(diff, 360f - diff);
        int singer2;
        if (diff >= 40f && hs[1] >= 0.15f) {
            singer2 = readable(second, 0.62f, 0.78f);
        } else {
            float[] h = {(hm[0] + 150f) % 360f, Math.max(hm[1], 0.5f), 0.7f};
            singer2 = ColorUtils.HSLToColor(h) | 0xFF000000;
        }
        return new int[]{readable(main, 0.62f, 0.78f), readable(second, 0.72f, 0.86f), singer2};
    }

    private static int pick(Palette.Swatch... swatches) {
        for (Palette.Swatch s : swatches) if (s != null) return s.getRgb();
        return 0;
    }

    /** Lightness moved into [minL, maxL]; saturation raised a little so a muddy cover still gives a visible color. */
    private static int readable(int rgb, float minL, float maxL) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(rgb, hsl);
        boolean grayish = hsl[1] < 0.10f;
        if (!grayish) hsl[1] = Math.max(hsl[1], 0.45f);
        hsl[2] = Math.max(minL, Math.min(maxL, hsl[2]));
        return ColorUtils.HSLToColor(hsl) | 0xFF000000;
    }
}
