package dev.opencode.hexcode.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * app-plan.md, Dictation › Meter and Press: bars rise in about 40 ms and fall in about 220 ms; until audio comes they
 * run a listening wave, and real levels blend in over about 80 ms.
 */
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

    @Test
    fun beforeAudioTheBarsShowTheListeningWave() {
        assertEquals(0.3f, HexMeterMotion.blend(0.3f, 0.9f, sinceAudioMs = null), 0f)
        assertEquals(0.3f, HexMeterMotion.blend(0.3f, 0.9f, sinceAudioMs = 0), 0f)
    }

    @Test
    fun realLevelsBlendInOver80ms() {
        assertEquals(0.6f, HexMeterMotion.blend(0.3f, 0.9f, sinceAudioMs = 40), 1e-6f)
        assertEquals(0.9f, HexMeterMotion.blend(0.3f, 0.9f, sinceAudioMs = 80), 1e-6f)
        assertEquals(0.9f, HexMeterMotion.blend(0.3f, 0.9f, sinceAudioMs = 500), 1e-6f)
        // Silence after the blend rests as dots, as before.
        assertEquals(0f, HexMeterMotion.blend(0.3f, 0f, sinceAudioMs = 120), 0f)
    }

    @Test
    fun theListeningWaveIsAboutAThirdHigh() {
        for (bar in 0 until 40) {
            for (t in 0L until 2_000L step 37) {
                assertTrue(HexMeterMotion.listening(bar, t) in 0.21f..0.43f)
            }
        }
        // It moves: the same bar a quarter period later is somewhere else.
        assertTrue(kotlin.math.abs(HexMeterMotion.listening(0, 0) - HexMeterMotion.listening(0, 227)) > 0.09f)
    }

    @Test
    fun theListeningWaveTravelsLeftToRight() {
        // The crest a bar shows now, the next bar to its right shows a little later.
        val now = HexMeterMotion.listening(3, 100)
        val later = (101L..400L).first { kotlin.math.abs(HexMeterMotion.listening(4, it) - now) < 0.002f }
        assertTrue(later in 140L..180L)
    }
}
