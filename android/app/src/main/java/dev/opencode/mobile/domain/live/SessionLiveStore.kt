package dev.opencode.mobile.domain.live

import kotlinx.coroutines.flow.Flow

interface SessionLiveStore {
    fun observeSessions(directory: String): Flow<SessionListSnapshot>

    fun clearCache()

    /**
     * Requests a forced refresh and waits, bounded, for the observation to report back.
     *
     * Returning means "stop waiting", not "the store now holds fresh data": the wait
     * also ends when the observation is cancelled or the refresh fails. Callers may
     * use it to release a spinner; they must not treat it as a freshness guarantee.
     */
    suspend fun invalidate(directory: String?) {}
}
