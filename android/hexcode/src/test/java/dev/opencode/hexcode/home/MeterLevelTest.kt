package dev.opencode.hexcode.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** app-plan.md, Dictation › Meter: −50 dBFS empty, −18 dBFS full, ease-out between. */
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
    fun belowMinus50TheBarsRestAsDots() {
        assertEquals(0f, MeterLevel.fromRms(0f))
        assertEquals(0f, MeterLevel.fromRms(rms(-60f)))
        assertEquals(0f, MeterLevel.fromRms(rms(-50f)), 0.0001f)
    }

    @Test
    fun fullAtMinus18AndAbove() {
        assertEquals(1f, MeterLevel.fromRms(rms(-18f)), 0.0001f)
        assertEquals(1f, MeterLevel.fromRms(rms(-6f)))
        assertEquals(1f, MeterLevel.fromRms(1f))
    }

    @Test
    fun normalSpeechFillsAboutHalfToNineTenths() {
        // Speech at arm's length is roughly −40 to −26 dBFS.
        val quiet = MeterLevel.fromRms(rms(-40f))
        val loud = MeterLevel.fromRms(rms(-26f))
        assertTrue("quiet speech at $quiet", quiet in 0.45f..0.6f)
        assertTrue("normal speech at $loud", loud in 0.85f..0.95f)
    }

    @Test
    fun theCurveEasesOutAndNeverFallsAsItGetsLouder() {
        val steps = (-60..0).map { MeterLevel.fromRms(rms(it.toFloat())) }
        steps.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        // Ease-out: the first half of the range lifts the bars more than the second.
        val mid = MeterLevel.fromRms(rms(-34f))
        assertTrue(mid > 0.7f)
    }
}
