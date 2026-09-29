package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.markdown.MarkdownDocument
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
    fun paintsInTimeline_falseOnlyForLiveThought() {
        val streaming =
            SessionChatTimelineBlock.Reasoning(
                blockID = "r1",
                text = "thinking",
                isStreaming = true,
            )
        assertFalse(streaming.paintsInTimeline)

        val settled =
            SessionChatTimelineBlock.Reasoning(
                blockID = "r2",
                text = "done",
                isStreaming = false,
                durationMs = 100,
            )
        assertTrue(settled.paintsInTimeline)

        val text =
            SessionChatTimelineBlock.Text(
                blockID = "t1",
                text = "Hello",
            )
        assertTrue(text.paintsInTimeline)
    }

    @Test
    fun paintedBlocks_streamingReasoningOnly_fallsBackToPlaceholder() {
        val message =
            SessionChatMessageItem(
                id = "msg-1",
                role = MessageRole.Assistant,
                text = "",
                metadataLabel = "",
                blocks =
                    listOf(
                        SessionChatTimelineBlock.Reasoning(
                            blockID = "r1",
                            text = "thinking",
                            isStreaming = true,
                        ),
                    ),
            )
        val painted = SessionChatTimelineBlocks.paintedBlocks(message)
        val text = painted.single() as SessionChatTimelineBlock.Text
        assertEquals(PresentationBlockId.messageFallback("msg-1"), text.blockID)
    }

    @Test
    fun paintedBlocks_textPlusStreamingReasoning_keepsOnlyText() {
        val textBlock =
            SessionChatTimelineBlock.Text(
                blockID = "t1",
                text = "Hello",
            )
        val message =
            SessionChatMessageItem(
                id = "msg-2",
                role = MessageRole.Assistant,
                text = "Hello",
                metadataLabel = "",
                blocks =
                    listOf(
                        textBlock,
                        SessionChatTimelineBlock.Reasoning(
                            blockID = "r1",
                            text = "thinking",
                            isStreaming = true,
                        ),
                    ),
            )
        assertEquals(listOf(textBlock), SessionChatTimelineBlocks.paintedBlocks(message))
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

    // --- Chip timeline (OPE-260) ---

    @Test
    fun chipTimeline_textThenTools_oneParagraphWithChips() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Text("Looking at the timeline", id = "t1"),
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            id = "p1",
                            input = json("filePath" to "/repo/ios/SessionChatView.swift"),
                        ),
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Completed,
                            id = "p2",
                            input = json("command" to "xcodebuild test -scheme App"),
                        ),
                    ),
            )

        val paragraph = blocks.single() as SessionChatTimelineBlock.StepParagraph
        assertEquals(PresentationBlockId.stepParagraph(listOf("t1", "p1", "p2")), paragraph.blockID)
        assertEquals(PresentationBlockId.mergedText(listOf("t1")), paragraph.textBlockID)
        assertEquals("Looking at the timeline", paragraph.text)
        assertEquals(listOf("SessionChatView.swift", "xcodebuild test -scheme App"), paragraph.chips.map { it.label })
        assertEquals(listOf(ToolChipKind.File, ToolChipKind.Command), paragraph.chips.map { it.kind })
        assertTrue(paragraph.errorLines.isEmpty())
    }

    @Test
    fun chipTimeline_toolsThenTextThenTools_twoParagraphs() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "p1"),
                        MessagePart.Text("Now the fix", id = "t1"),
                        MessagePart.Tool(toolName = "edit", status = ToolStatus.Completed, id = "p2"),
                    ),
            )

        assertEquals(2, blocks.size)
        val first = blocks[0] as SessionChatTimelineBlock.StepParagraph
        val second = blocks[1] as SessionChatTimelineBlock.StepParagraph
        assertNull(first.text)
        assertNull(first.textBlockID)
        assertEquals(PresentationBlockId.stepParagraph(listOf("p1")), first.blockID)
        assertEquals("Now the fix", second.text)
        assertEquals(PresentationBlockId.stepParagraph(listOf("t1", "p2")), second.blockID)
        assertEquals(1, second.chips.size)
    }

    @Test
    fun chipTimeline_multipleTextPartsMergeLikeTextBlock() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Text("One", id = "t1"),
                        MessagePart.Text(" Two ", id = "t2"),
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "p1"),
                    ),
            )

        val paragraph = blocks.single() as SessionChatTimelineBlock.StepParagraph
        assertEquals("One\nTwo", paragraph.text)
        assertEquals(PresentationBlockId.mergedText(listOf("t1", "t2")), paragraph.textBlockID)
    }

    @Test
    fun chipTimeline_childSessionTool_becomesAgentChip() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "task",
                            status = ToolStatus.Running,
                            id = "p1",
                            input = json("subagent_type" to "Explore", "description" to "Find the pill\nmore"),
                            metadata = json("sessionId" to "ses-child"),
                        ),
                    ),
            )

        val paragraph = blocks.single() as SessionChatTimelineBlock.StepParagraph
        val chip = paragraph.chips.single()
        assertEquals(ToolChipKind.Agent, chip.kind)
        assertEquals(ToolChipFamily.Agent, chip.family)
        assertEquals("Explore", chip.label)
        assertEquals("Find the pill", chip.brief)
        assertEquals(ToolStatus.Running, chip.status)
        assertNull(chip.trailingText)
        assertEquals("ses-child", chip.card.childSessionId)
        assertTrue(blocks.none { it is SessionChatTimelineBlock.ChildSessionRow })
    }

    @Test
    fun chipTimeline_failedTool_errorStatusAndErrorLine() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Error,
                            id = "p1",
                            input = json("command" to "npm test --silent"),
                            error = "exit code 1",
                        ),
                        MessagePart.Tool(toolName = "read", status = ToolStatus.Error, id = "p2"),
                    ),
            )

        val paragraph = blocks.single() as SessionChatTimelineBlock.StepParagraph
        assertEquals(listOf(ToolStatus.Error, ToolStatus.Error), paragraph.chips.map { it.status })
        assertEquals(listOf("read: Failed", "npm test --silent: exit code 1"), paragraph.errorLines)
    }

    @Test
    fun chipTimeline_labelsPerTool() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "glob",
                            status = ToolStatus.Completed,
                            id = "p1",
                            input = json("pattern" to "**/*.kt", "path" to "/repo"),
                        ),
                        MessagePart.Tool(
                            toolName = "grep",
                            status = ToolStatus.Completed,
                            id = "p2",
                            input = json("pattern" to "chipTimeline", "path" to "/repo/android"),
                        ),
                        MessagePart.Tool(
                            toolName = "websearch",
                            status = ToolStatus.Completed,
                            id = "p3",
                            input = json("query" to "compose flow row"),
                        ),
                        MessagePart.Tool(
                            toolName = "webfetch",
                            status = ToolStatus.Completed,
                            id = "p4",
                            input = json("url" to "https://developer.android.com/jetpack/compose"),
                        ),
                        MessagePart.Tool(
                            toolName = "edit",
                            status = ToolStatus.Completed,
                            id = "p5",
                            input = json("filePath" to "/repo/android/Navigation.kt"),
                            metadata =
                                buildJsonObject {
                                    put(
                                        "filediff",
                                        buildJsonObject {
                                            put("file", "/repo/android/Navigation.kt")
                                            put("additions", 4)
                                            put("deletions", 1)
                                        },
                                    )
                                },
                        ),
                        MessagePart.Tool(
                            toolName = "skill",
                            status = ToolStatus.Completed,
                            id = "p6",
                            input = json("name" to "feature"),
                        ),
                        MessagePart.Tool(
                            toolName = "shell",
                            status = ToolStatus.Completed,
                            id = "p7",
                            input = json("command" to "  ./gradlew   :app:testDebugUnitTest --tests Foo"),
                        ),
                        MessagePart.Tool(toolName = "linear_get_issue", status = ToolStatus.Completed, id = "p8"),
                    ),
            )

        val paragraph = blocks.single() as SessionChatTimelineBlock.StepParagraph
        val summary = requireNotNull(paragraph.exploreSummary)
        assertEquals("Explored \u00b7 1 glob \u00b7 1 grep \u00b7 1 search \u00b7 1 fetch", summary.label)
        assertEquals("Explored \u2014 1 glob \u00b7 1 grep \u00b7 1 search \u00b7 1 fetch", summary.title)
        assertEquals(ToolStatus.Completed, summary.status)
        assertEquals(PresentationBlockId.toolGroup(listOf("p1", "p2", "p3", "p4")), summary.blockID)
        assertEquals(listOf("p1", "p2", "p3", "p4").map(PresentationBlockId::direct), summary.members.map { it.blockID })
        val chips = paragraph.chips
        val byId = chips.associateBy { it.card.blockID }
        assertEquals(listOf("p5", "p6", "p7", "p8").map(PresentationBlockId::direct).toSet(), byId.keys)
        val edit = byId.getValue(PresentationBlockId.direct("p5"))
        assertEquals("Navigation.kt", edit.label)
        assertEquals(4, edit.additions)
        assertEquals(1, edit.deletions)
        assertEquals(ToolChipFamily.Change, edit.family)
        assertTrue(edit.card.opensChangesReview)
        val skill = byId.getValue(PresentationBlockId.direct("p6"))
        assertEquals("/feature", skill.label)
        assertEquals(ToolChipKind.Skill, skill.kind)
        assertEquals("./gradlew :app:testDebugUnitTest --tests Foo", byId.getValue(PresentationBlockId.direct("p7")).label)
        val generic = byId.getValue(PresentationBlockId.direct("p8"))
        assertEquals("linear_get_issue", generic.label)
        assertEquals(ToolChipFamily.Command, generic.family)
    }

    @Test
    fun chipTimeline_commandLabels_firstLineCollapsedAndTruncated() {
        val sixtyChars = "echo " + "x".repeat(55)
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Completed,
                            id = "p1",
                            input = json("command" to "\n\n  gh   pr list \t--state open\ncd repo && ls"),
                        ),
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Completed,
                            id = "p2",
                            input = json("command" to sixtyChars),
                        ),
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Completed,
                            id = "p3",
                            input = json("command" to "gh pr list --state open --limit 50"),
                        ),
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Completed,
                            id = "p4",
                            input = json("command" to "for n in 1 2 3; do echo \$n; done"),
                        ),
                    ),
            )

        val labels = (blocks.single() as SessionChatTimelineBlock.StepParagraph).chips.map { it.label }
        assertEquals("gh pr list --state open", labels[0])
        // Never cut inside the first token; the long second token is dropped whole.
        assertEquals("echo\u2026", labels[1])
        assertEquals("gh pr list --state open --limit 50", labels[2])
        assertEquals("for n in 1 2 3; do echo \$n; done", labels[3])
    }

    @Test
    fun chipTimeline_commandLabels_stripCdAndEnvPrefixes_cardKeepsFullCommand() {
        val command = "cd /repo/android && export JAVA_HOME=/jdk && CI=1 ./gradlew :app:assembleDebug"
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Completed,
                            id = "p1",
                            input = json("command" to command),
                        ),
                    ),
            )

        val chip = (blocks.single() as SessionChatTimelineBlock.StepParagraph).chips.single()
        assertEquals("./gradlew :app:assembleDebug", chip.label)
        assertEquals(command, chip.card.subtitle)
    }

    @Test
    fun chipTimeline_identicalConsecutiveCompletedRuns_foldKeepingTheLastCard() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Text("Testing", id = "t1"),
                        bashTool("p1", "npm test"),
                        bashTool("p2", "npm test"),
                        bashTool("p3", "npm test"),
                        bashTool("p4", "npm run lint"),
                        bashTool("p5", "npm test"),
                    ),
            )

        val paragraph = blocks.single() as SessionChatTimelineBlock.StepParagraph
        assertEquals(listOf("npm test", "npm run lint", "npm test"), paragraph.chips.map { it.label })
        assertEquals(listOf(3, 1, 1), paragraph.chips.map { it.repeatCount })
        assertEquals(PresentationBlockId.direct("p3"), paragraph.chips[0].card.blockID)
        assertEquals("\u00d73", paragraph.chips[0].displayTrailingText)
        assertEquals(PresentationBlockId.stepParagraph(listOf("t1", "p1", "p2", "p3", "p4", "p5")), paragraph.blockID)
    }

    @Test
    fun chipTimeline_failedOrRunningChip_breaksARepeatRunAndNeverFolds() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        bashTool("p1", "npm test"),
                        bashTool("p2", "npm test", ToolStatus.Error),
                        bashTool("p3", "npm test", ToolStatus.Error),
                        bashTool("p4", "npm test"),
                        bashTool("p5", "npm test"),
                        bashTool("p6", "npm test", ToolStatus.Running),
                    ),
            )

        val chips = (blocks.single() as SessionChatTimelineBlock.StepParagraph).chips
        assertEquals(
            listOf(ToolStatus.Completed, ToolStatus.Error, ToolStatus.Error, ToolStatus.Completed, ToolStatus.Running),
            chips.map { it.status },
        )
        assertEquals(listOf(1, 1, 1, 2, 1), chips.map { it.repeatCount })
        assertEquals(PresentationBlockId.direct("p5"), chips[3].card.blockID)
    }

    @Test
    fun chipTimeline_expansionId_isStableAsTheParagraphGrows() {
        fun paragraphs(parts: List<MessagePart>) =
            buildBlocks(role = MessageRole.Assistant, chipTimeline = true, parts = parts)
                .filterIsInstance<SessionChatTimelineBlock.StepParagraph>()

        val placeholderEarly = paragraphs(listOf(bashTool("p1", "ls")))
        val placeholderLater = paragraphs(listOf(bashTool("p1", "ls"), bashTool("p2", "pwd")))
        assertNotEquals(placeholderEarly.single().blockID, placeholderLater.single().blockID)
        assertEquals(placeholderEarly.single().expansionID, placeholderLater.single().expansionID)

        val narratedEarly = paragraphs(listOf(MessagePart.Text("Look", id = "t1")))
        val narratedLater = paragraphs(listOf(MessagePart.Text("Look", id = "t1"), bashTool("p1", "ls")))
        assertEquals(narratedEarly.single().expansionID, narratedLater.single().expansionID)
        assertNotEquals(placeholderEarly.single().expansionID, narratedLater.single().expansionID)
    }

    @Test
    fun chipTimeline_chipsOrderedByFamily() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "p1"),
                        MessagePart.Tool(toolName = "edit", status = ToolStatus.Completed, id = "p2"),
                        MessagePart.Tool(toolName = "task", status = ToolStatus.Completed, id = "p3"),
                        MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "p4"),
                        MessagePart.Tool(toolName = "skill", status = ToolStatus.Completed, id = "p5"),
                        MessagePart.Tool(toolName = "grep", status = ToolStatus.Completed, id = "p6"),
                    ),
            )

        val paragraph = blocks.single() as SessionChatTimelineBlock.StepParagraph
        // Explore tools fold into the summary; the rest keep a fixed group order.
        assertEquals("Explored \u00b7 1 read \u00b7 1 grep", paragraph.exploreSummary?.label)
        assertEquals(
            listOf(ToolChipFamily.Change, ToolChipFamily.Command, ToolChipFamily.Agent, ToolChipFamily.Skill),
            paragraph.chips.map { it.family },
        )
        // Arrival order is kept on each chip for scoped expansion.
        assertEquals(listOf(1, 0, 2, 3), paragraph.chips.map { it.arrivalIndex })
    }

    @Test
    fun chipTimeline_exploreSummary_runningWhileAnyMemberIsInFlight() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            id = "p1",
                            input = json("filePath" to "/a/b.kt"),
                        ),
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            id = "p2",
                            input = json("filePath" to "/a/c.kt"),
                        ),
                        MessagePart.Tool(toolName = "grep", status = ToolStatus.Running, id = "p3", input = json("pattern" to "foo")),
                    ),
            )

        val summary = requireNotNull((blocks.single() as SessionChatTimelineBlock.StepParagraph).exploreSummary)
        assertEquals("Exploring \u00b7 2 reads \u00b7 1 grep", summary.label)
        assertEquals(ToolStatus.Running, summary.status)
        assertEquals("/a/b.kt", summary.subtitle)
        assertEquals(3, summary.members.size)
    }

    @Test
    fun chipTimeline_singleExploreTool_keepsItsOwnChip() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            id = "p1",
                            input = json("filePath" to "/a/b.kt"),
                        ),
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "p2", input = json("command" to "ls")),
                    ),
            )

        val paragraph = blocks.single() as SessionChatTimelineBlock.StepParagraph
        assertNull(paragraph.exploreSummary)
        assertEquals(listOf("b.kt", "ls"), paragraph.chips.map { it.label })
    }

    @Test
    fun chipTimeline_failedExploreTool_staysAnAttentionChipOutsideTheSummary() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            id = "p1",
                            input = json("filePath" to "/a/b.kt"),
                        ),
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Error,
                            id = "p2",
                            input = json("filePath" to "/a/missing.kt"),
                            error = "ENOENT",
                        ),
                        MessagePart.Tool(toolName = "grep", status = ToolStatus.Completed, id = "p3", input = json("pattern" to "foo")),
                    ),
            )

        val paragraph = blocks.single() as SessionChatTimelineBlock.StepParagraph
        assertEquals("Explored \u00b7 1 read \u00b7 1 grep", paragraph.exploreSummary?.label)
        assertEquals(PresentationBlockId.toolGroup(listOf("p1", "p3")), paragraph.exploreSummary?.blockID)
        val failed = paragraph.chips.single()
        assertEquals("missing.kt", failed.label)
        assertEquals(ToolStatus.Error, failed.status)
        assertEquals(listOf("missing.kt: ENOENT"), paragraph.errorLines)
    }

    @Test
    fun chipTimeline_foldedChip_keepsTheFirstRunIdAndArrivalIndex() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        bashTool("p1", "npm lint"),
                        bashTool("p2", "npm test"),
                        bashTool("p3", "npm test"),
                    ),
            )

        val chips = (blocks.single() as SessionChatTimelineBlock.StepParagraph).chips
        assertEquals(listOf(PresentationBlockId.direct("p1"), PresentationBlockId.direct("p2")), chips.map { it.id })
        assertEquals(PresentationBlockId.direct("p3"), chips[1].card.blockID)
        assertEquals(listOf(0, 1), chips.map { it.arrivalIndex })
    }

    @Test
    fun chipTimeline_consecutiveThoughts_collapseIntoOneRowWithSummedDuration() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Reasoning(
                            text = "First",
                            isStreaming = false,
                            id = "th1",
                            timeStartEpochMs = 0,
                            timeEndEpochMs = 1_200,
                        ),
                        MessagePart.Reasoning(
                            text = "Second",
                            isStreaming = false,
                            id = "th2",
                            timeStartEpochMs = 1_200,
                            timeEndEpochMs = 6_000,
                        ),
                        MessagePart.Reasoning(text = "", isStreaming = false, id = "th3", timeStartEpochMs = 6_000, timeEndEpochMs = 6_500),
                        MessagePart.Text("Now", id = "t1"),
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "p1"),
                    ),
            )

        assertEquals(2, blocks.size)
        val thought = blocks[0] as SessionChatTimelineBlock.Reasoning
        // th3 is blank, so the visibility rule hides it; two settled thoughts merge.
        assertEquals(PresentationBlockId.mergedThought(listOf(PresentationBlockId.direct("th1"), "th2")), thought.blockID)
        assertEquals("First\n\nSecond", thought.text)
        assertEquals(6_000L, thought.durationMs)
        assertEquals("Now", (blocks[1] as SessionChatTimelineBlock.StepParagraph).text)
    }

    @Test
    fun chipTimeline_thoughtsAroundOneParagraph_mergeAboveIt() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Reasoning(
                            text = "First",
                            isStreaming = false,
                            id = "th1",
                            timeStartEpochMs = 0,
                            timeEndEpochMs = 1_000,
                        ),
                        MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "r1", input = json("filePath" to "a.kt")),
                        MessagePart.Reasoning(
                            text = "Second",
                            isStreaming = false,
                            id = "th2",
                            timeStartEpochMs = 1_000,
                            timeEndEpochMs = 3_000,
                        ),
                        MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "r2", input = json("filePath" to "b.kt")),
                        MessagePart.Text("Now narration", id = "t1"),
                        MessagePart.Reasoning(
                            text = "Third",
                            isStreaming = false,
                            id = "th3",
                            timeStartEpochMs = 3_000,
                            timeEndEpochMs = 4_000,
                        ),
                        MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "r3", input = json("filePath" to "c.kt")),
                    ),
            )

        // Rows: one merged thought for the tool-only stretch, its paragraph,
        // then the thought that followed the narration and the final paragraph.
        assertEquals(4, blocks.size)
        assertEquals(3_000L, (blocks[0] as SessionChatTimelineBlock.Reasoning).durationMs)
        val first = blocks[1] as SessionChatTimelineBlock.StepParagraph
        assertEquals(null, first.text)
        assertEquals("Explored \u00b7 2 reads", first.exploreSummary?.label)
        assertTrue(first.chips.isEmpty())
        assertEquals(PresentationBlockId.direct("th3"), blocks[2].blockID)
        val second = blocks[3] as SessionChatTimelineBlock.StepParagraph
        assertEquals("Now narration", second.text)
        assertEquals(listOf("c.kt"), second.chips.map { it.label })
    }

    @Test
    fun chipTimeline_thoughtAfterPendingNarration_collapsesIntoOneRow() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Reasoning(
                            text = "First",
                            isStreaming = false,
                            id = "th1",
                            timeStartEpochMs = 0,
                            timeEndEpochMs = 1_000,
                        ),
                        MessagePart.Text("Narration", id = "t1"),
                        MessagePart.Reasoning(
                            text = "Second",
                            isStreaming = false,
                            id = "th2",
                            timeStartEpochMs = 1_000,
                            timeEndEpochMs = 2_000,
                        ),
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            id = "r1",
                            input = json("filePath" to "a.kt"),
                        ),
                    ),
            )

        assertEquals(2, blocks.size)
        val thought = blocks[0] as SessionChatTimelineBlock.Reasoning
        assertEquals(PresentationBlockId.mergedThought(listOf(PresentationBlockId.direct("th1"), "th2")), thought.blockID)
        assertEquals("First\n\nSecond", thought.text)
        assertEquals(2_000L, thought.durationMs)
        val paragraph = blocks[1] as SessionChatTimelineBlock.StepParagraph
        assertEquals("Narration", paragraph.text)
        assertEquals(listOf("a.kt"), paragraph.chips.map { it.label })
    }

    @Test
    fun chipTimeline_consecutiveStepThoughts_collapseUntilAParagraphFlushes() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Reasoning(
                            text = "First",
                            isStreaming = false,
                            id = "th1",
                            timeStartEpochMs = 0,
                            timeEndEpochMs = 1_000,
                        ),
                        MessagePart.Text("Step one", id = "t1"),
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            id = "r1",
                            input = json("filePath" to "a.kt"),
                        ),
                        MessagePart.Reasoning(
                            text = "Second",
                            isStreaming = false,
                            id = "th2",
                            timeStartEpochMs = 1_000,
                            timeEndEpochMs = 2_500,
                        ),
                        MessagePart.Text("Step two", id = "t2"),
                        MessagePart.Tool(
                            toolName = "read",
                            status = ToolStatus.Completed,
                            id = "r2",
                            input = json("filePath" to "b.kt"),
                        ),
                        MessagePart.Reasoning(
                            text = "Third",
                            isStreaming = false,
                            id = "th3",
                            timeStartEpochMs = 2_500,
                            timeEndEpochMs = 3_000,
                        ),
                        MessagePart.Text("Step three", id = "t3"),
                    ),
            )

        assertEquals(5, blocks.size)
        val thought = blocks[0] as SessionChatTimelineBlock.Reasoning
        assertEquals("First\n\nSecond", thought.text)
        assertEquals(2_500L, thought.durationMs)
        val first = blocks[1] as SessionChatTimelineBlock.StepParagraph
        assertEquals("Step one", first.text)
        assertEquals(listOf("a.kt"), first.chips.map { it.label })
        val laterThought = blocks[2] as SessionChatTimelineBlock.Reasoning
        assertEquals("Third", laterThought.text)
        assertEquals(500L, laterThought.durationMs)
        val second = blocks[3] as SessionChatTimelineBlock.StepParagraph
        assertEquals("Step two", second.text)
        assertEquals(listOf("b.kt"), second.chips.map { it.label })
        val third = blocks[4] as SessionChatTimelineBlock.StepParagraph
        assertEquals("Step three", third.text)
        assertEquals(emptyList<String>(), third.chips.map { it.label })
    }

    @Test
    fun streamingStepParagraphBlockId_marksOnlyTheLatestNarration() {
        val earlier =
            SessionChatTimelineBlock.StepParagraph(
                blockID = "earlier",
                text = "Done",
                chips = emptyList(),
                errorLines = emptyList(),
            )
        val later =
            SessionChatTimelineBlock.StepParagraph(
                blockID = "later",
                text = "Live",
                chips = emptyList(),
                errorLines = emptyList(),
            )

        assertEquals(
            "later",
            SessionChatTimelineBlocks.streamingStepParagraphBlockId(listOf(earlier, later), messageIsStreaming = true),
        )
        assertNull(
            SessionChatTimelineBlocks.streamingStepParagraphBlockId(listOf(earlier, later), messageIsStreaming = false),
        )
    }

    @Test
    fun build_chipTimelineOff_consecutiveThoughts_stayAsSeparateRows() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Reasoning(text = "First", isStreaming = false, id = "th1"),
                        MessagePart.Reasoning(text = "Second", isStreaming = false, id = "th2"),
                    ),
            )

        assertEquals(2, blocks.size)
    }

    @Test
    fun chipTimeline_attachmentAndReasoning_emittedSeparatelyWithoutClosingParagraph() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Reasoning(
                            text = "thinking",
                            isStreaming = false,
                            id = "r1",
                            timeStartEpochMs = 1,
                            timeEndEpochMs = 2,
                        ),
                        MessagePart.Text("Intro", id = "t1"),
                        MessagePart.File(
                            id = "f1",
                            filename = "notes.txt",
                            mime = "text/plain",
                            kind = AttachmentKind.Text,
                            dataUrl = "data:text/plain;base64,aGk=",
                        ),
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "p1"),
                    ),
            )

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is SessionChatTimelineBlock.Reasoning)
        assertTrue(blocks[1] is SessionChatTimelineBlock.Attachment)
        val paragraph = blocks[2] as SessionChatTimelineBlock.StepParagraph
        assertEquals("Intro", paragraph.text)
        assertEquals(1, paragraph.chips.size)
    }

    @Test
    fun chipTimeline_hiddenToolsAndBlankTextSkipped() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts =
                    listOf(
                        MessagePart.Tool(toolName = "todowrite", status = ToolStatus.Completed, id = "p1"),
                        MessagePart.Text("   ", id = "t1"),
                        MessagePart.Text("hidden", id = "t2", isIgnored = true),
                        MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "p2"),
                    ),
            )

        val paragraph = blocks.single() as SessionChatTimelineBlock.StepParagraph
        assertNull(paragraph.text)
        assertEquals(PresentationBlockId.stepParagraph(listOf("p2")), paragraph.blockID)
    }

    @Test
    fun chipTimeline_onlyHiddenParts_emptyBlocks() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = true,
                parts = listOf(MessagePart.Tool(toolName = "todowrite", status = ToolStatus.Completed, id = "p1")),
            )
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun chipTimeline_missingPartIds_usesFallbackText() {
        val blocks =
            SessionChatTimelineBlocks.buildOrFallback(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Text("Hello", id = "t1"),
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = null),
                    ),
                messageId = "msg-1",
                fallbackText = "Hello",
                chipTimeline = true,
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
    fun chipTimeline_userRole_unchanged() {
        val parts =
            listOf(
                MessagePart.Text("Hi", id = "t1"),
                MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "p1"),
            )
        assertEquals(
            buildBlocks(MessageRole.User, parts),
            buildBlocks(MessageRole.User, parts, chipTimeline = true),
        )
    }

    @Test
    fun chipTimeline_off_keepsTextAndToolCards() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                chipTimeline = false,
                parts =
                    listOf(
                        MessagePart.Text("Hi", id = "t1"),
                        MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = "p1"),
                    ),
            )
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is SessionChatTimelineBlock.Text)
        assertTrue(blocks[1] is SessionChatTimelineBlock.ToolCard)
    }

    @Test
    fun merge_chipTimelineOn_consecutiveAssistantBodiesJoinIntoOneParagraph() {
        val bodies =
            listOf(
                textBody("a1", "Looking", chipTimeline = true),
                toolOnlyBody("a2", "bash-2", chipTimeline = true),
                toolOnlyBody("a3", "read-3", toolName = "read", chipTimeline = true),
            )
        val merged =
            SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
                bodies,
                collapseConsecutiveToolCalls = false,
                chipTimeline = true,
            )

        assertEquals(1, merged.size)
        assertEquals("a1", merged[0].message.id)
        val paragraph = merged[0].blocks.single() as SessionChatTimelineBlock.StepParagraph
        assertEquals("Looking", paragraph.text)
        assertEquals(2, paragraph.chips.size)
        assertEquals(PresentationBlockId.stepParagraph(listOf("a1-t", "bash-2", "read-3")), paragraph.blockID)
        assertEquals(3, merged[0].message.parts.size)
    }

    @Test
    fun merge_chipTimelineOn_userBodyBreaksRun() {
        val userParts = listOf(MessagePart.Text("question", id = "u-t"))
        val user =
            DisplayMessageBody(
                message = Message(id = "u1", role = MessageRole.User, text = "question", parts = userParts),
                blocks = buildBlocks(MessageRole.User, userParts),
            )
        val bodies =
            listOf(
                textBody("a1", "first", chipTimeline = true),
                user,
                textBody("a2", "second", chipTimeline = true),
                toolOnlyBody("a3", "bash-3", chipTimeline = true),
            )
        val merged =
            SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
                bodies,
                collapseConsecutiveToolCalls = false,
                chipTimeline = true,
            )

        assertEquals(3, merged.size)
        assertEquals(listOf("a1", "u1", "a2"), merged.map { it.message.id })
        val joined = merged[2].blocks.single() as SessionChatTimelineBlock.StepParagraph
        assertEquals("second", joined.text)
        assertEquals(1, joined.chips.size)
    }

    @Test
    fun project_chipMergedRun_joinsLaterParagraphText() {
        val merged =
            SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
                listOf(
                    textBody("a1", "First step", chipTimeline = true),
                    toolOnlyBody("a2", "bash-2", chipTimeline = true),
                    textBody("a3", "Second step", chipTimeline = true),
                ),
                collapseConsecutiveToolCalls = false,
                chipTimeline = true,
            )
        val frame =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = "session",
                        frameRevision = 1L,
                        sourceRevision = 0L,
                        messages = merged,
                    ),
            )

        assertEquals(1, frame.rows.size)
        assertEquals("First step\n\nSecond step", frame.rows.single().text)
        assertEquals(ContentRevision.of("First step\n\nSecond step"), frame.rows.single().contentRevision)
    }

    @Test
    fun merge_chipTimelineOn_keepsRenderedMarkdownOnJoinedParagraph() {
        val document = MarkdownDocument(sourceHash = 7, blocks = emptyList())
        val text = textBody("a1", "Looking", chipTimeline = true)
        val decorated =
            text.copy(
                blocks =
                    text.blocks.map { block ->
                        (block as SessionChatTimelineBlock.StepParagraph).copy(renderedMarkdown = document)
                    },
            )
        val merged =
            SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
                listOf(decorated, toolOnlyBody("a2", "bash-2", chipTimeline = true)),
                collapseConsecutiveToolCalls = false,
                chipTimeline = true,
            )

        val paragraph = merged.single().blocks.single() as SessionChatTimelineBlock.StepParagraph
        assertEquals(document, paragraph.renderedMarkdown)
    }

    @Test
    fun merge_chipTimelineOn_missingPartIdsLeaveRunUnmerged() {
        val partsWithoutIds = listOf(MessagePart.Tool(toolName = "bash", status = ToolStatus.Completed, id = null))
        val fallback =
            DisplayMessageBody(
                message = Message(id = "a2", role = MessageRole.Assistant, text = "", parts = partsWithoutIds),
                blocks =
                    SessionChatTimelineBlocks.buildOrFallback(
                        role = MessageRole.Assistant,
                        parts = partsWithoutIds,
                        messageId = "a2",
                        fallbackText = "raw",
                        chipTimeline = true,
                    ),
            )
        val merged =
            SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
                listOf(textBody("a1", "first", chipTimeline = true), fallback),
                collapseConsecutiveToolCalls = false,
                chipTimeline = true,
            )
        assertEquals(2, merged.size)
    }

    @Test
    fun merge_chipTimelineOff_unchanged() {
        val bodies = listOf(textBody("a1", "first"), toolOnlyBody("a2", "bash-2"))
        val merged =
            SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
                bodies,
                collapseConsecutiveToolCalls = false,
                chipTimeline = false,
            )
        assertEquals(bodies, merged)
    }

    @Test
    fun chipTimeline_skillChipNamesFromServerIdAndMetadata() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "skill",
                            status = ToolStatus.Running,
                            id = "s1",
                            input = json("id" to "feature"),
                        ),
                        MessagePart.Tool(
                            toolName = "skill",
                            status = ToolStatus.Completed,
                            id = "s2",
                            input = json("id" to "linear"),
                            metadata =
                                json(
                                    "name" to "linear-project-tracking",
                                    "directory" to "/repo/.agents/skills/linear-project-tracking",
                                ),
                        ),
                    ),
                chipTimeline = true,
            )

        val paragraph = blocks.single() as SessionChatTimelineBlock.StepParagraph
        assertEquals(listOf("/feature", "/linear-project-tracking"), paragraph.chips.map { it.label })
        assertEquals(listOf(ToolChipKind.Skill, ToolChipKind.Skill), paragraph.chips.map { it.kind })
        assertNull(paragraph.chips[0].brief)
        assertEquals("/repo/.agents/skills/linear-project-tracking", paragraph.chips[1].brief)
        assertEquals("linear-project-tracking", paragraph.chips[1].card.subtitle)
    }

    @Test
    fun skillUses_collectsAcrossParagraphsInFirstUseOrder() {
        val blocks =
            buildBlocks(
                role = MessageRole.Assistant,
                parts =
                    listOf(
                        MessagePart.Text(id = "t1", text = "Loading."),
                        MessagePart.Tool(
                            toolName = "skill",
                            status = ToolStatus.Completed,
                            id = "s1",
                            input = json("id" to "feature"),
                            metadata = json("name" to "feature", "directory" to "/repo/.agents/skills/feature"),
                        ),
                        MessagePart.Text(id = "t2", text = "Again."),
                        MessagePart.Tool(
                            toolName = "skill",
                            status = ToolStatus.Completed,
                            id = "s2",
                            input = json("id" to "linear"),
                        ),
                        MessagePart.Tool(
                            toolName = "skill",
                            status = ToolStatus.Error,
                            error = "denied",
                            id = "s3",
                            input = json("id" to "feature"),
                        ),
                        MessagePart.Tool(
                            toolName = "bash",
                            status = ToolStatus.Completed,
                            id = "b1",
                            input = json("command" to "ls"),
                        ),
                    ),
                chipTimeline = true,
            )

        val uses = SessionChatTimelineBlocks.skillUses(blocks)
        assertEquals(listOf("/feature", "/linear"), uses.map { it.label })
        assertEquals(PresentationBlockId.direct("s1"), uses[0].id)
        assertEquals(ToolStatus.Error, uses[0].status)
        assertEquals("/repo/.agents/skills/feature", uses[0].directory)
        assertEquals(ToolStatus.Completed, uses[1].status)
        assertNull(uses[1].directory)
        assertTrue(
            SessionChatTimelineBlocks
                .skillUses(
                    buildBlocks(
                        role = MessageRole.Assistant,
                        parts = listOf(MessagePart.Text(id = "t", text = "No tools.")),
                        chipTimeline = true,
                    ),
                ).isEmpty(),
        )
    }

    private fun buildBlocks(
        role: MessageRole,
        parts: List<MessagePart>,
        collapseConsecutiveToolCalls: Boolean = false,
        chipTimeline: Boolean = false,
    ): List<SessionChatTimelineBlock> =
        when (
            val result =
                SessionChatTimelineBlocks.build(role, parts, collapseConsecutiveToolCalls, chipTimeline)
        ) {
            is SessionChatTimelineBlocksResult.Ok -> result.blocks
            SessionChatTimelineBlocksResult.MissingPartIds -> error("expected Ok, got MissingPartIds")
        }

    private fun toolOnlyBody(
        id: String,
        toolId: String,
        toolName: String = "bash",
        status: ToolStatus = ToolStatus.Completed,
        chipTimeline: Boolean = false,
    ): DisplayMessageBody {
        val parts = listOf(MessagePart.Tool(toolName = toolName, status = status, id = toolId))
        return DisplayMessageBody(
            message = Message(id = id, role = MessageRole.Assistant, text = "", parts = parts),
            blocks =
                buildBlocks(
                    MessageRole.Assistant,
                    parts,
                    collapseConsecutiveToolCalls = true,
                    chipTimeline = chipTimeline,
                ),
        )
    }

    private fun textBody(
        id: String,
        text: String,
        chipTimeline: Boolean = false,
    ): DisplayMessageBody {
        val parts = listOf(MessagePart.Text(text, id = "$id-t"))
        return DisplayMessageBody(
            message = Message(id = id, role = MessageRole.Assistant, text = text, parts = parts),
            blocks = buildBlocks(MessageRole.Assistant, parts, chipTimeline = chipTimeline),
        )
    }

    private fun bashTool(
        id: String,
        command: String,
        status: ToolStatus = ToolStatus.Completed,
    ): MessagePart.Tool =
        MessagePart.Tool(
            toolName = "bash",
            status = status,
            id = id,
            input = json("command" to command),
            error = if (status == ToolStatus.Error) "exit code 1" else null,
        )

    private fun json(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (key, value) -> key to JsonPrimitive(value) })
}
