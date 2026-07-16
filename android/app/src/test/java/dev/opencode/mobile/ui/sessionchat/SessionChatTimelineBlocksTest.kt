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
    fun build_taskUsesAgentTitleDescriptionAndChildSessionId() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "task",
                            status = ToolStatus.Completed,
                            input = json("agent" to "explore", "description" to "Find auth bugs"),
                            metadata = json("sessionId" to "ses-child-1"),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("explore", tool.title)
        assertEquals("Find auth bugs", tool.subtitle)
        assertEquals("ses-child-1", tool.childSessionId)
        assertNull(tool.detailText)
    }

    @Test
    fun build_taskFallsBackTitleAndOmitsNavWithoutSessionId() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "task",
                            status = ToolStatus.Running,
                            title = "Working…",
                            input = json("prompt" to "Investigate flaky tests"),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("Task", tool.title)
        assertEquals("Investigate flaky tests", tool.subtitle)
        assertNull(tool.childSessionId)
        assertNull(tool.detailText)
    }

    @Test
    fun build_taskUsesInputNameFallbackAndOmitsBareBackground() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "task",
                            status = ToolStatus.Completed,
                            input = json("name" to "coder"),
                            metadata =
                                JsonObject(
                                    mapOf("background" to JsonPrimitive(true)),
                                ),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("coder", tool.title)
        assertNull(tool.subtitle)
        assertNull(tool.childSessionId)
    }

    @Test
    fun build_taskAppendsBackgroundMarkerAndUsesMetadataAgent() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "task",
                            status = ToolStatus.Completed,
                            input = json("description" to "Long research"),
                            metadata =
                                JsonObject(
                                    mapOf(
                                        "agent" to JsonPrimitive("researcher"),
                                        "background" to JsonPrimitive(true),
                                        "sessionId" to JsonPrimitive("ses-bg"),
                                    ),
                                ),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("researcher", tool.title)
        assertEquals("Long research (background)", tool.subtitle)
        assertEquals("ses-bg", tool.childSessionId)
    }

    @Test
    fun build_taskErrorKeepsSpecializedChrome() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "task",
                            status = ToolStatus.Error,
                            error = "child failed",
                            input = json("agent" to "build", "description" to "Ship it"),
                            metadata = json("sessionId" to "ses-err"),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("build", tool.title)
        assertEquals("Ship it", tool.subtitle)
        assertEquals("child failed", tool.errorText)
        assertEquals("ses-err", tool.childSessionId)
    }

    @Test
    fun build_editUsesFilediffPathCountsAndOpensChanges() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "edit",
                            status = ToolStatus.Completed,
                            metadata =
                                JsonObject(
                                    mapOf(
                                        "filediff" to
                                            JsonObject(
                                                mapOf(
                                                    "file" to JsonPrimitive("src/Main.kt"),
                                                    "additions" to JsonPrimitive(3),
                                                    "deletions" to JsonPrimitive(1),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("Edit", tool.title)
        assertEquals("src/Main.kt", tool.subtitle)
        assertEquals(3, tool.additions)
        assertEquals(1, tool.deletions)
        assertTrue(tool.opensChangesReview)
        assertEquals("src/Main.kt", tool.changesInitialFilePath)
        assertNull(tool.detailText)
        assertNull(tool.childSessionId)
    }

    @Test
    fun build_writeFallsBackToInputPathAndOmitsZeroCounts() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "write",
                            status = ToolStatus.Completed,
                            input = json("filePath" to "README.md"),
                            metadata =
                                JsonObject(
                                    mapOf(
                                        "filediff" to
                                            JsonObject(
                                                mapOf(
                                                    "file" to JsonPrimitive("README.md"),
                                                    "additions" to JsonPrimitive(5),
                                                    "deletions" to JsonPrimitive(0),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("Write", tool.title)
        assertEquals("README.md", tool.subtitle)
        assertEquals(5, tool.additions)
        assertNull(tool.deletions)
        assertTrue(tool.opensChangesReview)
        assertEquals("README.md", tool.changesInitialFilePath)
    }

    @Test
    fun build_fileChangeWithoutPathKeepsTitleOnlyAndNoTap() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts = listOf(MessagePart.Tool(toolName = "edit", status = ToolStatus.Completed)),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("Edit", tool.title)
        assertNull(tool.subtitle)
        assertNull(tool.additions)
        assertNull(tool.deletions)
        assertTrue(!tool.opensChangesReview)
        assertNull(tool.changesInitialFilePath)
    }

    @Test
    fun build_fileChangePendingWithoutPathIsTitleOnly() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "edit",
                            status = ToolStatus.Running,
                            title = "Editing…",
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("Edit", tool.title)
        assertNull(tool.subtitle)
        assertTrue(!tool.opensChangesReview)
    }

    @Test
    fun build_fileChangePendingWithPathIsDisplayOnly() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "edit",
                            status = ToolStatus.Running,
                            title = "Editing…",
                            input = json("filePath" to "App.kt"),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("Edit", tool.title)
        assertEquals("App.kt", tool.subtitle)
        assertTrue(!tool.opensChangesReview)
        assertNull(tool.changesInitialFilePath)
    }

    @Test
    fun build_applyPatchMultiFileUsesCountSubtitleAndListTap() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "apply_patch",
                            status = ToolStatus.Completed,
                            metadata =
                                JsonObject(
                                    mapOf(
                                        "files" to
                                            kotlinx.serialization.json.JsonArray(
                                                listOf(
                                                    JsonObject(
                                                        mapOf(
                                                            "filePath" to JsonPrimitive("a.kt"),
                                                            "additions" to JsonPrimitive(2),
                                                            "deletions" to JsonPrimitive(1),
                                                        ),
                                                    ),
                                                    JsonObject(
                                                        mapOf(
                                                            "filePath" to JsonPrimitive("b.kt"),
                                                            "additions" to JsonPrimitive(4),
                                                            "deletions" to JsonPrimitive(0),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("Patch", tool.title)
        assertEquals("2 files", tool.subtitle)
        assertEquals(6, tool.additions)
        assertEquals(1, tool.deletions)
        assertTrue(tool.opensChangesReview)
        assertNull(tool.changesInitialFilePath)
    }

    @Test
    fun build_applyPatchSingleFileDeepLinksPath() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "apply_patch",
                            status = ToolStatus.Error,
                            error = "conflict",
                            metadata =
                                JsonObject(
                                    mapOf(
                                        "files" to
                                            kotlinx.serialization.json.JsonArray(
                                                listOf(
                                                    JsonObject(
                                                        mapOf(
                                                            "filePath" to JsonPrimitive("solo.kt"),
                                                            "additions" to JsonPrimitive(1),
                                                            "deletions" to JsonPrimitive(1),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("Patch", tool.title)
        assertEquals("solo.kt", tool.subtitle)
        assertEquals(1, tool.additions)
        assertEquals(1, tool.deletions)
        assertEquals("conflict", tool.errorText)
        assertTrue(tool.opensChangesReview)
        assertEquals("solo.kt", tool.changesInitialFilePath)
    }

    @Test
    fun build_keepsGenericChromeForUnknownTools() {
        val tool =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts = listOf(MessagePart.Tool(toolName = "custom_mcp", status = ToolStatus.Completed)),
            ).single() as SessionChatTimelineBlock.ToolCard
        assertEquals("Called custom_mcp", tool.title)
        assertNull(tool.detailText)
        assertNull(tool.childSessionId)
        assertTrue(!tool.opensChangesReview)
    }

    private fun json(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (key, value) -> key to JsonPrimitive(value) })
}
