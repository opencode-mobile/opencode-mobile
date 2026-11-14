package dev.opencode.mobile.domain.message

/**
 * Images a tool returned (v1 completed-state `attachments`, v2 `content[type=file]`). Only
 * `data:` URLs of an image type the timeline can show are kept; other files are dropped.
 * The payload is not decoded here: a broken image shows as a failed tile, not a lost part.
 */
object ToolResultImages {
    /** Id of the [index]th image of tool part [toolPartId], the same on REST and live events. */
    fun id(
        toolPartId: String?,
        index: Int,
    ): String = "${toolPartId ?: "tool"}:image:$index"

    fun image(
        id: String,
        dataUrl: String?,
        mime: String?,
        name: String?,
    ): MessagePart.File? {
        val url = dataUrl?.takeIf { it.startsWith("data:", ignoreCase = true) } ?: return null
        // The data URL header is what the decoder checks, so it wins over the declared mime.
        val resolvedMime =
            (AttachmentDataUrl.mimeFromDataUrl(url) ?: mime ?: name?.let(AttachmentDataUrl::mimeFromFilename))
                ?.lowercase()
                ?: return null
        if (AttachmentDataUrl.kind(resolvedMime) != AttachmentKind.Image) return null
        if (!url.startsWith("data:$resolvedMime;base64,", ignoreCase = true)) return null
        return MessagePart.File(
            id = id,
            filename = displayName(name),
            mime = resolvedMime,
            kind = AttachmentKind.Image,
            dataUrl = url,
        )
    }

    /**
     * [incoming] tool images over [existing] ones, matched by id. A non-empty [incoming] list is
     * the authority for which images exist and their order; an empty one keeps [existing] (only
     * the terminal update carries files). Per image, a full data URL is never replaced by a
     * cached, thumbnail-only copy, and a known thumbnail is kept when the update has none.
     */
    fun merge(
        existing: List<MessagePart.File>,
        incoming: List<MessagePart.File>,
    ): List<MessagePart.File> {
        if (incoming.isEmpty()) return existing
        if (existing.isEmpty()) return incoming
        val byId = existing.associateBy { it.id }
        val merged =
            incoming.map { next ->
                val prior = byId[next.id] ?: return@map next
                mergeFile(prior, next)
            }
        return if (merged == existing) existing else merged
    }

    /** One file or image: data URL and thumbnail fill in from [prior] when [next] lacks them. */
    fun mergeFile(
        prior: MessagePart.File,
        next: MessagePart.File,
    ): MessagePart.File {
        val dataUrl = next.dataUrl.ifEmpty { prior.dataUrl }
        val thumbnailPath = next.thumbnailPath ?: prior.thumbnailPath
        return if (dataUrl == next.dataUrl && thumbnailPath == next.thumbnailPath) {
            next
        } else {
            next.copy(dataUrl = dataUrl, thumbnailPath = thumbnailPath)
        }
    }

    /** Tool images in [message] that carry their full data URL (not just cached metadata). */
    fun fullImageCount(message: Message): Int =
        message.parts.sumOf { part -> (part as? MessagePart.Tool)?.images?.count { it.hasFullData } ?: 0 }

    /** v2 `read` names the image by the path it read; the tile shows the file name only. */
    private fun displayName(name: String?): String {
        val trimmed = name?.trim().orEmpty().trimEnd('/', '\\')
        val last = trimmed.substringAfterLast('/').substringAfterLast('\\')
        return last.ifEmpty { "Image" }
    }
}
