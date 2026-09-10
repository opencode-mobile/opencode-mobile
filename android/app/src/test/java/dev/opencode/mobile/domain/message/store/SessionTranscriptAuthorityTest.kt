package dev.opencode.mobile.domain.message

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTranscriptAuthorityTest {
    @Test
    fun lastAssistantNeedsAuthority_hiddenReasoningAfterUser() {
        val messages =
            listOf(
                Message(id = "u1", role = MessageRole.User, text = "Hi"),
                Message(
                    id = "a1",
                    role = MessageRole.Assistant,
                    text = "",
                    parts = listOf(MessagePart.Reasoning(text = "", isStreaming = false)),
                ),
            )
        assertTrue(SessionTranscriptAuthority.lastAssistantNeedsAuthority(messages))
    }

    @Test
    fun lastAssistantNeedsAuthority_visibleTextDoesNot() {
        val messages =
            listOf(
                Message(id = "u1", role = MessageRole.User, text = "Hi"),
                Message(
                    id = "a1",
                    role = MessageRole.Assistant,
                    text = "Done",
                    parts = listOf(MessagePart.Text(text = "Done")),
                ),
            )
        assertFalse(SessionTranscriptAuthority.lastAssistantNeedsAuthority(messages))
    }

    @Test
    fun lastAssistantNeedsAuthority_earlierToolsDoNotHideLastEmptyAssistant() {
        val messages =
            listOf(
                Message(id = "u1", role = MessageRole.User, text = "Hi"),
                Message(
                    id = "a1",
                    role = MessageRole.Assistant,
                    text = "",
                    parts =
                        listOf(
                            MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, callId = "c1"),
                        ),
                ),
                Message(
                    id = "a2",
                    role = MessageRole.Assistant,
                    text = "",
                    parts = listOf(MessagePart.Reasoning(text = "")),
                ),
            )
        assertTrue(SessionTranscriptAuthority.lastAssistantNeedsAuthority(messages))
    }

    @Test
    fun lastAssistantNeedsAuthority_emptySessionDoesNot() {
        assertFalse(SessionTranscriptAuthority.lastAssistantNeedsAuthority(emptyList()))
        assertFalse(
            SessionTranscriptAuthority.lastAssistantNeedsAuthority(
                listOf(Message(id = "u1", role = MessageRole.User, text = "Hi")),
            ),
        )
    }
}
