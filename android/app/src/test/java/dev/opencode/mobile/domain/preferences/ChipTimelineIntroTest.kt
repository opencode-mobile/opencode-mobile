package dev.opencode.mobile.domain.preferences

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
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
    fun freshInstall_startsOnChipTimeline() {
        assertTrue(ChipTimelineIntro.startingChipTimeline(existingInstall = false))
    }

    @Test
    fun existingInstall_keepsStandardTimelineForTheIntro() {
        assertFalse(ChipTimelineIntro.startingChipTimeline(existingInstall = true))
        assertTrue(shouldShow(on = ChipTimelineIntro.startingChipTimeline(existingInstall = true)))
    }

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

    private fun tools(count: Int) = (1..count).map { MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "t$it") }

    @Test
    fun lastTurnToolCalls_countsAssistantsAfterLastUser() {
        val messages =
            listOf(
                Message(id = "u1", role = MessageRole.User, text = "first"),
                Message(id = "a1", role = MessageRole.Assistant, text = "", parts = tools(2)),
                Message(id = "u2", role = MessageRole.User, text = "second"),
                Message(id = "a2", role = MessageRole.Assistant, text = "", parts = tools(3)),
                Message(id = "a3", role = MessageRole.Assistant, text = "", parts = tools(5)),
            )

        assertEquals(8, ChipTimelineIntro.lastTurnToolCalls(messages))
    }

    @Test
    fun lastTurnToolCalls_queuedFollowUp_stillCountsFinishedTurn() {
        val messages =
            listOf(
                Message(id = "u1", role = MessageRole.User, text = "fix it"),
                Message(id = "a1", role = MessageRole.Assistant, text = "", parts = tools(9)),
                Message(id = "u2", role = MessageRole.User, text = "queued follow-up"),
            )

        assertEquals(9, ChipTimelineIntro.lastTurnToolCalls(messages))
    }

    @Test
    fun lastTurnToolCalls_noAssistant_isZero() {
        assertEquals(0, ChipTimelineIntro.lastTurnToolCalls(listOf(Message(id = "u1", role = MessageRole.User, text = "hi"))))
    }
}
