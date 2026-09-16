package dev.opencode.mobile.domain.connection

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

data class PairingQRCredentials(
    val baseUrl: String,
    val urls: List<String>,
    val username: String,
    val password: String,
)

enum class PairingQRError {
    InvalidPayload,
    NoReachableUrl,
}

sealed interface PairingQRParseResult {
    data class Ready(
        val credentials: PairingQRCredentials,
    ) : PairingQRParseResult

    data class Failed(
        val error: PairingQRError,
    ) : PairingQRParseResult
}

object PairingQRPayload {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Wire(
        val urls: List<String>,
        val username: String? = null,
        val password: String? = null,
    )

    fun parse(raw: String): PairingQRParseResult {
        val wire =
            runCatching { json.decodeFromString<Wire>(raw) }.getOrNull()
                ?: return PairingQRParseResult.Failed(PairingQRError.InvalidPayload)
        val urls =
            wire.urls
                .filterNot(::isLoopback)
                .mapNotNull(::normalizedBaseUrl)
                .distinct()
        if (urls.isEmpty()) {
            return PairingQRParseResult.Failed(PairingQRError.NoReachableUrl)
        }
        return PairingQRParseResult.Ready(
            PairingQRCredentials(
                baseUrl = preferredDisplayUrl(urls),
                urls = urls,
                username = ConnectionProfileIdentity.normalizeUsername(wire.username.orEmpty()),
                password = wire.password.orEmpty(),
            ),
        )
    }

    fun preferredDisplayUrl(urls: List<String>): String = urls.firstOrNull(::isTailscaleUrl) ?: urls.first()

    fun pickReachableUrl(
        fieldUrl: String,
        reachable: List<String>,
    ): String? {
        if (reachable.isEmpty()) return null
        val field = ConnectionProfileIdentity.normalizeBaseUrl(fieldUrl)
        reachable
            .firstOrNull { ConnectionProfileIdentity.normalizeBaseUrl(it) == field }
            ?.let { return it }
        reachable.firstOrNull(::isTailscaleUrl)?.let { return it }
        return reachable.first()
    }

    fun isTailscaleUrl(raw: String): Boolean {
        val host = hostOf(raw) ?: return false
        if (host == "ts.net" || host.endsWith(".ts.net")) return true
        if (isCgnatIpv4(host)) return true
        return isTailscaleIpv6(host)
    }

    private fun normalizedBaseUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val scheme =
            when {
                trimmed.startsWith("https://", ignoreCase = true) -> "https"
                else -> "http"
            }
        val withoutScheme =
            when {
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring(8)
                trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(7)
                else -> trimmed
            }
        val hostPort =
            withoutScheme
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .trim()
                .trimEnd('/')
        if (hostPort.isEmpty()) return null
        val candidate = "$scheme://$hostPort"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank()) return null
        return ConnectionProfileIdentity.normalizeBaseUrl(candidate)
    }

    fun isLoopback(raw: String): Boolean {
        val host = hostOf(raw) ?: return true
        return host == "localhost" ||
            host == "::1" ||
            host == "0.0.0.0" ||
            host.startsWith("127.")
    }

    private fun hostOf(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        return runCatching { URI(withScheme).host }
            .getOrNull()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun isCgnatIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 100 && octets[1] in 64..127
    }

    private fun isTailscaleIpv6(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        return h.startsWith("fd7a:115c:a1e0:") || h.startsWith("fd7a:115c:a1e0::")
    }
}
