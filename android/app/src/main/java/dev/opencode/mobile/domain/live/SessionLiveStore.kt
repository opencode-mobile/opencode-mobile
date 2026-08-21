package dev.opencode.mobile.domain.live

import kotlinx.coroutines.flow.Flow

interface SessionLiveStore {
    fun observeSessions(directory: String): Flow<SessionListSnapshot>

    fun clearCache()

    fun invalidate(directory: String?) {}
}
