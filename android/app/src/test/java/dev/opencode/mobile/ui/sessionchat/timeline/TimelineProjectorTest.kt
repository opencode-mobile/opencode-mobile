package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.command.ServerCommand
import dev.opencode.mobile.domain.command.ServerCommandSource
import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.PromptSkill
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
    fun metadataLabel_titleCasesModeModelAndVariant() {
        val label =
            TimelineProjector.metadataLabel(
                Message(
                    id = "msg-user",
                    role = MessageRole.User,
                    text = "Hello",
                    model = "grok-4.6",
                    variant = "high",
                    mode = "build",
                ),
            )
        assertEquals("Build · Grok 4.6 · High", label)
    }

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
        val firstGroup =
            first.rows
                .single()
                .blocks
                .single() as SessionChatTimelineBlock.ToolGroup
        assertEquals(PresentationBlockId.toolGroup(listOf("prt-a", "prt-b")), firstGroup.blockID)

        val expandedParts =
            groupedParts +
                MessagePart.Tool(toolName = "glob", status = ToolStatus.Completed, id = "prt-c")
        val second = projectAssistant(expandedParts, frameRevision = 2L, previous = first)
        val secondGroup =
            second.rows
                .single()
                .blocks
                .single() as SessionChatTimelineBlock.ToolGroup

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
    fun project_optimisticThenAuthoritative_baselineIds() {
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
                        baselineIds = setOf(baselineOther.id),
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
                        baselineIds = setOf(baselineOther.id),
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
    fun orderedDisplayMessages_ordersNullTimestampConsistentlyWithTimedMessages() {
        val ordered =
            TimelineDisplayOrder.ordered(
                listOf(
                    DisplayMessage(
                        Message(id = "msg-null", role = MessageRole.Assistant, text = "N", sentAtEpochMs = null),
                    ),
                    DisplayMessage(
                        Message(id = "msg-a", role = MessageRole.Assistant, text = "A", sentAtEpochMs = 100L),
                    ),
                    DisplayMessage(
                        Message(id = "msg-b", role = MessageRole.Assistant, text = "B", sentAtEpochMs = null),
                    ),
                    DisplayMessage(
                        Message(id = "msg-c", role = MessageRole.Assistant, text = "C", sentAtEpochMs = 50L),
                    ),
                ),
            )
        assertEquals(listOf("msg-c", "msg-a", "msg-null", "msg-b"), ordered.map { it.id })
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
    fun project_skillPromptWithImage_collapsesOntoOneRowAndDropsOptimisticCopy() {
        val args = "prototype me a better list item"
        val collapsed = "[/publish-prototype] $args"
        val message =
            Message(
                id = "msg-user",
                role = MessageRole.User,
                text = args,
                parts =
                    listOf(
                        MessagePart.Text(text = args, id = "u1"),
                        MessagePart.File(
                            id = "server-file",
                            filename = "16136.png",
                            mime = "image/png",
                            kind = AttachmentKind.Image,
                            dataUrl = "",
                        ),
                    ),
                promptSkills = listOf(PromptSkill(id = "publish-prototype", name = "publish-prototype")),
            )
        val blocks =
            SessionChatTimelineBlocks.present(
                message = message,
                commands =
                    listOf(
                        ServerCommand(
                            name = "publish-prototype",
                            source = ServerCommandSource.Skill,
                            skillId = "publish-prototype",
                        ),
                    ),
            )
        assertEquals(listOf(collapsed), blocks.filterIsInstance<SessionChatTimelineBlock.Text>().map { it.text })
        assertEquals(1, blocks.filterIsInstance<SessionChatTimelineBlock.Attachment>().size)

        val pendingAttachment =
            SessionChatAttachmentItem(
                id = "local-file",
                filename = "16136.png",
                variant = OpenCodeAttachmentVariant.Image,
                signature = "local",
            )
        val pending =
            TimelineProjector.rowFromMessageItem(
                SessionChatMessageItem(
                    id = "optimistic",
                    role = MessageRole.User,
                    text = collapsed,
                    metadataLabel = "Build · 12:48 AM",
                    isPending = true,
                    blocks =
                        listOf(
                            SessionChatTimelineBlock.Text(
                                blockID = PresentationBlockId.messageFallback("optimistic"),
                                text = collapsed,
                            ),
                            SessionChatTimelineBlock.Attachment(
                                blockID = PresentationBlockId.direct("local-file"),
                                attachment = pendingAttachment,
                            ),
                        ),
                    attachments = listOf(pendingAttachment),
                ),
            )
        val reconciled =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = "session",
                        frameRevision = 1L,
                        sourceRevision = 0L,
                        messages = listOf(DisplayMessageBody(message = message, blocks = blocks)),
                    ),
                optimisticInputs = OptimisticInputs(pendingRows = listOf(pending)),
            )

        assertEquals(listOf("msg-user"), reconciled.rows.map { it.id })
        assertEquals(collapsed, reconciled.rows.single().text)
        assertEquals(1, reconciled.rows.single().attachments.size)
        assertFalse(reconciled.rows.any { it.isPending })
    }

    @Test
    fun project_optimisticFile_reconcilesWhenLiveInboxOmitsFile() {
        val pendingAttachment =
            SessionChatAttachmentItem(
                id = "local-file",
                filename = "Photo 1.jpeg",
                variant = OpenCodeAttachmentVariant.Image,
                signature = "local-data-url",
            )
        val pending =
            TimelineProjector.rowFromMessageItem(
                SessionChatMessageItem(
                    id = "optimistic-photo",
                    role = MessageRole.User,
                    text = "What",
                    metadataLabel = "Build · MiMo V2.5 Free · 7:58 PM",
                    isPending = true,
                    blocks =
                        listOf(
                            SessionChatTimelineBlock.Text(
                                blockID = PresentationBlockId.messageFallback("optimistic-photo"),
                                text = "What",
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
                                            text = "What",
                                            parts = listOf(MessagePart.Text("What", id = "u1")),
                                        ),
                                    blocks =
                                        listOf(
                                            SessionChatTimelineBlock.Text(
                                                blockID = PresentationBlockId.direct("u1"),
                                                text = "What",
                                            ),
                                        ),
                                ),
                            ),
                    ),
                optimisticInputs = OptimisticInputs(pendingRows = listOf(pending)),
                previous = withOptimistic,
            )
        assertEquals(listOf("msg-photo"), reconciled.rows.map { it.id })
        assertFalse(reconciled.rows.any { it.isPending })
        assertEquals(setOf("optimistic-photo"), reconciled.removedRowIDs)
        assertEquals(listOf(pendingAttachment), reconciled.rows.single().attachments)
        assertEquals("Build · MiMo V2.5 Free · 7:58 PM", reconciled.rows.single().metadataLabel)
    }

    @Test
    fun project_optimisticFile_reconcilesOntoNewestTextOnlyWhenHistorySharesText() {
        val previous =
            DisplayMessageBody(
                message =
                    Message(
                        id = "msg-old",
                        role = MessageRole.User,
                        text = "What",
                        parts = listOf(MessagePart.Text("What", id = "u-old")),
                    ),
                blocks =
                    listOf(
                        SessionChatTimelineBlock.Text(
                            blockID = PresentationBlockId.direct("u-old"),
                            text = "What",
                        ),
                    ),
            )
        val history =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = "session",
                        frameRevision = 1L,
                        sourceRevision = 0L,
                        messages = listOf(previous),
                    ),
            )
        val oldRow = history.rows.single()
        val pendingAttachment =
            SessionChatAttachmentItem(
                id = "local-file",
                filename = "Photo 1.jpeg",
                variant = OpenCodeAttachmentVariant.Image,
                signature = "local-data-url",
            )
        val pending =
            TimelineProjector.rowFromMessageItem(
                SessionChatMessageItem(
                    id = "optimistic-photo",
                    role = MessageRole.User,
                    text = "What",
                    metadataLabel = "Build · 7:58 PM",
                    isPending = true,
                    blocks =
                        listOf(
                            SessionChatTimelineBlock.Text(
                                blockID = PresentationBlockId.messageFallback("optimistic-photo"),
                                text = "What",
                            ),
                            SessionChatTimelineBlock.Attachment(
                                blockID = PresentationBlockId.direct("local-file"),
                                attachment = pendingAttachment,
                            ),
                        ),
                    attachments = listOf(pendingAttachment),
                ),
            )
        val baseline =
            setOf(oldRow.id)
        val withOptimistic =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = "session",
                        frameRevision = 1L,
                        sourceRevision = 0L,
                        messages = listOf(previous),
                    ),
                optimisticInputs =
                    OptimisticInputs(
                        pendingRows = listOf(pending),
                        baselineIds = baseline,
                    ),
            )
        assertEquals(listOf("msg-old", "optimistic-photo"), withOptimistic.rows.map { it.id })
        val live =
            DisplayMessageBody(
                message =
                    Message(
                        id = "msg-new",
                        role = MessageRole.User,
                        text = "What",
                        parts = listOf(MessagePart.Text("What", id = "u-new")),
                    ),
                blocks =
                    listOf(
                        SessionChatTimelineBlock.Text(
                            blockID = PresentationBlockId.direct("u-new"),
                            text = "What",
                        ),
                    ),
            )
        val reconciled =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = "session",
                        frameRevision = 2L,
                        sourceRevision = 0L,
                        messages = listOf(previous, live),
                    ),
                optimisticInputs =
                    OptimisticInputs(
                        pendingRows = listOf(pending),
                        baselineIds = baseline,
                    ),
                previous = withOptimistic,
            )
        assertEquals(listOf("msg-old", "msg-new"), reconciled.rows.map { it.id })
        assertTrue(reconciled.rows[0].attachments.isEmpty())
        assertEquals(listOf(pendingAttachment), reconciled.rows[1].attachments)
        assertEquals("Build · 7:58 PM", reconciled.rows[1].metadataLabel)
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
                optimisticInputs =
                    OptimisticInputs(pendingRows = listOf(pending), baselineIds = setOf("msg-asst", "msg-hey")),
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

    @Test
    fun project_streamingMarkdownSourceHashChange_isGrowBottomNotLayout() {
        val firstMessage =
            Message(
                id = "msg-1",
                role = MessageRole.Assistant,
                text = "Hi",
                parts = listOf(MessagePart.Text("Hi", id = "prt-1", isStreaming = true)),
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
                                    firstMessage,
                                    listOf(
                                        SessionChatTimelineBlock.Text(
                                            blockID = PresentationBlockId.direct("prt-1"),
                                            text = "Hi",
                                            renderedMarkdown =
                                                MarkdownDocument(sourceHash = 1, blocks = emptyList()),
                                        ),
                                    ),
                                ),
                            ),
                    ),
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
                                    firstMessage.copy(
                                        text = "Hi there",
                                        parts =
                                            listOf(
                                                MessagePart.Text("Hi there", id = "prt-1", isStreaming = true),
                                            ),
                                    ),
                                    listOf(
                                        SessionChatTimelineBlock.Text(
                                            blockID = PresentationBlockId.direct("prt-1"),
                                            text = "Hi there",
                                            renderedMarkdown =
                                                MarkdownDocument(sourceHash = 2, blocks = emptyList()),
                                        ),
                                    ),
                                ),
                            ),
                    ),
                previous = first,
            )

        assertEquals(setOf("msg-1"), second.changedRowIDs)
        assertTrue(second.layoutChangedRowIDs.isEmpty())
        assertEquals(setOf("msg-1"), second.grewBottomRowIDs)
        assertEquals(0L, second.layoutAffectingRevision())
        assertEquals(first.rows.single().layoutRevision, second.rows.single().layoutRevision)
        val apply = TimelineApply.classify(second)
        assertTrue(apply.kinds.contains(TimelineApplyKind.GrowBottom))
        assertFalse(apply.kinds.contains(TimelineApplyKind.Structure))
    }

    @Test
    fun project_streamingReasoningGrowth_isOmittedFromTimeline() {
        val first =
            projectAssistant(
                parts =
                    listOf(
                        MessagePart.Reasoning(text = "Think", isStreaming = true, id = "prt-r"),
                    ),
                frameRevision = 1L,
            )
        val second =
            projectAssistant(
                parts =
                    listOf(
                        MessagePart.Reasoning(text = "Think harder", isStreaming = true, id = "prt-r"),
                    ),
                frameRevision = 2L,
                previous = first,
            )

        assertTrue(first.rows.isEmpty())
        assertTrue(second.rows.isEmpty())
        assertTrue(second.changedRowIDs.isEmpty())
        assertTrue(second.layoutChangedRowIDs.isEmpty())
        assertTrue(second.layoutAffectingRowIDs().isEmpty())
    }

    @Test
    fun project_reasoningComplete_insertsSettledThoughtRow() {
        val first =
            projectAssistant(
                parts =
                    listOf(
                        MessagePart.Reasoning(text = "Think", isStreaming = true, id = "prt-r"),
                    ),
                frameRevision = 1L,
            )
        val second =
            projectAssistant(
                parts =
                    listOf(
                        MessagePart.Reasoning(
                            text = "Think",
                            isStreaming = false,
                            id = "prt-r",
                            timeStartEpochMs = 1,
                            timeEndEpochMs = 2,
                        ),
                    ),
                frameRevision = 2L,
                previous = first,
            )

        assertTrue(first.rows.isEmpty())
        assertEquals(1, second.rows.size)
        assertEquals(setOf("msg-1"), second.insertedRowIDs)
        val thought =
            second.rows
                .single()
                .blocks
                .single() as SessionChatTimelineBlock.Reasoning
        assertEquals("Think", thought.text)
        assertFalse(thought.isStreaming)
        val apply = TimelineApply.classify(second)
        assertTrue(apply.kinds.contains(TimelineApplyKind.Structure))
    }

    @Test
    fun project_toolTitleChange_isContentNotLayout() {
        val first =
            projectAssistant(
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "task",
                            status = ToolStatus.Running,
                            id = "prt-1",
                            title = "Working…",
                        ),
                    ),
                frameRevision = 1L,
            )
        val second =
            projectAssistant(
                parts =
                    listOf(
                        MessagePart.Tool(
                            toolName = "task",
                            status = ToolStatus.Running,
                            id = "prt-1",
                            title = "explore",
                            input =
                                kotlinx.serialization.json.buildJsonObject {
                                    put("agent", kotlinx.serialization.json.JsonPrimitive("explore"))
                                },
                        ),
                    ),
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
    fun project_excludesQueuePendingUserMessage() {
        val frame =
            projectUser(
                text = "Held until idle",
                isWaitingForPickup = true,
                followUpDelivery = FollowUpDelivery.Queue,
            )
        assertTrue(frame.rows.isEmpty())
    }

    @Test
    fun project_excludesOptimisticPendingUserMessage() {
        val pending =
            TimelineProjector.rowFromMessageItem(
                SessionChatMessageItem(
                    id = "optimistic-1",
                    role = MessageRole.User,
                    text = "Hello",
                    metadataLabel = "build · deepseek-v4-flash-free · high · 10:21 AM",
                    pendingDelivery = FollowUpDelivery.Steer,
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
        val frame =
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
        assertTrue(frame.rows.isEmpty())
    }

    @Test
    fun project_excludesSteerPendingUserMessage() {
        val frame =
            projectUser(
                text = "Waiting to steer",
                isWaitingForPickup = true,
                followUpDelivery = FollowUpDelivery.Steer,
            )
        assertTrue(frame.rows.isEmpty())
    }

    @Test
    fun project_optimisticThenQueuePendingEcho_dropsOptimisticAndHidesEcho() {
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
                        baselineIds = setOf(baselineOther.id),
                    ),
            )
        assertEquals(listOf("msg-user-0", "optimistic-1"), emptyAuthoritative.rows.map { it.id })

        val queuePendingUser =
            Message(
                id = "msg-user-1",
                role = MessageRole.User,
                text = "Hello",
                parts = listOf(MessagePart.Text("Hello", id = "prt-user-1")),
                isWaitingForPickup = true,
                followUpDelivery = FollowUpDelivery.Queue,
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
                                    message = queuePendingUser,
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
                        baselineIds = setOf(baselineOther.id),
                    ),
                previous = emptyAuthoritative,
            )

        assertEquals(listOf("msg-user-0"), reconciled.rows.map { it.id })
        assertFalse(reconciled.rows.any { it.id == "optimistic-1" })
        assertFalse(reconciled.rows.any { it.id == "msg-user-1" })
    }

    @Test
    fun unmatchedPending_keepsOptimisticWhenBaselineHasHiddenInboxSameText() {
        val pending = userRow(id = "optimistic-1", text = "Hold this", isPending = true)
        val inbox = userRow(id = "msg-inbox", text = "Hold this", pendingDelivery = FollowUpDelivery.Queue)
        val leftover =
            TimelineProjector.unmatchedPending(
                pendingRows = listOf(pending),
                serverRows = listOf(inbox),
                baselineIds = setOf(inbox.id),
            )
        assertEquals(listOf("optimistic-1"), leftover.map { it.id })
    }

    @Test
    fun unmatchedPending_dropsOptimisticWhenNewEchoArrives() {
        val pending = userRow(id = "optimistic-1", text = "Hold this", isPending = true)
        val inbox = userRow(id = "msg-inbox", text = "Hold this", pendingDelivery = FollowUpDelivery.Queue)
        val echo = userRow(id = "msg-echo", text = "Hold this", pendingDelivery = FollowUpDelivery.Queue)
        val leftover =
            TimelineProjector.unmatchedPending(
                pendingRows = listOf(pending),
                serverRows = listOf(inbox, echo),
                baselineIds = setOf(inbox.id),
            )
        assertTrue(leftover.isEmpty())
    }

    @Test
    fun project_keepsOptimisticWhenBaselineHasHiddenInboxSameText() {
        val pending = userRow(id = "optimistic-1", text = "Hold this", isPending = true)
        val inboxBody = userBody(id = "msg-inbox", text = "Hold this", waiting = FollowUpDelivery.Queue)
        val inboxRow = TimelineProjector.projectServerRows(listOf(inboxBody)).single()
        val frame =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = "session",
                        frameRevision = 1L,
                        sourceRevision = 0L,
                        messages = listOf(inboxBody),
                    ),
                optimisticInputs =
                    OptimisticInputs(
                        pendingRows = listOf(pending),
                        baselineIds = setOf(inboxRow.id),
                    ),
            )
        assertEquals(listOf("optimistic-1"), frame.rows.map { it.id })
    }

    @Test
    fun project_dropsOptimisticWhenNewEchoArrives() {
        val pending = userRow(id = "optimistic-1", text = "Hold this", isPending = true)
        val inboxBody = userBody(id = "msg-inbox", text = "Hold this", waiting = FollowUpDelivery.Queue)
        val echoBody = userBody(id = "msg-echo", text = "Hold this", waiting = FollowUpDelivery.Queue)
        val inboxRow = TimelineProjector.projectServerRows(listOf(inboxBody)).single()
        val frame =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = "session",
                        frameRevision = 2L,
                        sourceRevision = 0L,
                        messages = listOf(inboxBody, echoBody),
                    ),
                optimisticInputs =
                    OptimisticInputs(
                        pendingRows = listOf(pending),
                        baselineIds = setOf(inboxRow.id),
                    ),
            )
        assertTrue(frame.rows.isEmpty())
        assertFalse(frame.rows.any { it.id == "optimistic-1" })
    }

    @Test
    fun project_attachmentOnlyOptimistic_dropsWhenMatchingAttachmentEchoArrives() {
        val pendingAttachment =
            SessionChatAttachmentItem(
                id = "local-file",
                filename = "notes.txt",
                variant = OpenCodeAttachmentVariant.Text,
                signature = "local-data-url",
            )
        val serverAttachment =
            SessionChatAttachmentItem(
                id = "server-file",
                filename = "notes.txt",
                variant = OpenCodeAttachmentVariant.Text,
                signature = "https://example.test/notes.txt",
            )
        val pending =
            TimelineProjector.rowFromMessageItem(
                SessionChatMessageItem(
                    id = "optimistic-file",
                    role = MessageRole.User,
                    text = "",
                    metadataLabel = "Build · 10:21 AM",
                    isPending = true,
                    blocks =
                        listOf(
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
        assertEquals(listOf("optimistic-file"), withOptimistic.rows.map { it.id })

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
                                            id = "msg-file",
                                            role = MessageRole.User,
                                            text = "",
                                            parts =
                                                listOf(
                                                    MessagePart.File(
                                                        id = "server-file",
                                                        filename = "notes.txt",
                                                        mime = "text/plain",
                                                        kind = AttachmentKind.Text,
                                                        dataUrl = "https://example.test/notes.txt",
                                                    ),
                                                ),
                                        ),
                                    blocks =
                                        listOf(
                                            SessionChatTimelineBlock.Attachment(
                                                blockID = PresentationBlockId.direct("server-file"),
                                                attachment = serverAttachment,
                                            ),
                                        ),
                                ),
                            ),
                    ),
                optimisticInputs = OptimisticInputs(pendingRows = listOf(pending)),
                previous = withOptimistic,
            )
        assertEquals(listOf("msg-file"), reconciled.rows.map { it.id })
        assertFalse(reconciled.rows.any { it.id == "optimistic-file" })
        assertEquals(setOf("optimistic-file"), reconciled.removedRowIDs)
    }

    @Test
    fun displayedOptimisticSendItems_excludesHiddenPendingRows() {
        val hidden =
            SessionChatMessageItem(
                id = "pending-1",
                role = MessageRole.User,
                text = "Hold this",
                metadataLabel = "",
                pendingDelivery = FollowUpDelivery.Steer,
                isPending = true,
            )
        val visible =
            SessionChatMessageItem(
                id = "pending-2",
                role = MessageRole.User,
                text = "Just sent",
                metadataLabel = "",
                isPending = true,
            )
        assertEquals(
            listOf("pending-2"),
            TimelineProjector.displayedOptimisticSendItems(listOf(hidden, visible)).map { it.id },
        )
        assertFalse(
            TimelineProjector.shouldMarkTimelineReadyAfterOptimisticSend(
                displayedItems = emptyList(),
                hadPriorDisplayedRows = false,
            ),
        )
        assertTrue(
            TimelineProjector.shouldMarkTimelineReadyAfterOptimisticSend(
                displayedItems = emptyList(),
                hadPriorDisplayedRows = true,
            ),
        )
    }

    @Test
    fun projectServerRows_baselineIncludesWaitingForPickupUser() {
        val inboxBody = userBody(id = "msg-inbox", text = "Hold this", waiting = FollowUpDelivery.Queue)
        val earlierBody = userBody(id = "msg-earlier", text = "Earlier")
        val rows = TimelineProjector.projectServerRows(listOf(earlierBody, inboxBody))
        assertEquals(setOf("msg-earlier", "msg-inbox"), rows.mapTo(HashSet()) { it.id })
        assertTrue(rows.single { it.id == "msg-inbox" }.pendingDelivery == FollowUpDelivery.Queue)
    }

    @Test
    fun unmatchedPending_keepsShownOptimisticWhenOnlyWaitingSteerEchoArrives() {
        val pending = userRow(id = "optimistic-1", text = "What is this", isPending = true)
        val steer = userRow(id = "msg-echo", text = "What is this", pendingDelivery = FollowUpDelivery.Steer)
        val leftover =
            TimelineProjector.unmatchedPending(
                pendingRows = listOf(pending),
                serverRows = listOf(steer),
                baselineIds = emptySet(),
            )
        assertEquals(listOf("optimistic-1"), leftover.map { it.id })
    }

    @Test
    fun unmatchedPending_hiddenSteerOptimisticStillRetiredBySteerEcho() {
        val pending =
            userRow(id = "optimistic-1", text = "Steer this", isPending = true, pendingDelivery = FollowUpDelivery.Steer)
        val steer = userRow(id = "msg-echo", text = "Steer this", pendingDelivery = FollowUpDelivery.Steer)
        val leftover =
            TimelineProjector.unmatchedPending(
                pendingRows = listOf(pending),
                serverRows = listOf(steer),
                baselineIds = emptySet(),
            )
        assertTrue(leftover.isEmpty())
    }

    @Test
    fun project_photoSend_keepsOptimisticThroughSteerEchoAndKeysDeliveredEchoUnderIt() {
        val pending =
            TimelineProjector.rowFromMessageItem(
                SessionChatMessageItem(
                    id = "optimistic-1",
                    role = MessageRole.User,
                    text = "What is this",
                    metadataLabel = "Build · Scripted · 10:58 PM",
                    isPending = true,
                    blocks =
                        listOf(
                            SessionChatTimelineBlock.Text(
                                blockID = PresentationBlockId.messageFallback("optimistic-1"),
                                text = "What is this",
                            ),
                        ),
                    attachments =
                        listOf(
                            SessionChatAttachmentItem(
                                id = "local-photo",
                                filename = "Photo 1.png",
                                variant = OpenCodeAttachmentVariant.Image,
                                signature = "local-png",
                            ),
                        ),
                ),
            )
        val inputs = OptimisticInputs(pendingRows = listOf(pending))
        val optimisticFrame =
            TimelineFrame(
                frameRevision = 1L,
                rows = listOf(pending),
                insertedRowIDs = setOf(pending.id),
                changedRowIDs = emptySet(),
                removedRowIDs = emptySet(),
                layoutChangedRowIDs = emptySet(),
            )

        val steerFrame =
            TimelineProjector.project(
                frame = photoEchoFrame(revision = 2L, waiting = FollowUpDelivery.Steer),
                optimisticInputs = inputs,
                previous = optimisticFrame,
            )
        assertEquals(listOf("optimistic-1"), steerFrame.rows.map { it.id })
        assertTrue(steerFrame.removedRowIDs.isEmpty())

        val deliveredFrame =
            TimelineProjector.project(
                frame = photoEchoFrame(revision = 3L, waiting = null),
                optimisticInputs = inputs,
                previous = steerFrame,
            )
        assertEquals(listOf("msg-echo"), deliveredFrame.rows.map { it.id })
        assertEquals(mapOf("msg-echo" to "optimistic-1"), deliveredFrame.listIdAliases)
        assertEquals("optimistic-1", deliveredFrame.listKey("msg-echo"))

        val settledFrame =
            TimelineProjector.project(
                frame = photoEchoFrame(revision = 4L, waiting = null),
                previous = deliveredFrame,
            )
        assertEquals("optimistic-1", settledFrame.listKey("msg-echo"))
    }

    @Test
    fun project_serverRowAlreadyListedKeepsItsOwnKey() {
        val pending = userRow(id = "optimistic-1", text = "Hold this", isPending = true)
        val echoBody = userBody(id = "msg-echo", text = "Hold this")
        val previous =
            TimelineProjector.project(
                frame = DisplayFrame(sessionKey = "session", frameRevision = 1L, sourceRevision = 0L, messages = listOf(echoBody)),
            )
        val frame =
            TimelineProjector.project(
                frame = DisplayFrame(sessionKey = "session", frameRevision = 2L, sourceRevision = 0L, messages = listOf(echoBody)),
                optimisticInputs = OptimisticInputs(pendingRows = listOf(pending)),
                previous = previous,
            )
        assertTrue(frame.listIdAliases.isEmpty())
        assertEquals("msg-echo", frame.listKey("msg-echo"))
    }

    @Test
    fun unmatchedPending_commandRowRetiredByFirstNewUserRowWhateverItsText() {
        val pending = userRow(id = "pending-command", text = "[/review-diff] money", isPending = true)
        val earlier = userRow(id = "msg-earlier", text = "[/review-diff] money")
        val echo = userRow(id = "msg-echo", text = "Review the staged diff. Focus: money")

        assertEquals(
            listOf("pending-command"),
            TimelineProjector
                .unmatchedPending(
                    pendingRows = listOf(pending),
                    serverRows = listOf(earlier),
                    baselineIds = setOf(earlier.id),
                ).map { it.id },
        )
        assertTrue(
            TimelineProjector
                .unmatchedPending(
                    pendingRows = listOf(pending),
                    serverRows = listOf(earlier, echo),
                    baselineIds = setOf(earlier.id),
                ).isEmpty(),
        )
    }

    @Test
    fun unmatchedPending_commandRowIgnoresNewNonUserRows() {
        val pending = userRow(id = "pending-command", text = "[/review-diff] money", isPending = true)
        val notice =
            TimelineProjector.rowFromMessageItem(
                SessionChatMessageItem(id = "msg-agent", role = MessageRole.Notice, text = "Agent changed", metadataLabel = ""),
            )

        val leftover =
            TimelineProjector.unmatchedPending(
                pendingRows = listOf(pending),
                serverRows = listOf(notice),
                baselineIds = emptySet(),
            )

        assertEquals(listOf("pending-command"), leftover.map { it.id })
    }

    @Test
    fun project_commandEchoReplacesOptimisticRowOnce() {
        val pending = userRow(id = "pending-command", text = "[/review-diff] money", isPending = true)
        val inputs = OptimisticInputs(pendingRows = listOf(pending), baselineIds = setOf("msg-earlier"))
        val sent =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = "session",
                        frameRevision = 1L,
                        sourceRevision = 0L,
                        messages = listOf(userBody(id = "msg-earlier", text = "Earlier")),
                    ),
                optimisticInputs = inputs,
            )
        val echoed =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = "session",
                        frameRevision = 2L,
                        sourceRevision = 0L,
                        messages =
                            listOf(
                                userBody(id = "msg-earlier", text = "Earlier"),
                                userBody(id = "msg-echo", text = "Review the staged diff. Focus: money"),
                            ),
                    ),
                optimisticInputs = inputs,
                previous = sent,
            )

        assertEquals(listOf("msg-earlier", "msg-echo"), echoed.rows.map { it.id })
    }

    @Test
    fun unmatchedPending_clientIdEchoRetiresRowWhateverItsText() {
        val pending = userRow(id = CLIENT_ID, text = "[/review] the diff", isPending = true)
        val echo = userRow(id = CLIENT_ID, text = "Review the staged diff and list risks.")

        val leftover =
            TimelineProjector.unmatchedPending(
                pendingRows = listOf(pending),
                serverRows = listOf(echo),
                baselineIds = emptySet(),
            )

        assertTrue(leftover.isEmpty())
    }

    @Test
    fun unmatchedPending_clientIdIgnoresSameTextUnderAnotherId() {
        val pending = userRow(id = CLIENT_ID, text = "yes", isPending = true)
        val other = userRow(id = "msg_from_another_client", text = "yes")

        val leftover =
            TimelineProjector.unmatchedPending(
                pendingRows = listOf(pending),
                serverRows = listOf(other),
                baselineIds = emptySet(),
            )

        assertEquals(listOf(CLIENT_ID), leftover.map { it.id })
    }

    @Test
    fun unmatchedPending_clientIdWaitingSteerEchoKeepsShownRow() {
        val pending = userRow(id = CLIENT_ID, text = "Hold this", isPending = true)
        val steer = userRow(id = CLIENT_ID, text = "Hold this", pendingDelivery = FollowUpDelivery.Steer)

        val leftover =
            TimelineProjector.unmatchedPending(
                pendingRows = listOf(pending),
                serverRows = listOf(steer),
                baselineIds = emptySet(),
            )

        assertEquals(listOf(CLIENT_ID), leftover.map { it.id })
    }

    @Test
    fun project_clientIdEchoReplacesOptimisticRowOnce() {
        val pending = userRow(id = CLIENT_ID, text = "[/review] the diff", isPending = true)
        val sent =
            TimelineProjector.project(
                frame = DisplayFrame(sessionKey = "session", frameRevision = 1L, sourceRevision = 0L, messages = emptyList()),
                optimisticInputs = OptimisticInputs(pendingRows = listOf(pending)),
            )
        val echoed =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = "session",
                        frameRevision = 2L,
                        sourceRevision = 0L,
                        messages = listOf(userBody(id = CLIENT_ID, text = "Review the staged diff and list risks.")),
                    ),
                optimisticInputs = OptimisticInputs(pendingRows = listOf(pending)),
                previous = sent,
            )

        assertEquals(listOf(CLIENT_ID), echoed.rows.map { it.id })
        assertFalse(echoed.rows.single().isPending)
        assertEquals("Review the staged diff and list risks.", echoed.rows.single().text)
    }

    private fun photoEchoFrame(
        revision: Long,
        waiting: FollowUpDelivery?,
    ): DisplayFrame {
        val file =
            MessagePart.File(
                id = "prt-photo",
                filename = "Photo 1.png",
                mime = "image/jpeg",
                kind = AttachmentKind.Image,
                dataUrl = "",
            )
        val message =
            Message(
                id = "msg-echo",
                role = MessageRole.User,
                text = "What is this",
                parts = listOf(MessagePart.Text("What is this", id = "prt-text"), file),
                isWaitingForPickup = waiting != null,
                followUpDelivery = waiting,
            )
        val blocks =
            when (val result = SessionChatTimelineBlocks.build(MessageRole.User, message.parts)) {
                is SessionChatTimelineBlocksResult.Ok -> result.blocks
                SessionChatTimelineBlocksResult.MissingPartIds -> error("expected Ok")
            }
        return DisplayFrame(
            sessionKey = "session",
            frameRevision = revision,
            sourceRevision = 0L,
            messages = listOf(DisplayMessageBody(message, blocks)),
        )
    }

    private fun userRow(
        id: String,
        text: String,
        isPending: Boolean = false,
        pendingDelivery: FollowUpDelivery? = null,
    ): TimelineRow =
        TimelineProjector.rowFromMessageItem(
            SessionChatMessageItem(
                id = id,
                role = MessageRole.User,
                text = text,
                metadataLabel = "",
                pendingDelivery = pendingDelivery,
                isPending = isPending,
                blocks =
                    listOf(
                        SessionChatTimelineBlock.Text(
                            blockID = PresentationBlockId.direct(id),
                            text = text,
                        ),
                    ),
            ),
        )

    private fun userBody(
        id: String,
        text: String,
        waiting: FollowUpDelivery? = null,
    ): DisplayMessageBody =
        DisplayMessageBody(
            message =
                Message(
                    id = id,
                    role = MessageRole.User,
                    text = text,
                    parts = listOf(MessagePart.Text(text, id = "prt-$id")),
                    isWaitingForPickup = waiting != null,
                    followUpDelivery = waiting,
                ),
            blocks =
                listOf(
                    SessionChatTimelineBlock.Text(
                        blockID = PresentationBlockId.direct("prt-$id"),
                        text = text,
                    ),
                ),
        )

    private fun projectUser(
        text: String,
        isWaitingForPickup: Boolean,
        followUpDelivery: FollowUpDelivery,
    ): TimelineFrame {
        val parts = listOf(MessagePart.Text(text, id = "prt-user"))
        val blocks =
            when (val result = SessionChatTimelineBlocks.build(MessageRole.User, parts)) {
                is SessionChatTimelineBlocksResult.Ok -> result.blocks
                SessionChatTimelineBlocksResult.MissingPartIds -> error("expected Ok")
            }
        return TimelineProjector.project(
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
                                        id = "msg-user",
                                        role = MessageRole.User,
                                        text = text,
                                        parts = parts,
                                        isWaitingForPickup = isWaitingForPickup,
                                        followUpDelivery = followUpDelivery,
                                    ),
                                blocks = blocks,
                            ),
                        ),
                ),
        )
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

    private companion object {
        const val CLIENT_ID = "msg_19a1b2c3d4e5AbCdEfGhIjKlMn"
    }
}
