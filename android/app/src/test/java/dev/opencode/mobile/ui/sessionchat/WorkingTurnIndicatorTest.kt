package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkingTurnIndicatorTest {
    @Test
    fun labelForActiveTurn_nullWhenIdle() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = null,
                messages = listOf(user("u1")),
            ),
        )
    }

    @Test
    fun labelForActiveTurn_showsWorkingAfterUserWithNoAssistant() {
        assertEquals(
            "Working",
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                messages = listOf(user("u1")),
            ),
        )
    }

    @Test
    fun labelForActiveTurn_hidesWhenAssistantHasText() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                messages = listOf(user("u1"), assistant("a1", "Hello")),
            ),
        )
    }

    @Test
    fun labelForActiveTurn_showsRetryingAfterUserWithNoAssistant() {
        assertEquals(
            "Retrying",
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Retrying",
                messages = listOf(user("u1")),
            ),
        )
    }

    @Test
    fun labelForActiveTurn_showsWhenAssistantHasBlankTextOnly() {
        assertEquals(
            "Working",
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                messages = listOf(user("u1"), assistant("a1", "   ")),
            ),
        )
    }

    @Test
    fun labelForActiveTurn_hidesWhenAssistantHasRunningToolCard() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                messages =
                    listOf(
                        user("u1"),
                        assistant(
                            id = "a1",
                            text = "",
                            blocks =
                                listOf(
                                    SessionChatTimelineBlock.ToolCard(
                                        blockID = "tool-1",
                                        toolName = "bash",
                                        status = ToolStatus.Running,
                                        title = "bash",
                                    ),
                                ),
                        ),
                    ),
            ),
        )
    }

    @Test
    fun labelForActiveTurn_showsWorkingAfterCompletedToolCard() {
        assertEquals(
            "Working",
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                messages =
                    listOf(
                        user("u1"),
                        assistant(
                            id = "a1",
                            text = "",
                            blocks =
                                listOf(
                                    SessionChatTimelineBlock.ToolCard(
                                        blockID = "tool-1",
                                        toolName = "bash",
                                        status = ToolStatus.Completed,
                                        title = "bash",
                                    ),
                                ),
                        ),
                    ),
            ),
        )
    }

    @Test
    fun labelForActiveTurn_showsCompactingEvenWithAssistantContent() {
        assertEquals(
            SessionCompactChrome.STATUS_LABEL,
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                messages = listOf(user("u1"), assistant("a1", "Hello")),
                isCompacting = true,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_showsCompactingWhenIdleStatus() {
        assertEquals(
            SessionCompactChrome.STATUS_LABEL,
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = null,
                messages = listOf(user("u1")),
                isCompacting = true,
            ),
        )
    }

    private fun user(id: String) =
        SessionChatMessageItem(
            id = id,
            role = MessageRole.User,
            text = "Hi",
            metadataLabel = "",
        )

    private fun assistant(
        id: String,
        text: String,
        blocks: List<SessionChatTimelineBlock> = emptyList(),
    ) = SessionChatMessageItem(
        id = id,
        role = MessageRole.Assistant,
        text = text,
        metadataLabel = "",
        blocks = blocks,
    )
}
