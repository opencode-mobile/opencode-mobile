package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionChatTimelineBlocksTest {
    @Test
    fun buildOrFallback_emptyParts_usesFallbackText() {
        val blocks =
            SessionChatTimelineBlocks.buildOrFallback(
                role = MessageRole.User,
                parts = emptyList(),
                messageId = "msg-1",
                fallbackText = "Hello",
            )
        assertEquals(
            listOf(
                SessionChatTimelineBlock.Text(
                    blockID = PresentationBlockId.messageFallback("msg-1"),
                    text = "Hello",
                ),
            ),
            blocks,
        )
    }

    @Test
    fun build_mergesAdjacentTextParts() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Text("Hello", id = "prt-1"),
                        MessagePart.Text("World", id = "prt-2"),
                    ),
            )

        assertEquals(
            listOf(
                SessionChatTimelineBlock.Text(
                    blockID = PresentationBlockId.mergedText(listOf("prt-1", "prt-2")),
                    text = "Hello\nWorld",
                ),
            ),
            blocks,
        )
    }

    @Test
    fun build_mergedTextUsesOrderedPartIds() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Text("A", id = "prt-a"),
                        MessagePart.Text("B", id = "prt-b"),
                        MessagePart.Text("C", id = "prt-c"),
                    ),
            )

        val text = blocks.single() as SessionChatTimelineBlock.Text
        assertEquals("A\nB\nC", text.text)
        assertEquals(PresentationBlockId.mergedText(listOf("prt-a", "prt-b", "prt-c")), text.blockID)
        assertNotEquals(PresentationBlockId.mergedText(listOf("prt-c", "prt-b", "prt-a")), text.blockID)
    }

    @Test
    fun build_missingPartId_returnsMissingPartIds() {
        val result =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts = listOf(MessagePart.Text("Hello")),
            )
        assertEquals(SessionChatTimelineBlocksResult.MissingPartIds, result)

        val toolMissing =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed),
                    ),
            )
        assertEquals(SessionChatTimelineBlocksResult.MissingPartIds, toolMissing)
    }

    @Test
    fun build_toolGroupMembershipChange_producesNewBlockId() {
        val first =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            id = "prt-1",
                            input = json("path" to "a.kt"),
                        ),
                        MessagePart.Tool(
                            toolName = "grep",
                            status = ToolStatus.Completed,
                            id = "prt-2",
                            input = json("pattern" to "foo"),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolGroup

        val second =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            id = "prt-1",
                            input = json("path" to "a.kt"),
                        ),
                        MessagePart.Tool(
                            toolName = "grep",
                            status = ToolStatus.Completed,
                            id = "prt-2",
                            input = json("pattern" to "foo"),
                        ),
                        MessagePart.Tool(
                            toolName = "glob",
                            status = ToolStatus.Completed,
                            id = "prt-3",
                            input = json("pattern" to "*.kt"),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolGroup

        assertEquals(PresentationBlockId.toolGroup(listOf("prt-1", "prt-2")), first.blockID)
        assertEquals(PresentationBlockId.toolGroup(listOf("prt-1", "prt-2", "prt-3")), second.blockID)
        assertNotEquals(first.blockID, second.blockID)
    }

    @Test
    fun build_interleavesTextAndToolCards() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Text("Before", id = "prt-1"),
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Completed,
                            id = "prt-2",
                            input = json("command" to "ls"),
                            output = "a.txt",
                        ),
                        MessagePart.Text("After", id = "prt-3"),
                    ),
            )

        assertEquals(3, blocks.size)
        assertEquals(
            SessionChatTimelineBlock.Text(
                blockID = PresentationBlockId.direct("prt-1"),
                text = "Before",
            ),
            blocks[0],
        )
        val tool = blocks[1] as SessionChatTimelineBlock.ToolCard
        assertEquals(PresentationBlockId.direct("prt-2"), tool.blockID)
        assertEquals("Shell", tool.title)
        assertEquals("ls", tool.subtitle)
        assertEquals("a.txt", tool.detailText)
        assertEquals(
            SessionChatTimelineBlock.Text(
                blockID = PresentationBlockId.direct("prt-3"),
                text = "After",
            ),
            blocks[2],
        )
    }

    @Test
    fun build_hidesTodowriteAlways() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Text("Visible", id = "prt-1"),
                        MessagePart.Tool(toolName = "todowrite", status = ToolStatus.Completed, id = "prt-2"),
                    ),
            )

        assertEquals(
            listOf(
                SessionChatTimelineBlock.Text(
                    blockID = PresentationBlockId.direct("prt-1"),
                    text = "Visible",
                ),
            ),
            blocks,
        )
    }

    @Test
    fun build_hidesInFlightQuestion_showsCompletedQuestion() {
        val hidden =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "question", status = ToolStatus.Pending, id = "prt-1"),
                        MessagePart.Tool(toolName = "question", status = ToolStatus.Running, id = "prt-2"),
                    ),
            )
        assertTrue(hidden.isEmpty())

        val shown =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "question",
                            status = ToolStatus.Completed,
                            id = "prt-3",
                            input = json("prompt" to "Which option?"),
                        ),
                    ),
            )
        val tool = shown.single() as SessionChatTimelineBlock.ToolCard
        assertEquals(PresentationBlockId.direct("prt-3"), tool.blockID)
        assertEquals("Question", tool.title)
        assertEquals("Which option?", tool.subtitle)
        assertNull(tool.detailText)
    }

    @Test
    fun build_nonAssistantRolesAreTextOnly() {
        val blocks =
            buildBlocks(
                role = MessageRole.User,
                parts =
                    listOf(
                        MessagePart.Text("Prompt", id = "prt-1"),
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "prt-2"),
                    ),
            )

        assertEquals(
            listOf(
                SessionChatTimelineBlock.Text(
                    blockID = PresentationBlockId.direct("prt-1"),
                    text = "Prompt",
                ),
            ),
            blocks,
        )
    }

    @Test
    fun build_returnsEmptyWhenOnlyHiddenTools() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "todowrite", status = ToolStatus.Running, id = "prt-1"),
                        MessagePart.Tool(toolName = "question", status = ToolStatus.Pending, id = "prt-2"),
                    ),
            )

        assertTrue(blocks.isEmpty())
    }

    @Test
    fun build_singleReadUsesSpecializedChrome() {
        val tool =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            id = "prt-1",
                            input = json("filePath" to "src/Main.kt"),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals(PresentationBlockId.direct("prt-1"), tool.blockID)
        assertEquals("Read", tool.title)
        assertEquals("src/Main.kt", tool.subtitle)
        assertNull(tool.detailText)
    }

    @Test
    fun build_contextFallsBackToGenericTitleWhenInputMissing() {
        val tool =
            buildBlocks(
                role = MessageRole.Assistant,
                parts = listOf(MessagePart.Tool(toolName = "glob", status = ToolStatus.Completed, id = "prt-1")),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals(PresentationBlockId.direct("prt-1"), tool.blockID)
        assertEquals("Called glob", tool.title)
        assertNull(tool.subtitle)
    }

    @Test
    fun build_runningContextPrefersToolTitleSubtitle() {
        val tool =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "grep",
                            status = ToolStatus.Running,
                            id = "prt-1",
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            id = "prt-1",
                            input = json("path" to "a.kt"),
                        ),
                        MessagePart.Tool(
                            toolName = "grep",
                            status = ToolStatus.Running,
                            id = "prt-2",
                            input = json("pattern" to "foo"),
                        ),
                        MessagePart.Tool(
                            toolName = "glob",
                            status = ToolStatus.Pending,
                            id = "prt-3",
                            input = json("pattern" to "*.kt"),
                        ),
                    ),
            )

        val group = blocks.single() as SessionChatTimelineBlock.ToolGroup
        assertEquals(PresentationBlockId.toolGroup(listOf("prt-1", "prt-2", "prt-3")), group.blockID)
        assertEquals(3, group.count)
        assertEquals(ToolStatus.Running, group.status)
        assertEquals("1 read · 1 grep · 1 glob", group.title)
    }

    @Test
    fun build_textBreaksContextGroup() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            id = "prt-1",
                            input = json("path" to "a.kt"),
                        ),
                        MessagePart.Text("note", id = "prt-2"),
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            id = "prt-3",
                            input = json("path" to "b.kt"),
                        ),
                    ),
            )

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is SessionChatTimelineBlock.ToolCard)
        assertEquals(
            SessionChatTimelineBlock.Text(
                blockID = PresentationBlockId.direct("prt-2"),
                text = "note",
            ),
            blocks[1],
        )
        assertTrue(blocks[2] is SessionChatTimelineBlock.ToolCard)
    }

    @Test
    fun build_nonContextToolBreaksContextGroup() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            id = "prt-1",
                            input = json("path" to "a.kt"),
                        ),
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Completed,
                            id = "prt-2",
                            input = json("command" to "pwd"),
                            output = "/",
                        ),
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            id = "prt-3",
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "prt-1"),
                        MessagePart.Tool(
                            toolName = "grep",
                            status = ToolStatus.Error,
                            id = "prt-2",
                            error = "boom",
                        ),
                        MessagePart.Tool(toolName = "glob", status = ToolStatus.Running, id = "prt-3"),
                    ),
            ).single() as SessionChatTimelineBlock.ToolGroup

        assertEquals(ToolStatus.Error, group.status)
        assertEquals("boom", group.errorText)
    }

    @Test
    fun build_pendingBashHasNoSheetDetail() {
        val tool =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Pending,
                            id = "prt-1",
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Error,
                            id = "prt-1",
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "webfetch",
                            status = ToolStatus.Completed,
                            id = "prt-1",
                            input = json("url" to "https://example.com"),
                            output = "ok",
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard
        assertEquals("Fetch", fetch.title)
        assertEquals("https://example.com", fetch.subtitle)
        assertEquals("ok", fetch.detailText)

        val search =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "websearch",
                            status = ToolStatus.Completed,
                            id = "prt-2",
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "webfetch",
                            status = ToolStatus.Running,
                            id = "prt-1",
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "skill",
                            status = ToolStatus.Completed,
                            id = "prt-1",
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "task",
                            status = ToolStatus.Completed,
                            id = "prt-1",
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "task",
                            status = ToolStatus.Running,
                            id = "prt-1",
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "task",
                            status = ToolStatus.Completed,
                            id = "prt-1",
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "task",
                            status = ToolStatus.Completed,
                            id = "prt-1",
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "task",
                            status = ToolStatus.Error,
                            id = "prt-1",
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "edit",
                            status = ToolStatus.Completed,
                            id = "prt-1",
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "write",
                            status = ToolStatus.Completed,
                            id = "prt-1",
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts = listOf(MessagePart.Tool(toolName = "edit", status = ToolStatus.Completed, id = "prt-1")),
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "edit",
                            status = ToolStatus.Running,
                            id = "prt-1",
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "edit",
                            status = ToolStatus.Running,
                            id = "prt-1",
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "apply_patch",
                            status = ToolStatus.Completed,
                            id = "prt-1",
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "apply_patch",
                            status = ToolStatus.Error,
                            id = "prt-1",
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
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "custom_mcp", status = ToolStatus.Completed, id = "prt-1"),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard
        assertEquals(PresentationBlockId.direct("prt-1"), tool.blockID)
        assertEquals("Called custom_mcp", tool.title)
        assertNull(tool.detailText)
        assertNull(tool.childSessionId)
        assertTrue(!tool.opensChangesReview)
    }

    private fun buildBlocks(
        role: MessageRole,
        parts: List<MessagePart>,
    ): List<SessionChatTimelineBlock> =
        when (val result = SessionChatTimelineBlocks.build(role, parts)) {
            is SessionChatTimelineBlocksResult.Ok -> result.blocks
            SessionChatTimelineBlocksResult.MissingPartIds -> error("expected Ok, got MissingPartIds")
        }

    private fun json(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (key, value) -> key to JsonPrimitive(value) })
}
