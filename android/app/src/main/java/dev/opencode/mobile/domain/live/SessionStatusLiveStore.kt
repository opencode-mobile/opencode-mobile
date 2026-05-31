package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.session.SessionStatus
import kotlinx.coroutines.flow.Flow

interface SessionStatusLiveStore {
    fun observeStatuses(directory: String): Flow<Map<String, SessionStatus>>
    fun clearCache()
}
