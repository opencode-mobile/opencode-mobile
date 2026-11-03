package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.MessageRole
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
import dev.opencode.mobile.ui.text.UiText

class SessionChatPillSlotsTest {
    private val now = 1_000_000L

    // Activity priority and overflow

    @Test
    fun priorityOrderPicksTheMostUrgentAndStacksTheRest() {
        val finished = SessionChatActivityItem.Finished("a", "Done", now)
        val failed = SessionChatActivityItem.RequestFailed("m1", "m1:failure")
        val slot =
            SessionChatPillSlots.activity(
                items =
                    listOf(
                        finished,
                        SessionChatActivityItem.Queued(3),
                        SessionChatActivityItem.SideQuestionsRunning(1),
                        SessionChatActivityItem.SideQuestionsReady(1),
                        failed,
                        SessionChatActivityItem.ReviewPermission,
                    ),
                keyboardVisible = false,
                nowEpochMs = now,
            )

        assertEquals(SessionChatActivityItem.ReviewPermission, slot.primary)
        assertEquals(
            listOf(
                failed,
                SessionChatActivityItem.SideQuestionsReady(1),
                SessionChatActivityItem.SideQuestionsRunning(1),
                SessionChatActivityItem.Queued(3),
                finished,
            ),
            slot.overflow,
        )
        assertTrue(slot.showsOverflowButton)
        assertFalse(slot.primaryCompact)
    }

    @Test
    fun priorityRanksMatchTheSpec() {
        assertEquals(
            listOf("NeedsInput", "Error", "AnswerReady", "Running", "Queued", "Finished"),
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
                nowEpochMs = now,
            )

        assertEquals(SessionChatActivityItem.SideQuestionsRunning(2), slot.primary)
        assertEquals(listOf(SessionChatActivityItem.Subagents(verbatimPill("1 subagent", false, "1 subagent running"))), slot.overflow)
    }

    @Test
    fun singleItemHasNoOverflowButton() {
        val slot = SessionChatPillSlots.activity(listOf(SessionChatActivityItem.Queued(1)), keyboardVisible = false, nowEpochMs = now)

        assertEquals(SessionChatActivityItem.Queued(1), slot.primary)
        assertTrue(slot.overflow.isEmpty())
        assertFalse(slot.showsOverflowButton)
    }

    @Test
    fun noItemsIsEmpty() {
        assertEquals(SessionChatActivitySlot(), SessionChatPillSlots.activity(emptyList(), keyboardVisible = true, nowEpochMs = now))
    }

    // Keyboard

    @Test
    fun keyboardCompactsThePillAndHidesOverflow() {
        val slot =
            SessionChatPillSlots.activity(
                listOf(SessionChatActivityItem.Queued(2), SessionChatActivityItem.SideQuestionsReady(1)),
                keyboardVisible = true,
                nowEpochMs = now,
            )

        assertEquals(SessionChatActivityItem.SideQuestionsReady(1), slot.primary)
        assertTrue(slot.primaryCompact)
        assertTrue(slot.overflow.isEmpty())
        assertFalse(slot.showsOverflowButton)
    }

    @Test
    fun needsInputKeepsItsLabelWithTheKeyboardUp() {
        val slot =
            SessionChatPillSlots.activity(
                listOf(SessionChatActivityItem.Queued(2), SessionChatActivityItem.AnswerQuestion),
                keyboardVisible = true,
                nowEpochMs = now,
            )

        assertEquals(SessionChatActivityItem.AnswerQuestion, slot.primary)
        assertFalse(slot.primaryCompact)
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

    // Finished fade

    @Test
    fun finishedShowsAloneUntilItFades() {
        val finished = SessionChatActivityItem.Finished("a", "Done", now)

        val fresh = SessionChatPillSlots.activity(listOf(finished), keyboardVisible = false, nowEpochMs = now + 2_999)
        val faded = SessionChatPillSlots.activity(listOf(finished), keyboardVisible = false, nowEpochMs = now + 3_000)

        assertEquals(finished, fresh.primary)
        assertEquals(SessionChatActivitySlot(), faded)
    }

    @Test
    fun finishedOnlyShowsInTheStackBesideOtherWork() {
        val finished = SessionChatActivityItem.Finished("a", "Done", now)
        val slot =
            SessionChatPillSlots.activity(
                listOf(finished, SessionChatActivityItem.SideQuestionsRunning(1)),
                keyboardVisible = false,
                nowEpochMs = now + 1_000,
            )

        assertEquals(SessionChatActivityItem.SideQuestionsRunning(1), slot.primary)
        assertEquals(listOf(finished), slot.overflow)
    }

    @Test
    fun nextFadeIsTheEarliestUnfadedFinish() {
        val items =
            listOf(
                SessionChatActivityItem.Finished("old", "Done", now - 5_000),
                SessionChatActivityItem.Finished("a", "Done", now - 1_000),
                SessionChatActivityItem.Finished("b", "Done", now),
                SessionChatActivityItem.Queued(1),
            )

        assertEquals(now + 2_000, SessionChatPillSlots.nextFadeEpochMs(items, now))
        assertNull(SessionChatPillSlots.nextFadeEpochMs(listOf(SessionChatActivityItem.Queued(1)), now))
    }

    // Context

    @Test
    fun contextShowsBranchInTheCornerThenTodosAtMostTwo() {
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
        assertEquals(2, SessionChatPillSlots.MAX_CONTEXT_PILLS)
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
    fun awayFromTheEndFoldsEverythingIntoOneRoundPill() {
        val items =
            listOf(SessionChatActivityItem.Queued(2), SessionChatActivityItem.ReviewPermission, SessionChatActivityItem.SideQuestionsRunning(1))
        val slot = SessionChatPillSlots.activity(items, keyboardVisible = false, nowEpochMs = now, awayFromEnd = true)

        assertTrue(slot.collapsed)
        assertTrue(slot.primaryCompact)
        assertFalse(slot.showsOverflowButton)
        assertEquals(SessionChatActivityItem.ReviewPermission, slot.primary)
        assertEquals(listOf(SessionChatActivityItem.SideQuestionsRunning(1), SessionChatActivityItem.Queued(2)), slot.overflow)
        assertEquals(3, slot.itemCount)
    }

    @Test
    fun atTheEndOrWithTheKeyboardTheLeftSideDoesNotCollapse() {
        val items = listOf(SessionChatActivityItem.Queued(2), SessionChatActivityItem.ReviewPermission)
        assertFalse(SessionChatPillSlots.activity(items, keyboardVisible = false, nowEpochMs = now).collapsed)
        assertFalse(SessionChatPillSlots.activity(items, keyboardVisible = true, nowEpochMs = now, awayFromEnd = true).collapsed)
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
                nowEpochMs = now,
            )

        assertEquals(SessionChatActivityPriority.Running, subagents.priority)
        assertEquals(SessionChatActivityItem.SideQuestionsReady(1), slot.primary)
        assertEquals(listOf(subagents, SessionChatActivityItem.Queued(1)), slot.overflow)
    }

    @Test
    fun waitingSubagentNeedsInputAndStaysFullWithTheKeyboardUp() {
        val subagents = SessionChatActivityItem.Subagents(waitingSubagent)
        val slot =
            SessionChatPillSlots.activity(
                items = listOf(SessionChatActivityItem.SideQuestionsFailed(1), subagents),
                keyboardVisible = true,
                nowEpochMs = now,
            )

        assertEquals(subagents, slot.primary)
        assertFalse(slot.primaryCompact)
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
