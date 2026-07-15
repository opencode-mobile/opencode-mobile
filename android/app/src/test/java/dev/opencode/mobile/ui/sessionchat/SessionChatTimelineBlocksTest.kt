package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionChatTimelineBlocksTest {
    @Test
    fun build_mergesAdjacentTextParts() {
        val blocks =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Text("Hello"),
                        MessagePart.Text("World"),
                    ),
            )

        assertEquals(
            listOf(SessionChatTimelineBlock.Text("Hello\nWorld")),
            blocks,
        )
    }

    @Test
    fun build_interleavesTextAndGenericToolCards() {
        val blocks =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Text("Before"),
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed),
                        MessagePart.Text("After"),
                    ),
            )

        assertEquals(3, blocks.size)
        assertEquals(SessionChatTimelineBlock.Text("Before"), blocks[0])
        val tool = blocks[1] as SessionChatTimelineBlock.ToolCard
        assertEquals("Called bash", tool.title)
        assertEquals(ToolStatus.Completed, tool.status)
        assertNull(tool.subtitle)
        assertNull(tool.errorText)
        assertEquals(SessionChatTimelineBlock.Text("After"), blocks[2])
    }

    @Test
    fun build_runningToolUsesTitleAsSubtitle() {
        val blocks =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "grep",
                            status = ToolStatus.Running,
                            title = "Searching…",
                        ),
                    ),
            )

        val tool = blocks.single() as SessionChatTimelineBlock.ToolCard
        assertEquals("Called grep", tool.title)
        assertEquals("Searching…", tool.subtitle)
        assertEquals(ToolStatus.Running, tool.status)
    }

    @Test
    fun build_pendingAndCompletedDoNotUseTitleSubtitle() {
        val pending =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Pending,
                            title = "file.kt",
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard
        assertNull(pending.subtitle)

        val completed =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            title = "file.kt",
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard
        assertNull(completed.subtitle)
    }

    @Test
    fun build_errorUsesErrorSlot() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "webfetch",
                            status = ToolStatus.Error,
                            title = "https://example.com",
                            error = "timeout",
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("Called webfetch", tool.title)
        assertNull(tool.subtitle)
        assertEquals("timeout", tool.errorText)
        assertEquals(ToolStatus.Error, tool.status)
    }

    @Test
    fun build_hidesTodowriteAlways() {
        val blocks =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Text("Visible"),
                        MessagePart.Tool(toolName = "todowrite", status = ToolStatus.Completed),
                    ),
            )

        assertEquals(listOf(SessionChatTimelineBlock.Text("Visible")), blocks)
    }

    @Test
    fun build_hidesInFlightQuestion_showsCompletedQuestion() {
        val hidden =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "question", status = ToolStatus.Pending),
                        MessagePart.Tool(toolName = "question", status = ToolStatus.Running),
                    ),
            )
        assertTrue(hidden.isEmpty())

        val shown =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "question", status = ToolStatus.Completed),
                    ),
            )
        val tool = shown.single() as SessionChatTimelineBlock.ToolCard
        assertEquals("Called question", tool.title)
    }

    @Test
    fun build_nonAssistantRolesAreTextOnly() {
        val blocks =
            SessionChatTimelineBlocks.build(
                role = MessageRole.User,
                parts =
                    listOf(
                        MessagePart.Text("Prompt"),
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed),
                    ),
            )

        assertEquals(listOf(SessionChatTimelineBlock.Text("Prompt")), blocks)
    }

    @Test
    fun build_returnsEmptyWhenOnlyHiddenTools() {
        val blocks =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "todowrite", status = ToolStatus.Running),
                        MessagePart.Tool(toolName = "question", status = ToolStatus.Pending),
                    ),
            )

        assertTrue(blocks.isEmpty())
    }

    @Test
    fun build_genericChromeForSpecializedToolIds() {
        for (name in listOf("bash", "edit", "task", "webfetch", "skill")) {
            val tool =
                SessionChatTimelineBlocks.build(
                    role = MessageRole.Assistant,
                    parts = listOf(MessagePart.Tool(toolName = name, status = ToolStatus.Completed)),
                ).single() as SessionChatTimelineBlock.ToolCard
            assertEquals("Called $name", tool.title)
        }
    }
}
