package dev.opencode.mobile.domain.connection

import java.net.URI

object ConnectionProfileIdentity {
    const val FALLBACK_NAME = "Server"

    fun normalizeBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.endsWith('/')) trimmed else "$trimmed/"
    }

    fun normalizeUsername(username: String): String = username.trim().ifBlank { "opencode" }

    fun hostLabel(baseUrl: String): String {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        val host = uri?.host?.takeIf { it.isNotBlank() }
        val port = uri?.port?.takeIf { it > 0 }
        return when {
            host != null && port != null -> "$host:$port"
            host != null -> host
            else -> FALLBACK_NAME
        }
    }

    fun sameConnectionIdentity(
        leftUrl: String,
        leftUsername: String,
        rightUrl: String,
        rightUsername: String,
    ): Boolean =
        normalizeBaseUrl(leftUrl) == normalizeBaseUrl(rightUrl) &&
            normalizeUsername(leftUsername) == normalizeUsername(rightUsername)
}
