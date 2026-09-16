package dev.opencode.mobile.domain.recovery

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MutableWorktreeProfileStore(
    worktrees: List<String> = emptyList(),
) : ConnectionProfileStore {
    private var profile: ConnectionProfile? = null
    private val worktreesByServer = mutableMapOf<String, MutableList<String>>()
    private val revisions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        if (worktrees.isNotEmpty()) {
            worktreesByServer[DEFAULT_SERVER] = worktrees.toMutableList()
        }
    }

    override fun configurationRevisions(): Flow<Unit> = revisions.asSharedFlow()

    override suspend fun activeProfile(): ConnectionProfile? = profile

    override suspend fun saveProfile(profile: ConnectionProfile) {
        this.profile = profile
        worktreesByServer.putIfAbsent(profile.baseUrl, mutableListOf())
        revisions.tryEmit(Unit)
    }

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = worktreesByServer[serverBaseUrl].orEmpty()

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {
        worktreesByServer.getOrPut(serverBaseUrl) { mutableListOf() }.add(worktree)
    }

    override suspend fun removeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {
        worktreesByServer[serverBaseUrl]?.remove(worktree)
    }

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) {
        worktreesByServer[serverBaseUrl] = worktrees.toMutableList()
        revisions.tryEmit(Unit)
    }

    override suspend fun clearAll() {
        profile = null
        worktreesByServer.clear()
    }

    companion object {
        const val DEFAULT_SERVER = "http://127.0.0.1:4096/"
    }
}
