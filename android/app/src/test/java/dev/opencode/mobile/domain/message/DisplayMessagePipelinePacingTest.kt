package dev.opencode.mobile.domain.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayMessagePipelinePacingTest {
    @Test
    fun streamingMaxBatchUsesConfiguredDuration() {
        assertEquals(80L, STREAMING_PREPARE_DEBOUNCE_MS)
        assertEquals(160L, STREAMING_PREPARE_MAX_BATCH_MS)
    }

    @Test
    fun largeHistoryTriggersPlainFirstPublishGate() {
        val text = "# Heading\n\n".repeat(500)
        assertTrue(text.length >= MessageContentPreparer.HISTORY_PLAIN_FIRST_MARKDOWN_CHARACTER_THRESHOLD)
        val message =
            Message(
                id = "msg-1",
                role = MessageRole.Assistant,
                text = text,
                parts = listOf(MessagePart.Text(text = text, isStreaming = false)),
            )
        val messages = listOf(message)
        assertTrue(shouldPlainFirstHistoryPublish(messages, emptyMap()))
        // Previous plain frames (no markdown docs) still allow plain-first.
        assertTrue(
            shouldPlainFirstHistoryPublish(
                messages,
                mapOf("msg-1" to DisplayMessage(message = message, renderedMarkdown = null)),
            ),
        )
        assertFalse(shouldPlainFirstHistoryPublish(listOf(message.copy(text = "hi", parts = listOf(MessagePart.Text("hi")))), emptyMap()))
    }
}
