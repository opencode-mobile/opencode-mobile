package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.components.OpenCodeFloatingPillLayout
import dev.opencode.mobile.ui.components.OpenCodeFloatingPillLeading
import dev.opencode.mobile.ui.components.OpenCodeFloatingPillState
import dev.opencode.mobile.ui.components.OpenCodeSessionBranchChangedFile
import dev.opencode.mobile.ui.components.OpenCodeSessionBranchSummary
import dev.opencode.mobile.ui.components.floatingPillLeading
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.sessionchat.view.SessionChatActivityPillCopy
import dev.opencode.mobile.ui.sessionchat.view.SessionChatCornerSlotsLayout
import dev.opencode.mobile.ui.sessionchat.view.SessionChatNavigationCopy
import dev.opencode.mobile.ui.text.EnglishStrings
import dev.opencode.mobile.ui.text.english
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionChatPillSlotsTest {
    // One spinner in the folded row

    @Test
    fun foldedWorkingPillMergesOnlyIntoARunningActivityPill() {
        val working =
            SessionChatTurnStatusSlot(
                phase = dev.opencode.mobile.ui.components.OpenCodeTurnStatusPillPhase.Working("Working"),
                compact = true,
                leaving = false,
            )
        val done = working.copy(phase = dev.opencode.mobile.ui.components.OpenCodeTurnStatusPillPhase.Done)
        val running = SessionChatActivityItem.SideQuestionsRunning(1)

        assertTrue(SessionChatPillSlots.turnMergesIntoActivity(working, turnCompact = true, primary = running))
        assertFalse(SessionChatPillSlots.turnMergesIntoActivity(working, turnCompact = false, primary = running))
        assertFalse(SessionChatPillSlots.turnMergesIntoActivity(done, turnCompact = true, primary = running))
        assertFalse(SessionChatPillSlots.turnMergesIntoActivity(working, turnCompact = true, primary = SessionChatActivityItem.Queued(1)))
        assertFalse(SessionChatPillSlots.turnMergesIntoActivity(working, turnCompact = true, primary = null))
    }

    @Test
    fun mergingTurnPillLeavesItsRoomToTheActivityLabel() {
        val widths =
            SessionChatPillRowWidths(
                available = 200,
                turn = 100,
                turnCompact = 36,
                secondary = 170,
                secondaryCompact = 36,
                overflow = 0,
                trailing = 0,
                spacing = 8,
            )

        assertEquals(
            SessionChatPillRowFold(turnCompact = true),
            SessionChatPillSlots.pillRowFold(widths, turnCanFold = true, turnMergesWhenCompact = true),
        )
        assertEquals(
            SessionChatPillRowFold(turnCompact = true, secondaryCompact = true),
            SessionChatPillSlots.pillRowFold(widths, turnCanFold = true),
        )
    }

    @Test
    fun trailingWidthCountsTheSpacerGapAndNavigationGap() {
        assertEquals(0, SessionChatCornerSlotsLayout.trailingRowWidth(0, hasNavigation = false, spacingPx = 8))
        assertEquals(108, SessionChatCornerSlotsLayout.trailingRowWidth(100, hasNavigation = false, spacingPx = 8))
        assertEquals(116, SessionChatCornerSlotsLayout.trailingRowWidth(100, hasNavigation = true, spacingPx = 8))
    }

    @Test
    fun mergedPillStillSpeaksTheTurnStatus() {
        val label = SessionChatActivityPillCopy.accessibilityLabel(SessionChatActivityItem.SideQuestionsRunning(1))

        assertEquals("Working. ${label.english()}", SessionChatActivityPillCopy.withMergedTurn("Working", label).english())
        assertEquals(label.english(), SessionChatActivityPillCopy.withMergedTurn(null, label).english())
    }

    // Activity priority and overflow

    @Test
    fun priorityOrderPicksTheMostUrgentAndStacksTheRest() {
        val slot =
            SessionChatPillSlots.activity(
                items =
                    listOf(
                        SessionChatActivityItem.Queued(3),
                        SessionChatActivityItem.SideQuestionsRunning(1),
                        SessionChatActivityItem.SideQuestionsReady(1),
                        SessionChatActivityItem.RequestFailed("m1", "m1:failure"),
                        SessionChatActivityItem.ReviewPermission,
                    ),
                keyboardVisible = false,
            )

        // Requests and failures belong to the turn pill, so they are neither shown nor counted.
        assertEquals(SessionChatActivityItem.SideQuestionsReady(1), slot.primary)
        assertEquals(listOf(SessionChatActivityItem.SideQuestionsRunning(1), SessionChatActivityItem.Queued(3)), slot.overflow)
        assertTrue(slot.showsOverflowButton)
        assertFalse(slot.primaryCompact)
    }

    @Test
    fun mergedItemsLeaveNoSecondaryPill() {
        val slot =
            SessionChatPillSlots.activity(
                listOf(
                    SessionChatActivityItem.ReviewPermission,
                    SessionChatActivityItem.AnswerQuestion,
                    SessionChatActivityItem.RequestFailed("m1", "m1:failure"),
                ),
                keyboardVisible = false,
            )

        assertEquals(SessionChatActivitySlot(), slot)
    }

    @Test
    fun aWaitingSubagentLeavesOnlyItsRunningCount() {
        val rows =
            listOf(
                subagentRow("p1", SessionChatSubagentStatus.WaitingForInput),
                subagentRow("p2", SessionChatSubagentStatus.Running),
            )
        val pill = requireNotNull(SessionChatBackgroundWork.pill(rows))
        val slot = SessionChatPillSlots.activity(listOf(SessionChatActivityItem.Subagents(pill)), keyboardVisible = false)

        assertEquals("1 subagent", (slot.primary as SessionChatActivityItem.Subagents).pill.labelText.english())
        assertFalse((slot.primary as SessionChatActivityItem.Subagents).pill.needsInput)

        val alone = requireNotNull(SessionChatBackgroundWork.pill(rows.take(1)))
        assertEquals(
            SessionChatActivitySlot(),
            SessionChatPillSlots.activity(listOf(SessionChatActivityItem.Subagents(alone)), keyboardVisible = false),
        )
    }

    private fun subagentRow(
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

    @Test
    fun priorityRanksMatchTheSpec() {
        assertEquals(
            listOf("NeedsInput", "Error", "AnswerReady", "Running", "Queued"),
            SessionChatActivityPriority.entries.map { it.name },
        )
        assertEquals(SessionChatActivityPriority.NeedsInput, SessionChatActivityItem.AnswerQuestion.priority)
        assertEquals(SessionChatActivityPriority.Error, SessionChatActivityItem.SideQuestionsFailed(1).priority)
        assertEquals(SessionChatActivityPriority.Running, SessionChatActivityItem.SideQuestionsRunning(1).priority)
    }

    @Test
    fun equalPrioritiesKeepTheCallersOrder() {
        val slot =
            SessionChatPillSlots.activity(
                listOf(SessionChatActivityItem.SideQuestionsRunning(2), SessionChatActivityItem.Subagents(verbatimPill("1 subagent", false, "1 subagent running"))),
                keyboardVisible = false,
            )

        assertEquals(SessionChatActivityItem.SideQuestionsRunning(2), slot.primary)
        assertEquals(listOf(SessionChatActivityItem.Subagents(verbatimPill("1 subagent", false, "1 subagent running"))), slot.overflow)
    }

    @Test
    fun singleItemHasNoOverflowButton() {
        val slot = SessionChatPillSlots.activity(listOf(SessionChatActivityItem.Queued(1)), keyboardVisible = false)

        assertEquals(SessionChatActivityItem.Queued(1), slot.primary)
        assertTrue(slot.overflow.isEmpty())
        assertFalse(slot.showsOverflowButton)
    }

    @Test
    fun noItemsIsEmpty() {
        assertEquals(SessionChatActivitySlot(), SessionChatPillSlots.activity(emptyList(), keyboardVisible = true))
    }

    // Keyboard

    @Test
    fun keyboardCompactsThePillAndHidesOverflow() {
        val slot =
            SessionChatPillSlots.activity(
                listOf(SessionChatActivityItem.Queued(2), SessionChatActivityItem.SideQuestionsReady(1)),
                keyboardVisible = true,
            )

        assertEquals(SessionChatActivityItem.SideQuestionsReady(1), slot.primary)
        assertTrue(slot.primaryCompact)
        assertTrue(slot.overflow.isEmpty())
        assertFalse(slot.showsOverflowButton)
    }

    @Test
    fun keyboardHidesContextAndNavigation() {
        val branch = SessionBranchPillUiState(
                OpenCodeSessionBranchSummary("main", files = listOf(OpenCodeSessionBranchChangedFile("a.kt", 1, 2))),
            )
        assertFalse(SessionChatPillSlots.showsContext(keyboardVisible = true))
        assertTrue(SessionChatPillSlots.showsContext(keyboardVisible = false))
        // The items stay, so the top row keeps its measured inset while hidden.
        assertEquals(2, SessionChatPillSlots.context(1, 3, branch).size)
        assertNull(SessionChatPillSlots.navigation(awayFromEnd = true, newCount = 2, keyboardVisible = true))
    }

    // Context

    @Test
    fun contextShowsBranchInTheCornerThenTodosWithoutAGoal() {
        val branch = SessionBranchPillUiState(
                OpenCodeSessionBranchSummary("main", files = listOf(OpenCodeSessionBranchChangedFile("a.kt", 1, 2))),
            )

        assertEquals(
            listOf(SessionChatContextItem.Branch(branch), SessionChatContextItem.Todos(2, 5)),
            SessionChatPillSlots.context(2, 5, branch),
        )
        assertEquals(
            listOf(SessionChatContextItem.Branch(branch)),
            SessionChatPillSlots.context(0, 0, branch),
        )
        assertTrue(SessionChatPillSlots.context(null, null, null).isEmpty())
        assertEquals(3, SessionChatPillSlots.MAX_CONTEXT_PILLS)
    }

    // Navigation

    @Test
    fun navigationIsEmptyAtTheEnd() {
        assertNull(SessionChatPillSlots.navigation(awayFromEnd = false, newCount = 3, keyboardVisible = false))
        assertEquals(
            SessionChatNavigationPill(0),
            SessionChatPillSlots.navigation(awayFromEnd = true, newCount = 0, keyboardVisible = false),
        )
        assertEquals(
            SessionChatNavigationPill(3),
            SessionChatPillSlots.navigation(awayFromEnd = true, newCount = 3, keyboardVisible = false),
        )
    }

    @Test
    fun awayFromTheEndFoldsTheSecondaryPillsIntoOneRoundPill() {
        val items =
            listOf(SessionChatActivityItem.Queued(2), SessionChatActivityItem.ReviewPermission, SessionChatActivityItem.SideQuestionsRunning(1))
        val slot = SessionChatPillSlots.activity(items, keyboardVisible = false, awayFromEnd = true)

        assertTrue(slot.collapsed)
        assertTrue(slot.primaryCompact)
        assertFalse(slot.showsOverflowButton)
        assertEquals(SessionChatActivityItem.SideQuestionsRunning(1), slot.primary)
        assertEquals(listOf(SessionChatActivityItem.Queued(2)), slot.overflow)
        assertEquals(2, slot.itemCount)
    }

    @Test
    fun atTheEndOrWithTheKeyboardTheLeftSideDoesNotCollapse() {
        val items = listOf(SessionChatActivityItem.Queued(2), SessionChatActivityItem.ReviewPermission)
        assertFalse(SessionChatPillSlots.activity(items, keyboardVisible = false).collapsed)
        assertFalse(SessionChatPillSlots.activity(items, keyboardVisible = true, awayFromEnd = true).collapsed)
        assertTrue(SessionChatPillSlots.collapsesActivity(awayFromEnd = true, keyboardVisible = false))
        assertFalse(SessionChatPillSlots.collapsesActivity(awayFromEnd = false, keyboardVisible = false))
        assertFalse(SessionChatPillSlots.collapsesActivity(awayFromEnd = true, keyboardVisible = true))
    }

    @Test
    fun syncingIsACircleAwayFromTheEndOrWithTheKeyboard() {
        assertFalse(SessionChatPillSlots.syncingCompact(awayFromEnd = false, keyboardVisible = false))
        assertTrue(SessionChatPillSlots.syncingCompact(awayFromEnd = true, keyboardVisible = false))
        assertTrue(SessionChatPillSlots.syncingCompact(awayFromEnd = false, keyboardVisible = true))
    }

    @Test
    fun navigationAlwaysHasALabel() {
        assertEquals("Scroll to end", SessionChatNavigationCopy.label(SessionChatNavigationPill(0)).english())
        assertEquals("4 new", SessionChatNavigationCopy.label(SessionChatNavigationPill(4)).english())
        assertEquals("Scroll to end", SessionChatNavigationCopy.accessibilityLabel(SessionChatNavigationPill(0)).english())
        assertEquals("Scroll to end, 1 new message", SessionChatNavigationCopy.accessibilityLabel(SessionChatNavigationPill(1)).english())
        assertEquals("Scroll to end, 3 new messages", SessionChatNavigationCopy.accessibilityLabel(SessionChatNavigationPill(3)).english())
    }

    @Test
    fun reserveIsThePillHeightOnly() {
        assertEquals(36.dp, SessionChatCornerSlotsLayout.reservedHeight(hasActivity = true))
        assertEquals(0.dp, SessionChatCornerSlotsLayout.reservedHeight(hasActivity = false))
    }

    @Test
    fun newMessagesCountOnlyRowsAfterTheBaseline() {
        assertEquals(2, SessionChatPillSlots.newMessageCount(listOf("a", "b", "c", "d"), "b"))
        // Older history prepended above does not count.
        assertEquals(0, SessionChatPillSlots.newMessageCount(listOf("x", "a", "b"), "b"))
        assertEquals(0, SessionChatPillSlots.newMessageCount(listOf("a"), null))
        assertEquals(0, SessionChatPillSlots.newMessageCount(listOf("a"), "gone"))
    }

    // Reserved pill row

    @Test
    fun reservedRowFollowsThePillOnlyAtTheEnd() {
        assertTrue(SessionChatPillSlots.reservesActivityRow(reserved = false, hasActivity = true, pinnedToEnd = true))
        assertFalse(SessionChatPillSlots.reservesActivityRow(reserved = true, hasActivity = false, pinnedToEnd = true))
        // Scrolled away: a pill coming or going keeps the reserve, so visible rows do not move.
        assertFalse(SessionChatPillSlots.reservesActivityRow(reserved = false, hasActivity = true, pinnedToEnd = false))
        assertTrue(SessionChatPillSlots.reservesActivityRow(reserved = true, hasActivity = false, pinnedToEnd = false))
    }

    // Request failed

    @Test
    fun latestFailureIsTheLastTurnsFailure() {
        val rows = listOf(user("u1"), assistant("a1", failure("a1:failure")))

        assertEquals(
            SessionChatActivityItem.RequestFailed("a1", "a1:failure"),
            SessionChatPillSlots.latestFailure(rows, isRetrying = false),
        )
    }

    @Test
    fun noFailurePillAfterANewPromptWhileRetryingOrForNeutralMarks() {
        val failed = assistant("a1", failure("a1:failure"))

        assertNull(SessionChatPillSlots.latestFailure(listOf(failed, user("u2")), isRetrying = false))
        assertNull(SessionChatPillSlots.latestFailure(listOf(failed), isRetrying = true))
        assertNull(
            SessionChatPillSlots.latestFailure(listOf(assistant("a2", failure("a2:failure", neutral = true))), isRetrying = false),
        )
    }

    private fun user(id: String) = SessionChatMessageItem(id = id, role = MessageRole.User, text = "Hi", metadataLabel = "")

    private fun assistant(
        id: String,
        failure: SessionChatTimelineBlock,
    ) = SessionChatMessageItem(id = id, role = MessageRole.Assistant, text = "", metadataLabel = "", blocks = listOf(failure))

    private fun failure(
        blockId: String,
        neutral: Boolean = false,
    ) = SessionChatTimelineBlock.FailureLine(blockID = blockId, text = UiText.verbatim("HTTP 500 · Server error"), isNeutral = neutral)

    // Background work (subagents)

    private val runningSubagents =
        verbatimPill("3 subagents", needsInput = false, accessibilityLabel = "3 subagents running")
    private val waitingSubagent =
        verbatimPill(
            "general needs input · 2 running",
            needsInput = true,
            accessibilityLabel = "general needs input, 2 subagents running",
        )

    @Test
    fun runningSubagentsRankAsRunningWork() {
        val subagents = SessionChatActivityItem.Subagents(runningSubagents)
        val slot =
            SessionChatPillSlots.activity(
                items = listOf(SessionChatActivityItem.Queued(1), subagents, SessionChatActivityItem.SideQuestionsReady(1)),
                keyboardVisible = false,
            )

        assertEquals(SessionChatActivityPriority.Running, subagents.priority)
        assertEquals(SessionChatActivityItem.SideQuestionsReady(1), slot.primary)
        assertEquals(listOf(subagents, SessionChatActivityItem.Queued(1)), slot.overflow)
    }

    @Test
    fun subagentsPillUsesTheCounterWithThePulsingDotWhileWaiting() {
        val running = SessionChatActivityItem.Subagents(runningSubagents)
        val waiting = SessionChatActivityItem.Subagents(waitingSubagent)

        assertEquals(OpenCodeFloatingPillLayout.Counter("3 subagents", waiting = false), SessionChatActivityPillCopy.layout(running, EnglishStrings))
        assertEquals(OpenCodeFloatingPillState.Live, SessionChatActivityPillCopy.state(running))
        assertEquals(
            OpenCodeFloatingPillLayout.Counter("general needs input · 2 running", waiting = true),
            SessionChatActivityPillCopy.layout(waiting, EnglishStrings),
        )
        assertEquals(OpenCodeFloatingPillState.NeedsAttention, SessionChatActivityPillCopy.state(waiting))
        assertEquals(
            OpenCodeFloatingPillLeading.PulsingDot,
            floatingPillLeading(SessionChatActivityPillCopy.layout(waiting, EnglishStrings), SessionChatActivityPillCopy.state(waiting)),
        )
        assertTrue(SessionChatActivityPillCopy.isClickable(running))
        assertFalse(SessionChatActivityPillCopy.isClosable(running))
        assertEquals(
            "general needs input, 2 subagents running. Open request",
            SessionChatActivityPillCopy.accessibilityLabel(waiting).english(),
        )
    }

    @Test
    fun activityPillTextComesFromTheStringFiles() {
        val failedTwice = SessionChatActivityItem.SideQuestionsFailed(2)
        val queuedOne = SessionChatActivityItem.Queued(1)

        assertEquals(
            OpenCodeFloatingPillLayout.Closable("/btw failed ×2 · Retry"),
            SessionChatActivityPillCopy.layout(failedTwice, EnglishStrings),
        )
        assertEquals(
            OpenCodeFloatingPillLayout.Closable("/btw failed · Retry"),
            SessionChatActivityPillCopy.layout(SessionChatActivityItem.SideQuestionsFailed(1), EnglishStrings),
        )
        assertEquals("2 side questions failed. Retry", SessionChatActivityPillCopy.accessibilityLabel(failedTwice).english())
        assertEquals("1 queued prompt. Open Up next", SessionChatActivityPillCopy.accessibilityLabel(queuedOne).english())
        assertEquals(
            "Request failed. Hide 3 activities",
            SessionChatActivityPillCopy
                .collapsedAccessibilityLabel(SessionChatActivityItem.RequestFailed("m1", "m1:failure"), count = 3, stackOpen = true)
                .english(),
        )
        assertEquals(
            "Review permission request. Show 1 activity",
            SessionChatActivityPillCopy.collapsedAccessibilityLabel(SessionChatActivityItem.ReviewPermission, count = 1, stackOpen = false).english(),
        )
    }
}
