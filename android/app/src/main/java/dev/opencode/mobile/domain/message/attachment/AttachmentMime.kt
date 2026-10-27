package dev.opencode.mobile.domain.message

import java.util.Locale

/**
 * Normalizes a file's mime the way the opencode desktop app does before it picks a destination.
 * The rule is shared with iOS through `shared/attachment-mime/attachment-mime-cases.json`.
 */
object AttachmentMime {
    const val BINARY = "application/octet-stream"
    const val TEXT = "text/plain"

    /** Bytes inspected for a NUL when the declared type says nothing. */
    const val SNIFF_BYTES = 4096

    private val keptTypes =
        setOf("image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf")
    private val textTypes =
        setOf(
            "application/json",
            "application/xml",
            "application/yaml",
            "application/javascript",
            "application/x-sh",
        )

    /** [declared] is the provider's type or null; [head] is the first [SNIFF_BYTES] of the file. */
    fun normalize(
        declared: String?,
        head: ByteArray,
    ): String {
        val mime =
            declared
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.US)
                ?.takeIf { it.isNotEmpty() }
        return when {
            mime in keptTypes -> mime!!
            mime != null && (mime.startsWith("text/") || mime in textTypes) -> TEXT
            head.take(SNIFF_BYTES).any { it == 0.toByte() } -> mime ?: BINARY
            else -> TEXT
        }
    }

    /**
     * The type to classify with: the provider's, unless it names nothing useful (null, blank or the
     * generic `application/octet-stream`) and the extension is one the app reads natively.
     */
    fun resolveDeclared(
        declared: String?,
        filename: String,
    ): String? {
        val mime = declared?.substringBefore(';')?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
        if (mime != null && mime != BINARY) return declared
        return declaredFromExtension(filename) ?: declared
    }

    /** The type a provider could not name, recovered from the extensions the app already knows. */
    fun declaredFromExtension(filename: String): String? =
        when (filename.substringAfterLast('.', "").lowercase(Locale.US)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            else -> null
        }
}
