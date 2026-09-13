package dev.opencode.mobile.ui.sessionchat.view

import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSteerSheetDismissTest {
    @Test
    fun keepsSheetWhenTargetIsStillSteerPending() {
        assertFalse(
            shouldDismissPendingSteerSheet(
                targetId = "msg-1",
                timeline = SessionChatTimelineState.Ready,
                messages = listOf(message("msg-1", FollowUpDelivery.Steer)),
            ),
        )
    }

    @Test
    fun dismissesWhenAgentPicksUpPrompt() {
        assertTrue(
            shouldDismissPendingSteerSheet(
                targetId = "msg-1",
                timeline = SessionChatTimelineState.Ready,
                messages = listOf(message("msg-1", pendingDelivery = null)),
            ),
        )
    }

    @Test
    fun dismissesWhenRowLeavesTimeline() {
        assertTrue(
            shouldDismissPendingSteerSheet(
                targetId = "msg-1",
                timeline = SessionChatTimelineState.Ready,
                messages = listOf(message("other", FollowUpDelivery.Steer)),
            ),
        )
    }

    @Test
    fun dismissesWhenLoadedTimelineIsEmpty() {
        assertTrue(
            shouldDismissPendingSteerSheet(
                targetId = "msg-1",
                timeline = SessionChatTimelineState.Empty,
                messages = emptyList(),
            ),
        )
    }

    @Test
    fun doesNotDismissDuringInitialLoad() {
        assertFalse(
            shouldDismissPendingSteerSheet(
                targetId = "msg-1",
                timeline = SessionChatTimelineState.Loading,
                messages = emptyList(),
            ),
        )
    }

    @Test
    fun doesNotDismissWhenNoTarget() {
        assertFalse(
            shouldDismissPendingSteerSheet(
                targetId = null,
                timeline = SessionChatTimelineState.Ready,
                messages = emptyList(),
            ),
        )
    }

    private fun message(
        id: String,
        pendingDelivery: FollowUpDelivery?,
    ) = SessionChatMessageItem(
        id = id,
        role = MessageRole.User,
        text = "prompt",
        metadataLabel = "user",
        pendingDelivery = pendingDelivery,
    )
}
