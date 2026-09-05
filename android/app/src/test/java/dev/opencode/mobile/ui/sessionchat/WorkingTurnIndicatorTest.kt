package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingTurnIndicatorTest {
    @Test
    fun labelForActiveTurn_nullWhenIdle() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(statusLabel = null),
        )
    }

    @Test
    fun labelForActiveTurn_showsWorkingWhenBusy() {
        assertEquals(
            "Working",
            WorkingTurnIndicator.labelForActiveTurn(statusLabel = "Working"),
        )
    }

    @Test
    fun labelForActiveTurn_showsSendingPercentWhileBodyIsOnTheWire() {
        assertEquals(
            "Sending 25%",
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                sendProgressPercent = 25,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_showsSendingEllipsisAtZero() {
        assertEquals(
            "Sending…",
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                sendProgressPercent = 0,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_compactingWinsOverSending() {
        assertEquals(
            SessionCompactChrome.STATUS_LABEL,
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                isCompacting = true,
                sendProgressPercent = 40,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_hidesSendingWhenAwaitingUser() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                isAwaitingUser = true,
                sendProgressPercent = 40,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_showsRetrying() {
        assertEquals(
            "Retrying",
            WorkingTurnIndicator.labelForActiveTurn(statusLabel = "Retrying"),
        )
    }

    @Test
    fun labelForActiveTurn_hidesWhenAwaitingUser() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                isAwaitingUser = true,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_hidesCompactingWhenAwaitingUser() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                isCompacting = true,
                isAwaitingUser = true,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_showsCompactingEvenWhenStatusIsWorking() {
        assertEquals(
            SessionCompactChrome.STATUS_LABEL,
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
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
                isCompacting = true,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_hidesWhenLastIsThinking() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                lastIsThinking = true,
            ),
        )
    }

    @Test
    fun labelForActiveTurn_hidesCompactingWhenLastIsThinking() {
        assertNull(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel = "Working",
                isCompacting = true,
                lastIsThinking = true,
            ),
        )
    }

    @Test
    fun lastIsThinking_trueWhenLastBlockIsStreamingReasoning() {
        assertTrue(
            WorkingTurnIndicator.lastIsThinking(
                listOf(message(SessionChatTimelineBlock.Reasoning("r1", "hmm", isStreaming = true))),
            ),
        )
    }

    @Test
    fun lastIsThinking_falseWhenLastIsSettledThought() {
        assertFalse(
            WorkingTurnIndicator.lastIsThinking(
                listOf(message(SessionChatTimelineBlock.Reasoning("r1", "hmm", isStreaming = false))),
            ),
        )
    }

    @Test
    fun lastIsThinking_falseWhenThinkingThenText() {
        assertFalse(
            WorkingTurnIndicator.lastIsThinking(
                listOf(
                    message(
                        SessionChatTimelineBlock.Reasoning("r1", "hmm", isStreaming = true),
                        SessionChatTimelineBlock.Text("t1", "Hello"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun lastIsThinking_falseWhenEmpty() {
        assertFalse(WorkingTurnIndicator.lastIsThinking(emptyList()))
    }

    private fun message(vararg blocks: SessionChatTimelineBlock): SessionChatMessageItem =
        SessionChatMessageItem(
            id = "m1",
            role = MessageRole.Assistant,
            text = "",
            metadataLabel = "",
            blocks = blocks.toList(),
        )
}
