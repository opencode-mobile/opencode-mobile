package dev.opencode.mobile.domain.message

import java.io.File

object AttachmentLimits {
    const val MAX_DECODED_BYTES: Long = 20L * 1024L * 1024L
    const val TOO_LARGE_ERROR: String = "This file is larger than 20 MB."
    const val STORE_DIRECTORY_NAME: String = "opencode-attachments"

    fun persistedDataUrl(value: String): String = if (value.startsWith("data:", ignoreCase = true)) "" else value

    fun discard(path: String?) {
        val file = path?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        if (file.parentFile?.name != STORE_DIRECTORY_NAME) return
        runCatching { file.delete() }
    }
}
