package dev.opencode.mobile.network

import dev.opencode.mobile.data.local.ServerConnectionDefaults
import dev.opencode.mobile.domain.connection.ConnectionEngine
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ServerCapabilities
import dev.opencode.mobile.domain.connection.ServerProtocol

data class ServerConnection(
    val baseUrl: String,
    val username: String = ServerConnectionDefaults.USERNAME,
    val password: String? = null,
    val serverProtocol: ServerProtocol? = null,
    val engine: ConnectionEngine = ConnectionEngine.OPENCODE,
) {
    val apiBaseUrl: String =
        baseUrl.trimEnd('/') + '/'

    val capabilities: ServerCapabilities
        get() = ServerCapabilities.forEngine(engine, serverProtocol)

    fun hasSameIdentity(other: ServerConnection): Boolean = baseUrl == other.baseUrl && username == other.username

    fun withProtocol(protocol: ServerProtocol): ServerConnection = copy(serverProtocol = protocol)

    companion object {
        fun fromProfile(
            profile: ConnectionProfile,
            serverProtocol: ServerProtocol? = null,
        ): ServerConnection =
            ServerConnection(
                baseUrl = profile.baseUrl,
                username = profile.username.ifBlank { ServerConnectionDefaults.USERNAME },
                password = profile.password.takeIf { it.isNotEmpty() },
                serverProtocol = serverProtocol ?: profile.serverProtocol,
                engine = profile.engine,
            )
    }
}
