package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.domain.session.SessionActivitySnapshot
import kotlinx.coroutines.flow.Flow

interface SessionStatusLiveStore {
    fun observeActivity(directory: String): Flow<SessionActivitySnapshot>

    fun clearCache()

    suspend fun invalidate(directory: String?) {}

    fun setOptimistic(
        directory: String,
        sessionId: String,
        activity: SessionActivity,
        ttlMs: Long = DEFAULT_OPTIMISTIC_TTL_MS,
    ) {}

    companion object {
        const val DEFAULT_OPTIMISTIC_TTL_MS = 10_000L
        const val ABORT_OPTIMISTIC_TTL_MS = 3_000L
    }
}
