package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.text.UiTextLookup
import dev.opencode.mobile.ui.components.OpenCodeTurnStatusAttentionMark
import dev.opencode.mobile.ui.components.OpenCodeTurnStatusPillPhase
import dev.opencode.mobile.ui.text.EnglishStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionChatTurnStatusSlotTest {
    private val failed = SessionChatActivityItem.RequestFailed("m", "b")
    private val waitingSubagent =
        SessionChatActivityItem.Subagents(
            requireNotNull(
                SessionChatBackgroundWork.pill(
                    listOf(subagent("p1", SessionChatSubagentStatus.WaitingForInput), subagent("p2", SessionChatSubagentStatus.Running)),
                ),
            ),
        )

    private fun subagent(
        id: String,
        status: SessionChatSubagentStatus,
    ) = SessionChatSubagent(
        id = id,
        childSessionId = "child-$id",
        agentName = "general",
        title = null,
        status = status,
        startedAtMs = null,
        finishedAtMs = null,
    )

    private fun slot(
        label: String? = "Working",
        fading: Boolean = false,
        awaiting: Boolean = false,
        items: List<SessionChatActivityItem> = emptyList(),
        keyboard: Boolean = false,
        away: Boolean = false,
    ) = SessionChatPillSlots.turnStatus(label, fading, awaiting, items, keyboard, away, EnglishStrings)

    private fun attention(
        label: String,
        mark: OpenCodeTurnStatusAttentionMark,
    ) = OpenCodeTurnStatusPillPhase.Attention(label, mark)

    /** Compacting reaches the pill as an English sentinel; the pill shows the keyed text. */
    @Test
    fun compactingShowsTheKeyedStatus() {
        val marked =
            object : UiTextLookup by EnglishStrings {
                override fun string(
                    id: Int,
                    args: Array<out Any>,
                ) = "keyed:" + EnglishStrings.string(id, args)
            }

        assertEquals(
            OpenCodeTurnStatusPillPhase.Working("keyed:Compacting…"),
            SessionChatPillSlots.turnStatus(SessionCompactChrome.STATUS_LABEL, false, false, emptyList(), false, false, marked)?.phase,
        )
        assertEquals(OpenCodeTurnStatusPillPhase.Working("Compacting…"), slot(label = SessionCompactChrome.STATUS_LABEL)?.phase)
    }

    // Priority ladder

    @Test
    fun ladderRunsFromFailedDownToWorking() {
        val all = listOf(SessionChatActivityItem.AnswerQuestion, waitingSubagent, SessionChatActivityItem.ReviewPermission, failed)

        assertEquals(OpenCodeTurnStatusPillPhase.Failed("Request failed"), slot(items = all, awaiting = true)?.phase)
        assertEquals(
            attention("Review permission", OpenCodeTurnStatusAttentionMark.Reopen),
            slot(items = all - failed, awaiting = true)?.phase,
        )
        assertEquals(
            attention("Answer question", OpenCodeTurnStatusAttentionMark.Reopen),
            slot(items = all - failed - SessionChatActivityItem.ReviewPermission, awaiting = true)?.phase,
        )
        assertEquals(
            attention("Waiting for you", OpenCodeTurnStatusAttentionMark.PulsingDot),
            slot(items = listOf(waitingSubagent), awaiting = true)?.phase,
        )
        assertEquals(
            attention("general needs input", OpenCodeTurnStatusAttentionMark.PulsingDot),
            slot(items = listOf(waitingSubagent))?.phase,
        )
        assertEquals(OpenCodeTurnStatusPillPhase.Working("Working"), slot()?.phase)
        assertEquals(OpenCodeTurnStatusPillPhase.Working("Compacting…"), slot(label = "Compacting…")?.phase)
        assertEquals(OpenCodeTurnStatusPillPhase.Done, slot(label = WorkingTurnIndicator.IDLE_LABEL)?.phase)
        assertNull(slot(label = null))
    }

    @Test
    fun attentionShowsWithoutAnActiveLabel() {
        assertEquals(
            attention("Waiting for you", OpenCodeTurnStatusAttentionMark.PulsingDot),
            slot(label = null, awaiting = true)?.phase,
        )
        assertEquals(OpenCodeTurnStatusPillPhase.Failed("Request failed"), slot(label = null, items = listOf(failed))?.phase)
    }

    // Taps

    @Test
    fun onlyActionableStatesCarryAnItem() {
        assertEquals(failed, slot(items = listOf(failed))?.item)
        assertTrue(slot(items = listOf(failed))?.isClosable == true)
        assertEquals(SessionChatActivityItem.ReviewPermission, slot(items = listOf(SessionChatActivityItem.ReviewPermission))?.item)
        assertEquals(SessionChatActivityItem.AnswerQuestion, slot(items = listOf(SessionChatActivityItem.AnswerQuestion))?.item)
        assertNull(slot(awaiting = true)?.item)
        assertNull(slot()?.item)
        assertNull(slot(label = WorkingTurnIndicator.IDLE_LABEL)?.item)
        assertFalse(slot(items = listOf(SessionChatActivityItem.ReviewPermission))?.isClosable == true)
    }

    @Test
    fun aWaitingSubagentOpensItsRequestWithoutTheRunningSuffix() {
        val item = slot(items = listOf(waitingSubagent))?.item as SessionChatActivityItem.Subagents

        assertTrue(item.pill.needsInput)
        assertEquals(item.pill.labelText, item.pill.accessibilityText)
    }

    // Folding

    @Test
    fun workingAndDoneFoldAwayFromTheEndOrWithTheKeyboard() {
        listOf("Working", WorkingTurnIndicator.IDLE_LABEL).forEach { label ->
            assertEquals(false, slot(label = label)?.compact)
            assertEquals(true, slot(label = label, away = true)?.compact)
            assertEquals(true, slot(label = label, keyboard = true)?.compact)
        }
    }

    @Test
    fun aSecondaryPillDoesNotFoldTheTurnPill() {
        assertEquals(false, slot(items = listOf(SessionChatActivityItem.Queued(1)))?.compact)
    }

    @Test
    fun attentionAndFailedNeverFold() {
        listOf(
            slot(items = listOf(failed), away = true, keyboard = true),
            slot(items = listOf(SessionChatActivityItem.ReviewPermission), away = true, keyboard = true),
            slot(awaiting = true, away = true, keyboard = true),
            slot(items = listOf(waitingSubagent), away = true, keyboard = true),
        ).forEach {
            assertEquals(false, it?.compact)
            assertEquals(false, it?.canFold)
        }
    }

    private fun widths(
        available: Int,
        turn: Int = 100,
        secondary: Int = 0,
        overflow: Int = 0,
        trailing: Int = 0,
    ) = SessionChatPillRowWidths(
        available = available,
        turn = turn,
        turnCompact = 36,
        secondary = secondary,
        secondaryCompact = if (secondary > 0) 36 else 0,
        overflow = overflow,
        trailing = trailing,
        spacing = 8,
    )

    @Test
    fun aRowThatFitsFoldsNothing() {
        // 100 + 80 + 40 + 60 plus three gaps is exactly 304.
        val row = widths(available = 304, secondary = 80, overflow = 40, trailing = 60)

        assertEquals(SessionChatPillRowFold(), SessionChatPillSlots.pillRowFold(row, turnCanFold = true))
        assertEquals(SessionChatPillRowFold(), SessionChatPillSlots.pillRowFold(row, turnCanFold = false))
    }

    @Test
    fun anOverflowingRowFoldsAWorkingPill() {
        val row = widths(available = 303, secondary = 80, overflow = 40, trailing = 60)

        assertEquals(SessionChatPillRowFold(turnCompact = true), SessionChatPillSlots.pillRowFold(row, turnCanFold = true))
    }

    @Test
    fun anOverflowingRowFoldsTheSecondaryBesideAttention() {
        val row = widths(available = 303, secondary = 80, overflow = 40, trailing = 60)

        assertEquals(SessionChatPillRowFold(secondaryCompact = true), SessionChatPillSlots.pillRowFold(row, turnCanFold = false))
    }

    @Test
    fun aRowTooNarrowForAFoldedTurnFoldsBoth() {
        val row = widths(available = 200, secondary = 120, overflow = 40, trailing = 60)

        assertEquals(
            SessionChatPillRowFold(turnCompact = true, secondaryCompact = true),
            SessionChatPillSlots.pillRowFold(row, turnCanFold = true),
        )
    }

    @Test
    fun attentionWithNoSecondaryTruncatesInsteadOfFolding() {
        assertEquals(SessionChatPillRowFold(turnMaxWidth = 50), SessionChatPillSlots.pillRowFold(widths(available = 50), turnCanFold = false))
    }

    // Row reserve

    @Test
    fun theRowStaysReservedThroughEveryStateUntilTheFade() {
        listOf(
            slot(),
            slot(label = WorkingTurnIndicator.IDLE_LABEL),
            slot(awaiting = true),
            slot(items = listOf(SessionChatActivityItem.ReviewPermission)),
            slot(items = listOf(failed)),
            slot(items = listOf(waitingSubagent)),
        ).forEach { assertEquals(true, it?.reservesRow) }
        assertEquals(false, slot(label = WorkingTurnIndicator.IDLE_LABEL, fading = true)?.reservesRow)
    }

    @Test
    fun aRequestArrivingDuringTheFadeTakesTheRowBack() {
        val attentionSlot = slot(label = WorkingTurnIndicator.IDLE_LABEL, fading = true, awaiting = true)

        assertEquals(false, attentionSlot?.leaving)
        assertEquals(true, attentionSlot?.reservesRow)
    }

    @Test
    fun neverCountsTowardOverflow() {
        val items = listOf(SessionChatActivityItem.Queued(1))
        val activity = SessionChatPillSlots.activity(items, keyboardVisible = false)

        assertTrue(activity.overflow.isEmpty())
        assertFalse(activity.showsOverflowButton)
    }

    @Test
    fun aPinnedPillThatStillDoesNotFitTruncatesAfterTheSecondaryFolds() {
        val fold =
            SessionChatPillSlots.pillRowFold(
                SessionChatPillRowWidths(
                    available = 200,
                    turn = 260,
                    turnCompact = 36,
                    secondary = 120,
                    secondaryCompact = 36,
                    overflow = 0,
                    trailing = 0,
                    spacing = 8,
                ),
                turnCanFold = false,
            )
        assertEquals(SessionChatPillRowFold(secondaryCompact = true, turnMaxWidth = 156), fold)
    }
}
