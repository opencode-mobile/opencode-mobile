package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.ui.sessionchat.region.SessionChatSideQuestionsUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenRequestPillTest {
    private val base = SessionChatUiState(sessionId = "s1", sessionTitle = "Title", timeline = SessionChatTimelineState.Ready)

    @Test
    fun collapsedQuestionInChildSession_keepsItsPillUnderTheLockout() {
        // A hidden question no longer claims the composer, so a child session falls back to its lockout.
        val kind = SessionChatComposerKind.resolve(isChildSession = true, hasPermission = false, hasQuestion = false)
        assertEquals(SessionChatComposerKind.ChildLockout, kind)
        assertEquals(
            listOf(SessionChatActivityItem.AnswerQuestion),
            sessionChatActivityItems(base.copy(questionDockHidden = true), kind),
        )
    }

    @Test
    fun collapsedPermission_showsReviewPermission() {
        assertEquals(
            listOf(SessionChatActivityItem.ReviewPermission),
            sessionChatActivityItems(base.copy(permissionDockHidden = true), SessionChatComposerKind.Prompt),
        )
    }

    @Test
    fun nothingHidden_showsNoPill() {
        assertEquals(emptyList<SessionChatActivityItem>(), sessionChatActivityItems(base, SessionChatComposerKind.Prompt))
    }

    @Test
    fun promptComposer_addsSideQuestionsAndQueued() {
        val state =
            base.copy(
                sideQuestions = SessionChatSideQuestionsUiState(pendingCount = 1, readyCount = 2, failedCount = 1),
                pendingPrompts =
                    listOf(
                        PendingPrompt(id = "p1", text = "one", delivery = FollowUpDelivery.Queue),
                        PendingPrompt(id = "p2", text = "two", delivery = FollowUpDelivery.Queue),
                    ),
            )

        assertEquals(
            listOf(
                SessionChatActivityItem.SideQuestionsFailed(1),
                SessionChatActivityItem.SideQuestionsReady(2),
                SessionChatActivityItem.SideQuestionsRunning(1),
                SessionChatActivityItem.Queued(2),
            ),
            sessionChatActivityItems(state, SessionChatComposerKind.Prompt),
        )
        // A permission panel replaces the composer: only the panel's own UI shows.
        assertEquals(emptyList<SessionChatActivityItem>(), sessionChatActivityItems(state, SessionChatComposerKind.Permission))
    }
}
