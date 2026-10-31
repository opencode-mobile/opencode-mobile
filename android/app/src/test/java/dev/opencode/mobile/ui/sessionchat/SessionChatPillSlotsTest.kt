package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals(SessionChatActivityPriority.Running, SessionChatActivityItem.Syncing("Syncing").priority)
    }

    @Test
    fun equalPrioritiesKeepTheCallersOrder() {
        val slot =
            SessionChatPillSlots.activity(
                listOf(SessionChatActivityItem.SideQuestionsRunning(2), SessionChatActivityItem.Syncing("Syncing")),
                keyboardVisible = false,
                nowEpochMs = now,
            )

        assertEquals(SessionChatActivityItem.SideQuestionsRunning(2), slot.primary)
        assertEquals(listOf(SessionChatActivityItem.Syncing("Syncing")), slot.overflow)
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
        val branch = SessionBranchPillUiState("main", 1, 2)
        assertTrue(SessionChatPillSlots.context(1, 3, branch, keyboardVisible = true).isEmpty())
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
    fun contextShowsTodosThenBranchAtMostTwo() {
        val branch = SessionBranchPillUiState("main", 1, 2)

        assertEquals(
            listOf(SessionChatContextItem.Todos(2, 5), SessionChatContextItem.Branch(branch)),
            SessionChatPillSlots.context(2, 5, branch, keyboardVisible = false),
        )
        assertEquals(
            listOf(SessionChatContextItem.Branch(branch)),
            SessionChatPillSlots.context(0, 0, branch, keyboardVisible = false),
        )
        assertTrue(SessionChatPillSlots.context(null, null, null, keyboardVisible = false).isEmpty())
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
    fun newMessagesCountOnlyRowsAfterTheBaseline() {
        assertEquals(2, SessionChatPillSlots.newMessageCount(listOf("a", "b", "c", "d"), "b"))
        // Older history prepended above does not count.
        assertEquals(0, SessionChatPillSlots.newMessageCount(listOf("x", "a", "b"), "b"))
        assertEquals(0, SessionChatPillSlots.newMessageCount(listOf("a"), null))
        assertEquals(0, SessionChatPillSlots.newMessageCount(listOf("a"), "gone"))
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
    ) = SessionChatTimelineBlock.FailureLine(blockID = blockId, text = "HTTP 500 · Server error", isNeutral = neutral)
}
