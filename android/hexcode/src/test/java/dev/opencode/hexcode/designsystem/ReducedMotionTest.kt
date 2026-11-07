package dev.opencode.hexcode.designsystem

import dev.opencode.hexcode.designsystem.components.HexMeterMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * app-plan.md, Settings and finish (M6) › Testing › Reduce Motion: no element moves or loops except spinners, the stop
 * timer and the meter's levels, and every pulse rests at its visible state.
 */
class ReducedMotionTest {
    @Test
    fun removeAnimationsIsAScaleOfZero() {
        assertTrue(ReducedMotion.fromScale(0f))
        assertFalse(ReducedMotion.fromScale(1f))
        assertFalse(ReducedMotion.fromScale(0.5f))
    }

    @Test
    fun aPulseRestsAtItsVisibleEndNotWhereComposeWouldFreezeIt() {
        assertEquals(1f, ReducedMotion.pulse(reduced = true, animated = 0.35f))
        assertEquals(0.35f, ReducedMotion.pulse(reduced = false, animated = 0.35f))
    }

    @Test
    fun pressFeedbackIsColourOnly() {
        assertEquals(1f, ReducedMotion.pressScale(reduced = true, pressed = 0.94f))
        assertEquals(0.94f, ReducedMotion.pressScale(reduced = false, pressed = 0.94f))
    }

    @Test
    fun theMeterFollowsTheVoiceAtOnceAndItsListeningWaveStandsStill() {
        assertEquals(0.8f, HexMeterMotion.follow(0.1f, 0.8f, dtMs = 16, reduced = true))
        val heights = (0 until 12).flatMap { bar -> listOf(0L, 250L, 900L).map { HexMeterMotion.listening(bar, it, reduced = true) } }
        assertTrue(heights.all { it == HexMeterMotion.LISTENING_BASE })
        // Without it the wave travels.
        assertTrue(HexMeterMotion.listening(0, 0) != HexMeterMotion.listening(0, 250))
    }

    @Test
    fun spinnersAndTheStopRingRunAtFullSpeed() {
        assertEquals(1f, FullSpeed.scaleFactor)
    }
}
