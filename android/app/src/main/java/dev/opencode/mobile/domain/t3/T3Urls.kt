package dev.opencode.mobile.domain.t3

import java.net.URLEncoder

object T3Urls {
    const val ORCHESTRATION_PROTOCOL = 2
    const val ORCHESTRATION_PROTOCOL_HEADER = "x-t3-orchestration-protocol"
    const val ENVIRONMENT_PATH = "/.well-known/t3/environment"
    const val TOKEN_PATH = "/oauth/token"
    const val SHELL_PATH = "/api/orchestration/shell"
    const val THREAD_PATH = "/api/orchestration/threads"
    const val WS_TICKET_PATH = "/api/auth/websocket-ticket"

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
}
