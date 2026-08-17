package dev.opencode.mobile.domain.message

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object AttachmentDataUrl {
    private val imageMimes = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

    fun kind(mime: String): AttachmentKind? =
        when (mime.lowercase()) {
            in imageMimes -> AttachmentKind.Image
            "application/pdf" -> AttachmentKind.Pdf
            "text/plain" -> AttachmentKind.Text
            else -> null
        }

    @OptIn(ExperimentalEncodingApi::class)
    fun make(
        bytes: ByteArray,
        mime: String,
    ): String? {
        val normalizedMime = mime.lowercase()
        if (kind(normalizedMime) == null) return null
        return "data:$normalizedMime;base64,${Base64.Default.encode(bytes)}"
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun fromBase64(
        base64: String,
        mime: String,
    ): String? {
        val bytes = runCatching { Base64.Default.decode(base64) }.getOrNull() ?: return null
        return make(bytes, mime)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decode(
        value: String,
        expectedMime: String,
    ): ByteArray? {
        val prefix = "data:${expectedMime.lowercase()};base64,"
        if (!value.startsWith(prefix, ignoreCase = true)) return null
        return runCatching { Base64.Default.decode(value.substring(prefix.length)) }.getOrNull()
    }

    fun validates(
        value: String,
        mime: String,
    ): Boolean = kind(mime) != null && decode(value, mime) != null

    fun mimeFromDataUrl(value: String): String? {
        if (!value.startsWith("data:", ignoreCase = true)) return null
        val header = value.substringAfter("data:").substringBefore(',', missingDelimiterValue = "")
        val mime = header.substringBefore(';').trim().lowercase()
        return mime.takeIf { it.isNotEmpty() && kind(it) != null }
    }
}
