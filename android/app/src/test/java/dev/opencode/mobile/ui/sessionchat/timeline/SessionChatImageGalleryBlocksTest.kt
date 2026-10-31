package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Standard timeline image galleries: tool results, user sends and assistant files (#898). */
class SessionChatImageGalleryBlocksTest {
    @Test
    fun toolWithImages_getsAGalleryDirectlyUnderItsCard() {
        for (count in 1..5) {
            val blocks = build(MessageRole.Assistant, listOf(readTool("t1", images = count)))

            assertEquals(2, blocks.size)
            assertTrue(blocks[0] is SessionChatTimelineBlock.ToolCard)
            val gallery = blocks[1] as SessionChatTimelineBlock.ImageGallery
            assertEquals(PresentationBlockId.imageGallery(PresentationBlockId.direct("t1")), gallery.blockID)
            assertEquals(count, gallery.images.size)
            assertTrue(gallery.fromTool)
        }
    }

    @Test
    fun toolWithoutImages_hasNoGallery() {
        val blocks = build(MessageRole.Assistant, listOf(readTool("t1", images = 0)))

        assertEquals(1, blocks.size)
        assertTrue((blocks.single() as SessionChatTimelineBlock.ToolCard).images.isEmpty())
    }

    @Test
    fun toolGroup_showsEveryMembersImagesInOneGalleryUnderTheGroup() {
        val blocks =
            build(
                MessageRole.Assistant,
                listOf(readTool("t1", images = 2), readTool("t2", images = 0), readTool("t3", images = 3)),
                collapse = true,
            )

        val group = blocks[0] as SessionChatTimelineBlock.ToolGroup
        val gallery = blocks[1] as SessionChatTimelineBlock.ImageGallery
        assertEquals(2, blocks.size)
        assertEquals(listOf(2, 0, 3), group.members.map { it.images.size })
        assertEquals(group.images, gallery.images)
        assertEquals(5, gallery.images.size)
        // Keyed on the first member: a card that grows into a group keeps its gallery id.
        assertEquals(PresentationBlockId.imageGallery(PresentationBlockId.direct("t1")), gallery.blockID)
    }

    @Test
    fun consecutiveToolOnlyMessagesWithImages_stillMergeIntoOneGroup() {
        val bodies = listOf(toolOnlyBody("a1", "t1", images = 1), toolOnlyBody("a2", "t2", images = 2))

        val merged =
            SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(bodies, collapseConsecutiveToolCalls = true)

        val blocks = merged.single().blocks
        assertTrue(blocks[0] is SessionChatTimelineBlock.ToolGroup)
        assertEquals(3, (blocks[1] as SessionChatTimelineBlock.ImageGallery).images.size)
    }

    @Test
    fun assistantFiles_twoOrMoreImagesFormAGalleryAfterTheText() {
        val blocks =
            build(
                MessageRole.Assistant,
                listOf(MessagePart.Text("Here they are.", id = "x1"), image("f1"), image("f2"), image("f3")),
            )

        assertTrue(blocks[0] is SessionChatTimelineBlock.Text)
        val gallery = blocks[1] as SessionChatTimelineBlock.ImageGallery
        assertEquals(listOf("f1", "f2", "f3"), gallery.images.map { it.id })
        assertFalse(gallery.fromTool)
        assertEquals(PresentationBlockId.imageGallery(PresentationBlockId.direct("f1")), gallery.blockID)
    }

    @Test
    fun aLoneImageStaysAnAttachment() {
        val blocks = build(MessageRole.User, listOf(MessagePart.Text("Look", id = "x1"), image("f1")))

        assertEquals(SessionChatTimelineBlock.Attachment::class, blocks[1]::class)
    }

    @Test
    fun userSend_imagesGroupAndOtherFilesSplitTheRun() {
        val blocks =
            build(
                MessageRole.User,
                listOf(MessagePart.Text("Compare", id = "x1"), image("f1"), image("f2"), pdf("p1"), image("f3")),
            )

        assertEquals(
            listOf(
                SessionChatTimelineBlock.Text::class,
                SessionChatTimelineBlock.ImageGallery::class,
                SessionChatTimelineBlock.Attachment::class,
                SessionChatTimelineBlock.Attachment::class,
            ),
            blocks.map { it::class },
        )
        assertEquals("p1", (blocks[2] as SessionChatTimelineBlock.Attachment).attachment.id)
        assertEquals("f3", (blocks[3] as SessionChatTimelineBlock.Attachment).attachment.id)
    }

    @Test
    fun otherTimelineStyles_doNotShowToolImagesYet() {
        val parts = listOf(MessagePart.Text("Checking", id = "x1"), readTool("t1", images = 2))
        val styles =
            listOf(
                SessionChatTimelineBlocks.build(MessageRole.Assistant, parts, chipTimeline = true),
                SessionChatTimelineBlocks.build(MessageRole.Assistant, parts, stepRail = true),
                SessionChatTimelineBlocks.build(MessageRole.Assistant, parts, activityCard = true),
            )

        styles.forEach { result ->
            val blocks = (result as SessionChatTimelineBlocksResult.Ok).blocks
            assertTrue(blocks.none { it is SessionChatTimelineBlock.ImageGallery })
        }
    }

    @Test
    fun messageAttachments_countSentGalleriesButNotToolImages() {
        val user = build(MessageRole.User, listOf(image("f1"), image("f2")))
        val tool = build(MessageRole.Assistant, listOf(readTool("t1", images = 2)))

        assertEquals(listOf("f1", "f2"), user.flatMap(TimelineProjector::messageAttachments).map { it.id })
        assertTrue(tool.flatMap(TimelineProjector::messageAttachments).isEmpty())
    }

    @Test
    fun attachmentBlocks_matchServerBuildForAnOptimisticSend() {
        val items = listOf(image("f1"), image("f2")).map(AttachmentPresentationMapper::from)

        val optimistic = SessionChatTimelineBlocks.attachmentBlocks(items)

        assertEquals(build(MessageRole.User, listOf(image("f1"), image("f2"))), optimistic)
    }

    private fun build(
        role: MessageRole,
        parts: List<MessagePart>,
        collapse: Boolean = false,
    ): List<SessionChatTimelineBlock> =
        (SessionChatTimelineBlocks.build(role, parts, collapseConsecutiveToolCalls = collapse) as SessionChatTimelineBlocksResult.Ok).blocks

    private fun toolOnlyBody(
        id: String,
        toolId: String,
        images: Int,
    ): DisplayMessageBody {
        val parts = listOf(readTool(toolId, images))
        return DisplayMessageBody(
            message = Message(id = id, role = MessageRole.Assistant, text = "", parts = parts),
            blocks = build(MessageRole.Assistant, parts, collapse = true),
        )
    }

    private fun readTool(
        id: String,
        images: Int,
    ): MessagePart.Tool =
        MessagePart.Tool(
            toolName = "read",
            status = ToolStatus.Completed,
            id = id,
            input = JsonObject(mapOf("filePath" to JsonPrimitive("/Users/example/project/shot-$id.png"))),
            output = "Image read successfully",
            images = (0 until images).map { index -> image("$id:image:$index") },
        )

    /**
     * Server-path images: a data URL would make block building decode a thumbnail, and
     * `BitmapFactory` is not available on the JVM. Grouping does not depend on the source.
     */
    private fun image(id: String): MessagePart.File =
        MessagePart.File(
            id = id,
            filename = "$id.png",
            mime = "image/png",
            kind = AttachmentKind.Image,
            dataUrl = "",
            serverPath = "/tmp/example/$id.png",
        )

    private fun pdf(id: String): MessagePart.File =
        MessagePart.File(
            id = id,
            filename = "$id.pdf",
            mime = "application/pdf",
            kind = AttachmentKind.Pdf,
            dataUrl = "data:application/pdf;base64,JVBERi0x",
        )
}
