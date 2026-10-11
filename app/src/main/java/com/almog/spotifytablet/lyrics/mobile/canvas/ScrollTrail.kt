package com.almog.spotifytablet.lyrics.mobile.canvas

import com.almog.spotifytablet.lyrics.mobile.animation.AppleMusicMotion
import kotlin.math.abs

/**
 * The scroll's recent positions, so a line can follow the same move a little later: the Apple
 * Music style sets each line past the active one off [AppleMusicMotion.STAGGER_S] after the one
 * before it, up to [AppleMusicMotion.STAGGER_STEPS] lines, like a CSS transition delay.
 */
internal class ScrollTrail {
    private val times = LongArray(CAPACITY)
    private val positions = FloatArray(CAPACITY)
    private var count = 0
    private var head = 0

    /** Which way the view last moved: -1 as the lyrics go up (forward), 1 as they come back down. */
    var direction = -1
        private set

    /** The line the moves are measured from: the one scrolled to. */
    var reference = 0

    fun record(timeNanos: Long, y: Float) {
        if (count > 0) {
            val last = positions[(head - 1 + CAPACITY) % CAPACITY]
            if (abs(y - last) > 0.01f) direction = if (y < last) -1 else 1
        }
        times[head] = timeNanos
        positions[head] = y
        head = (head + 1) % CAPACITY
        if (count < CAPACITY) count++
    }

    fun clear() {
        count = 0
        head = 0
    }

    /** Where the view was at [timeNanos], between the frames recorded around it. */
    fun at(timeNanos: Long): Float {
        if (count == 0) return 0f
        var newer = (head - 1 + CAPACITY) % CAPACITY
        if (timeNanos >= times[newer]) return positions[newer]
        for (i in 1 until count) {
            val older = (newer - 1 + CAPACITY) % CAPACITY
            if (timeNanos >= times[older]) {
                val span = (times[newer] - times[older]).coerceAtLeast(1L)
                val f = (timeNanos - times[older]).toFloat() / span
                return positions[older] + (positions[newer] - positions[older]) * f
            }
            newer = older
        }
        return positions[newer]
    }

    /** How long after the view [lineIndex] moves. */
    fun delayNanos(lineIndex: Int): Long {
        val steps = if (direction < 0) lineIndex - reference else reference - lineIndex
        return steps.coerceIn(0, AppleMusicMotion.STAGGER_STEPS) * (AppleMusicMotion.STAGGER_S * 1e9f).toLong()
    }

    /** True while a line that follows late is still behind the view. */
    fun catchingUp(nowNanos: Long): Boolean {
        if (count == 0) return false
        val y = at(nowNanos)
        return abs(at(nowNanos - MAX_DELAY_NANOS) - y) > 0.25f
    }

    private companion object {
        /** Enough frames for the longest delay at 120 fps. */
        const val CAPACITY = 64
        val MAX_DELAY_NANOS = (AppleMusicMotion.STAGGER_STEPS * AppleMusicMotion.STAGGER_S * 1e9f).toLong()
    }
}
