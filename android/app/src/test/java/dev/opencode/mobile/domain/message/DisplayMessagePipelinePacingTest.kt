package dev.opencode.mobile.domain.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayMessagePipelinePacingTest {
    @Test
    fun streamingMaxBatchStaysFlatAsMessageGrows() {
        assertEquals(STREAMING_PREPARE_MAX_BATCH_MS, streamingPrepareMaxBatchMs(1_000))
        assertEquals(STREAMING_PREPARE_MAX_BATCH_MS, streamingPrepareMaxBatchMs(10_000))
        assertEquals(STREAMING_PREPARE_MAX_BATCH_MS, streamingPrepareMaxBatchMs(30_000))
        assertEquals(STREAMING_PREPARE_MAX_BATCH_MS, streamingPrepareMaxBatchMs(80_000))
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
