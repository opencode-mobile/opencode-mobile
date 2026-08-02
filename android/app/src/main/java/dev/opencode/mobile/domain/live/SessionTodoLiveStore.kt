package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.session.SessionTodo
import kotlinx.coroutines.flow.Flow

interface SessionTodoLiveStore {
    fun observeTodos(
        sessionId: String,
        directory: String?,
    ): Flow<List<SessionTodo>>

    /** Drive live lifecycle: GET (force if cached) when becoming live; clear when idle. */
    fun setLive(
        isLive: Boolean,
        sessionId: String,
        directory: String?,
    )

    fun clearCache()
}

object EmptySessionTodoLiveStore : SessionTodoLiveStore {
    override fun observeTodos(
        sessionId: String,
        directory: String?,
    ): Flow<List<SessionTodo>> = kotlinx.coroutines.flow.flowOf(emptyList())

    override fun setLive(
        isLive: Boolean,
        sessionId: String,
        directory: String?,
    ) = Unit

    override fun clearCache() = Unit
}
