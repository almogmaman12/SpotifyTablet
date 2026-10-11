package com.almog.spotifytablet.lyrics.mobile.animation

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Apple Music lyrics style's motion, as plain functions of time: nothing here steps a spring,
 * so any frame (or a seek) lands on the same pose. Lengths are in lyric font sizes (em), times in
 * milliseconds unless named otherwise. The word rise, line dimming, distance blur, background
 * vocal size and the dots' breathing are measured off iPhone screen recordings of the Music app;
 * the rest follows the open recreations of it (`docs/upstream-notes.md` has both).
 */
object AppleMusicMotion {
    // ── Words ───────────────────────────────────────────────────────────────────────

    /** How far a word rises as it's sung (measured: 2.3px on 41px text); background vocals rise twice as far. */
    const val WORD_LIFT_EM = 0.055f
    /** The rise's spring: critically damped, settling over about this long, whatever the word's length. */
    const val RISE_RESPONSE_S = 1.4f

    /**
     * How far up a word is (0 → 1, times [WORD_LIFT_EM]): it springs up from its start, stays up
     * while its line is sung, and once the line ends springs back down from wherever it got to.
     * [lineEndMs] null means the line is still going.
     */
    fun wordRise(t: Double, wordStartMs: Long, @Suppress("UNUSED_PARAMETER") wordEndMs: Long, lineEndMs: Long?): Float {
        fun up(ms: Double) = springProgress((ms / 1000.0).toFloat(), RISE_RESPONSE_S)
        if (lineEndMs == null || t < lineEndMs) return up(t - wordStartMs)
        return up((lineEndMs - wordStartMs).toDouble()) * (1f - up(t - lineEndMs))
    }

    /** A critically damped spring's step, 0 → 1, settling over about [response] seconds. */
    fun springProgress(t: Float, response: Float): Float {
        if (t <= 0f) return 0f
        if (t.isInfinite()) return 1f
        val phase = 2f * PI.toFloat() * t / response.coerceAtLeast(0.001f)
        return 1f - (1f + phase) * kotlin.math.exp(-phase)
    }

    /** Unsung text: white at 55%. Sung text and the active line's lit words: white. */
    const val DIM_ALPHA = 0.55f
    const val BRIGHT_ALPHA = 1f
    /** Background vocals: the same against a grey, so they sit behind the lead. */
    const val BACKGROUND_DIM_ALPHA = 0.4f
    const val BACKGROUND_BRIGHT_ALPHA = 0.75f

    /**
     * The soft edge behind the wipe: 0.75em, up to 0.45em more for long words, 0.3em for CJK; the
     * renderer keeps it to half the word, or a short word would light all at once.
     */
    fun featherEm(charCount: Int, cjk: Boolean): Float =
        if (cjk) 0.3f else 0.75f + ((charCount - 6) / 10f).coerceIn(0f, 1f) * 0.45f

    // ── Held words ──────────────────────────────────────────────────────────────────

    /** Held words of 1s or longer get the letter emphasis: 2 to 7 letters, or any CJK word. */
    const val EMPHASIS_MIN_MS = 1000L
    const val EMPHASIS_MAX_LETTERS = 7

    fun emphasizes(text: String, letterCount: Int, durationMs: Long): Boolean = when {
        durationMs < EMPHASIS_MIN_MS || isRtl(text) -> false
        isCjk(text) -> true
        else -> letterCount in 2..EMPHASIS_MAX_LETTERS
    }

    /**
     * One letter of a held word at [t]: the letters swell, push apart from the middle and glow
     * one after another, each bobbing up a little on top of the word's own rise. A line's last
     * word does it harder and longer. [index] is the letter's place among [count].
     */
    fun emphasisLetter(
        t: Double,
        wordStartMs: Long,
        wordEndMs: Long,
        index: Int,
        count: Int,
        lastWord: Boolean,
        background: Boolean,
    ): LetterPose {
        val n = count.coerceAtLeast(1)
        var du = maxOf(1000.0, (wordEndMs - wordStartMs).toDouble())
        var amount = du / 2000.0
        amount = if (amount > 1) sqrt(amount) else amount * amount * amount
        var blur = du / 3000.0
        blur = if (blur > 1) sqrt(blur) else blur * blur * blur
        amount *= 0.6
        blur *= 0.5
        // A line's last word is held a little longer, but no bigger.
        if (lastWord) du *= 1.2
        amount = minOf(1.2, amount) * EMPHASIS_STRENGTH
        blur = minOf(0.8, blur)

        // Each letter starts a 2.5th of the word's length over the letter count after the one before.
        val start = wordStartMs + du / 2.5 / n * index
        // Swell, spread and glow over the word's length, rising and falling back.
        val grow = emphasisEasing(((t - start) / du).coerceIn(0.0, 1.0).toFloat())
        // The bob: up and back down over 1.4 times that, starting 400ms ahead.
        val bobX = ((t - (start - 400.0)) / (du * 1.4)).coerceIn(0.0, 1.0)
        val bob = sin(bobX * PI).toFloat() * WORD_LIFT_EM * (if (background) 2f else 1f)
        val a = amount.toFloat()
        return LetterPose(
            xOffset = -grow * 0.03f * a * (n / 2f - index),
            yOffset = -grow * 0.025f * a - bob,
            scale = 1f + grow * 0.1f * a,
            glow = grow * blur.toFloat(),
            glowRadiusEm = minOf(0.3f, blur.toFloat() * 0.3f),
        )
    }

    /** How much of the recreation's swell and spread is used: the full amount went too far. */
    const val EMPHASIS_STRENGTH = 0.6f

    /** One letter's pose on top of its word's rise: offsets in em, [glow] its halo's opacity. */
    data class LetterPose(
        val xOffset: Float,
        val yOffset: Float,
        val scale: Float,
        val glow: Float,
        val glowRadiusEm: Float = 0f,
    )

    private val EMPHASIS_IN = CubicBezier(0.2f, 0.4f, 0.58f, 1f)
    private val EMPHASIS_OUT = CubicBezier(0.3f, 0f, 0.58f, 1f)

    /** Up to the peak over the first half, back down over the second. */
    private fun emphasisEasing(x: Float): Float =
        if (x < 0.5f) EMPHASIS_IN(x / 0.5f) else 1f - EMPHASIS_OUT((x - 0.5f) / 0.5f)

    // ── Lines ───────────────────────────────────────────────────────────────────────

    /** Lines other than the active one, at their resting size, shrunk about their start edge. */
    const val INACTIVE_SCALE = 0.98f
    /** A line fades and shrinks over these. */
    const val OPACITY_MS = 700
    const val SCALE_IN_MS = 500
    const val SCALE_OUT_MS = 700

    /**
     * Line opacity: every line but the active one alike (measured at 0.35 of a lit line's brightness,
     * which is this times [DIM_ALPHA]); distance shows through the blur instead. While the user
     * scrolls, a little brighter.
     */
    fun lineOpacity(distance: Int, userScrolling: Boolean): Float = when {
        distance == 0 -> 1f
        userScrolling -> 0.8f
        else -> 0.64f
    }

    /**
     * Distance blur, as a Gaussian deviation in em: [signedDistance] is negative above the active
     * line. The shape (steep over the first lines, then level) is measured off older recordings,
     * whose compression made it read too strong; [BLUR_STRENGTH] brings the next line to the
     * 0.04-0.05em measured on iOS 26.
     */
    fun blurEm(signedDistance: Int): Float = BLUR_STRENGTH * when {
        signedDistance == 0 -> 0f
        signedDistance == -1 -> 0.08f
        signedDistance < 0 -> 0.12f
        signedDistance == 1 -> 0.07f
        signedDistance == 2 -> 0.11f
        signedDistance == 3 -> 0.13f
        else -> 0.15f
    }

    /** How much of the older recordings' blur is used, matching iOS 26. */
    const val BLUR_STRENGTH = 0.6f

    /** Background vocals take this share of their distance's blur, and none while their line is sung. */
    const val BACKGROUND_BLUR_SHARE = 0.5f

    /** A line's blur comes and goes over this long. */
    const val BLUR_FADE_MS = 500

    /** Once a line ends, its lit words dim to the unsung colour over this long. */
    const val LINE_SETTLE_MS = 600

    /** Background vocals' text size against the lead's. */
    const val BACKGROUND_SIZE = 0.7f

    /**
     * Background vocals open under their line while it's sung, and fold away after, on a
     * critically damped spring (settling in about 0.8s): it keeps its speed when it turns back
     * halfway, so vocals that come and go quickly never jerk.
     */
    const val BACKGROUND_SPRING_STIFFNESS = 60f

    /**
     * Background vocals' opacity for how far open they are: they show only once their room has
     * mostly opened, and are gone before it closes, so they're never squeezed half-visible.
     */
    fun backgroundAlpha(presence: Float): Float {
        val x = ((presence - 0.3f) / 0.7f).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
    /** Folded, they're this size and sit this far (of their slot) up under the line. */
    const val BACKGROUND_FOLDED_SCALE = 0.96f
    const val BACKGROUND_FOLDED_SHIFT = 0.3f

    // ── Scrolling ───────────────────────────────────────────────────────────────────

    /** Lines past the active one start their scroll this much later each, up to [STAGGER_STEPS]. */
    const val STAGGER_S = 0.05f
    const val STAGGER_STEPS = 4
    /** The scroll's spring: mass 1, stiffness 100, damping 18 (ω 10 rad/s, damping ratio 0.9). */
    const val SCROLL_FREQUENCY_HZ = 1.5915494f // 10 / 2π
    const val SCROLL_DAMPING = 0.9f

    // ── Interlude dots ──────────────────────────────────────────────────────────────

    /**
     * The breathing dots over an instrumental break [durationMs] long, at [t] ms into it: they
     * grow in, breathe slowly, dip and rise once just before the singing comes back, then pop
     * away; each dot lights from 40% to full in turn across the break.
     */
    class Dots(private val durationMs: Float, private val count: Int = 3) {
        private val factor = (durationMs / (ENTER + DIP + STILL + EXIT)).coerceAtMost(1f)
        private val enterEnd = ENTER * factor
        private val exitStart = durationMs - EXIT * factor
        private val stillStart = exitStart - STILL * factor
        private val dipStart = stillStart - DIP * factor
        private val breathing = (dipStart - enterEnd).coerceAtLeast(0f)
        private val breathes = breathing > 16f
        private val halfCycles = Math.round(breathing / 1500f).coerceAtLeast(1).let { if (it % 2 == 0) it + 1 else it }
        private val period = 2f * breathing / halfCycles
        private val dotSpan = (exitStart - enterEnd).coerceAtLeast(1f) / count

        fun scale(t: Float): Float = when {
            durationMs <= 0f -> 0f
            t < enterEnd -> smooth(t / enterEnd) * if (breathes) SMALL else 1f
            breathes && t < dipStart -> (1f + SMALL) / 2f - (1f - SMALL) / 2f * cos((t - enterEnd) / period * 2f * PI.toFloat())
            t < dipStart -> 1f
            t < stillStart -> SMALL + (1f - SMALL) * cos(((t - dipStart) / (stillStart - dipStart).coerceAtLeast(1e-6f)).coerceIn(0f, 1f) * 2f * PI.toFloat())
            t < exitStart -> 1f
            else -> smooth((durationMs - t) / (durationMs - exitStart).coerceAtLeast(1e-6f))
        }

        fun alpha(t: Float): Float = when {
            durationMs <= 0f -> 0f
            t < enterEnd -> smooth(t / enterEnd)
            t < exitStart -> 1f
            else -> smooth((durationMs - t) / (durationMs - exitStart).coerceAtLeast(1e-6f))
        }

        fun dotAlpha(index: Int, t: Float): Float =
            0.4f + 0.6f * ((t - enterEnd - dotSpan * index) / dotSpan).coerceIn(0f, 1f)

        private fun smooth(v: Float): Float {
            val x = v.coerceIn(0f, 1f)
            return x * x * (3f - 2f * x)
        }

        private companion object {
            const val ENTER = 3000f
            const val DIP = 3000f
            const val STILL = 200f
            const val EXIT = 200f
            /** The breath's small end: measured on iOS the group swells only ~8% across. */
            const val SMALL = 0.92f
        }
    }

    // ── Text ────────────────────────────────────────────────────────────────────────

    /** Chinese and Japanese text, which has no spaces to call words by. */
    fun isCjk(text: String): Boolean = text.any { c ->
        c in '㐀'..'䶿' || c in '一'..'鿿' || c in '぀'..'ヿ'
    }

    /** Right-to-left scripts, kept whole: their letters join, so they can't move apart. */
    fun isRtl(text: String): Boolean = text.any { it in '֐'..'ࣿ' }

}

/** A CSS `cubic-bezier()` timing curve, solved for y at a given x. */
internal class CubicBezier(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float) {
    operator fun invoke(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        // Newton's method on x(s), falling back to bisection if it strays.
        var s = x
        repeat(8) {
            val dx = curve(s, x1, x2) - x
            if (abs(dx) < 1e-5f) return curve(s, y1, y2)
            val d = slope(s, x1, x2)
            if (abs(d) < 1e-6f) return@repeat
            s -= dx / d
        }
        var lo = 0f
        var hi = 1f
        s = x
        repeat(30) {
            val cx = curve(s, x1, x2)
            if (abs(cx - x) < 1e-5f) return curve(s, y1, y2)
            if (cx < x) lo = s else hi = s
            s = (lo + hi) / 2f
        }
        return curve(s, y1, y2)
    }

    private fun curve(s: Float, p1: Float, p2: Float): Float {
        val u = 1f - s
        return 3f * u * u * s * p1 + 3f * u * s * s * p2 + s * s * s
    }

    private fun slope(s: Float, p1: Float, p2: Float): Float {
        val u = 1f - s
        return 3f * u * u * p1 + 6f * u * s * (p2 - p1) + 3f * s * s * (1f - p2)
    }
}
