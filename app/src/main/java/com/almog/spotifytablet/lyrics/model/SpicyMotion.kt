package com.almog.spotifytablet.lyrics.model

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * Natural cubic spline through the given control points (second derivative is zero at both ends).
 *
 * Spicy Lyrics describes its per-word scale / lift / glow animation as a handful of control points
 * and interpolates them with the `cubic-spline` npm package, which is also a natural cubic spline.
 * Using the same interpolation here reproduces its curves exactly instead of approximating them
 * with smoothstep segments.
 */
class SpicySpline(private val xs: FloatArray, private val ys: FloatArray) {
    private val second: FloatArray

    init {
        require(xs.size == ys.size && xs.size >= 2) { "A spline needs at least two matching points" }
        val n = xs.size
        second = FloatArray(n)
        if (n > 2) {
            // Tridiagonal system for the interior second derivatives, solved with the Thomas algorithm.
            val lower = FloatArray(n)
            val diag = FloatArray(n)
            val upper = FloatArray(n)
            val rhs = FloatArray(n)
            for (i in 1 until n - 1) {
                val h0 = xs[i] - xs[i - 1]
                val h1 = xs[i + 1] - xs[i]
                lower[i] = h0
                diag[i] = 2f * (h0 + h1)
                upper[i] = h1
                rhs[i] = 6f * ((ys[i + 1] - ys[i]) / h1 - (ys[i] - ys[i - 1]) / h0)
            }
            for (i in 2 until n - 1) {
                val w = lower[i] / diag[i - 1]
                diag[i] -= w * upper[i - 1]
                rhs[i] -= w * rhs[i - 1]
            }
            second[n - 2] = rhs[n - 2] / diag[n - 2]
            for (i in n - 3 downTo 1) {
                second[i] = (rhs[i] - upper[i] * second[i + 1]) / diag[i]
            }
        }
    }

    /** Value of the spline at [x], clamped to the range of the control points. */
    fun at(x: Float): Float {
        val clamped = x.coerceIn(xs.first(), xs.last())
        var i = xs.size - 2
        for (k in 0 until xs.size - 1) {
            if (clamped <= xs[k + 1]) {
                i = k
                break
            }
        }
        val h = xs[i + 1] - xs[i]
        val a = (xs[i + 1] - clamped) / h
        val b = (clamped - xs[i]) / h
        return a * ys[i] + b * ys[i + 1] +
                ((a * a * a - a) * second[i] + (b * b * b - b) * second[i + 1]) * (h * h) / 6f
    }

    companion object {
        fun of(vararg points: Pair<Float, Float>): SpicySpline =
            SpicySpline(
                FloatArray(points.size) { points[it].first },
                FloatArray(points.size) { points[it].second }
            )
    }
}

/**
 * Motion curves lifted from Spicy Lyrics' LyricsAnimator. Scale is a multiplier; lift is a fraction
 * of the lyric font size (positive = down); glow is 0..1.
 */
object SpicyMotion {
    val WordScale = SpicySpline.of(0f to 0.95f, 0.7f to 1.0505f, 1f to 1f)
    val LetterScale = SpicySpline.of(0f to 0.95f, 0.7f to 1.175f, 1f to 1f)
    val WordLift = SpicySpline.of(0f to 0.01f, 0.9f to -(1f / 60f), 1f to 0f)
    val LetterLift = SpicySpline.of(0f to 0.01f, 0.9f to -(1f / 56f), 1f to 0f)
    val Glow = SpicySpline.of(0f to 0f, 0.15f to 1f, 0.6f to 1f, 1f to 0f)

    val DotScale = SpicySpline.of(0f to 0.75f, 0.7f to 1.05f, 1f to 1f)
    val DotLift = SpicySpline.of(0f to 0f, 0.9f to -0.12f, 1f to 0f)
    val DotGlow = SpicySpline.of(0f to 0f, 0.6f to 1f, 1f to 1f)
    val DotOpacity = SpicySpline.of(0f to 0.35f, 0.6f to 1f, 1f to 1f)

    /** Letters of an emphasised word finish this long before the word itself does. */
    const val LetterTailMs = 250L

    /** Words this long (or longer) are split into individually animated letters. */
    const val LetterMinDurationMs = 1000L

    /** Distance blur per line away from the active line, and its cap (Spicy: 1.25 * 5.465). */
    const val BlurPerLinePx = 1.25f
    const val BlurMaxPx = 1.25f * 5.465f

    /**
     * Sweep gradient alphas. Words and letters carry their own values (0.85 lit, 0.5 dim); the 0.35
     * in Spicy's CSS only applies to the line element itself, which never paints text in syllable
     * lyrics. Background vocals use 0.6 / 0.3.
     */
    const val LitAlpha = 0.85f
    const val DimAlpha = 0.5f
    const val BgLitAlpha = 0.6f
    const val BgDimAlpha = 0.3f

    /** Spicy springs are specified as frequency (Hz) and damping ratio; Compose wants stiffness. */
    private fun stiffness(frequencyHz: Float): Float {
        val omega = 2f * Math.PI.toFloat() * frequencyHz
        return omega * omega
    }

    /**
     * Spicy glides the lyric list to the next line with a critically damped spring at 1 Hz: about
     * 0.8s, no overshoot, and a retarget keeps its velocity so quick line changes read as one glide.
     */
    val LineScrollSpring: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = stiffness(1f))

    val WordScaleSpring: SpringSpec<Float> = spring(dampingRatio = 0.64f, stiffness = stiffness(0.88f))
    val WordLiftSpring: SpringSpec<Float> = spring(dampingRatio = 0.4f, stiffness = stiffness(1.45f))
    val WordGlowSpring: SpringSpec<Float> = spring(dampingRatio = 0.56f, stiffness = stiffness(1.18f))

    val DotScaleSpring: SpringSpec<Float> = spring(dampingRatio = 0.6f, stiffness = stiffness(0.7f))
    val DotLiftSpring: SpringSpec<Float> = spring(dampingRatio = 0.4f, stiffness = stiffness(1.25f))
    val DotFadeSpring: SpringSpec<Float> = spring(dampingRatio = 0.5f, stiffness = stiffness(1f))
}
