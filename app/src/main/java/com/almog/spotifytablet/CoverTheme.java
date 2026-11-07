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

    /** [0] main accent, [1] a second, softer color (glow and the clock). Returns null if nothing usable was found. */
    public static int[] extract(Bitmap cover) {
        if (cover == null || cover.isRecycled()) return null;
        Palette p = Palette.from(cover).maximumColorCount(12).generate();
        int main = pick(p.getVibrantSwatch(),
                p.getLightVibrantSwatch(), p.getDominantSwatch(), p.getMutedSwatch());
        if (main == 0) return null;
        int second = pick(p.getLightMutedSwatch(), p.getLightVibrantSwatch(), p.getMutedSwatch(), p.getDominantSwatch());
        if (second == 0) second = main;
        return new int[]{readable(main, 0.62f, 0.78f), readable(second, 0.72f, 0.86f)};
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
