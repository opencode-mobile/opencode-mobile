package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.markdown.MarkdownDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineProjectorTest {
    @Test
    fun project_textToolText_blockIdsStableAcrossReproject() {
        val parts =
            listOf(
                MessagePart.Text("Before", id = "prt-1"),
                MessagePart.Tool(
                    toolName = "bash",
                    status = ToolStatus.Completed,
                    id = "prt-2",
                    input = null,
                    output = "ok",
                ),
                MessagePart.Text("After", id = "prt-3"),
            )
        val first = projectAssistant(parts, frameRevision = 1L)
        val second = projectAssistant(parts, frameRevision = 2L, previous = first)

        val firstBlocks = first.rows.single().blocks
        val secondBlocks = second.rows.single().blocks
        assertEquals(3, firstBlocks.size)
        assertEquals(
            listOf(
                PresentationBlockId.direct("prt-1"),
                PresentationBlockId.direct("prt-2"),
                PresentationBlockId.direct("prt-3"),
            ),
            firstBlocks.map { it.blockID },
        )
        assertEquals(firstBlocks.map { it.blockID }, secondBlocks.map { it.blockID })
        assertSame(first.rows.single(), second.rows.single())
        assertTrue(second.changedRowIDs.isEmpty())
        assertTrue(second.layoutChangedRowIDs.isEmpty())
    }

    @Test
    fun project_toolGroupMembershipChange_newBlockIdAndChangedRow() {
        val groupedParts =
            listOf(
                MessagePart.Tool(toolName = "read", status = ToolStatus.Completed, id = "prt-a"),
                MessagePart.Tool(toolName = "grep", status = ToolStatus.Completed, id = "prt-b"),
            )
        val first = projectAssistant(groupedParts, frameRevision = 1L)
        val firstGroup = first.rows.single().blocks.single() as SessionChatTimelineBlock.ToolGroup
        assertEquals(PresentationBlockId.toolGroup(listOf("prt-a", "prt-b")), firstGroup.blockID)

        val expandedParts =
            groupedParts +
                MessagePart.Tool(toolName = "glob", status = ToolStatus.Completed, id = "prt-c")
        val second = projectAssistant(expandedParts, frameRevision = 2L, previous = first)
        val secondGroup = second.rows.single().blocks.single() as SessionChatTimelineBlock.ToolGroup

        assertEquals(
            PresentationBlockId.toolGroup(listOf("prt-a", "prt-b", "prt-c")),
            secondGroup.blockID,
        )
        assertNotEquals(firstGroup.blockID, secondGroup.blockID)
        assertEquals(setOf("msg-1"), second.changedRowIDs)
        assertEquals(setOf("msg-1"), second.layoutChangedRowIDs)
        assertTrue(second.rows.single().layoutRevision > first.rows.single().layoutRevision)
    }

    @Test
    fun project_optimisticThenAuthoritative_reconciliationSignatures() {
        val pending =
            TimelineProjector.rowFromMessageItem(
                SessionChatMessageItem(
                    id = "optimistic-1",
                    role = MessageRole.User,
                    text = "Hello",
                    metadataLabel = "",
                    isPending = true,
                    blocks =
                        listOf(
                            SessionChatTimelineBlock.Text(
                                blockID = PresentationBlockId.messageFallback("optimistic-1"),
                                text = "Hello",
                            ),
                        ),
                ),
            )
        val baselineOther =
            TimelineProjector.rowFromMessageItem(
                SessionChatMessageItem(
                    id = "msg-user-0",
                    role = MessageRole.User,
                    text = "Earlier",
                    metadataLabel = "",
                    blocks =
                        listOf(
                            SessionChatTimelineBlock.Text(
                                blockID = PresentationBlockId.direct("prt-0"),
                                text = "Earlier",
                            ),
                        ),
                ),
            )
        val emptyAuthoritative =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = "session",
                        frameRevision = 1L,
                        sourceRevision = 0L,
                        messages =
                            listOf(
                                DisplayMessageBody(
                                    message =
                                        Message(
                                            id = "msg-user-0",
                                            role = MessageRole.User,
                                            text = "Earlier",
                                            parts = listOf(MessagePart.Text("Earlier", id = "prt-0")),
                                        ),
                                    blocks =
                                        listOf(
                                            SessionChatTimelineBlock.Text(
                                                blockID = PresentationBlockId.direct("prt-0"),
                                                text = "Earlier",
                                            ),
                                        ),
                                ),
                            ),
                    ),
                optimisticInputs =
                    OptimisticInputs(
                        pendingRows = listOf(pending),
                        baselineSignatures =
                            mapOf(
                                baselineOther.id to TimelineProjector.reconciliationSignature(baselineOther),
                            ),
                    ),
            )
        assertEquals(listOf("msg-user-0", "optimistic-1"), emptyAuthoritative.rows.map { it.id })

        val authoritativeUser =
            Message(
                id = "msg-user-1",
                role = MessageRole.User,
                text = "Hello",
                parts = listOf(MessagePart.Text("Hello", id = "prt-user-1")),
            )
        val reconciled =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = "session",
                        frameRevision = 2L,
                        sourceRevision = 0L,
                        messages =
                            listOf(
                                DisplayMessageBody(
                                    message =
                                        Message(
                                            id = "msg-user-0",
                                            role = MessageRole.User,
                                            text = "Earlier",
                                            parts = listOf(MessagePart.Text("Earlier", id = "prt-0")),
                                        ),
                                    blocks =
                                        listOf(
                                            SessionChatTimelineBlock.Text(
                                                blockID = PresentationBlockId.direct("prt-0"),
                                                text = "Earlier",
                                            ),
                                        ),
                                ),
                                DisplayMessageBody(
                                    message = authoritativeUser,
                                    blocks =
                                        listOf(
                                            SessionChatTimelineBlock.Text(
                                                blockID = PresentationBlockId.direct("prt-user-1"),
                                                text = "Hello",
                                            ),
                                        ),
                                ),
                            ),
                    ),
                optimisticInputs =
                    OptimisticInputs(
                        pendingRows = listOf(pending),
                        baselineSignatures =
                            mapOf(
                                baselineOther.id to TimelineProjector.reconciliationSignature(baselineOther),
                            ),
                    ),
                previous = emptyAuthoritative,
            )

        assertEquals(listOf("msg-user-0", "msg-user-1"), reconciled.rows.map { it.id })
        assertFalse(reconciled.rows.any { it.id == "optimistic-1" })
        assertEquals(setOf("msg-user-1"), reconciled.insertedRowIDs)
        assertEquals(setOf("optimistic-1"), reconciled.removedRowIDs)
    }

    @Test
    fun project_unchangedRow_retainsIdentityAndLayoutRevision() {
        val parts = listOf(MessagePart.Text("Stable", id = "prt-1"))
        val first = projectAssistant(parts, frameRevision = 1L)
        val second = projectAssistant(parts, frameRevision = 2L, previous = first)

        assertSame(first.rows.single(), second.rows.single())
        assertEquals(0L, second.rows.single().layoutRevision)
        assertTrue(second.changedRowIDs.isEmpty())
        assertTrue(second.layoutChangedRowIDs.isEmpty())
        assertEquals(first.rows.single().contentRevision, second.rows.single().contentRevision)
    }

    @Test
    fun project_plainToDecorated_advancesLayoutRevisionAndLayoutChangedRowIds() {
        val plainBlocks =
            listOf(
                SessionChatTimelineBlock.Text(
                    blockID = PresentationBlockId.direct("prt-1"),
                    text = "Hello",
                ),
            )
        val message =
            Message(
                id = "msg-1",
                role = MessageRole.Assistant,
                text = "Hello",
                parts = listOf(MessagePart.Text("Hello", id = "prt-1")),
            )
        val plain =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = "session",
                        frameRevision = 1L,
                        sourceRevision = 0L,
                        messages = listOf(DisplayMessageBody(message, plainBlocks)),
                    ),
            )
        val decoratedBlocks =
            listOf(
                SessionChatTimelineBlock.Text(
                    blockID = PresentationBlockId.direct("prt-1"),
                    text = "Hello",
                    renderedMarkdown =
                        MarkdownDocument(
                            sourceHash = ContentRevision.of("Hello").textHash,
                            blocks = emptyList(),
                        ),
                ),
            )
        val decorated =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = "session",
                        frameRevision = 2L,
                        sourceRevision = 0L,
                        messages = listOf(DisplayMessageBody(message, decoratedBlocks)),
                    ),
                previous = plain,
            )

        assertEquals(plain.rows.single().contentRevision, decorated.rows.single().contentRevision)
        assertEquals(plain.rows.single().layoutRevision + 1L, decorated.rows.single().layoutRevision)
        assertEquals(setOf("msg-1"), decorated.changedRowIDs)
        assertEquals(setOf("msg-1"), decorated.layoutChangedRowIDs)
        assertNotEquals(plain.rows.single(), decorated.rows.single())
        assertEquals(setOf("msg-1"), decorated.layoutAffectingRowIDs())
        assertEquals(2L, decorated.layoutAffectingRevision())
    }

    @Test
    fun layoutAffectingRevision_contentOnly_isZero() {
        val first =
            projectAssistant(
                parts = listOf(MessagePart.Text("Hello", id = "prt-1")),
                frameRevision = 1L,
            )
        // Same length, different characters → content change without layoutRevision bump.
        val second =
            projectAssistant(
                parts = listOf(MessagePart.Text("Hallo", id = "prt-1")),
                frameRevision = 2L,
                previous = first,
            )
        assertEquals(setOf("msg-1"), second.changedRowIDs)
        assertTrue(second.layoutChangedRowIDs.isEmpty())
        assertTrue(second.layoutAffectingRowIDs().isEmpty())
        assertEquals(0L, second.layoutAffectingRevision())
        assertEquals(first.rows.single().layoutRevision, second.rows.single().layoutRevision)
    }

    @Test
    fun layoutAffectingRevision_insertsDriveFollowKey() {
        val first =
            projectAssistant(
                parts = listOf(MessagePart.Text("One", id = "prt-1")),
                frameRevision = 1L,
            )
        val message =
            Message(
                id = "msg-2",
                role = MessageRole.Assistant,
                text = "Two",
                parts = listOf(MessagePart.Text("Two", id = "prt-2")),
            )
        val blocks =
            when (val result = SessionChatTimelineBlocks.build(MessageRole.Assistant, message.parts)) {
                is SessionChatTimelineBlocksResult.Ok -> result.blocks
                SessionChatTimelineBlocksResult.MissingPartIds -> error("expected Ok")
            }
        val second =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = "session",
                        frameRevision = 4L,
                        sourceRevision = 0L,
                        messages =
                            listOf(
                                DisplayMessageBody(
                                    message =
                                        Message(
                                            id = "msg-1",
                                            role = MessageRole.Assistant,
                                            text = "One",
                                            parts = listOf(MessagePart.Text("One", id = "prt-1")),
                                        ),
                                    blocks =
                                        when (
                                            val result =
                                                SessionChatTimelineBlocks.build(
                                                    MessageRole.Assistant,
                                                    listOf(MessagePart.Text("One", id = "prt-1")),
                                                )
                                        ) {
                                            is SessionChatTimelineBlocksResult.Ok -> result.blocks
                                            SessionChatTimelineBlocksResult.MissingPartIds -> error("expected Ok")
                                        },
                                ),
                                DisplayMessageBody(message, blocks),
                            ),
                    ),
                previous = first,
            )
        assertEquals(setOf("msg-2"), second.insertedRowIDs)
        assertEquals(setOf("msg-2"), second.layoutAffectingRowIDs())
        assertEquals(4L, second.layoutAffectingRevision())
    }

    @Test
    fun layoutAffectingRowIDs_isInsertedUnionLayoutChanged() {
        val frame =
            TimelineFrame(
                frameRevision = 9L,
                rows = emptyList(),
                insertedRowIDs = setOf("new"),
                changedRowIDs = setOf("content-only", "taller"),
                removedRowIDs = emptySet(),
                layoutChangedRowIDs = setOf("taller"),
            )
        assertEquals(setOf("new", "taller"), frame.layoutAffectingRowIDs())
        assertEquals(9L, frame.layoutAffectingRevision())
        assertFalse(frame.layoutAffectingRowIDs().contains("content-only"))
    }

    private fun projectAssistant(
        parts: List<MessagePart>,
        frameRevision: Long,
        previous: TimelineFrame? = null,
    ): TimelineFrame {
        val blocks =
            when (val result = SessionChatTimelineBlocks.build(MessageRole.Assistant, parts)) {
                is SessionChatTimelineBlocksResult.Ok -> result.blocks
                SessionChatTimelineBlocksResult.MissingPartIds -> error("expected Ok")
            }
        val text = parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }
        val message =
            Message(
                id = "msg-1",
                role = MessageRole.Assistant,
                text = text,
                parts = parts,
            )
        return TimelineProjector.project(
            frame =
                DisplayFrame(
                    sessionKey = "session",
                    frameRevision = frameRevision,
                    sourceRevision = 0L,
                    messages = listOf(DisplayMessageBody(message, blocks)),
                ),
            previous = previous,
        )
    }
}
