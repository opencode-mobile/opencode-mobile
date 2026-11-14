package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.dto.MessageErrorDataDto
import dev.opencode.mobile.data.remote.dto.MessageErrorInfoDto
import dev.opencode.mobile.data.remote.dto.MessageInfoDto
import dev.opencode.mobile.data.remote.dto.MessageToolStateDto
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlocks
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.opencode.mobile.ui.text.english

private const val V1_REJECTED = "The user rejected permission to use this specific tool call"
private const val V1_NOTED = "$V1_REJECTED with the following feedback: Run the tests first"

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
    fun v2PermissionRejected_isAUserInterruption() {
        assertTrue(ToolInterruption.isUserInterruptionType("permission.rejected"))
        assertTrue(ToolInterruption.isUserInterruptionType("aborted"))
        assertTrue(ToolInterruption.isPermissionRejectedType("permission.rejected"))
        assertFalse(ToolInterruption.isPermissionRejectedType("aborted"))
        assertFalse(ToolInterruption.isUserInterruptionType("unknown"))
        assertFalse(ToolInterruption.isUserInterruptionType(null))
    }

    @Test
    fun userNote_v1FeedbackSuffix() {
        assertEquals(
            "Run the tests first",
            ToolInterruption.userNote(
                type = null,
                message = "$V1_REJECTED with the following feedback:  Run the tests first \n",
            ),
        )
        // A plain v1 deny, or blank feedback, has no note.
        assertEquals(null, ToolInterruption.userNote(null, "The user rejected permission to use this specific tool call."))
        assertEquals(
            null,
            ToolInterruption.userNote(null, "$V1_REJECTED with the following feedback:   "),
        )
        // Other errors never yield a note, even with the marker text.
        assertEquals(null, ToolInterruption.userNote(null, "exit 1 with the following feedback: nope"))
        assertEquals(null, ToolInterruption.userNote(null, null))
    }

    @Test
    fun userNote_v2PermissionRejectedMessage() {
        assertEquals("Use a branch instead", ToolInterruption.userNote("permission.rejected", " Use a branch instead "))
        assertEquals(null, ToolInterruption.userNote("permission.rejected", "  "))
        assertEquals(null, ToolInterruption.userNote("aborted", "Tool execution interrupted"))
        assertEquals(null, ToolInterruption.userNote("unknown", "lock timeout"))
    }

    @Test
    fun v1ToolState_deniedWithNote_isDeclinedWithTheNote() {
        val noted =
            adapter.toToolPart(
                toolName = "bash",
                state =
                    MessageToolStateDto(
                        status = "error",
                        error = V1_NOTED,
                    ),
            )
        assertTrue(noted.isInterrupted)
        assertTrue(noted.isDeclined)
        assertEquals("Run the tests first", noted.userNote)

        val plain =
            adapter.toToolPart(
                toolName = "bash",
                state = MessageToolStateDto(status = "error", error = "The user rejected permission to use this specific tool call."),
            )
        assertTrue(plain.isDeclined)
        assertEquals(null, plain.userNote)

        val aborted = adapter.toToolPart(toolName = "bash", state = MessageToolStateDto(status = "error", error = "Tool execution aborted"))
        assertFalse(aborted.isDeclined)
        assertEquals(null, aborted.userNote)
    }

    @Test
    fun v1DeniedWithNote_cardShowsDeclinedAndTheNote() {
        val tool =
            adapter
                .toToolPart(
                    toolName = "bash",
                    state =
                        MessageToolStateDto(
                            status = "error",
                            input = buildJsonObject { put("command", JsonPrimitive("git push")) },
                            error = V1_NOTED,
                        ),
                ).copy(id = "prt_1")
        val blocks =
            (
                SessionChatTimelineBlocks.build(
                    role = MessageRole.Assistant,
                    parts = listOf(tool),
                ) as dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlocksResult.Ok
            ).blocks
        val card = blocks.filterIsInstance<dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock.ToolCard>().single()
        assertEquals("Declined", card.errorText?.english())
        assertEquals("Your note: Run the tests first", card.noteText?.english())

        val plainCard =
            (
                SessionChatTimelineBlocks.build(
                    role = MessageRole.Assistant,
                    parts = listOf(tool.copy(userNote = null)),
                ) as dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlocksResult.Ok
            ).blocks.filterIsInstance<dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock.ToolCard>().single()
        assertEquals("Declined", plainCard.errorText?.english())
        assertEquals(null, plainCard.noteText)
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
            SessionChatTimelineBlocks.interruptedToolText("The user declined this tool call").english(),
        )
        assertEquals("Stopped", SessionChatTimelineBlocks.interruptedToolText("Tool execution interrupted").english())
        // v2 sends the note as the error text: the declined flag picks the copy.
        assertEquals("Declined", SessionChatTimelineBlocks.interruptedToolText("Run the tests first", declined = true).english())
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
