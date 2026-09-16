package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun ensureLiveRow_keepsEmptyStreamingAssistant() {
        val message =
            Message(
                id = "msg-2",
                role = MessageRole.Assistant,
                text = "",
                parts = listOf(MessagePart.Text(text = "", isStreaming = true, id = "prt-1")),
            )
        val blocks =
            SessionChatTimelineBlocks.ensureLiveRow(
                message = message,
                isStreaming = true,
                blocks = emptyList(),
            )
        val text = blocks.single() as SessionChatTimelineBlock.Text
        assertEquals(PresentationBlockId.messageFallback("msg-2"), text.blockID)
        assertEquals("", text.text)
    }

    @Test
    fun ensureLiveRow_omitsHiddenToolOnlyAssistant() {
        val message =
            Message(
                id = "msg-2",
                role = MessageRole.Assistant,
                text = "",
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "todowrite", status = ToolStatus.Running, id = "prt-1"),
                    ),
            )
        assertTrue(
            SessionChatTimelineBlocks
                .ensureLiveRow(message = message, isStreaming = false, blocks = emptyList())
                .isEmpty(),
        )
    }

    @Test
    fun ensureLiveRow_omitsEmptyTextAfterStreamEnds() {
        val message =
            Message(
                id = "msg-2",
                role = MessageRole.Assistant,
                text = "",
                parts =
                    listOf(
                        MessagePart.Text(text = "", isStreaming = false, id = "prt-1"),
                        MessagePart.Tool(toolName = "todowrite", status = ToolStatus.Completed, id = "prt-2"),
                    ),
            )
        assertTrue(
            SessionChatTimelineBlocks
                .ensureLiveRow(message = message, isStreaming = false, blocks = emptyList())
                .isEmpty(),
        )
    }

    @Test
    fun build_hidesBlankReasoning() {
        val blankCompleted =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Reasoning(
                            text = "",
                            isStreaming = false,
                            id = "r1",
                            timeStartEpochMs = 1,
                            timeEndEpochMs = 2,
                        ),
                    ),
            )
        assertTrue((blankCompleted as SessionChatTimelineBlocksResult.Ok).blocks.isEmpty())

        val blankStreaming =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Reasoning(text = "", isStreaming = true, id = "r2"),
                    ),
            )
        assertTrue((blankStreaming as SessionChatTimelineBlocksResult.Ok).blocks.isEmpty())

        val redactedOnly =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Reasoning(text = "[REDACTED]", isStreaming = false, id = "r3"),
                    ),
            )
        assertTrue((redactedOnly as SessionChatTimelineBlocksResult.Ok).blocks.isEmpty())
    }

    @Test
    fun build_hidesStackedEmptyReasoningBeforeTool() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    (1..12).map { index ->
                        MessagePart.Reasoning(
                            text = "",
                            isStreaming = false,
                            id = "r$index",
                            timeStartEpochMs = index.toLong(),
                            timeEndEpochMs = index.toLong() + 1,
                        )
                    } +
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Completed,
                            id = "shell-1",
                        ),
            )
        assertEquals(1, blocks.size)
        val shell = blocks.single() as SessionChatTimelineBlock.ToolCard
        assertEquals("bash", shell.toolName)
    }

    @Test
    fun streamingReasoning_isNotEmittedAsBlock() {
        val streaming =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Reasoning(text = "Need to greet the user.", isStreaming = true, id = "r1"),
                    ),
            )
        assertTrue((streaming as SessionChatTimelineBlocksResult.Ok).blocks.isEmpty())

        val settled =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Reasoning(text = "Need to greet the user.", isStreaming = false, id = "r1"),
                    ),
            )
        val thought = (settled as SessionChatTimelineBlocksResult.Ok).blocks.single() as SessionChatTimelineBlock.Reasoning
        assertEquals("Need to greet the user.", thought.text)
        assertFalse(thought.isStreaming)
    }

    @Test
    fun streamingReasoningOnlyMessage_fallsBackToLiveRow() {
        val message =
            Message(
                id = "msg-thought",
                role = MessageRole.Assistant,
                text = "",
                parts =
                    listOf(
                        MessagePart.Reasoning(text = "Need to greet the user.", isStreaming = true, id = "r1"),
                    ),
            )
        val built =
            SessionChatTimelineBlocks.buildOrFallback(
                role = message.role,
                parts = message.parts,
                messageId = message.id,
                fallbackText = message.text,
            )
        val blocks =
            SessionChatTimelineBlocks.ensureLiveRow(
                message = message,
                isStreaming = true,
                blocks = built,
            )
        val text = blocks.single() as SessionChatTimelineBlock.Text
        assertEquals(PresentationBlockId.messageFallback(message.id), text.blockID)
        assertEquals(message.text, text.text)
    }

    @Test
    fun build_reasoningPart_becomesThoughtBlock() {
        val result =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Reasoning(text = "Need to greet the user.", id = "r1"),
                        MessagePart.Text("Hello!", id = "t1"),
                    ),
            )
        val blocks = (result as SessionChatTimelineBlocksResult.Ok).blocks
        val thought = blocks[0] as SessionChatTimelineBlock.Reasoning
        assertEquals("Need to greet the user.", thought.text)
        assertFalse(thought.isStreaming)
        assertNull(thought.durationMs)
        assertEquals("Hello!", (blocks[1] as SessionChatTimelineBlock.Text).text)
    }

    @Test
    fun thoughtDurationLabel_matchesTuiBuckets() {
        assertEquals("438ms", SessionChatTimelineBlocks.thoughtDurationLabel(438))
        assertEquals("6.4s", SessionChatTimelineBlocks.thoughtDurationLabel(6400))
        assertEquals("2m 7s", SessionChatTimelineBlocks.thoughtDurationLabel(127_000))
        assertNull(SessionChatTimelineBlocks.thoughtDurationLabel(null))
    }

    @Test
    fun build_reasoningPart_includesDuration() {
        val result =
            SessionChatTimelineBlocks.build(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Reasoning(
                            text = "Need to greet the user.",
                            id = "r1",
                            timeStartEpochMs = 1_000,
                            timeEndEpochMs = 7_400,
                        ),
                    ),
            )
        val thought = (result as SessionChatTimelineBlocksResult.Ok).blocks.single() as SessionChatTimelineBlock.Reasoning
        assertEquals(6_400L, thought.durationMs)
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
        assertEquals("Asked 1 question", tool.title)
        assertNull(tool.subtitle)
        assertEquals("Which option?", tool.detailText)
    }

    @Test
    fun build_dismissedQuestionIsTappable() {
        val questions =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("question", "What would you like to do in this session?")
                    },
                )
            }
        val shown =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "question",
                            status = ToolStatus.Error,
                            id = "prt-d",
                            input = JsonObject(mapOf("questions" to questions)),
                            error = "The user dismissed this question",
                        ),
                    ),
            )
        val tool = shown.single() as SessionChatTimelineBlock.ToolCard
        assertEquals("Asked 1 question", tool.title)
        assertEquals("The user dismissed this question", tool.errorText)
        assertEquals(
            "What would you like to do in this session?\n\nThe user dismissed this question",
            tool.detailText,
        )
    }

    @Test
    fun build_answeredQuestionShowsQuestionAndReply() {
        val questions =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("question", "What should we do with these two projects this session?")
                    },
                )
            }
        val answers =
            buildJsonArray {
                add(
                    buildJsonArray {
                        add(JsonPrimitive("Build a third sibling"))
                    },
                )
            }
        val shown =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "question",
                            status = ToolStatus.Completed,
                            id = "prt-q",
                            input = JsonObject(mapOf("questions" to questions)),
                            metadata = JsonObject(mapOf("answers" to answers)),
                        ),
                    ),
            )
        val tool = shown.single() as SessionChatTimelineBlock.ToolCard
        assertEquals("Asked 1 question", tool.title)
        assertEquals("Build a third sibling", tool.subtitle)
        assertEquals(
            "What should we do with these two projects this session?\nBuild a third sibling",
            tool.detailText,
        )
    }

    @Test
    fun build_questionToolUsesAskedCountTitleEvenWhenNameIsPlaceholder() {
        val questions =
            buildJsonArray {
                repeat(3) { index ->
                    add(buildJsonObject { put("question", "Q$index") })
                }
            }
        val hidden =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "tool",
                            status = ToolStatus.Running,
                            id = "prt-q",
                            input = JsonObject(mapOf("questions" to questions)),
                        ),
                    ),
            )
        assertTrue(hidden.isEmpty())

        val shown =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "tool",
                            status = ToolStatus.Completed,
                            id = "prt-q",
                            input = JsonObject(mapOf("questions" to questions)),
                        ),
                    ),
            )
        val tool = shown.single() as SessionChatTimelineBlock.ToolCard
        assertEquals("Asked 3 questions", tool.title)
    }

    @Test
    fun build_hidesPlaceholderToolNameWithoutQuestions() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "tool",
                            status = ToolStatus.Running,
                            id = "prt-unknown",
                        ),
                    ),
            )
        assertTrue(blocks.isEmpty())
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
    fun build_hidesSyntheticTextBesideFileAttachment() {
        val dataUrl = "data:text/plain;base64,ZmlsZSBib2R5"
        val blocks =
            buildBlocks(
                role = MessageRole.User,
                parts =
                    listOf(
                        MessagePart.Text("I attached logs", id = "prt-1"),
                        MessagePart.Text(
                            """Called the Read tool with the following input: {"filePath":"notes.txt"}""",
                            id = "prt-2",
                            isSynthetic = true,
                        ),
                        MessagePart.Text("file body", id = "prt-3", isSynthetic = true),
                        MessagePart.File(
                            id = "prt-4",
                            filename = "notes.txt",
                            mime = "text/plain",
                            kind = AttachmentKind.Text,
                            dataUrl = dataUrl,
                        ),
                    ),
            )

        assertEquals(2, blocks.size)
        assertEquals(
            SessionChatTimelineBlock.Text(
                blockID = PresentationBlockId.direct("prt-1"),
                text = "I attached logs",
            ),
            blocks[0],
        )
        val attachment = blocks[1] as SessionChatTimelineBlock.Attachment
        assertEquals(PresentationBlockId.direct("prt-4"), attachment.blockID)
        assertEquals("notes.txt", attachment.attachment.filename)
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
        assertEquals("Explored — 1 read", tool.title)
        assertEquals("src/Main.kt", tool.subtitle)
        assertNull(tool.detailText)
    }

    @Test
    fun build_readDoesNotUseDirectoryPathAsSubtitle() {
        val tool =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            id = "prt-1",
                            input = json("path" to "/home/ivan/Projects/session-test"),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("Explored — 1 read", tool.title)
        assertNull(tool.subtitle)
    }

    @Test
    fun build_contextFallsBackToGenericTitleWhenInputMissing() {
        val tool =
            buildBlocks(
                role = MessageRole.Assistant,
                parts = listOf(MessagePart.Tool(toolName = "glob", status = ToolStatus.Completed, id = "prt-1")),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals(PresentationBlockId.direct("prt-1"), tool.blockID)
        assertEquals("Explored — 1 glob", tool.title)
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

        assertEquals("Explored — 1 grep", tool.title)
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
                            input = json("filePath" to "a.kt"),
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
        assertEquals("Explored — 1 read · 1 grep · 1 glob", group.title)
        assertEquals("a.kt", group.subtitle)
    }

    @Test
    fun build_consecutiveWebsearchesUseExploredSearchChrome() {
        val group =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "websearch",
                            status = ToolStatus.Completed,
                            id = "prt-1",
                            input = json("query" to "one"),
                        ),
                        MessagePart.Tool(
                            toolName = "websearch",
                            status = ToolStatus.Completed,
                            id = "prt-2",
                            input = json("query" to "two"),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolGroup

        assertEquals("Explored — 2 searches", group.title)
        assertEquals("one", group.subtitle)
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
    fun build_v2ShellUsesCommandAsSubtitle() {
        val tool =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "shell",
                            status = ToolStatus.Running,
                            id = "prt-1",
                            title = "shell",
                            input = json("command" to "git status"),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals("Shell", tool.title)
        assertEquals("git status", tool.subtitle)
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
        assertEquals("Explored — 1 search", search.title)
        assertEquals("opencode mobile", search.subtitle)
        assertNull(search.detailText)
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
        val row =
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
            ).single() as SessionChatTimelineBlock.ChildSessionRow

        assertEquals("explore", row.agentName)
        assertEquals("E", row.letter)
        assertEquals("Find auth bugs", row.secondLine)
        assertEquals("ses-child-1", row.childSessionId)
        assertFalse(row.isBackground)
        assertNull(row.timeStartEpochMs)
    }

    @Test
    fun build_v2SubagentUsesChildSessionRow() {
        val row =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "subagent",
                            status = ToolStatus.Completed,
                            id = "call_1",
                            title = "subagent",
                            input =
                                json(
                                    "agent" to "explore",
                                    "description" to "Find git projects in directories",
                                ),
                            metadata = json("sessionID" to "ses-child-2"),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ChildSessionRow

        assertEquals("explore", row.agentName)
        assertEquals("E", row.letter)
        assertEquals("Find git projects in directories", row.secondLine)
        assertEquals("ses-child-2", row.childSessionId)
    }

    @Test
    fun knownChildSessionIds_includesChildRowAndRunningToolCard() {
        val state =
            SessionChatTimelineRegionState(
                messages =
                    listOf(
                        SessionChatMessageItem(
                            id = "msg-1",
                            role = MessageRole.Assistant,
                            text = "",
                            metadataLabel = "",
                            blocks =
                                listOf(
                                    SessionChatTimelineBlock.ChildSessionRow(
                                        blockID = "row-1",
                                        agentName = "explore",
                                        letter = "E",
                                        isBackground = false,
                                        childSessionId = "ses-child",
                                    ),
                                    SessionChatTimelineBlock.ToolCard(
                                        blockID = "tool-1",
                                        toolName = "subagent",
                                        status = ToolStatus.Running,
                                        title = "explore",
                                        childSessionId = "ses-running",
                                    ),
                                    SessionChatTimelineBlock.ToolCard(
                                        blockID = "tool-2",
                                        toolName = "subagent",
                                        status = ToolStatus.Completed,
                                        title = "explore",
                                        childSessionId = "ses-done",
                                    ),
                                ),
                        ),
                    ),
            )

        assertEquals(setOf("ses-child", "ses-running"), state.knownChildSessionIds())
    }

    @Test
    fun build_taskFallsBackTitleAndOmitsNavWithoutSessionId() {
        val row =
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
            ).single() as SessionChatTimelineBlock.ChildSessionRow

        assertEquals("Task", row.agentName)
        assertEquals("T", row.letter)
        assertEquals("Investigate flaky tests", row.secondLine)
        assertNull(row.childSessionId)
        assertFalse(row.isBackground)
        assertNull(row.timeStartEpochMs)
    }

    @Test
    fun build_taskStaysTaskUntilInputAgentLands() {
        val row =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "task",
                            status = ToolStatus.Running,
                            id = "prt-1",
                            title = "explore",
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ChildSessionRow

        assertEquals("Task", row.agentName)
    }

    @Test
    fun build_subagentDefaultsToGeneralAndPrefersDescriptionOverCompositeTitle() {
        val row =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "subagent",
                            status = ToolStatus.Running,
                            id = "prt-1",
                            title = "General Subagent – First 7 days of October 2026",
                            input = json("description" to "First 7 days of October 2026"),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ChildSessionRow

        assertEquals("General", row.agentName)
        assertEquals("G", row.letter)
        assertEquals("First 7 days of October 2026", row.secondLine)
    }

    @Test
    fun build_subagentUsesSubagentTypeBeforeDefault() {
        val row =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "subagent",
                            status = ToolStatus.Running,
                            id = "prt-1",
                            input = json("subagent_type" to "explore"),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ChildSessionRow

        assertEquals("explore", row.agentName)
    }

    @Test
    fun build_pendingTaskQuietRowUsesPromptWhenTitleMissing() {
        val row =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "task",
                            status = ToolStatus.Pending,
                            id = "prt-1",
                            input = json("agent" to "explore", "description" to "Find auth bugs"),
                            metadata =
                                JsonObject(
                                    mapOf(
                                        "background" to JsonPrimitive(true),
                                        "sessionId" to JsonPrimitive("ses-child-1"),
                                    ),
                                ),
                            timeStartEpochMs = 1_710_000_000_000,
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ChildSessionRow

        assertEquals("explore", row.agentName)
        assertEquals("E", row.letter)
        assertEquals("Find auth bugs", row.secondLine)
        assertTrue(row.isBackground)
        assertEquals("ses-child-1", row.childSessionId)
        assertEquals(1_710_000_000_000, row.timeStartEpochMs)
    }

    @Test
    fun build_taskUsesInputNameFallbackAndOmitsBareBackground() {
        val row =
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
            ).single() as SessionChatTimelineBlock.ChildSessionRow

        assertEquals("Task", row.agentName)
        assertEquals("T", row.letter)
        assertNull(row.secondLine)
        assertTrue(row.isBackground)
        assertNull(row.childSessionId)
        assertNull(row.timeStartEpochMs)
    }

    @Test
    fun build_taskAppendsBackgroundMarkerAndUsesMetadataAgent() {
        val row =
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
            ).single() as SessionChatTimelineBlock.ChildSessionRow

        assertEquals("researcher", row.agentName)
        assertEquals("R", row.letter)
        assertEquals("Long research", row.secondLine)
        assertTrue(row.isBackground)
        assertEquals("ses-bg", row.childSessionId)
        assertNull(row.timeStartEpochMs)
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

    @Test
    fun build_collapseOff_keepsNonContextToolsSeparate() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "prt-1"),
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "prt-2"),
                    ),
            )

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is SessionChatTimelineBlock.ToolCard)
        assertTrue(blocks[1] is SessionChatTimelineBlock.ToolCard)
        assertEquals(PresentationBlockId.direct("prt-1"), blocks[0].blockID)
        assertEquals(PresentationBlockId.direct("prt-2"), blocks[1].blockID)
    }

    @Test
    fun build_collapseOn_groupsMixedConsecutiveTools() {
        val group =
            buildBlocks(
                role = MessageRole.Assistant,
                collapseConsecutiveToolCalls = true,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "prt-1"),
                        MessagePart.Tool(toolName = "edit", status = ToolStatus.Completed, id = "prt-2"),
                        MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "prt-3"),
                    ),
            ).single() as SessionChatTimelineBlock.ToolGroup

        assertEquals(PresentationBlockId.toolGroup(listOf("prt-1", "prt-2", "prt-3")), group.blockID)
        assertEquals(3, group.count)
        assertEquals("3 tools", group.title)
        assertEquals("Shell · Edit · Read", group.subtitle)
    }

    @Test
    fun build_collapseOn_mixedFamilyTitleCounts() {
        val group =
            buildBlocks(
                role = MessageRole.Assistant,
                collapseConsecutiveToolCalls = true,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "prt-1"),
                        MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "prt-2"),
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "prt-3"),
                        MessagePart.Tool(toolName = "edit", status = ToolStatus.Completed, id = "prt-4"),
                    ),
            ).single() as SessionChatTimelineBlock.ToolGroup

        assertEquals(4, group.count)
        assertEquals("4 tools", group.title)
        assertEquals("2 reads · Shell · Edit", group.subtitle)
    }

    @Test
    fun build_groupMembersUseCommandAndPath() {
        val group =
            buildBlocks(
                role = MessageRole.Assistant,
                collapseConsecutiveToolCalls = true,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Completed,
                            id = "bash-1",
                            input = json("command" to "xcodebuild build"),
                        ),
                        MessagePart.Tool(
                            toolName = "edit",
                            status = ToolStatus.Completed,
                            id = "edit-1",
                            input = json("filePath" to "App.swift"),
                        ),
                    ),
            ).single() as SessionChatTimelineBlock.ToolGroup

        assertEquals("2 tools", group.title)
        assertEquals("Shell · Edit", group.subtitle)
        assertEquals(2, group.members.size)
        assertEquals("Shell", group.members[0].title)
        assertEquals("xcodebuild build", group.members[0].subtitle)
        assertEquals("Edit", group.members[1].title)
        assertEquals("App.swift", group.members[1].subtitle)
        assertTrue(group.members[1].opensChangesReview)
    }

    @Test
    fun build_collapseOn_singleToolStaysCard() {
        val card =
            buildBlocks(
                role = MessageRole.Assistant,
                collapseConsecutiveToolCalls = true,
                parts = listOf(MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "prt-1")),
            ).single() as SessionChatTimelineBlock.ToolCard

        assertEquals(PresentationBlockId.direct("prt-1"), card.blockID)
    }

    @Test
    fun build_collapseOn_textBreaksGroup() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                collapseConsecutiveToolCalls = true,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "prt-1"),
                        MessagePart.Text("note", id = "prt-2"),
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "prt-3"),
                    ),
            )

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is SessionChatTimelineBlock.ToolCard)
        assertTrue(blocks[2] is SessionChatTimelineBlock.ToolCard)
    }

    @Test
    fun build_collapseOff_mixedToolsContextStillGroups() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "prt-1"),
                        MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "prt-2"),
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "prt-3"),
                    ),
            )

        assertEquals(2, blocks.size)
        val group = blocks[0] as SessionChatTimelineBlock.ToolGroup
        assertEquals(PresentationBlockId.toolGroup(listOf("prt-1", "prt-2")), group.blockID)
        assertEquals("Explored — 2 reads", group.title)
        assertNull(group.subtitle)
        assertTrue(blocks[1] is SessionChatTimelineBlock.ToolCard)
    }

    @Test
    fun build_collapseOn_emptyTextDoesNotBreakGroup() {
        val group =
            buildBlocks(
                role = MessageRole.Assistant,
                collapseConsecutiveToolCalls = true,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "bash-1"),
                        MessagePart.Text("  \n", id = "gap-1"),
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "bash-2"),
                    ),
            ).single() as SessionChatTimelineBlock.ToolGroup
        assertEquals(2, group.count)
        assertEquals("2 tools", group.title)
        assertEquals("2 shells", group.subtitle)
    }

    @Test
    fun build_collapseOn_hiddenToolDoesNotBreakGroup() {
        val group =
            buildBlocks(
                role = MessageRole.Assistant,
                collapseConsecutiveToolCalls = true,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "prt-1"),
                        MessagePart.Tool(toolName = "todowrite", status = ToolStatus.Completed, id = "prt-2"),
                        MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "prt-3"),
                    ),
            ).single() as SessionChatTimelineBlock.ToolGroup
        assertEquals(PresentationBlockId.toolGroup(listOf("prt-1", "prt-3")), group.blockID)
        assertEquals(2, group.count)
        assertEquals("Explored — 2 reads", group.title)
        assertNull(group.subtitle)
    }

    @Test
    fun build_collapseOn_groupStatusPrefersError() {
        val group =
            buildBlocks(
                role = MessageRole.Assistant,
                collapseConsecutiveToolCalls = true,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "prt-1"),
                        MessagePart.Tool(toolName = "edit", status = ToolStatus.Error, id = "prt-2", error = "boom"),
                        MessagePart.Tool(toolName = "read", status = ToolStatus.Running, id = "prt-3"),
                    ),
            ).single() as SessionChatTimelineBlock.ToolGroup

        assertEquals(ToolStatus.Error, group.status)
        assertEquals("boom", group.errorText)
    }

    @Test
    fun build_collapseOn_taskNeverGroups() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                collapseConsecutiveToolCalls = true,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "task",
                            status = ToolStatus.Completed,
                            id = "prt-1",
                            metadata = json("sessionId" to "ses-1"),
                        ),
                        MessagePart.Tool(
                            toolName = "task",
                            status = ToolStatus.Completed,
                            id = "prt-2",
                            metadata = json("sessionId" to "ses-2"),
                        ),
                    ),
            )

        assertEquals(2, blocks.size)
        val first = blocks[0] as SessionChatTimelineBlock.ChildSessionRow
        val second = blocks[1] as SessionChatTimelineBlock.ChildSessionRow
        assertEquals(PresentationBlockId.direct("prt-1"), first.blockID)
        assertEquals(PresentationBlockId.direct("prt-2"), second.blockID)
        assertEquals("ses-1", first.childSessionId)
    }

    @Test
    fun build_collapseOn_taskBreaksGroup() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                collapseConsecutiveToolCalls = true,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "prt-1"),
                        MessagePart.Tool(toolName = "task", status = ToolStatus.Completed, id = "prt-2"),
                        MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "prt-3"),
                    ),
            )

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is SessionChatTimelineBlock.ToolCard)
        assertTrue(blocks[1] is SessionChatTimelineBlock.ChildSessionRow)
        assertTrue(blocks[2] is SessionChatTimelineBlock.ToolCard)
    }

    @Test
    fun merge_collapseOn_groupsConsecutiveToolOnlyAssistantMessages() {
        val bodies =
            listOf(
                toolOnlyBody("a1", "bash-1"),
                toolOnlyBody("a2", "bash-2"),
                toolOnlyBody("a3", "bash-3"),
            )
        val merged =
            SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
                bodies,
                collapseConsecutiveToolCalls = true,
            )
        assertEquals(1, merged.size)
        assertEquals("a1", merged[0].message.id)
        val group = merged[0].blocks.single() as SessionChatTimelineBlock.ToolGroup
        assertEquals(3, group.count)
        assertEquals("3 tools", group.title)
        assertEquals("3 shells", group.subtitle)
    }

    @Test
    fun merge_collapseOff_keepsConsecutiveBashMessagesSeparate() {
        val bodies = listOf(toolOnlyBody("a1", "bash-1"), toolOnlyBody("a2", "bash-2"))
        val merged =
            SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
                bodies,
                collapseConsecutiveToolCalls = false,
            )
        assertEquals(2, merged.size)
        assertTrue(merged[0].blocks.single() is SessionChatTimelineBlock.ToolCard)
        assertTrue(merged[1].blocks.single() is SessionChatTimelineBlock.ToolCard)
    }

    @Test
    fun merge_collapseOn_textMessageBreaksRun() {
        val bodies =
            listOf(
                toolOnlyBody("a1", "bash-1"),
                textBody("a2", "note"),
                toolOnlyBody("a3", "bash-3"),
            )
        val merged =
            SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
                bodies,
                collapseConsecutiveToolCalls = true,
            )
        assertEquals(3, merged.size)
    }

    @Test
    fun merge_collapseOn_taskMessageBreaksRun() {
        val bodies =
            listOf(
                toolOnlyBody("a1", "bash-1"),
                toolOnlyBody("a2", "task-1", toolName = "task"),
                toolOnlyBody("a3", "bash-3"),
            )
        val merged =
            SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
                bodies,
                collapseConsecutiveToolCalls = true,
            )
        assertEquals(3, merged.size)
    }

    @Test
    fun merge_collapseOn_runningTaskRowBreaksRun() {
        val bodies =
            listOf(
                toolOnlyBody("a1", "bash-1"),
                toolOnlyBody("a2", "task-1", toolName = "task", status = ToolStatus.Running),
                toolOnlyBody("a3", "bash-3"),
            )
        val merged =
            SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
                bodies,
                collapseConsecutiveToolCalls = true,
            )
        assertEquals(3, merged.size)
        assertTrue(merged[1].blocks.single() is SessionChatTimelineBlock.ChildSessionRow)
    }

    private fun buildBlocks(
        role: MessageRole,
        parts: List<MessagePart>,
        collapseConsecutiveToolCalls: Boolean = false,
    ): List<SessionChatTimelineBlock> =
        when (
            val result =
                SessionChatTimelineBlocks.build(role, parts, collapseConsecutiveToolCalls)
        ) {
            is SessionChatTimelineBlocksResult.Ok -> result.blocks
            SessionChatTimelineBlocksResult.MissingPartIds -> error("expected Ok, got MissingPartIds")
        }

    private fun toolOnlyBody(
        id: String,
        toolId: String,
        toolName: String = "bash",
        status: ToolStatus = ToolStatus.Completed,
    ): DisplayMessageBody {
        val parts = listOf(MessagePart.Tool(toolName = toolName, status = status, id = toolId))
        return DisplayMessageBody(
            message = Message(id = id, role = MessageRole.Assistant, text = "", parts = parts),
            blocks = buildBlocks(MessageRole.Assistant, parts, collapseConsecutiveToolCalls = true),
        )
    }

    private fun textBody(
        id: String,
        text: String,
    ): DisplayMessageBody {
        val parts = listOf(MessagePart.Text(text, id = "$id-t"))
        return DisplayMessageBody(
            message = Message(id = id, role = MessageRole.Assistant, text = text, parts = parts),
            blocks = buildBlocks(MessageRole.Assistant, parts),
        )
    }

    private fun json(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (key, value) -> key to JsonPrimitive(value) })
}
