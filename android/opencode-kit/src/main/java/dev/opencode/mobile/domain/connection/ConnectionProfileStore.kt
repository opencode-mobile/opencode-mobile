package dev.opencode.mobile.domain.connection

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface ConnectionProfileStore {
    fun configurationRevisions(): Flow<Unit> = emptyFlow()

    suspend fun activeProfile(): ConnectionProfile?

    suspend fun listProfiles(): List<ConnectionProfile> = listOfNotNull(activeProfile())

    /**
     * The saved profiles and the active id without reading any password, so every password is
     * empty. Reading passwords decrypts each one through the secure store; use this when only names,
     * URLs or ids are shown.
     */
    suspend fun catalogWithoutPasswords(): ConnectionProfileCatalog =
        ConnectionProfileCatalog(
            profiles = listProfiles().map { it.copy(password = "") },
            activeId = activeProfile()?.id,
        )

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
