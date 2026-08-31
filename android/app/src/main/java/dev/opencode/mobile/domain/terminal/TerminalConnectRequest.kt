package dev.opencode.mobile.domain.terminal

import dev.opencode.mobile.domain.connection.ServerProtocol
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

data class TerminalConnectRequest(
    val url: String,
) {
    companion object {
        fun build(
            id: String,
            directory: String,
            protocol: ServerProtocol,
            baseUrl: String,
            cursor: String,
            ticket: String? = null,
            username: String,
            password: String,
        ): TerminalConnectRequest {
            val isV1 = protocol == ServerProtocol.V1
            val path = if (isV1) "/pty/$id/connect" else "/api/pty/$id/connect"
            val httpUrl = joinUrl(baseUrl, path)
            val scheme =
                if (httpUrl.startsWith("https://", ignoreCase = true) ||
                    httpUrl.startsWith("wss://", ignoreCase = true)
                ) {
                    "wss"
                } else {
                    "ws"
                }
            val withoutScheme = httpUrl.substringAfter("://")
            val query =
                buildList {
                    if (isV1) {
                        add("directory" to directory)
                    } else {
                        add("location[directory]" to directory)
                    }
                    add("cursor" to cursor)
                    val trimmedTicket = ticket?.takeIf { it.isNotBlank() }
                    if (trimmedTicket != null) {
                        add("ticket" to trimmedTicket)
                    } else if (isV1 && password.isNotEmpty()) {
                        val user = username.ifBlank { "opencode" }
                        add("auth_token" to basicToken(user, password))
                    }
                }.joinToString("&") { (name, value) ->
                    "${queryEncode(name)}=${queryEncode(value)}"
                }
            return TerminalConnectRequest("$scheme://$withoutScheme?$query")
        }
    }
}

fun connectRequest(
    id: String,
    directory: String,
    protocol: ServerProtocol,
    baseUrl: String,
    cursor: String,
    ticket: String? = null,
    username: String,
    password: String,
): TerminalConnectRequest =
    TerminalConnectRequest.build(
        id = id,
        directory = directory,
        protocol = protocol,
        baseUrl = baseUrl,
        cursor = cursor,
        ticket = ticket,
        username = username,
        password = password,
    )

private fun joinUrl(
    baseUrl: String,
    path: String,
): String {
    val base = baseUrl.trimEnd('/')
    val suffix = path.trimStart('/')
    return "$base/$suffix"
}

private fun basicToken(
    username: String,
    password: String,
): String = Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))

private fun queryEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
