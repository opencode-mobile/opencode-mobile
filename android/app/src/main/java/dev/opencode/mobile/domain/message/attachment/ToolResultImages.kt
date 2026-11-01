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

    /** v2 `read` names the image by the path it read; the tile shows the file name only. */
    private fun displayName(name: String?): String {
        val trimmed = name?.trim().orEmpty().trimEnd('/', '\\')
        val last = trimmed.substringAfterLast('/').substringAfterLast('\\')
        return last.ifEmpty { "Image" }
    }
}
