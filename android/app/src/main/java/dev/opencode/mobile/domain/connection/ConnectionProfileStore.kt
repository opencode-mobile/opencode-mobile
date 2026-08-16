package dev.opencode.mobile.domain.connection

interface ConnectionProfileStore {
    suspend fun activeProfile(): ConnectionProfile?

    suspend fun listProfiles(): List<ConnectionProfile> = listOfNotNull(activeProfile())

    suspend fun saveProfile(profile: ConnectionProfile)

    suspend fun applyProfile(id: String) = Unit

    suspend fun removeProfile(id: String) = Unit

    suspend fun addedWorktrees(serverBaseUrl: String): List<String>

    suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    )

    suspend fun removeWorktree(
        serverBaseUrl: String,
        worktree: String,
    )

    suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    )

    suspend fun clearAll()
}
