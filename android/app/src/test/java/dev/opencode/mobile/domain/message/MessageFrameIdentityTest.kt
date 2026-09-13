package dev.opencode.mobile.domain.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFrameIdentityTest {
    @Test
    fun userMessageWithNilTimes_isSettled() {
        val message = Message(id = "u1", role = MessageRole.User, text = "hi")
        assertTrue(message.isSettled())
    }

    @Test
    fun assistantWithoutCompletedAt_isNotSettled() {
        val message =
            Message(
                id = "a1",
                role = MessageRole.Assistant,
                text = "done",
                parts = listOf(MessagePart.Text(text = "done")),
                sentAtEpochMs = 1_000L,
                completedAtEpochMs = null,
            )
        assertFalse(message.isSettled())
    }

    @Test
    fun toolPartRunning_isNotSettled() {
        val message =
            Message(
                id = "a1",
                role = MessageRole.Assistant,
                text = "",
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Running,
                        ),
                    ),
                sentAtEpochMs = 1_000L,
                completedAtEpochMs = 2_000L,
            )
        assertFalse(message.isSettled())
    }

    @Test
    fun completedAssistantWithoutInFlightParts_isSettled() {
        val message =
            Message(
                id = "a1",
                role = MessageRole.Assistant,
                text = "done",
                parts = listOf(MessagePart.Text(text = "done")),
                sentAtEpochMs = 1_000L,
                completedAtEpochMs = 2_000L,
            )
        assertTrue(message.isSettled())
        assertEquals(2_000L, message.tailTimestampMs())
    }

    @Test
    fun streamingTail_doesNotMatchIdentity() {
        val streaming =
            Message(
                id = "a1",
                role = MessageRole.Assistant,
                text = "partial",
                parts = listOf(MessagePart.Text(text = "partial", isStreaming = true)),
                sentAtEpochMs = 1_000L,
            )
        val local = listOf(user("m1"), streaming)
        assertFalse(local.matchesTailIdentity(listOf(user("m1"), streaming)))
        assertFalse(emptyList<Message>().matchesTailIdentity(listOf(user("m1"))))
        assertTrue(emptyList<Message>().matchesTailIdentity(emptyList()))
    }

    private fun user(id: String) = Message(id = id, role = MessageRole.User, text = id)
}
