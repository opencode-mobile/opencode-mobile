package dev.opencode.mobile.domain.filesystem

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.io.encoding.Base64

/** Turns v1 and v2 read responses into [ProjectFileContent]. Pure, so both protocols share tests. */
object ProjectFileContentDecoder {
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp")

    fun isImagePath(path: String): Boolean = path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in imageExtensions

    private fun isImage(
        path: String,
        mimeType: String?,
    ): Boolean = isImagePath(path) || mimeType?.lowercase()?.startsWith("image/") == true

    /** v1 `/file/content` body. Text arrives decoded (and `.trim()`med by the server). */
    fun decodeV1(
        path: String,
        type: String,
        content: String,
        encoding: String?,
        mimeType: String?,
    ): ProjectFileContent {
        val isBase64 = encoding.equals("base64", ignoreCase = true)
        if (type == "binary" || isBase64) {
            val bytes = if (isBase64) runCatching { Base64.Default.decode(content) }.getOrNull() else null
            if (bytes != null && bytes.isEmpty()) return ProjectFileContent.Empty
            if (bytes != null && isImage(path, mimeType) && bytes.size <= ProjectFileLimits.IMAGE_PREVIEW_BYTES) {
                return ProjectFileContent.Image(bytes, mimeType)
            }
            return ProjectFileContent.Binary(mimeType = mimeType, byteCount = bytes?.size?.toLong())
        }
        if (content.isEmpty()) return ProjectFileContent.Empty
        val bytes = content.encodeToByteArray()
        if (bytes.size <= ProjectFileLimits.TEXT_PREVIEW_BYTES) {
            return ProjectFileContent.Text(content, isTruncated = false, totalBytes = bytes.size.toLong())
        }
        val prefix =
            truncatedText(bytes, ProjectFileLimits.TEXT_PREVIEW_BYTES) ?: return ProjectFileContent.Binary(mimeType, bytes.size.toLong())
        return ProjectFileContent.Text(prefix, isTruncated = true, totalBytes = bytes.size.toLong())
    }

    /** v1 body that went past [ProjectFileLimits.V1_BODY_BYTES]. */
    fun v1TooLarge(): ProjectFileContent = ProjectFileContent.Binary()

    /**
     * v2 `/api/fs/read` bytes. [bytes] holds at most the requested limit + 1; [exceededLimit] means
     * more bytes exist than were kept.
     */
    fun decodeV2(
        path: String,
        bytes: ByteArray,
        exceededLimit: Boolean,
        totalBytes: Long?,
        contentType: String?,
        requestedAsImage: Boolean,
    ): ProjectFileContent {
        if (bytes.isEmpty() && !exceededLimit) return ProjectFileContent.Empty
        if (requestedAsImage) {
            return if (exceededLimit) {
                ProjectFileContent.Binary(mimeType = contentType, byteCount = totalBytes)
            } else {
                ProjectFileContent.Image(bytes, contentType)
            }
        }
        val sniffEnd = minOf(bytes.size, ProjectFileLimits.BINARY_SNIFF_BYTES)
        for (index in 0 until sniffEnd) {
            if (bytes[index] == 0.toByte()) {
                return ProjectFileContent.Binary(
                    mimeType = contentType,
                    byteCount =
                        totalBytes ?: bytes.size.takeUnless { exceededLimit }?.toLong(),
                )
            }
        }
        val truncated = exceededLimit || bytes.size > ProjectFileLimits.TEXT_PREVIEW_BYTES
        val text =
            if (truncated) {
                truncatedText(bytes, ProjectFileLimits.TEXT_PREVIEW_BYTES)
            } else {
                decodeStrictUtf8(bytes, bytes.size)
            } ?: return ProjectFileContent.Binary(mimeType = contentType, byteCount = totalBytes)
        return ProjectFileContent.Text(
            text = text,
            isTruncated = truncated,
            totalBytes = totalBytes ?: bytes.size.takeUnless { truncated }?.toLong(),
        )
    }

    /**
     * First [limit] bytes cut back to a UTF-8 boundary, strictly decoded, with the trailing partial
     * line dropped. Null when the prefix is not valid UTF-8.
     */
    fun truncatedText(
        bytes: ByteArray,
        limit: Int,
    ): String? {
        var end = minOf(limit, bytes.size)
        if (end < bytes.size) {
            // bytes[end] is the first byte left out; a continuation byte means a split character.
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        }
        val text = decodeStrictUtf8(bytes, end) ?: return null
        val lastNewline = text.lastIndexOf('\n')
        return if (lastNewline >= 0) text.substring(0, lastNewline) else text
    }

    fun decodeStrictUtf8(
        bytes: ByteArray,
        length: Int,
    ): String? =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, length))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
}
