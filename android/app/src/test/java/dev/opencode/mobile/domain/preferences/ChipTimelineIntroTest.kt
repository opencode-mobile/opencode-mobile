package dev.opencode.mobile.domain.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChipTimelineIntroTest {
    private val now = 1_000_000L

    private fun shouldShow(
        available: Boolean = true,
        on: Boolean = false,
        done: Boolean = false,
        snoozedUntil: Long? = null,
        toolCalls: Int = ChipTimelineIntro.MIN_TOOL_CALLS,
    ) = ChipTimelineIntro.shouldShow(available, on, done, snoozedUntil, now, toolCalls)

    @Test
    fun longTurn_withChipTimelineOff_shows() {
        assertTrue(shouldShow())
    }

    @Test
    fun shortTurn_doesNotShow() {
        assertFalse(shouldShow(toolCalls = ChipTimelineIntro.MIN_TOOL_CALLS - 1))
    }

    @Test
    fun flagOff_doesNotShow() {
        assertFalse(shouldShow(available = false))
    }

    @Test
    fun chipTimelineAlreadyOn_doesNotShow() {
        assertFalse(shouldShow(on = true))
    }

    @Test
    fun introDone_doesNotShow() {
        assertFalse(shouldShow(done = true))
    }

    @Test
    fun snoozed_showsOnlyAfterSnoozeEnds() {
        assertFalse(shouldShow(snoozedUntil = now + 1))
        assertTrue(shouldShow(snoozedUntil = now))
    }

    @Test
    fun firstLater_snoozesThreeDays() {
        assertEquals(now + ChipTimelineIntro.SNOOZE_MILLIS, ChipTimelineIntro.laterSnoozeUntil(null, now))
    }

    @Test
    fun secondLater_endsIntro() {
        assertNull(ChipTimelineIntro.laterSnoozeUntil(now - 1, now))
    }
}
