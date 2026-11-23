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

    /**
     * Rewrites a stored profile's address or protocol, never its password. Returns false, and
     * stores nothing, when [id] is gone: a late probe must not bring a removed profile back.
     */
    suspend fun updateStoredProfile(
        id: String,
        transform: (ConnectionProfile) -> ConnectionProfile,
    ): Boolean {
        val stored = listProfiles().firstOrNull { it.id == id } ?: return false
        saveProfile(transform(stored))
        return true
    }

    suspend fun applyProfile(id: String) = Unit

    suspend fun removeProfile(id: String) = Unit

    suspend fun addedWorktrees(serverBaseUrl: String): List<String>

    /** [profileId]'s projects, for a profile that is not bound yet; a URL can belong to two usernames. */
    suspend fun addedWorktreesForProfile(
        profileId: String,
        serverBaseUrl: String,
    ): List<String> = addedWorktrees(serverBaseUrl)

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
