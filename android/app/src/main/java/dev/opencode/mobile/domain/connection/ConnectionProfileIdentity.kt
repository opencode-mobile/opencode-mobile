package dev.opencode.mobile.domain.connection

import java.net.URI
import java.security.MessageDigest

object ConnectionProfileIdentity {
    const val FALLBACK_NAME = "Server"
    private const val FINGERPRINT_HEX_CHARS = 16

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

    /** SHA-256 hex prefix of `username + NUL + password`. Never log this or the raw password. */
    fun credentialFingerprint(
        username: String,
        password: String,
    ): String {
        val material = "${normalizeUsername(username)}\u0000$password"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }.take(FINGERPRINT_HEX_CHARS)
    }

    fun hasSameIdentity(
        left: ConnectionProfile,
        right: ConnectionProfile,
    ): Boolean =
        sameConnectionIdentity(
            left.baseUrl,
            left.username,
            right.baseUrl,
            right.username,
        ) &&
            credentialFingerprint(left.username, left.password) ==
            credentialFingerprint(right.username, right.password)
}
