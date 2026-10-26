package dev.opencode.mobile.domain.message

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One entry of a prompt's `metadata.attachments`: a file the client uploaded and named in the text. */
data class UploadedAttachment(
    val name: String,
    val mime: String,
    val path: String,
) {
    val kind: AttachmentKind get() = AttachmentDataUrl.kindOrFile(mime)
}

/** Naming uploaded files on the wire: server paths, the prompt text lines, and `metadata.attachments`. */
object UploadedAttachments {
    /** `<tmp>/uploads/<uuid>/<filename>`, one directory per file so names never collide. */
    fun serverPath(
        tmp: String,
        uuid: String,
        filename: String,
    ): String {
        val separator = if (tmp.contains('\\') && !tmp.contains('/')) '\\' else '/'
        val safeName = filename.replace('/', '_').replace('\\', '_').ifBlank { "attachment" }
        return "${tmp.trimEnd('/', '\\')}${separator}uploads$separator$uuid$separator$safeName"
    }

    /** Prompt text line naming an uploaded file, shared by both apps. */
    fun promptLine(serverPath: String): String = "Attached file: `$serverPath`"

    /** [text] followed by one [promptLine] per path, separated by a newline. */
    fun promptText(
        text: String,
        serverPaths: List<String>,
    ): String {
        if (serverPaths.isEmpty()) return text
        val lines = serverPaths.joinToString("\n", transform = ::promptLine)
        return if (text.isBlank()) lines else "$text\n$lines"
    }

    fun parse(metadata: JsonObject?): List<UploadedAttachment> {
        val entries = metadata?.get("attachments") as? JsonArray ?: return emptyList()
        return entries.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val path = string(entry["path"]) ?: return@mapNotNull null
            val name = string(entry["name"]) ?: path.substringAfterLast('/').substringAfterLast('\\')
            val mime = string(entry["mime"])?.lowercase() ?: AttachmentDataUrl.mimeFromFilename(name) ?: AttachmentMime.BINARY
            UploadedAttachment(name.ifBlank { "Attachment" }, mime, path)
        }
    }

    /** The prompt text without the `Attached file:` lines that the chips already say. */
    fun stripLines(
        text: String,
        uploads: List<UploadedAttachment>,
    ): String {
        if (uploads.isEmpty() || text.isEmpty()) return text
        val lines = uploads.map { promptLine(it.path) }.toSet()
        return text
            .lines()
            .filterNot { it.trim() in lines }
            .joinToString("\n")
            .trimEnd()
    }

    private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
