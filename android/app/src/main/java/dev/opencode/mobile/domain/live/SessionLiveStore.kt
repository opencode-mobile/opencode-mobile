package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.session.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface SessionLiveStore {
    fun observeSessions(directory: String): Flow<SessionListSnapshot>

    fun clearCache()

    suspend fun invalidate(directory: String?) {}

    /**
     * Raises [directory]'s window by [HomeSessionPaging.PAGE_SIZE] and refetches it in one
     * request; returns once that load settled. A no-op while a load-more for it runs or when
     * its latest list has nothing more.
     */
    suspend fun loadMore(directory: String) {}

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
