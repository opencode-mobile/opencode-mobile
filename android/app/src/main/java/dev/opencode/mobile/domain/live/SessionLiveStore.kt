package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.session.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface SessionLiveStore {
    fun observeSessions(directory: String): Flow<SessionListSnapshot>

    fun clearCache()

    suspend fun invalidate(directory: String?) {}

    /**
     * Latest list copy of [sessionId] from any observed directory, or null when no
     * observed list carries it. Emits again when that session changes.
     */
    fun observeKnownSession(sessionId: String): Flow<Session?> = flowOf(null)

    /**
     * Applies a server read acknowledgement locally (`session.viewed` echo or a
     * successful view post) so lists update without a REST refetch.
     */
    fun applySessionViewed(
        sessionId: String,
        idleAtEpochMs: Long,
    ) {}
}
