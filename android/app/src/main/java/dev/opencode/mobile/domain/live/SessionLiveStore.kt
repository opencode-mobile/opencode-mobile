package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.session.Session
import kotlinx.coroutines.flow.Flow

interface SessionLiveStore {
    fun observeSessions(directory: String): Flow<List<Session>>

    fun clearCache()
}
