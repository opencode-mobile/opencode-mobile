package dev.opencode.mobile.domain.message

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
}
