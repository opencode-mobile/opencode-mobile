package dev.opencode.mobile.domain.sync

import kotlinx.coroutines.flow.Flow

/** The profile generation and connection a load started on; see [SyncCoordinator.isCurrent]. */
data class SyncLoadStamp(
    val generation: Long,
    val connection: Any?,
)

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

    fun beginProfileSwitch(generation: Long) = Unit

    val profileGeneration: Long
        get() = 0

    fun loadStamp(): SyncLoadStamp = SyncLoadStamp(profileGeneration, null)

    /** False once the profile changed or another connection was bound since [stamp] was taken. */
    fun isCurrent(stamp: SyncLoadStamp): Boolean = loadStamp() == stamp
}
