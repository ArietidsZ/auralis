package com.dialect.interpreter.ui

import com.dialect.interpreter.ui.components.WAVE_LOUD_CEIL
import com.dialect.interpreter.ui.components.WAVE_MIN_FRACTION
import com.dialect.interpreter.ui.components.WAVE_QUIET_FLOOR
import com.dialect.interpreter.ui.components.WAVE_REST_LEVEL
import com.dialect.interpreter.ui.components.barHeightFraction
import com.dialect.interpreter.ui.components.barWindow
import com.dialect.interpreter.ui.components.levelFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.nextDown
import kotlin.math.nextUp

/**
 * Pure mapping behind the waveform visualizer (motion spec: real RMS, bounded
 * visual mapping readable for quiet speech, safe static fallback for
 * non-finite input). Compose-free so it runs on the JVM.
 */
class WaveformLevelMappingTest {

    @Test
    fun `active level is bounded and clamps out-of-range rms`() {
        assertEquals(0f, levelFraction(0f, isActive = true))
        assertEquals(0f, levelFraction(WAVE_QUIET_FLOOR, isActive = true))
        assertEquals(1f, levelFraction(WAVE_LOUD_CEIL, isActive = true))
        assertEquals(1f, levelFraction(WAVE_LOUD_CEIL * 8f, isActive = true))
        for (i in 0 until 200) {
            val rms = i / 50f
            val level = levelFraction(rms, isActive = true)
            assertTrue("rms=$rms level=$level", level in 0f..1f)
        }
    }

    @Test
    fun `quiet speech spreads above the rest outline instead of collapsing`() {
        // 0.01–0.1 RMS is ordinary conversation; it must land clearly above the
        // rest outline and well before the ceiling (the old 0.15 floor made it
        // indistinguishable from silence).
        val quiet = levelFraction(0.01f, isActive = true)
        val normal = levelFraction(0.1f, isActive = true)
        assertTrue("0.01 RMS should be visible: $quiet", quiet >= WAVE_REST_LEVEL + 0.02f)
        assertTrue("0.1 RMS should be strong: $normal", normal >= 0.6f)
        assertTrue(normal < 1f)
    }

    @Test
    fun `level is monotonic in rms`() {
        var previous = -1f
        var rms = 1e-4f
        while (rms <= 1f) {
            val level = levelFraction(rms, isActive = true)
            assertTrue("rms=$rms regressed: $level < $previous", level >= previous)
            previous = level
            rms = rms.nextUp()
        }
    }

    @Test
    fun `inactive and non-finite inputs fall back to the static rest level`() {
        for (amplitude in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.3f)) {
            assertEquals(
                "inactive amplitude=$amplitude",
                WAVE_REST_LEVEL, levelFraction(amplitude, isActive = false))
        }
        for (amplitude in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(
                "non-finite amplitude=$amplitude",
                WAVE_REST_LEVEL, levelFraction(amplitude, isActive = true))
        }
    }

    @Test
    fun `bar heights stay bounded with a center-weighted static window`() {
        for (level in listOf(0f, 0.15f, 0.65f, 1f)) {
            for (index in 0 until 7) {
                val fraction = barHeightFraction(level, index)
                assertTrue("level=$level index=$index fraction=$fraction", fraction in WAVE_MIN_FRACTION..1f)
            }
        }
        // The window is a fixed shape: center bar tallest, edges equal.
        assertTrue(barWindow(3) > barWindow(0) && barWindow(3) > barWindow(6))
        assertEquals(barWindow(0), barWindow(6), 1e-6f)
        // Rest outline: level 0 keeps every bar at or above the visible floor.
        for (index in 0 until 7) {
            assertTrue(barHeightFraction(0f, index) >= WAVE_MIN_FRACTION)
        }
    }

    @Test
    fun `bar count of one does not divide by zero`() {
        // Degenerate count: the window guard clamps the divisor instead of
        // crashing; the shape stays a bounded static fraction.
        assertTrue(barWindow(0, barCount = 1) in 0f..1f)
        assertTrue(barHeightFraction(0.5f, 0, barCount = 1) in WAVE_MIN_FRACTION..1f)
    }
}
