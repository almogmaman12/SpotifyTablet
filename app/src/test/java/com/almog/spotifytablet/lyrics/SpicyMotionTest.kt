package com.almog.spotifytablet.lyrics

import com.almog.spotifytablet.lyrics.model.SpicyMotion
import com.almog.spotifytablet.lyrics.model.SpicySpline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpicyMotionTest {

    private val eps = 1e-4f

    @Test
    fun spline_passesThroughControlPoints() {
        val spline = SpicySpline.of(0f to 0.95f, 0.7f to 1.0505f, 1f to 1f)
        assertEquals(0.95f, spline.at(0f), eps)
        assertEquals(1.0505f, spline.at(0.7f), eps)
        assertEquals(1f, spline.at(1f), eps)
    }

    @Test
    fun spline_clampsOutsideRange() {
        val spline = SpicyMotion.WordScale
        assertEquals(spline.at(0f), spline.at(-5f), eps)
        assertEquals(spline.at(1f), spline.at(5f), eps)
    }

    @Test
    fun glow_isOffAtBothEndsAndOnInTheMiddle() {
        assertEquals(0f, SpicyMotion.Glow.at(0f), eps)
        assertEquals(1f, SpicyMotion.Glow.at(0.15f), eps)
        assertEquals(1f, SpicyMotion.Glow.at(0.6f), eps)
        assertEquals(0f, SpicyMotion.Glow.at(1f), eps)
    }

    @Test
    fun wordScale_swellsPastRestingSize() {
        assertTrue(SpicyMotion.WordScale.at(0.7f) > 1f)
        assertTrue(SpicyMotion.LetterScale.at(0.7f) > SpicyMotion.WordScale.at(0.7f))
    }

    @Test
    fun wordLift_dipsThenRises() {
        assertTrue(SpicyMotion.WordLift.at(0f) > 0f)
        assertTrue(SpicyMotion.WordLift.at(0.9f) < 0f)
        assertEquals(0f, SpicyMotion.WordLift.at(1f), eps)
    }
}
