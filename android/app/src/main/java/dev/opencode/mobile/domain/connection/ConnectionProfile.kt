package dev.opencode.mobile.domain.connection

data class ConnectionProfile(
    val baseUrl: String,
    val username: String,
    val password: String = "",
    val serverProtocol: ServerProtocol? = null,
    val id: String = "",
    val name: String = "",
    val serverUrls: List<String> = emptyList(),
    val engine: ConnectionEngine = ConnectionEngine.OPENCODE,
) {
    val displayName: String
        get() = name.trim().ifBlank { ConnectionProfileIdentity.hostLabel(baseUrl) }

    fun hasSameIdentity(other: ConnectionProfile): Boolean = ConnectionProfileIdentity.hasSameIdentity(this, other)
}
