package dev.opencode.mobile.network

import dev.opencode.mobile.data.local.ServerConnectionDefaults
import dev.opencode.mobile.domain.connection.ConnectionProfile

data class ServerConnection(
    val baseUrl: String,
    val username: String = ServerConnectionDefaults.USERNAME,
    val password: String? = null,
) {
    val apiBaseUrl: String =
        baseUrl.trimEnd('/') + '/'

    companion object {
        fun fromProfile(profile: ConnectionProfile): ServerConnection =
            ServerConnection(
                baseUrl = profile.baseUrl,
                username = profile.username.ifBlank { ServerConnectionDefaults.USERNAME },
                password = profile.password.takeIf { it.isNotEmpty() },
            )
    }
}
