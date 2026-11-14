package dev.opencode.hexcode.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** app-plan.md, Dictation › Meter, on Android: −68 dBFS empty, −38 dBFS full, ease-out between. */
class MeterLevelTest {
    /** RMS (full scale 1) for a level in dBFS. */
    private fun rms(db: Float) = 10f.pow(db / 20f)

    @Test
    fun dbfsOfFullScaleAndSilence() {
        assertEquals(0f, MeterLevel.dbfs(1f), 0.001f)
        assertEquals(-20f, MeterLevel.dbfs(0.1f), 0.001f)
        // Digital silence is a large negative number, not minus infinity.
        assertEquals(-120f, MeterLevel.dbfs(0f), 0.001f)
    }

    @Test
    fun belowMinus68TheBarsRestAsDots() {
        assertEquals(0f, MeterLevel.fromRms(0f))
        assertEquals(0f, MeterLevel.fromRms(rms(-75f)))
        assertEquals(0f, MeterLevel.fromRms(rms(-68f)), 0.0001f)
    }

    @Test
    fun fullAtMinus38AndAbove() {
        assertEquals(1f, MeterLevel.fromRms(rms(-38f)), 0.0001f)
        assertEquals(1f, MeterLevel.fromRms(rms(-20f)))
        assertEquals(1f, MeterLevel.fromRms(1f))
    }

    @Test
    fun normalSpeechOnThePixelFillsAboutHalfToNineTenths() {
        // The Pixel 9's speech-recognition input gave −56 to −46 dBFS for normal speech (p50 −52.6).
        val quiet = MeterLevel.fromRms(rms(-56f))
        val loud = MeterLevel.fromRms(rms(-46f))
        assertTrue("quiet speech at $quiet", quiet in 0.55f..0.7f)
        assertTrue("normal speech at $loud", loud in 0.88f..0.97f)
    }

    @Test
    fun theCurveEasesOutAndNeverFallsAsItGetsLouder() {
        val steps = (-80..0).map { MeterLevel.fromRms(rms(it.toFloat())) }
        steps.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        // Ease-out: half way up the range lifts the bars three quarters.
        assertEquals(0.75f, MeterLevel.fromRms(rms(-53f)), 0.001f)
    }
}
