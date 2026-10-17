package com.almog.spotifytablet;

/**
 * A Gaussian blur done as three box blurs (the standard fast approximation), with the edge pixels
 * repeated outward so the borders don't darken (what Android's CLAMP tile mode does). Pure Java,
 * on packed ARGB pixels, so it runs on a small copy of the cover once instead of the GPU re-blurring
 * a full-screen image every frame.
 */
public final class BlurMath {
    private BlurMath() {}

    /** Blurs [argb] (w*h pixels) in place with a Gaussian of standard deviation [sigma] (pixels). */
    public static void gaussian(int[] argb, int w, int h, float sigma) {
        if (sigma < 0.5f || w < 1 || h < 1) return;
        int[] sizes = boxSizes(sigma, 3);
        int[] tmp = new int[argb.length];
        for (int size : sizes) {
            int r = (size - 1) / 2;
            if (r < 1) continue;
            boxHorizontal(argb, tmp, w, h, r);
            boxVertical(tmp, argb, w, h, r);
        }
    }

    /** Widths of [n] box blurs whose combined variance matches a Gaussian of [sigma]. */
    static int[] boxSizes(float sigma, int n) {
        double ideal = Math.sqrt(12.0 * sigma * sigma / n + 1);
        int wl = (int) Math.floor(ideal);
        if (wl % 2 == 0) wl--;
        int wu = wl + 2;
        double m = (12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4.0);
        int mi = (int) Math.round(m);
        int[] sizes = new int[n];
        for (int i = 0; i < n; i++) sizes[i] = i < mi ? wl : wu;
        return sizes;
    }

    private static void boxHorizontal(int[] src, int[] dst, int w, int h, int r) {
        float inv = 1f / (2 * r + 1);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int first = src[row], last = src[row + w - 1];
            long sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[row + clamp(i, 0, w - 1)];
                sa += p >>> 24; sr += (p >> 16) & 0xFF; sg += (p >> 8) & 0xFF; sb += p & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = pack(sa * inv, sr * inv, sg * inv, sb * inv);
                int add = src[row + clamp(x + r + 1, 0, w - 1)];
                int sub = src[row + clamp(x - r, 0, w - 1)];
                sa += (add >>> 24) - (sub >>> 24);
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static void boxVertical(int[] src, int[] dst, int w, int h, int r) {
        float inv = 1f / (2 * r + 1);
        for (int x = 0; x < w; x++) {
            long sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[clamp(i, 0, h - 1) * w + x];
                sa += p >>> 24; sr += (p >> 16) & 0xFF; sg += (p >> 8) & 0xFF; sb += p & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = pack(sa * inv, sr * inv, sg * inv, sb * inv);
                int add = src[clamp(y + r + 1, 0, h - 1) * w + x];
                int sub = src[clamp(y - r, 0, h - 1) * w + x];
                sa += (add >>> 24) - (sub >>> 24);
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : Math.min(v, hi); }

    private static int pack(double a, double r, double g, double b) {
        return ((int) (a + 0.5) << 24) | ((int) (r + 0.5) << 16) | ((int) (g + 0.5) << 8) | (int) (b + 0.5);
    }
}
