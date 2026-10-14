package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationOrbMotionTest {
    @Test
    fun smoothing_risesFasterThanItFalls() {
        val frame = 1f / 60f
        val rise = smoothDictationLevel(current = 0f, target = 1f, dtSeconds = frame)
        val fall = 1f - smoothDictationLevel(current = 1f, target = 0f, dtSeconds = frame)
        assertTrue(rise > fall)
        assertEquals(0.4f, smoothDictationLevel(0.4f, 1f, dtSeconds = 0f))
    }

    @Test
    fun smoothing_movesGraduallyBetweenFrames() {
        var level = 0f
        val steps = mutableListOf<Float>()
        repeat(6) {
            level = smoothDictationLevel(level, 1f, 1f / 60f)
            steps += level
        }
        assertTrue(steps.zipWithNext().all { (a, b) -> b > a && b - a < 0.3f })
    }

    @Test
    fun barExtent_staysInRangeAndGrowsWithLevel() {
        for (index in 0 until 24) {
            for (step in 0 until 40) {
                val seconds = step * 0.05f
                val quiet = dictationBarExtent(index, 0f, seconds)
                val loud = dictationBarExtent(index, 1f, seconds)
                assertTrue(quiet in 0f..0.1f)
                assertTrue(loud in 0f..1f)
                assertTrue(loud >= quiet + 0.3f)
            }
        }
    }
}
