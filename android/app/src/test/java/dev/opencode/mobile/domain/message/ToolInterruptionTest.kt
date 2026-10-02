package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.dto.MessageErrorDataDto
import dev.opencode.mobile.data.remote.dto.MessageErrorInfoDto
import dev.opencode.mobile.data.remote.dto.MessageInfoDto
import dev.opencode.mobile.data.remote.dto.MessageToolStateDto
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolInterruptionTest {
    private val adapter = MessageDomainAdapter()

    @Test
    fun v1AbortAndRejectText_isInterruption() {
        assertTrue(ToolInterruption.isUserInterruption("Tool execution aborted"))
        assertTrue(ToolInterruption.isUserInterruption("The user rejected permission to use this specific tool call."))
        assertTrue(ToolInterruption.isUserInterruption("The user dismissed this question"))
        assertFalse(ToolInterruption.isUserInterruption("ENOENT: no such file"))
        assertFalse(ToolInterruption.isUserInterruption(null))
    }

    @Test
    fun v1ToolState_marksAbortedErrorsInterrupted() {
        val aborted =
            adapter.toToolPart(
                toolName = "bash",
                state = MessageToolStateDto(status = "error", error = "Tool execution aborted"),
            )
        assertTrue(aborted.isInterrupted)
        val failed = adapter.toToolPart(toolName = "bash", state = MessageToolStateDto(status = "error", error = "exit 1"))
        assertFalse(failed.isInterrupted)
    }

    @Test
    fun sessionAbortErrors() {
        assertTrue(ToolInterruption.isUserAbortError(type = "aborted", name = null))
        assertTrue(ToolInterruption.isUserAbortError(type = null, name = "MessageAbortedError"))
        assertFalse(ToolInterruption.isUserAbortError(type = "provider", name = "APIError"))
    }

    @Test
    fun interruptedCopy() {
        assertEquals(
            "Declined",
            SessionChatTimelineBlocks.interruptedToolText("The user declined this tool call"),
        )
        assertEquals("Stopped", SessionChatTimelineBlocks.interruptedToolText("Tool execution interrupted"))
    }

    @Test
    fun v1Info_stopReason() {
        val truncated = adapter.toMessage(MessageInfoDto(id = "a1", role = "assistant", finish = "length"), emptyList())
        assertEquals(MessageStopReason.Length, truncated.stopReason)
        val aborted =
            adapter.toMessage(
                MessageInfoDto(
                    id = "a2",
                    role = "assistant",
                    error = MessageErrorInfoDto(name = "MessageAbortedError", data = MessageErrorDataDto(message = "Aborted")),
                ),
                emptyList(),
            )
        assertEquals(MessageStopReason.Interrupted, aborted.stopReason)
        assertEquals("", aborted.text)
        val done = adapter.toMessage(MessageInfoDto(id = "a3", role = "assistant", finish = "stop"), emptyList())
        assertEquals(null, done.stopReason)
    }
}
