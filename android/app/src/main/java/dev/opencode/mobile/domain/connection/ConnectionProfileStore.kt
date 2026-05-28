package dev.opencode.mobile.domain.connection

interface ConnectionProfileStore {
  suspend fun activeProfile(): ConnectionProfile?

  suspend fun saveProfile(profile: ConnectionProfile)

  suspend fun addedWorktrees(serverBaseUrl: String): List<String>

  suspend fun addWorktree(serverBaseUrl: String, worktree: String)

  suspend fun setAddedWorktrees(serverBaseUrl: String, worktrees: List<String>)
}
