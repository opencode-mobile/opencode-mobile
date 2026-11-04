package dev.opencode.mobile.domain.connection

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.net.URI

data class PairingQRCredentials(
    val baseUrl: String,
    val urls: List<String>,
    val username: String,
    val password: String,
    val code: String? = null,
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
    private val pairingCode = Regex("^[A-Za-z0-9_-]+$")
    private val connectPath = Regex("^/auth/connect/([^/]+)$")

    @Serializable
    private data class Wire(
        val urls: List<String>,
        val username: String? = null,
        val password: String? = null,
    )

    fun parse(raw: String): PairingQRParseResult {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return parseJson(trimmed)
        return parseConnectLink(trimmed)
    }

    fun isConnectLink(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("{")) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val path = uri.rawPath ?: return false
        return path == "/auth/connect" || path.startsWith("/auth/connect/")
    }

    fun preferredDisplayUrl(urls: List<String>): String = urls.firstOrNull(::isTailscaleUrl) ?: urls.first()

    /**
     * Tailscale wins when it answered. Otherwise the first other address that answered.
     * When [currentBaseUrl] is `https`, only `https` candidates are eligible: a saved encrypted
     * address is never replaced with a cleartext one.
     */
    fun pickReachableUrl(
        reachable: List<String>,
        currentBaseUrl: String? = null,
    ): String? {
        val keepEncrypted = currentBaseUrl?.let(::isEncryptedUrl) ?: false
        val eligible = reachable.filter { !keepEncrypted || isEncryptedUrl(it) }
        if (eligible.isEmpty()) return null
        return eligible.firstOrNull(::isTailscaleUrl) ?: eligible.first()
    }

    fun isEncryptedUrl(raw: String): Boolean {
        val trimmed = raw.trim().lowercase()
        return trimmed.startsWith("https://") || trimmed.startsWith("wss://")
    }

    fun isTailscaleUrl(raw: String): Boolean {
        val host = hostOf(raw) ?: return false
        if (host == "ts.net" || host.endsWith(".ts.net")) return true
        if (isCgnatIpv4(host)) return true
        return isTailscaleIpv6(host)
    }

    /** OpenCode remote tunnel. Tailscale wins when a host matches both checks. */
    fun isRemoteTunnelUrl(raw: String): Boolean {
        if (isTailscaleUrl(raw)) return false
        val host = hostOf(raw) ?: return false
        return host.endsWith(".opentunnel.xyz")
    }

    private fun parseJson(raw: String): PairingQRParseResult {
        val element =
            runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
                ?: return invalid()
        if (element.containsKey("code")) return parseCodedJson(element)
        return parseLegacyJson(element)
    }

    private fun parseCodedJson(element: JsonObject): PairingQRParseResult {
        val codeElement = element["code"] as? JsonPrimitive ?: return invalid()
        if (!codeElement.isString) return invalid()
        val code = codeElement.content
        if (!pairingCode.matches(code)) return invalid()
        val urlsElement = element["urls"] as? JsonArray ?: return invalid()
        if (urlsElement.isEmpty()) return invalid()
        val parsed = ArrayList<String>(urlsElement.size)
        for (item in urlsElement) {
            val urlElement = item as? JsonPrimitive ?: return invalid()
            if (!urlElement.isString) return invalid()
            val origin = strictHttpOrigin(urlElement.content) ?: return invalid()
            parsed += origin
        }
        val urls = parsed.filterNot(::isLoopback).distinct()
        if (urls.isEmpty()) {
            return PairingQRParseResult.Failed(PairingQRError.NoReachableUrl)
        }
        return PairingQRParseResult.Ready(
            PairingQRCredentials(
                baseUrl = preferredDisplayUrl(urls),
                urls = urls,
                username = ConnectionProfileIdentity.normalizeUsername(""),
                password = "",
                code = code,
            ),
        )
    }

    private fun parseLegacyJson(element: JsonObject): PairingQRParseResult {
        val wire =
            runCatching { json.decodeFromJsonElement<Wire>(element) }.getOrNull()
                ?: return invalid()
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

    private fun parseConnectLink(raw: String): PairingQRParseResult {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return invalid()
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return invalid()
        if (!uri.userInfo.isNullOrEmpty()) return invalid()
        if (uri.rawQuery != null || uri.rawFragment != null) return invalid()
        val code = connectPath.matchEntire(uri.rawPath ?: return invalid())?.groupValues?.get(1)
        if (code == null || !pairingCode.matches(code)) return invalid()
        val origin = originOf(uri) ?: return invalid()
        return PairingQRParseResult.Ready(
            PairingQRCredentials(
                baseUrl = origin,
                urls = listOf(origin),
                username = ConnectionProfileIdentity.normalizeUsername(""),
                password = "",
                code = code,
            ),
        )
    }

    private fun strictHttpOrigin(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (
            !trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.userInfo.isNullOrEmpty()) return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        val path = uri.rawPath.orEmpty()
        if (path.isNotEmpty() && path != "/") return null
        return originOf(uri)
    }

    private fun originOf(uri: URI): String? {
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val formattedHost = if (host.contains(':')) "[$host]" else host
        val hostPort = if (uri.port >= 0) "$formattedHost:${uri.port}" else formattedHost
        return ConnectionProfileIdentity.normalizeBaseUrl("$scheme://$hostPort")
    }

    private fun invalid(): PairingQRParseResult = PairingQRParseResult.Failed(PairingQRError.InvalidPayload)

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
