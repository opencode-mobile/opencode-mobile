package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.session.SessionStatus
import kotlinx.coroutines.flow.Flow

interface SessionStatusLiveStore {
    fun observeStatuses(directory: String): Flow<Map<String, SessionStatus>>

    fun clearCache()

    /**
     * Requests a forced refresh and waits, bounded, for the observation to report back.
     *
     * Returning means "stop waiting", not "the store now holds fresh data": the wait
     * also ends when the observation is cancelled or the refresh fails. Callers may
     * use it to release a spinner; they must not treat it as a freshness guarantee.
     */
    suspend fun invalidate(directory: String?) {}

    fun markBusy(
        directory: String,
        sessionId: String,
    ) {}

    fun clearLocalBusy(
        directory: String,
        sessionId: String,
    ) {}

    fun markIdle(
        directory: String,
        sessionId: String,
    ) {}
}
