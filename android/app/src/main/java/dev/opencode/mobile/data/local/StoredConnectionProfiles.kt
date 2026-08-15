package dev.opencode.mobile.data.local

import kotlinx.serialization.Serializable

@Serializable
data class StoredConnectionProfiles(
    val activeId: String? = null,
    val profiles: List<StoredConnectionProfile> = emptyList(),
)

@Serializable
data class StoredConnectionProfile(
    val id: String,
    val name: String = "",
    val baseUrl: String,
    val username: String,
    val serverProtocol: String? = null,
)
