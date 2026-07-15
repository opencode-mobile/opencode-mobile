package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    fun build_interleavesTextAndToolCards() {
        val blocks =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Text("Before"),
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Completed,
                            input = json("command" to "ls"),
                            output = "a.txt",
                        ),
                        MessagePart.Text("After"),
                    ),
            )

        assertEquals(3, blocks.size)
        assertEquals(SessionChatTimelineBlock.Text("Before"), blocks[0])
        val tool = blocks[1] as SessionChatTimelineBlock.ToolCard
        assertEquals("Shell", tool.title)
        assertEquals("ls", tool.subtitle)
        assertEquals("a.txt", tool.detailText)
        assertEquals(SessionChatTimelineBlock.Text("After"), blocks[2])
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
                        MessagePart.Tool(
                            toolName = "question",
                            status = ToolStatus.Completed,
                            input = json("prompt" to "Which option?"),
                        ),
                    ),
            )
        val tool = shown.single() as SessionChatTimelineBlock.ToolCard
        assertEquals("Question", tool.title)
        assertEquals("Which option?", tool.subtitle)
        assertNull(tool.detailText)
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
    fun build_singleReadUsesSpecializedChrome() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            input = json("filePath" to "src/Main.kt"),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("Read", tool.title)
        assertEquals("src/Main.kt", tool.subtitle)
        assertNull(tool.detailText)
    }

    @Test
    fun build_contextFallsBackToGenericTitleWhenInputMissing() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts = listOf(MessagePart.Tool(toolName = "glob", status = ToolStatus.Completed)),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("Called glob", tool.title)
        assertNull(tool.subtitle)
    }

    @Test
    fun build_runningContextPrefersToolTitleSubtitle() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "grep",
                            status = ToolStatus.Running,
                            title = "Searching…",
                            input = json("pattern" to "TODO"),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("Grep", tool.title)
        assertEquals("Searching…", tool.subtitle)
    }

    @Test
    fun build_consecutiveContextToolsBecomeToolGroup() {
        val blocks =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            input = json("path" to "a.kt"),
                        ),
                        MessagePart.Tool(
                            toolName = "grep",
                            status = ToolStatus.Running,
                            input = json("pattern" to "foo"),
                        ),
                        MessagePart.Tool(
                            toolName = "glob",
                            status = ToolStatus.Pending,
                            input = json("pattern" to "*.kt"),
                        ),
                    ),
            )

        val group = blocks.single() as SessionChatTimelineBlock.ToolGroup
        assertEquals(3, group.count)
        assertEquals(ToolStatus.Running, group.status)
        assertEquals("1 read · 1 grep · 1 glob", group.title)
    }

    @Test
    fun build_textBreaksContextGroup() {
        val blocks =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            input = json("path" to "a.kt"),
                        ),
                        MessagePart.Text("note"),
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            input = json("path" to "b.kt"),
                        ),
                    ),
            )

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is SessionChatTimelineBlock.ToolCard)
        assertEquals(SessionChatTimelineBlock.Text("note"), blocks[1])
        assertTrue(blocks[2] is SessionChatTimelineBlock.ToolCard)
    }

    @Test
    fun build_nonContextToolBreaksContextGroup() {
        val blocks =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            input = json("path" to "a.kt"),
                        ),
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Completed,
                            input = json("command" to "pwd"),
                            output = "/",
                        ),
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            input = json("path" to "b.kt"),
                        ),
                    ),
            )

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is SessionChatTimelineBlock.ToolCard)
        assertTrue(blocks[1] is SessionChatTimelineBlock.ToolCard)
        assertTrue(blocks[2] is SessionChatTimelineBlock.ToolCard)
    }

    @Test
    fun build_toolGroupStatusPrefersError() {
        val group =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "read", status = ToolStatus.Completed),
                        MessagePart.Tool(
                            toolName = "grep",
                            status = ToolStatus.Error,
                            error = "boom",
                        ),
                        MessagePart.Tool(toolName = "glob", status = ToolStatus.Running),
                    ),
            ).single() as SessionChatTimelineBlock.ToolGroup

        assertEquals(ToolStatus.Error, group.status)
        assertEquals("boom", group.errorText)
    }

    @Test
    fun build_pendingBashHasNoSheetDetail() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Pending,
                            input = json("command" to "echo hi"),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("Shell", tool.title)
        assertEquals("echo hi", tool.subtitle)
        assertNull(tool.detailText)
    }

    @Test
    fun build_bashErrorUsesErrorSlotAndSheetDetail() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Error,
                            input = json("command" to "false"),
                            error = "exit 1",
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("Shell", tool.title)
        assertEquals("false", tool.subtitle)
        assertEquals("exit 1", tool.errorText)
        assertEquals("exit 1", tool.detailText)
    }

    @Test
    fun build_networkFetchAndSearchChrome() {
        val fetch =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "webfetch",
                            status = ToolStatus.Completed,
                            input = json("url" to "https://example.com"),
                            output = "ok",
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard
        assertEquals("Fetch", fetch.title)
        assertEquals("https://example.com", fetch.subtitle)
        assertEquals("ok", fetch.detailText)

        val search =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "websearch",
                            status = ToolStatus.Completed,
                            input = json("query" to "opencode mobile"),
                            output = "https://a.example\nhttps://b.example",
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard
        assertEquals("Search", search.title)
        assertEquals("opencode mobile", search.subtitle)
        assertEquals("https://a.example\nhttps://b.example", search.detailText)
    }

    @Test
    fun build_runningNetworkHasNoSheetDetailEvenWithOutput() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "webfetch",
                            status = ToolStatus.Running,
                            input = json("url" to "https://example.com"),
                            output = "partial",
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("Fetch", tool.title)
        assertNull(tool.detailText)
    }

    @Test
    fun build_skillUsesSpecializedChrome() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "skill",
                            status = ToolStatus.Completed,
                            input = json("name" to "tdd"),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("Skill", tool.title)
        assertEquals("tdd", tool.subtitle)
        assertNull(tool.detailText)
    }

    @Test
    fun build_keepsGenericChromeForLaterTickets() {
        for (name in listOf("edit", "write", "apply_patch", "task", "custom_mcp")) {
            val tool =
                SessionChatTimelineBlocks.build(
                    role = MessageRole.Assistant,
                    parts = listOf(MessagePart.Tool(toolName = name, status = ToolStatus.Completed)),
                ).single() as SessionChatTimelineBlock.ToolCard
            assertEquals("Called $name", tool.title)
            assertNull(tool.detailText)
        }
    }

    private fun json(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (key, value) -> key to JsonPrimitive(value) })
}
