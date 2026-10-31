package dev.opencode.mobile.domain.message

/**
 * Thumbnails for image file parts that arrive as data URLs with no preview (the server
 * echo of a sent photo, history reloads). Small images decode on first read; larger ones
 * only through [warm], which the timeline projection calls off the main thread, so block
 * building on main never decodes a full photo.
 */
class AttachmentPreviewCache(
    private val maxEntries: Int = 48,
    private val thumbnailer: (ByteArray) -> ByteArray? = AttachmentThumbnailer::create,
) {
    private data class Key(
        val id: String,
        val dataUrlLength: Int,
    )

    /** `null` value: decoded and not previewable. */
    private val entries = LinkedHashMap<Key, ByteArray?>(16, 0.75f, true)

    /** Cached preview for [file], decoding it now when its data URL is small. */
    fun preview(file: MessagePart.File): ByteArray? {
        val key = key(file) ?: return null
        synchronized(entries) {
            if (entries.containsKey(key)) return entries[key]
        }
        if (file.dataUrl.length > INLINE_DECODE_MAX_DATA_URL_CHARS) return null
        return decodeAndStore(file, key)
    }

    /** Decodes missing previews of any size. Call off the main thread. */
    fun warm(messages: List<Message>) {
        messages.forEach { message ->
            message.parts.forEach { part ->
                val files =
                    when (part) {
                        is MessagePart.File -> listOf(part)
                        is MessagePart.Tool -> part.images
                        else -> return@forEach
                    }
                files.forEach { file ->
                    val key = key(file) ?: return@forEach
                    val cached = synchronized(entries) { entries.containsKey(key) }
                    if (!cached) decodeAndStore(file, key)
                }
            }
        }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    private fun decodeAndStore(
        file: MessagePart.File,
        key: Key,
    ): ByteArray? {
        val preview = AttachmentDataUrl.decode(file.dataUrl, file.mime)?.let(thumbnailer)
        synchronized(entries) {
            entries[key] = preview
            val iterator = entries.keys.iterator()
            while (entries.size > maxEntries && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return preview
    }

    private fun key(file: MessagePart.File): Key? {
        if (file.kind != AttachmentKind.Image || file.dataUrl.isEmpty()) return null
        return Key(file.id, file.dataUrl.length)
    }

    companion object {
        val shared = AttachmentPreviewCache()

        /** Callers warm only the newest messages, so image-heavy history does not hold back first paint. */
        const val WARM_MESSAGE_COUNT = 6

        /** Data URLs up to this size decode on read, as before the cache existed. */
        const val INLINE_DECODE_MAX_DATA_URL_CHARS = 512 * 1024
    }
}
