package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.ui.components.OpenCodeAttachmentVariant
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
                    metadataLabel = "build · deepseek-v4-flash-free · high · 10:21 AM",
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
        assertEquals(
            "build · deepseek-v4-flash-free · high · 10:21 AM",
            reconciled.rows.single { it.id == "msg-user-1" }.metadataLabel,
        )
        assertEquals(setOf("msg-user-1"), reconciled.insertedRowIDs)
        assertEquals(setOf("optimistic-1"), reconciled.removedRowIDs)
    }

    @Test
    fun orderedDisplayMessages_keepsStoreUserBeforeAssistantWhenUserTimestampIsLater() {
        val ordered =
            TimelineDisplayOrder.ordered(
                listOf(
                    DisplayMessage(
                        Message(id = "msg-photo", role = MessageRole.User, text = "What is it", sentAtEpochMs = 3_000L),
                    ),
                    DisplayMessage(
                        Message(id = "msg-asst", role = MessageRole.Assistant, text = "A photo", sentAtEpochMs = 1_000L),
                    ),
                    DisplayMessage(
                        Message(id = "msg-hey", role = MessageRole.User, text = "hey", sentAtEpochMs = 2_000L),
                    ),
                ),
            )
        assertEquals(listOf("msg-photo", "msg-asst", "msg-hey"), ordered.map { it.id })
    }

    @Test
    fun project_optimisticFile_reconcilesWhenLocalDataUrlDiffersFromServer() {
        val pendingAttachment =
            SessionChatAttachmentItem(
                id = "local-file",
                filename = "IMG_1234.JPG",
                variant = OpenCodeAttachmentVariant.Image,
                signature = "local-data-url",
            )
        val serverAttachment =
            SessionChatAttachmentItem(
                id = "server-file",
                filename = "IMG_1234.JPG",
                variant = OpenCodeAttachmentVariant.Image,
                signature = "https://example.test/photo.jpg",
            )
        val pending =
            TimelineProjector.rowFromMessageItem(
                SessionChatMessageItem(
                    id = "optimistic-photo",
                    role = MessageRole.User,
                    text = "What is it",
                    metadataLabel = "build · 1:21 PM",
                    isPending = true,
                    blocks =
                        listOf(
                            SessionChatTimelineBlock.Text(
                                blockID = PresentationBlockId.messageFallback("optimistic-photo"),
                                text = "What is it",
                            ),
                            SessionChatTimelineBlock.Attachment(
                                blockID = PresentationBlockId.direct("local-file"),
                                attachment = pendingAttachment,
                            ),
                        ),
                    attachments = listOf(pendingAttachment),
                ),
            )
        val withOptimistic =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = "session",
                        frameRevision = 1L,
                        sourceRevision = 0L,
                        messages = emptyList(),
                    ),
                optimisticInputs = OptimisticInputs(pendingRows = listOf(pending)),
            )
        assertEquals(listOf("optimistic-photo"), withOptimistic.rows.map { it.id })
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
                                            id = "msg-photo",
                                            role = MessageRole.User,
                                            text = "What is it",
                                            parts = listOf(MessagePart.Text("What is it", id = "u1")),
                                        ),
                                    blocks =
                                        listOf(
                                            SessionChatTimelineBlock.Text(
                                                blockID = PresentationBlockId.direct("u1"),
                                                text = "What is it",
                                            ),
                                            SessionChatTimelineBlock.Attachment(
                                                blockID = PresentationBlockId.direct("server-file"),
                                                attachment = serverAttachment,
                                            ),
                                        ),
                                ),
                                DisplayMessageBody(
                                    message =
                                        Message(
                                            id = "msg-asst",
                                            role = MessageRole.Assistant,
                                            text = "Working",
                                            parts = listOf(MessagePart.Text("Working", id = "a1", isStreaming = true)),
                                        ),
                                    blocks =
                                        listOf(
                                            SessionChatTimelineBlock.Text(
                                                blockID = PresentationBlockId.direct("a1"),
                                                text = "Working",
                                            ),
                                        ),
                                ),
                            ),
                    ),
                optimisticInputs = OptimisticInputs(pendingRows = listOf(pending)),
                previous = withOptimistic,
            )
        assertFalse(reconciled.rows.any { it.isPending })
        assertEquals(setOf("optimistic-photo"), reconciled.removedRowIDs)
    }

    @Test
    fun project_unmatchedPendingUser_insertsBeforeStreamingAssistant() {
        val pending =
            TimelineProjector.rowFromMessageItem(
                SessionChatMessageItem(
                    id = "optimistic-photo",
                    role = MessageRole.User,
                    text = "What is it",
                    metadataLabel = "",
                    isPending = true,
                    blocks =
                        listOf(
                            SessionChatTimelineBlock.Text(
                                blockID = PresentationBlockId.messageFallback("optimistic-photo"),
                                text = "What is it",
                            ),
                        ),
                ),
            )
        val merged =
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
                                            id = "msg-asst",
                                            role = MessageRole.Assistant,
                                            text = "Working",
                                            parts = listOf(MessagePart.Text("Working", id = "a1", isStreaming = true)),
                                        ),
                                    blocks =
                                        listOf(
                                            SessionChatTimelineBlock.Text(
                                                blockID = PresentationBlockId.direct("a1"),
                                                text = "Working",
                                            ),
                                        ),
                                ),
                                DisplayMessageBody(
                                    message =
                                        Message(
                                            id = "msg-hey",
                                            role = MessageRole.User,
                                            text = "hey",
                                            parts = listOf(MessagePart.Text("hey", id = "u-hey")),
                                        ),
                                    blocks =
                                        listOf(
                                            SessionChatTimelineBlock.Text(
                                                blockID = PresentationBlockId.direct("u-hey"),
                                                text = "hey",
                                            ),
                                        ),
                                ),
                            ),
                    ),
                optimisticInputs = OptimisticInputs(pendingRows = listOf(pending)),
            )
        assertEquals(listOf("optimistic-photo", "msg-asst", "msg-hey"), merged.rows.map { it.id })
    }

    @Test
    fun project_metadataArrivingOnSameUserRow_updatesLabelAndLayout() {
        val sparse =
            Message(
                id = "msg-user",
                role = MessageRole.User,
                text = "Hello",
                parts = listOf(MessagePart.Text("Hello", id = "u1")),
            )
        val first =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = "session",
                        frameRevision = 1L,
                        sourceRevision = 0L,
                        messages =
                            listOf(
                                DisplayMessageBody(
                                    message = sparse,
                                    blocks =
                                        listOf(
                                            SessionChatTimelineBlock.Text(
                                                blockID = PresentationBlockId.direct("u1"),
                                                text = "Hello",
                                            ),
                                        ),
                                ),
                            ),
                    ),
            )
        assertEquals("", first.rows.single().metadataLabel)

        val enriched =
            Message(
                id = "msg-user",
                role = MessageRole.User,
                text = "Hello",
                parts = listOf(MessagePart.Text("Hello", id = "u1")),
                model = "deepseek-v4-flash-free",
                variant = "high",
                mode = "build",
                sentAtEpochMs = 1_710_000_000_000L,
            )
        val second =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = "session",
                        frameRevision = 2L,
                        sourceRevision = 0L,
                        messages =
                            listOf(
                                DisplayMessageBody(
                                    message = enriched,
                                    blocks =
                                        listOf(
                                            SessionChatTimelineBlock.Text(
                                                blockID = PresentationBlockId.direct("u1"),
                                                text = "Hello",
                                            ),
                                        ),
                                ),
                            ),
                    ),
                previous = first,
            )
        val expected = TimelineProjector.metadataLabel(enriched)
        assertTrue(expected.isNotEmpty())
        assertEquals(expected, second.rows.single().metadataLabel)
        assertEquals(setOf("msg-user"), second.changedRowIDs)
        assertEquals(setOf("msg-user"), second.layoutChangedRowIDs)
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
    fun project_streamingTextGrowth_marksGrewBottomNotLayoutChanged() {
        val first =
            projectAssistant(
                parts = listOf(MessagePart.Text("Hi", id = "prt-1", isStreaming = true)),
                frameRevision = 1L,
            )
        val second =
            projectAssistant(
                parts = listOf(MessagePart.Text("Hi there", id = "prt-1", isStreaming = true)),
                frameRevision = 2L,
                previous = first,
            )

        assertEquals(setOf("msg-1"), second.changedRowIDs)
        assertTrue(second.layoutChangedRowIDs.isEmpty())
        assertTrue(second.layoutAffectingRowIDs().isEmpty())
        assertEquals(0L, second.layoutAffectingRevision())
        assertEquals(setOf("msg-1"), second.grewBottomRowIDs)
        assertEquals(2L, second.growBottomRevision())
        assertEquals(first.rows.single().layoutRevision, second.rows.single().layoutRevision)
        assertTrue(second.rows.single().isStreaming)
    }

    @Test
    fun project_streamingEnd_marksLayoutChanged() {
        val streaming =
            projectAssistant(
                parts = listOf(MessagePart.Text("Done", id = "prt-1", isStreaming = true)),
                frameRevision = 1L,
            )
        val finalized =
            projectAssistant(
                parts = listOf(MessagePart.Text("Done", id = "prt-1", isStreaming = false)),
                frameRevision = 2L,
                previous = streaming,
            )

        assertEquals(setOf("msg-1"), finalized.changedRowIDs)
        assertEquals(setOf("msg-1"), finalized.layoutChangedRowIDs)
        assertTrue(finalized.decorateRowIDs.isEmpty())
        assertTrue(finalized.grewBottomRowIDs.isEmpty())
        assertEquals(0L, finalized.growBottomRevision())
        assertTrue(finalized.rows.single().layoutRevision > streaming.rows.single().layoutRevision)
        val apply = TimelineApply.classify(finalized)
        assertTrue(apply.kinds.contains(TimelineApplyKind.Structure))
        assertFalse(apply.kinds.contains(TimelineApplyKind.Decorate))
    }

    @Test
    fun project_plainToDecorated_marksDecorateNotStructureAlone() {
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

        assertEquals(setOf("msg-1"), decorated.layoutChangedRowIDs)
        assertEquals(setOf("msg-1"), decorated.decorateRowIDs)
        val apply = TimelineApply.classify(decorated)
        assertTrue(apply.kinds.contains(TimelineApplyKind.Decorate))
        assertFalse(apply.kinds.contains(TimelineApplyKind.Structure))
        assertEquals(setOf("msg-1"), decorated.layoutAffectingRowIDs())
    }

    @Test
    fun classify_requiredAndGrowBottom_carryMultipleKinds() {
        val streaming =
            projectAssistant(
                parts = listOf(MessagePart.Text("Hi", id = "prt-1", isStreaming = true)),
                frameRevision = 1L,
            )
        val grew =
            projectAssistant(
                parts = listOf(MessagePart.Text("Hi there", id = "prt-1", isStreaming = true)),
                frameRevision = 2L,
                previous = streaming,
            ).withRequired(true)
        val apply = TimelineApply.classify(grew)
        assertEquals(
            setOf(TimelineApplyKind.Required, TimelineApplyKind.GrowBottom),
            apply.kinds,
        )
        assertEquals(0L, apply.layoutAffectingRevision)
        assertEquals(2L, apply.growBottomRevision)
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

    @Test
    fun mergeMessageItems_reusesUnchangedHistoryWhileStreamingBottomRebuilds() {
        val history =
            SessionChatMessageItem(
                id = "history-1",
                role = MessageRole.User,
                text = "Earlier",
                metadataLabel = "user",
            )
        val priorStreaming =
            SessionChatMessageItem(
                id = "stream-1",
                role = MessageRole.Assistant,
                text = "Hi",
                metadataLabel = "assistant",
                isStreaming = true,
            )
        val frame =
            TimelineFrame(
                frameRevision = 2L,
                rows =
                    listOf(
                        TimelineRow(
                            id = "history-1",
                            role = MessageRole.User,
                            text = "Earlier",
                            metadataLabel = "user",
                            contentRevision = ContentRevision.of("Earlier"),
                            layoutRevision = 1L,
                        ),
                        TimelineRow(
                            id = "stream-1",
                            role = MessageRole.Assistant,
                            text = "Hi there",
                            metadataLabel = "assistant",
                            isStreaming = true,
                            contentRevision = ContentRevision.of("Hi there"),
                            layoutRevision = 1L,
                        ),
                    ),
                insertedRowIDs = emptySet(),
                changedRowIDs = setOf("stream-1"),
                removedRowIDs = emptySet(),
                layoutChangedRowIDs = emptySet(),
                grewBottomRowIDs = setOf("stream-1"),
            )

        val merged =
            TimelineProjector.mergeMessageItems(
                frame = frame,
                previousById = mapOf(history.id to history, priorStreaming.id to priorStreaming),
            )

        assertEquals(2, merged.size)
        assertSame(history, merged[0])
        assertEquals("Hi there", merged[1].text)
        assertTrue(merged[1] !== priorStreaming)
    }

    @Test
    fun mergeMessageItems_rebuildsInsertedAndPendingRows() {
        val existing =
            SessionChatMessageItem(
                id = "msg-1",
                role = MessageRole.User,
                text = "Hello",
                metadataLabel = "user",
            )
        val previousPending =
            SessionChatMessageItem(
                id = "pending-1",
                role = MessageRole.User,
                text = "Sending",
                metadataLabel = "user",
                isPending = true,
            )
        val frame =
            TimelineFrame(
                frameRevision = 3L,
                rows =
                    listOf(
                        TimelineRow(
                            id = "msg-1",
                            role = MessageRole.User,
                            text = "Hello",
                            metadataLabel = "user",
                            contentRevision = ContentRevision.of("Hello"),
                            layoutRevision = 1L,
                        ),
                        TimelineRow(
                            id = "pending-1",
                            role = MessageRole.User,
                            text = "Sending",
                            metadataLabel = "user",
                            isPending = true,
                            contentRevision = ContentRevision.of("Sending"),
                            layoutRevision = 1L,
                        ),
                        TimelineRow(
                            id = "msg-2",
                            role = MessageRole.Assistant,
                            text = "New",
                            metadataLabel = "assistant",
                            contentRevision = ContentRevision.of("New"),
                            layoutRevision = 1L,
                        ),
                    ),
                insertedRowIDs = setOf("msg-2"),
                changedRowIDs = emptySet(),
                removedRowIDs = emptySet(),
                layoutChangedRowIDs = emptySet(),
            )

        val merged =
            TimelineProjector.mergeMessageItems(
                frame = frame,
                previousById =
                    mapOf(
                        existing.id to existing,
                        previousPending.id to previousPending,
                    ),
            )

        assertSame(existing, merged[0])
        assertTrue(merged[1].isPending)
        assertTrue(merged[1] !== previousPending)
        assertEquals("pending-1", merged[1].id)
        assertEquals("msg-2", merged[2].id)
        assertEquals("New", merged[2].text)
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
