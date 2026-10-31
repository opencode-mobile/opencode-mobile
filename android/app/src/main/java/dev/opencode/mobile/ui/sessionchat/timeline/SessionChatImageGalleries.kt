package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.AttachmentDataUrl
import dev.opencode.mobile.ui.components.OpenCodeAttachmentImageSource
import dev.opencode.mobile.ui.components.OpenCodeGalleryImage
import dev.opencode.mobile.ui.components.OpenCodeGalleryImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The full-screen image viewer: the gallery block it pages through and the page on screen. */
data class SessionChatImageViewerState(
    /** [SessionChatTimelineBlock.ImageGallery] or lone image [SessionChatTimelineBlock.Attachment] block id. */
    val galleryId: String,
    val index: Int,
)

/** Finds what a gallery or the viewer shows in the Session Chat timeline. */
object SessionChatImageGalleries {
    /**
     * The images the viewer pages through for [galleryId]: an image gallery block's images, or a
     * lone image attachment. Null when no row carries that block any more.
     */
    fun images(
        messages: List<SessionChatMessageItem>,
        galleryId: String,
    ): List<SessionChatAttachmentItem>? {
        messages.forEach { message ->
            message.blocks.forEach { block ->
                if (block.blockID != galleryId) return@forEach
                when (block) {
                    is SessionChatTimelineBlock.ImageGallery -> return block.images
                    is SessionChatTimelineBlock.Attachment ->
                        if (block.attachment.isGalleryImage) return listOf(block.attachment)
                    else -> Unit
                }
            }
        }
        return null
    }

    fun galleryImage(item: SessionChatAttachmentItem): OpenCodeGalleryImage = OpenCodeGalleryImage(id = item.id, name = item.filename)

    /** Every image the timeline can draw, by id: attachments, galleries, and tool cards' images. */
    fun imagesById(messages: List<SessionChatMessageItem>): Map<String, SessionChatAttachmentItem> =
        buildMap {
            fun add(item: SessionChatAttachmentItem) {
                if (item.isGalleryImage) put(item.id, item)
            }
            messages.forEach { message ->
                message.attachments.forEach(::add)
                message.blocks.forEach { block ->
                    when (block) {
                        is SessionChatTimelineBlock.Attachment -> add(block.attachment)
                        is SessionChatTimelineBlock.ImageGallery -> block.images.forEach(::add)
                        is SessionChatTimelineBlock.ToolCard -> block.images.forEach(::add)
                        is SessionChatTimelineBlock.ToolGroup -> block.images.forEach(::add)
                        else -> Unit
                    }
                }
            }
        }
}

/**
 * [OpenCodeGalleryImageLoader] over Session Chat images: resolves a gallery image id to its
 * attachment item, previews from the thumbnail already in memory, and loads the full picture
 * from its data URL or, for an uploaded file, through [serverLoader]. [update] swaps the images
 * it can see without recreating the loader, so the timeline does not recompose for it.
 */
class SessionChatGalleryImageLoader(
    private val serverLoader: suspend (OpenCodeAttachmentImageSource.ServerPath) -> ByteArray?,
) : OpenCodeGalleryImageLoader {
    @Volatile private var messages: List<SessionChatMessageItem> = emptyList()

    @Volatile private var extra: List<SessionChatAttachmentItem> = emptyList()

    @Volatile private var index: Map<String, SessionChatAttachmentItem>? = null

    fun update(
        messages: List<SessionChatMessageItem>,
        extra: List<SessionChatAttachmentItem> = emptyList(),
    ) {
        if (messages === this.messages && extra == this.extra) return
        this.messages = messages
        this.extra = extra
        index = null
    }

    fun item(id: String): SessionChatAttachmentItem? {
        val current =
            index ?: (SessionChatImageGalleries.imagesById(messages) + extra.associateBy { it.id }).also { index = it }
        return current[id]
    }

    override fun preview(id: String): ByteArray? = item(id)?.imageBytes

    override suspend fun load(id: String): ByteArray? =
        when (val source = item(id)?.imageSource) {
            is OpenCodeAttachmentImageSource.ServerPath -> serverLoader(source)
            is OpenCodeAttachmentImageSource.DataUrl ->
                withContext(Dispatchers.Default) { AttachmentDataUrl.decode(source.dataUrl, source.mime) }
            null -> null
        }
}
