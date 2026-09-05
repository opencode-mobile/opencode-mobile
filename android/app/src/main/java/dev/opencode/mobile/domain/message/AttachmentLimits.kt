package dev.opencode.mobile.domain.message

object AttachmentLimits {
    const val MAX_DECODED_BYTES: Long = 20L * 1024L * 1024L
    const val TOO_LARGE_ERROR: String = "This file is larger than 20 MB."

    fun persistedDataUrl(value: String): String =
        if (value.startsWith("data:", ignoreCase = true)) "" else value
}
