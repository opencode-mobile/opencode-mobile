package dev.opencode.mobile.domain.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object NoOpSyncCoordinator : SyncCoordinator {
    override fun register(
        key: ProjectionKey,
        loader: suspend () -> Unit,
    ): SyncRegistration =
        object : SyncRegistration {
            override fun close() = Unit
        }

    override fun unregister(registration: SyncRegistration) = Unit

    override fun observe(key: ProjectionKey): Flow<ProjectionState> = MutableStateFlow(ProjectionState.Fresh).asStateFlow()

    override suspend fun ensureFresh(key: ProjectionKey) = Unit

    override fun invalidate(key: ProjectionKey?) = Unit

    override fun bootstrap(reason: SyncBootstrapReason) = Unit

    override fun markFocused(sessionKey: ProjectionKey.Transcript?) = Unit

    override fun visible(
        key: ProjectionKey,
        visible: Boolean,
    ) = Unit
}
