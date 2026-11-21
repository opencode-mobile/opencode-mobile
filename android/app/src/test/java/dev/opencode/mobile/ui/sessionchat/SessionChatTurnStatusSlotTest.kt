package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.ui.components.OpenCodeTurnStatusPillPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionChatTurnStatusSlotTest {
    private val now = 1_000_000L

    private fun slot(
        label: String? = "Working",
        collapsed: Boolean = false,
        fading: Boolean = false,
        awaiting: Boolean = false,
        items: List<SessionChatActivityItem> = emptyList(),
        keyboard: Boolean = false,
        away: Boolean = false,
    ) = SessionChatPillSlots.turnStatus(label, collapsed, fading, awaiting, items, keyboard, away, now)

    @Test
    fun aloneIsAFullPill() {
        assertEquals(
            SessionChatTurnStatusSlot(OpenCodeTurnStatusPillPhase.Working("Working"), compact = false, leaving = false),
            slot(),
        )
        assertEquals(OpenCodeTurnStatusPillPhase.Done, slot(label = WorkingTurnIndicator.IDLE_LABEL)?.phase)
        assertEquals(OpenCodeTurnStatusPillPhase.Working("Compacting…"), slot(label = "Compacting…")?.phase)
    }

    @Test
    fun foldsBesideAnotherPillOrAwayFromTheEnd() {
        assertEquals(true, slot(items = listOf(SessionChatActivityItem.Queued(1)))?.compact)
        assertEquals(true, slot(away = true)?.compact)
        assertEquals(true, slot(keyboard = true)?.compact)
    }

    @Test
    fun aFadedFinishedItemDoesNotFoldIt() {
        val faded = SessionChatActivityItem.Finished("a", "a", now - 10_000)
        assertEquals(false, slot(items = listOf(faded))?.compact)
    }

    @Test
    fun hiddenForRequestsAndFailures() {
        assertNull(slot(awaiting = true))
        assertNull(slot(items = listOf(SessionChatActivityItem.ReviewPermission)))
        assertNull(slot(items = listOf(SessionChatActivityItem.AnswerQuestion)))
        assertNull(slot(items = listOf(SessionChatActivityItem.RequestFailed("m", "b"))))
        assertNull(slot(label = null))
        assertNull(slot(collapsed = true))
    }

    @Test
    fun neverCountsTowardOverflow() {
        val items = listOf(SessionChatActivityItem.Queued(1))
        assertNotNull(slot(items = items))
        val activity = SessionChatPillSlots.activity(items, keyboardVisible = false, nowEpochMs = now)
        assertTrue(activity.overflow.isEmpty())
        assertFalse(activity.showsOverflowButton)
    }

    @Test
    fun leavingReleasesTheRow() {
        assertEquals(true, slot()?.reservesRow)
        assertEquals(false, slot(fading = true)?.reservesRow)
    }

    private fun resolve(nowMs: Long) =
        WorkingTurnIndicator.resolveFooter(
            activeLabel = null,
            lastIsThinking = false,
            hideImmediately = false,
            previous = WorkingTurnIndicator.Chrome("Working", collapsed = false, fading = false),
            hold = WorkingTurnIndicator.Hold(startedAtMs = 0),
            nowMs = nowMs,
            holdMs = WorkingTurnIndicator.TURN_PILL_HOLD_MS,
            fadeMs = WorkingTurnIndicator.TURN_PILL_FADE_MS,
        )

    @Test
    fun doneHoldsThenFadesThenCollapses() {
        assertFalse(resolve(2_999).chrome.fading)
        assertEquals(1L, resolve(2_999).wakeAfterMs)
        assertTrue(resolve(3_000).chrome.fading)
        assertEquals(240L, resolve(3_000).wakeAfterMs)
        assertTrue(resolve(3_240).chrome.collapsed)
        assertNull(resolve(3_240).wakeAfterMs)
    }

    @Test
    fun otherStylesKeepTheFiveSecondIdle() {
        val resolution =
            WorkingTurnIndicator.resolveFooter(
                activeLabel = null,
                lastIsThinking = false,
                hideImmediately = false,
                previous = WorkingTurnIndicator.Chrome("Working", collapsed = false, fading = false),
                hold = WorkingTurnIndicator.Hold(startedAtMs = 0),
                nowMs = 4_999,
            )
        assertEquals(WorkingTurnIndicator.Chrome("Idle", collapsed = false, fading = false), resolution.chrome)
        assertEquals(1L, resolution.wakeAfterMs)
    }
}
