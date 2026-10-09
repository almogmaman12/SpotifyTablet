package com.almog.spotifytablet.lyrics.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Playback state is deliberately discrete. A derived state can observe the playback clock,
 * but the lyric tree only recomposes when a word crosses one of these boundaries.
 */
internal enum class SpicyWordPlaybackState {
    Upcoming,
    Active,
    Completed
}

internal data class SpicyWordFrame(
    val scale: Float,
    val yOffsetEm: Float
)

/**
 * Native Kotlin port of the damped spring used by Spicy Lyrics.
 *
 * Frequency is expressed in Hz, dampingRatio follows the standard under/critical/overdamped
 * definition. This class owns no Compose state, so frame updates never force recomposition.
 */
private class SpicySpring(
    startPosition: Double,
    frequency: Double,
    dampingRatio: Double
) {
    private var damping = dampingRatio
    private var frequencyHz = frequency
    private var goal = startPosition
    private var position = startPosition
    private var velocity = 0.0

    fun setGoal(value: Double, replacePosition: Boolean = false) {
        goal = value
        if (replacePosition) {
            position = value
            velocity = 0.0
        }
    }

    fun step(deltaSeconds: Double): Double {
        if (deltaSeconds <= 0.0) return position

        val d = damping
        val f = frequencyHz * (2.0 * PI)
        val g = goal
        var p = position
        var v = velocity

        if (d == 1.0) {
            val q = exp(-f * deltaSeconds)
            val w = deltaSeconds * q
            val c0 = q + w * f
            val c2 = q - w * f
            val c3 = w * f * f
            val offset = p - g
            p = offset * c0 + v * w + g
            v = v * c2 - offset * c3
        } else if (d < 1.0) {
            val q = exp(-d * f * deltaSeconds)
            val c = sqrt(max(0.0, 1.0 - d * d))
            val i = cos(deltaSeconds * f * c)
            val j = sin(deltaSeconds * f * c)
            val z = if (c > SPRING_EPSILON) {
                j / c
            } else {
                val a = deltaSeconds * f
                a + ((a * a) * (c * c) * (c * c) / 20.0 - c * c) *
                    (a * a * a) / 6.0
            }
            val y = if (f * c > SPRING_EPSILON) {
                j / (f * c)
            } else {
                val b = f * c
                deltaSeconds + ((deltaSeconds * deltaSeconds) *
                    (b * b) * (b * b) / 20.0 - b * b) *
                    (deltaSeconds * deltaSeconds * deltaSeconds) / 6.0
            }
            val offset = p - g
            p = (offset * (i + z * d) + v * y) * q + g
            v = (v * (i - z * d) - offset * (z * f)) * q
        } else {
            val c = sqrt(d * d - 1.0)
            val r1 = -f * (d + c)
            val r2 = -f * (d - c)
            val ec1 = exp(r1 * deltaSeconds)
            val ec2 = exp(r2 * deltaSeconds)
            val offset = p - g
            val co2 = (v - offset * r1) / (2.0 * f * c)
            val co1 = ec1 * (offset - co2)
            p = co1 + co2 * ec2 + g
            v = co1 * r1 + co2 * ec2 * r2
        }

        position = p
        velocity = v

        if (abs(position - goal) < SPRING_POSITION_EPSILON &&
            abs(velocity) < SPRING_VELOCITY_EPSILON
        ) {
            position = goal
            velocity = 0.0
        }
        return position
    }

    companion object {
        private const val SPRING_EPSILON = 1e-5
        private const val SPRING_POSITION_EPSILON = 1.0 / 3840.0
        private const val SPRING_VELOCITY_EPSILON = 1e-2
    }
}

/** Natural cubic spline, matching the reference renderer's cubic-spline profile curves. */
private class SpicySpline(points: List<Pair<Double, Double>>) {
    private val x = points.map { it.first }.toDoubleArray()
    private val y = points.map { it.second }.toDoubleArray()
    private val secondDerivatives = DoubleArray(points.size)

    init {
        val interiorCount = points.size - 2
        if (interiorCount > 0) {
            val lower = DoubleArray(interiorCount)
            val diagonal = DoubleArray(interiorCount)
            val upper = DoubleArray(interiorCount)
            val rhs = DoubleArray(interiorCount)

            for (row in 0 until interiorCount) {
                val i = row + 1
                val hBefore = x[i] - x[i - 1]
                val hAfter = x[i + 1] - x[i]
                lower[row] = if (row == 0) 0.0 else hBefore
                diagonal[row] = 2.0 * (hBefore + hAfter)
                upper[row] = if (row == interiorCount - 1) 0.0 else hAfter
                rhs[row] = 6.0 * (
                    (y[i + 1] - y[i]) / hAfter -
                        (y[i] - y[i - 1]) / hBefore
                    )
            }

            for (i in 1 until interiorCount) {
                val factor = lower[i] / diagonal[i - 1]
                diagonal[i] -= factor * upper[i - 1]
                rhs[i] -= factor * rhs[i - 1]
            }

            val solution = DoubleArray(interiorCount)
            solution[interiorCount - 1] = rhs[interiorCount - 1] / diagonal[interiorCount - 1]
            for (i in interiorCount - 2 downTo 0) {
                solution[i] = (rhs[i] - upper[i] * solution[i + 1]) / diagonal[i]
            }
            for (i in solution.indices) {
                secondDerivatives[i + 1] = solution[i]
            }
        }
    }

    fun at(rawValue: Double): Double {
        val value = rawValue.coerceIn(x.first(), x.last())
        var segment = 0
        while (segment < x.lastIndex - 1 && value > x[segment + 1]) {
            segment++
        }

        val h = x[segment + 1] - x[segment]
        if (h <= 0.0) return y[segment]
        val a = (x[segment + 1] - value) / h
        val b = (value - x[segment]) / h
        return a * y[segment] + b * y[segment + 1] +
            ((a * a * a - a) * secondDerivatives[segment] +
                (b * b * b - b) * secondDerivatives[segment + 1]) *
            h * h / 6.0
    }
}

/**
 * One instance is remembered per visible word or letter. It is sampled only by Compose's
 * layer/draw lambdas while that element is active, never during ordinary composition.
 */
internal class SpicyLyricsAnimator(private val isLetter: Boolean) {
    private val scaleSpring = SpicySpring(
        startPosition = 0.95,
        frequency = 0.88,
        dampingRatio = 0.64
    )
    private val ySpring = SpicySpring(
        startPosition = 0.01,
        frequency = 1.45,
        dampingRatio = 0.4
    )
    private var lastPositionMs = Long.MIN_VALUE
    private var lastFrameNanos = 0L
    private var cachedPositionMs = Long.MIN_VALUE
    private var cachedFrameNanos = 0L
    private var cachedFrame: SpicyWordFrame? = null

    fun sample(
        positionMs: Long,
        startTimeMs: Long,
        endTimeMs: Long,
        frameTimeNanos: Long
    ): SpicyWordFrame {
        val previous = cachedFrame
        if (previous != null &&
            cachedPositionMs == positionMs &&
            frameTimeNanos >= cachedFrameNanos &&
            frameTimeNanos - cachedFrameNanos <= SAME_FRAME_CACHE_NANOS
        ) {
            return previous
        }

        val duration = (endTimeMs - startTimeMs).coerceAtLeast(1L)
        val progress = ((positionMs + PRE_ROLL_OFFSET_MS - startTimeMs).toDouble() /
            duration.toDouble()).coerceIn(0.0, 1.0)

        val scaleTarget = if (isLetter) LetterScaleSpline.at(progress) else WordScaleSpline.at(progress)
        val yTarget = (if (isLetter) LetterYOffsetSpline else WordYOffsetSpline).at(progress)
        val isSeek = lastPositionMs != Long.MIN_VALUE &&
            abs(positionMs - lastPositionMs) > SEEK_SNAP_THRESHOLD_MS
        scaleSpring.setGoal(scaleTarget, replacePosition = isSeek)
        ySpring.setGoal(yTarget, replacePosition = isSeek)
        val deltaSeconds = if (lastFrameNanos == 0L || frameTimeNanos <= lastFrameNanos) {
            0.0
        } else {
            ((frameTimeNanos - lastFrameNanos).toDouble() / NANOS_PER_SECOND)
                .coerceIn(0.0, MAX_FRAME_DELTA_SECONDS)
        }

        val frame = SpicyWordFrame(
            scale = scaleSpring.step(deltaSeconds).toFloat(),
            yOffsetEm = ySpring.step(deltaSeconds).toFloat()
        )

        lastPositionMs = positionMs
        lastFrameNanos = frameTimeNanos
        cachedPositionMs = positionMs
        cachedFrameNanos = frameTimeNanos
        cachedFrame = frame
        return frame
    }

    companion object {
        private const val PRE_ROLL_OFFSET_MS = 45L
        private const val SEEK_SNAP_THRESHOLD_MS = 1500L
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val MAX_FRAME_DELTA_SECONDS = 0.05
        private const val SAME_FRAME_CACHE_NANOS = 4_000_000L

        private val WordScaleSpline = SpicySpline(
            listOf(0.0 to 0.95, 0.7 to 1.0505, 1.0 to 1.0)
        )
        private val LetterScaleSpline = SpicySpline(
            listOf(0.0 to 0.95, 0.7 to 1.175, 1.0 to 1.0)
        )
        private val WordYOffsetSpline = SpicySpline(
            listOf(0.0 to 0.01, 0.9 to -(1.0 / 60.0), 1.0 to 0.0)
        )
        private val LetterYOffsetSpline = SpicySpline(
            listOf(0.0 to 0.01, 0.9 to -(1.0 / 56.0), 1.0 to 0.0)
        )
    }
}
