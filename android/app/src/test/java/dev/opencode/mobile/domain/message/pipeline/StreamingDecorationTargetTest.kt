package dev.opencode.mobile.domain.message

import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.ui.sessionchat.PresentationBlockId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingDecorationTargetTest {
    @Test
    fun interleavedToolSplitsRunsAndTargetsLastStreamingText() {
        val message =
            Message(
                id = "msg-1",
                role = MessageRole.Assistant,
                text = "intro\n```js\nconst x = 1\n```",
                parts =
                    listOf(
                        MessagePart.Text(text = "intro", isStreaming = false, id = "prt-1"),
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            id = "prt-tool",
                        ),
                        MessagePart.Text(
                            text = "```js\nconst x = 1\n```",
                            isStreaming = true,
                            id = "prt-2",
                        ),
                    ),
            )

        val target = resolveStreamingDecorationTarget(message)
        assertNotNull(target)
        assertEquals(PresentationBlockId.mergedText(listOf("prt-2")), target!!.blockID)
        assertEquals("```js\nconst x = 1\n```", target.plain)
        assertTrue(target.isStreaming)
    }

    @Test
    fun invisibleTodoWriteDoesNotSplitMergedStreamingText() {
        val message =
            Message(
                id = "msg-1",
                role = MessageRole.Assistant,
                text = "one\ntwo",
                parts =
                    listOf(
                        MessagePart.Text(text = "one", isStreaming = false, id = "prt-1"),
                        MessagePart.Tool(
                            toolName = "todowrite",
                            status = ToolStatus.Completed,
                            id = "prt-todo",
                        ),
                        MessagePart.Text(text = "two", isStreaming = true, id = "prt-2"),
                    ),
            )

        val target = resolveStreamingDecorationTarget(message)
        assertNotNull(target)
        assertEquals(PresentationBlockId.mergedText(listOf("prt-1", "prt-2")), target!!.blockID)
        assertEquals("one\ntwo", target.plain)
    }

    @Test
    fun missingPartIdsFallBackToMessageScopedBlock() {
        val message =
            Message(
                id = "msg-1",
                role = MessageRole.Assistant,
                text = "hello",
                parts = listOf(MessagePart.Text(text = "hello", isStreaming = true, id = null)),
            )

        val target = resolveStreamingDecorationTarget(message)
        assertNotNull(target)
        assertEquals(PresentationBlockId.messageFallback("msg-1"), target!!.blockID)
        assertEquals("hello", target.plain)
    }

    @Test
    fun liveDocumentsAreDroppedOnceTheirMessageStopsStreamingOrLeaves() {
        fun message(
            id: String,
            streaming: Boolean,
        ) = Message(
            id = id,
            role = MessageRole.Assistant,
            text = "# $id",
            parts = listOf(MessagePart.Text(text = "# $id", isStreaming = streaming, id = "prt-$id")),
        )
        val document = MarkdownDocument(sourceHash = 1, blocks = emptyList())
        val docs = mutableMapOf("live" to document, "done" to document, "gone" to document)

        docs.retainStillStreaming(listOf(message("live", streaming = true), message("done", streaming = false)))
        assertEquals(setOf("live"), docs.keys)

        docs.retainStillStreaming(listOf(message("live", streaming = false)))
        assertTrue(docs.isEmpty())
    }
}
