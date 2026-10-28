package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.session.PermissionRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface PermissionLiveStore {
    fun observePermissions(directory: String): Flow<List<PermissionRequest>>

    fun clearCache()

    fun invalidate(directory: String?) {}

    /**
     * Sessions currently known in [directory]. An indexed directory refetches only when a
     * permission event names one of them (or a session already in its last permission list).
     * A directory with no index still refetches on every permission event.
     */
    fun noteSessions(
        directory: String,
        sessionIds: Set<String>,
    ) {}

    /** Drops every directory index. Observations with no index refetch on any permission event. */
    fun clearSessionIndex() {}
}

object NoOpPermissionLiveStore : PermissionLiveStore {
    override fun observePermissions(directory: String): Flow<List<PermissionRequest>> = flowOf(emptyList())

    override fun clearCache() = Unit
}
