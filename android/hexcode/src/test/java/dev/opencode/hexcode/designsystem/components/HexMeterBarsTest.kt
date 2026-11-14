package dev.opencode.hexcode.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** app-plan.md, Dictation › Meter: each bar position has a fixed factor between 0.88 and 1.12. */
class HexMeterBarsTest {
    @Test
    fun eachPositionHasAFixedFactorBetween088And112() {
        val factors = (0 until 80).map(::barFactor)
        assertTrue(factors.all { it in 0.88f..1.12f })
        assertEquals(factors, (0 until 80).map(::barFactor))
        // They differ, so the row doesn't look flat.
        assertTrue(factors.distinct().size > 60)
    }
}
