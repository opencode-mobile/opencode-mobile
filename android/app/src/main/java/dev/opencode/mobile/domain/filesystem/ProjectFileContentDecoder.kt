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
            if (bytes != null && isImage(path, mimeType)) {
                return if (bytes.size <= ProjectFileLimits.IMAGE_PREVIEW_BYTES) {
                    ProjectFileContent.Image(bytes, mimeType)
                } else {
                    ProjectFileContent.TooLarge(byteCount = bytes.size.toLong())
                }
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

    /** v1 body that went past [ProjectFileLimits.V1_BODY_BYTES]; the file size is unknown. */
    fun v1TooLarge(): ProjectFileContent = ProjectFileContent.TooLarge(byteCount = null)

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
                ProjectFileContent.TooLarge(byteCount = totalBytes)
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
     * First [limit] bytes of content known to be cut, backed off to a UTF-8 boundary, strictly
     * decoded, with the trailing partial line dropped. Null when the prefix is not valid UTF-8.
     */
    fun truncatedText(
        bytes: ByteArray,
        limit: Int,
    ): String? {
        val end = utf8BoundaryAtOrBefore(bytes, minOf(limit, bytes.size))
        val text = decodeStrictUtf8(bytes, end) ?: return null
        val lastNewline = text.lastIndexOf('\n')
        return if (lastNewline >= 0) text.substring(0, lastNewline) else text
    }

    /**
     * [end] moved back so `bytes[0, end)` does not finish inside a multibyte sequence. The cut
     * may sit at the end of the array (a 206 body of exactly the requested range), so this looks
     * back from `end - 1` for the lead byte instead of forward at the first byte left out.
     * Malformed input is left for the strict decoder to reject.
     */
    internal fun utf8BoundaryAtOrBefore(
        bytes: ByteArray,
        end: Int,
    ): Int {
        var lead = end - 1
        while (lead >= 0 && end - lead < UTF8_MAX_SEQUENCE && bytes[lead].isContinuation()) lead--
        if (lead < 0) return end
        val length =
            when (bytes[lead].toInt() and 0xFF) {
                in 0xC0..0xDF -> 2
                in 0xE0..0xEF -> 3
                in 0xF0..0xF7 -> 4
                else -> return end
            }
        return if (lead + length > end) lead else end
    }

    private fun Byte.isContinuation(): Boolean = (toInt() and 0xC0) == 0x80

    private const val UTF8_MAX_SEQUENCE = 4

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
