package dev.opencode.mobile.domain.t3

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object T3Urls {
    const val ORCHESTRATION_PROTOCOL = 2
    const val ORCHESTRATION_PROTOCOL_HEADER = "x-t3-orchestration-protocol"
    const val ENVIRONMENT_PATH = "/.well-known/t3/environment"
    const val TOKEN_PATH = "/oauth/token"
    const val SHELL_PATH = "/api/orchestration/shell"
    const val THREAD_PATH = "/api/orchestration/threads"
    const val WS_TICKET_PATH = "/api/auth/websocket-ticket"

    /**
     * T3 environments are normally reached over TLS (hosted pairing links are
     * https-only), so a host typed without a scheme defaults to https. LAN
     * opencode keeps its own http default elsewhere.
     */
    fun withDefaultScheme(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.contains("://")) return trimmed
        return "https://$trimmed"
    }

    fun join(
        baseUrl: String,
        path: String,
    ): String {
        val root = baseUrl.trim().trimEnd('/')
        val rel = if (path.startsWith('/')) path else "/$path"
        return root + rel
    }

    fun threadPath(threadId: String): String = "$THREAD_PATH/${threadId.trim()}"

    fun websocketUrl(
        baseUrl: String,
        ticket: String,
    ): String {
        val http = join(baseUrl, "/ws")
        val ws =
            when {
                http.startsWith("https://", ignoreCase = true) -> "wss://" + http.substring(8)
                http.startsWith("http://", ignoreCase = true) -> "ws://" + http.substring(7)
                else -> http
            }
        val encodedTicket = URLEncoder.encode(ticket, Charsets.UTF_8.name())
        return "$ws?wsTicket=$encodedTicket&orchestrationProtocol=$ORCHESTRATION_PROTOCOL"
    }

    /**
     * Parses the pairing link T3 Code renders as a QR / copyable URL.
     *
     * Desktop form: `http(s)://host[:port]/pair#token=<credential>` (token may also
     * sit in the query). Hosted form: `https://app.t3.codes/pair?host=<backend>&label=<label>#token=<credential>`.
     * Returns null when the text is not a T3 pairing link so the caller can try
     * other payload formats.
     */
    fun parsePairingLink(raw: String): T3PairingLink? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.rawPath?.trimEnd('/') != PAIRING_PATH) return null
        val query = formParams(uri.rawQuery)
        val fragment = formParams(uri.rawFragment)
        val token = fragment[PAIRING_TOKEN_PARAM]?.trim().orEmpty().ifEmpty { query[PAIRING_TOKEN_PARAM]?.trim().orEmpty() }
        if (token.isEmpty()) return null
        val hostedHost = query[HOSTED_PAIRING_HOST_PARAM]?.trim().orEmpty()
        val baseUrl =
            if (hostedHost.isNotEmpty()) {
                val hostedUri = runCatching { URI(withDefaultScheme(hostedHost)) }.getOrNull() ?: return null
                originOf(hostedUri) ?: return null
            } else {
                originOf(uri) ?: return null
            }
        return T3PairingLink(
            baseUrl = baseUrl,
            pairingToken = token,
            label = query[HOSTED_PAIRING_LABEL_PARAM]?.trim().orEmpty(),
        )
    }

    private fun originOf(uri: URI): String? {
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val authority = if (uri.port > 0) "$host:${uri.port}" else host
        return "$scheme://$authority/"
    }

    private fun formParams(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                decode(key) to decode(value)
            }
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)

    private const val PAIRING_PATH = "/pair"
    private const val PAIRING_TOKEN_PARAM = "token"
    private const val HOSTED_PAIRING_HOST_PARAM = "host"
    private const val HOSTED_PAIRING_LABEL_PARAM = "label"
}

/** Host and credential lifted from a T3 Code pairing link. */
data class T3PairingLink(
    val baseUrl: String,
    val pairingToken: String,
    val label: String,
)
