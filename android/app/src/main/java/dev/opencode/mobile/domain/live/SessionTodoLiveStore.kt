package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.session.SessionTodo
import kotlinx.coroutines.flow.Flow

interface SessionTodoLiveStore {
    fun observeTodos(
        sessionId: String,
        directory: String?,
    ): Flow<List<SessionTodo>>

    /** Force GET when becoming live. Idle keeps the cached list. */
    fun setLive(
        isLive: Boolean,
        sessionId: String,
        directory: String?,
    )

    fun clearCache()

    fun invalidate(
        sessionId: String,
        directory: String?,
    ) {}
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
