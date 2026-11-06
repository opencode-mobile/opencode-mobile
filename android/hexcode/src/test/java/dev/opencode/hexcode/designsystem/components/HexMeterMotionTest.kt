package dev.opencode.hexcode.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** app-plan.md, Dictation › Meter: bars rise in about 40 ms and fall in about 220 ms. */
class HexMeterMotionTest {
    private fun run(
        from: Float,
        to: Float,
        ms: Int,
    ): Float {
        var level = from
        repeat(ms / 16) { level = HexMeterMotion.follow(level, to, 16) }
        return level
    }

    @Test
    fun risesFast() {
        // Two thirds of the way in one attack time, nearly there after three.
        assertTrue(run(0f, 1f, 48) > 0.63f)
        assertTrue(run(0f, 1f, 128) > 0.94f)
    }

    @Test
    fun fallsSlowly() {
        assertTrue(run(1f, 0f, 48) > 0.75f)
        assertTrue(run(1f, 0f, 224) in 0.3f..0.4f)
    }

    @Test
    fun staysPutWhenAlreadyThere() {
        assertEquals(0.5f, HexMeterMotion.follow(0.5f, 0.5f, 16), 0f)
    }
}
