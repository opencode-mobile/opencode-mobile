package dev.opencode.mobile.domain.sync

import kotlinx.coroutines.flow.Flow

interface SyncRegistration {
    fun close()
}

interface SyncCoordinator {
    fun register(
        key: ProjectionKey,
        loader: suspend () -> Unit,
    ): SyncRegistration

    fun unregister(registration: SyncRegistration)

    fun observe(key: ProjectionKey): Flow<ProjectionState>

    suspend fun ensureFresh(key: ProjectionKey)

    fun invalidate(key: ProjectionKey?)

    fun bootstrap(reason: SyncBootstrapReason)

    fun markFocused(sessionKey: ProjectionKey.Transcript?)

    fun visible(
        key: ProjectionKey,
        visible: Boolean,
    )
}
