package dev.opencode.mobile.domain.connection

import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class PairingQRCredentials(
    val baseUrl: String,
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
        val url =
            wire.urls.firstOrNull { !isLoopback(it) }?.let(::normalizedBaseUrl)
                ?: return PairingQRParseResult.Failed(PairingQRError.NoReachableUrl)
        return PairingQRParseResult.Ready(
            PairingQRCredentials(
                baseUrl = url,
                username = ConnectionProfileIdentity.normalizeUsername(wire.username.orEmpty()),
                password = wire.password.orEmpty(),
            ),
        )
    }

    private fun normalizedBaseUrl(raw: String): String? {
        val trimmed = raw.trim()
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank()) return null
        val withoutQuery = withScheme.substringBefore('?').substringBefore('#')
        return ConnectionProfileIdentity.normalizeBaseUrl(withoutQuery)
    }

    fun isLoopback(raw: String): Boolean {
        val trimmed = raw.trim()
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val host =
            runCatching { URI(withScheme).host }
                .getOrNull()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
                ?: return true
        return host == "localhost" ||
            host == "::1" ||
            host == "0.0.0.0" ||
            host.startsWith("127.")
    }
}
