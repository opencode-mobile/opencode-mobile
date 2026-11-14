package dev.opencode.mobile.ui.sessionchat

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf
import dev.opencode.mobile.domain.message.AttachmentDataUrl
import dev.opencode.mobile.domain.message.ToolImageThumbnails
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

    fun galleryImage(item: SessionChatAttachmentItem): OpenCodeGalleryImage =
        OpenCodeGalleryImage(id = item.id, name = item.filename, loadKey = loadKey(item))

    /** Changes when more of the picture becomes loadable (a thumbnail, a preview, or the full data). */
    private fun loadKey(item: SessionChatAttachmentItem): String =
        buildString {
            if (item.thumbnailPath != null) append('t')
            if (item.imageBytes != null) append('p')
            if (item.imageSource != null) append('s')
        }

    /** Every image the timeline can draw, by id: attachments, galleries, and tool cards' images. */
    fun imagesById(messages: List<SessionChatMessageItem>): Map<String, SessionChatAttachmentItem> =
        sourcesById(messages).mapValues { it.value.item }

    /** Every drawable image by id, with the id of the message (timeline row) that carries it. */
    fun sourcesById(messages: List<SessionChatMessageItem>): Map<String, SessionChatImageSource> =
        buildMap {
            messages.forEach { message ->
                fun add(item: SessionChatAttachmentItem) {
                    if (item.isGalleryImage) put(item.id, SessionChatImageSource(item, message.id))
                }
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

/** A timeline image and the message it belongs to, so the viewer can fetch its full picture. */
data class SessionChatImageSource(
    val item: SessionChatAttachmentItem,
    val messageId: String?,
)

/**
 * Loads a timeline image's full picture from the server when only its thumbnail is on the
 * device: (message id, image id) to the decoded bytes; throws when it cannot. Session Chat
 * provides it; null where no session is bound (the picture then loads only from memory).
 */
val LocalSessionChatFullImageLoader =
    staticCompositionLocalOf<(suspend (messageId: String, imageId: String) -> ByteArray)?> { null }

/**
 * Full pictures the viewer loaded, by image id, bounded by bytes (~32 MB) and dropped on
 * memory pressure. Lets Share and a reopened viewer reuse a picture fetched from the server.
 */
object SessionChatFullImageMemory {
    private const val MAX_BYTES = 32L * 1024L * 1024L

    val store = SessionChatByteLru(MAX_BYTES)

    @Volatile private var registered = false

    fun get(id: String): ByteArray? = store.get(id)

    fun put(
        id: String,
        value: ByteArray,
    ) = store.put(id, value)

    fun clear() = store.clear()

    /** Clears the cache on memory trims; idempotent. */
    fun register(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            registered = true
            context.applicationContext.registerComponentCallbacks(
                object : ComponentCallbacks2 {
                    override fun onConfigurationChanged(newConfig: Configuration) = Unit

                    @Deprecated("Deprecated in Java")
                    override fun onLowMemory() = clear()

                    override fun onTrimMemory(level: Int) = clear()
                },
            )
        }
    }
}

/** Least-recently-used byte arrays under a total size; an entry larger than the cap is not kept. */
class SessionChatByteLru(
    private val maxBytes: Long,
) {
    private val entries = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var total = 0L

    @Synchronized
    fun get(id: String): ByteArray? = entries[id]

    @Synchronized
    fun put(
        id: String,
        value: ByteArray,
    ) {
        if (value.size > maxBytes) return
        entries.put(id, value)?.let { total -= it.size }
        total += value.size
        val iterator = entries.entries.iterator()
        while (total > maxBytes && iterator.hasNext()) {
            total -= iterator.next().value.size
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        total = 0
    }

    @get:Synchronized
    val totalBytes: Long
        get() = total
}

/**
 * [OpenCodeGalleryImageLoader] over Session Chat images: resolves a gallery image id to its
 * attachment item, previews from the thumbnail already in memory or saved on the device, and
 * loads the full picture from its data URL, for an uploaded file through [serverLoader], or,
 * when only a cached thumbnail is here, through [fullImageLoader]. [update] swaps the images
 * it can see without recreating the loader, so the timeline does not recompose for it.
 */
class SessionChatGalleryImageLoader(
    private val fullImageLoader: (suspend (messageId: String, imageId: String) -> ByteArray)? = null,
    private val thumbnailReader: (path: String) -> ByteArray? = { path -> ToolImageThumbnails.readFile(path) },
    private val memory: SessionChatByteLru = SessionChatFullImageMemory.store,
    private val serverLoader: suspend (OpenCodeAttachmentImageSource.ServerPath) -> ByteArray?,
) : OpenCodeGalleryImageLoader {
    @Volatile private var messages: List<SessionChatMessageItem> = emptyList()

    @Volatile private var extra: List<SessionChatAttachmentItem> = emptyList()

    @Volatile private var extraMessageId: String? = null

    @Volatile private var index: Map<String, SessionChatImageSource>? = null

    fun update(
        messages: List<SessionChatMessageItem>,
        extra: List<SessionChatAttachmentItem> = emptyList(),
        extraMessageId: String? = null,
    ) {
        if (messages === this.messages && extra == this.extra && extraMessageId == this.extraMessageId) return
        this.messages = messages
        this.extra = extra
        this.extraMessageId = extraMessageId
        index = null
    }

    private fun source(id: String): SessionChatImageSource? {
        val current =
            index
                ?: (
                    SessionChatImageGalleries.sourcesById(messages) +
                        extra.associate { it.id to SessionChatImageSource(it, extraMessageId) }
                ).also { index = it }
        return current[id]
    }

    fun item(id: String): SessionChatAttachmentItem? = source(id)?.item

    override fun preview(id: String): ByteArray? = item(id)?.imageBytes

    override suspend fun thumbnail(id: String): ByteArray? {
        val path = item(id)?.thumbnailPath ?: return null
        return withContext(Dispatchers.IO) { thumbnailReader(path) }
    }

    override suspend fun load(id: String): ByteArray? = runCatching { loadFull(id) }.getOrNull()

    /** The full picture of [id]; throws when it cannot be loaded. */
    suspend fun loadFull(id: String): ByteArray {
        memory.get(id)?.let { return it }
        val source = source(id) ?: error("image is no longer in the timeline")
        val bytes =
            when (val imageSource = source.item.imageSource) {
                is OpenCodeAttachmentImageSource.ServerPath -> serverLoader(imageSource)
                is OpenCodeAttachmentImageSource.DataUrl ->
                    withContext(Dispatchers.Default) { AttachmentDataUrl.decode(imageSource.dataUrl, imageSource.mime) }
                null -> {
                    val loader = fullImageLoader ?: error("full image unavailable here")
                    val messageId = source.messageId ?: error("image has no message")
                    loader(messageId, id)
                }
            } ?: error("image could not be loaded")
        // Only pictures fetched for a cached thumbnail are worth keeping: the rest are already in memory.
        if (source.item.imageSource == null) memory.put(id, bytes)
        return bytes
    }
}
