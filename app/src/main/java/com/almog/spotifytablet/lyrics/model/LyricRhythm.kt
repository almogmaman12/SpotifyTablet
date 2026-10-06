package com.almog.spotifytablet.lyrics.model

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.math.sin

/**
 * Rhythm and tempo metrics for song-level and word-level synchronization.
 *
 * @property bpm Track tempo in beats per minute (e.g. 128f).
 * @property hasExplicitBpm True if BPM was retrieved from a definitive source (e.g. GetSongBPM / Spotify Audio Features).
 */
data class TrackRhythmContext(
    val bpm: Float = 120.0f,
    val hasExplicitBpm: Boolean = false
) {
    /**
     * Duration of a standard quarter note (beat interval) in milliseconds.
     * Beat_Interval_MS = 60000 / BPM
     */
    val beatIntervalMs: Float
        get() = (60_000.0f / bpm.coerceIn(40.0f, 260.0f))

    /**
     * Eighth note interval (half beat) in milliseconds.
     */
    val eighthIntervalMs: Float
        get() = beatIntervalMs * 0.5f

    /**
     * Sixteenth note interval (quarter beat) in milliseconds.
     */
    val sixteenthIntervalMs: Float
        get() = beatIntervalMs * 0.25f

    /**
     * Normalized tempo factor centered at 120 BPM [0.55 .. 2.1].
     */
    val tempoFactor: Float
        get() = (bpm / 120.0f).coerceIn(0.55f, 2.1f)

    companion object {
        val Default = TrackRhythmContext(bpm = 120.0f, hasExplicitBpm = false)
    }
}

/**
 * Calculates adaptive spring parameters based on track tempo, with optional lerp interpolation factor.
 * Higher BPM creates snappier springs, while slower tracks produce smoother, fluid glides.
 */
fun <T> TrackRhythmContext.calculateRhythmSpringSpec(
    baseStiffness: Float = 280f,
    baseDamping: Float = 0.80f,
    lerpFactor: Float = 1.0f
): SpringSpec<T> {
    val factor = tempoFactor
    // High BPM = snappier stiffness (e.g. 380f+), Low BPM = soft float (e.g. 190f)
    val targetStiffness = (baseStiffness * Math.pow(factor.toDouble(), 1.15)).toFloat().coerceIn(160f, 600f)
    // Slightly tighter damping on faster tracks to prevent chaotic ringing
    val targetDamping = (baseDamping - (factor - 1.0f) * 0.04f).coerceIn(0.72f, 0.90f)

    val effectiveStiffness = if (lerpFactor < 1.0f) {
        baseStiffness + (targetStiffness - baseStiffness) * lerpFactor.coerceIn(0f, 1f)
    } else {
        targetStiffness
    }

    val effectiveDamping = if (lerpFactor < 1.0f) {
        baseDamping + (targetDamping - baseDamping) * lerpFactor.coerceIn(0f, 1f)
    } else {
        targetDamping
    }

    return spring(stiffness = effectiveStiffness, dampingRatio = effectiveDamping)
}

/**
 * Calculates progressive word/syllable sweep progress using rhythm-aware easing curves.
 * Fast-path clamped and branch-optimized.
 *
 * @param progress Linear progress ratio [0f .. 1f] of the current word.
 * @param wordDurationMs Total duration of the word in milliseconds.
 * @param rhythm Track rhythm context (BPM).
 */
fun calculateWordProgressEasing(
    progress: Float,
    wordDurationMs: Long,
    rhythm: TrackRhythmContext
): Float {
    if (progress <= 0f) return 0f
    if (progress >= 1f) return 1f

    val clamped = progress
    val eighthMs = rhythm.eighthIntervalMs

    return when {
        wordDurationMs <= eighthMs -> {
            // Staccato / Fast rapid fire syllable: Fast attack cubic ease-out
            val t = 1.0f - clamped
            1.0f - (t * t * t)
        }
        wordDurationMs >= (rhythm.beatIntervalMs * 1.5f).toLong() -> {
            // Sustained long note: Hermite smoothstep with soft tail to sustain vocal presence
            clamped * clamped * (3.0f - 2.0f * clamped)
        }
        else -> {
            // Standard note duration: Smooth sinusoidal acceleration
            (1.0f - kotlin.math.cos(clamped * Math.PI.toFloat())) * 0.5f
        }
    }
}

/**
 * Computes dynamic scale magnification and micro-pulsing for active words.
 *
 * @param progress Linear progress ratio [0f .. 1f].
 * @param durationMs Syllable duration.
 * @param currentPositionMs Current player position for sub-beat pulsing.
 * @param rhythm Track rhythm metrics.
 */
fun calculateRhythmWordScale(
    progress: Float,
    durationMs: Long,
    currentPositionMs: Long,
    rhythm: TrackRhythmContext
): Float {
    if (progress <= 0f) return 0.97f
    if (progress >= 1f) return 1.0f

    val tempoFactor = rhythm.tempoFactor
    // Snappier pop on fast tracks, relaxed float on slow tracks
    val peakScale = if (durationMs < rhythm.eighthIntervalMs) {
        1.14f * (0.95f + 0.05f * tempoFactor)
    } else {
        1.10f
    }

    val baseScale = if (progress < 0.50f) {
        val t = progress / 0.50f
        val ease = 1f - (1f - t) * (1f - t) * (1f - t)
        0.97f + (peakScale - 0.97f) * ease
    } else {
        val t = (progress - 0.50f) / 0.50f
        val ease = t * t * (3f - 2f * t)
        peakScale - (peakScale - 1.0f) * ease
    }

    // Optional beat-synced micro-pulse for sustained vocal holds (> 1.25 beats)
    val microPulse = if (durationMs > rhythm.beatIntervalMs * 1.25f && rhythm.hasExplicitBpm) {
        val beatInterval = rhythm.beatIntervalMs
        val beatPhase = (currentPositionMs % beatInterval.toLong()).toFloat() / beatInterval
        // Subtle rhythmic harmonic wave (< 2% amplitude) with exponential decay
        (0.018f * sin(beatPhase * Math.PI.toFloat()) * kotlin.math.exp(-2.2f * beatPhase)).coerceAtLeast(0f)
    } else {
        0.0f
    }

    return baseScale + microPulse
}

/**
 * Computes dynamic vertical rise/lift for active syllables adapted to track pacing.
 */
fun calculateRhythmWordYOffset(
    progress: Float,
    durationMs: Long,
    rhythm: TrackRhythmContext
): Float {
    if (progress <= 0f || progress >= 1f) return 0f

    val maxLift = if (durationMs < rhythm.eighthIntervalMs) -3.4f else -2.2f
    return if (progress < 0.50f) {
        val t = progress / 0.50f
        val ease = 1f - (1f - t) * (1f - t) * (1f - t)
        maxLift * ease
    } else {
        val t = (progress - 0.50f) / 0.50f
        val ease = t * t * (3f - 2f * t)
        maxLift * (1f - ease)
    }
}
