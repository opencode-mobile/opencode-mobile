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

    fun normalizeServerUrls(urls: List<String>): List<String> =
        urls.map(::normalizeBaseUrl).filter { it.isNotBlank() }.distinct()

    /** Manual profiles contribute only [ConnectionProfile.baseUrl]. QR profiles contribute every saved address. */
    fun addressesOf(profile: ConnectionProfile): Set<String> {
        val raw =
            if (profile.serverUrls.isEmpty()) {
                listOf(profile.baseUrl)
            } else {
                profile.serverUrls + profile.baseUrl
            }
        return raw.map(::normalizeBaseUrl).filter { it.isNotBlank() }.toSet()
    }

    /**
     * Manual profiles stay baseUrl + username. A QR profile matches when the username matches and
     * any saved address overlaps.
     */
    fun overlapsConnection(
        left: ConnectionProfile,
        right: ConnectionProfile,
    ): Boolean {
        if (normalizeUsername(left.username) != normalizeUsername(right.username)) return false
        if (left.serverUrls.isEmpty() && right.serverUrls.isEmpty()) {
            return sameConnectionIdentity(left.baseUrl, left.username, right.baseUrl, right.username)
        }
        return addressesOf(left).intersect(addressesOf(right)).isNotEmpty()
    }

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
    ): Boolean {
        if (credentialFingerprint(left.username, left.password) != credentialFingerprint(right.username, right.password)) {
            return false
        }
        if (sameConnectionIdentity(left.baseUrl, left.username, right.baseUrl, right.username)) {
            return true
        }
        if (left.id.isBlank() || left.id != right.id) return false
        val owned =
            (left.serverUrls + right.serverUrls)
                .map(::normalizeBaseUrl)
                .filter { it.isNotBlank() }
                .toSet()
        if (owned.isEmpty()) return false
        val leftUrl = normalizeBaseUrl(left.baseUrl)
        val rightUrl = normalizeBaseUrl(right.baseUrl)
        return leftUrl in owned && rightUrl in owned
    }
}
