package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionTodo
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSessionTodoLiveStore
    @Inject
    constructor(
        private val sessionRepository: SessionRepository,
        private val eventStream: ForegroundEventStream,
        private val liveStoreScope: LiveStoreScope,
    ) : SessionTodoLiveStore {
        private val adapter = SessionDomainAdapter()

        private data class SessionKey(
            val sessionId: String,
            val directory: String,
        )

        private data class SessionState(
            var todos: List<SessionTodo> = emptyList(),
            var hasCache: Boolean = false,
            var isLive: Boolean = false,
            val updates: MutableSharedFlow<List<SessionTodo>> =
                MutableSharedFlow(
                    replay = 1,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                ),
            var eventJob: Job? = null,
        )

        private val sessions = ConcurrentHashMap<SessionKey, SessionState>()

        override fun observeTodos(
            sessionId: String,
            directory: String?,
        ): Flow<List<SessionTodo>> {
            val key = SessionKey(sessionId, directory.orEmpty())
            val state = sessions.getOrPut(key) { SessionState() }
            ensureEventLoop(key, state)
            if (!state.hasCache) {
                liveStoreScope.coroutineScope.launch {
                    fetch(key, state, force = false)
                }
            }
            return state.updates
                .onStart { emit(state.todos) }
                .distinctUntilChanged()
        }

        override fun setLive(
            isLive: Boolean,
            sessionId: String,
            directory: String?,
        ) {
            val key = SessionKey(sessionId, directory.orEmpty())
            val state = sessions.getOrPut(key) { SessionState() }
            val wasLive = state.isLive
            state.isLive = isLive
            ensureEventLoop(key, state)
            if (wasLive == isLive || !isLive) return
            liveStoreScope.coroutineScope.launch {
                fetch(key, state, force = state.hasCache)
            }
        }

        override fun clearCache() {
            sessions.values.forEach { it.eventJob?.cancel() }
            sessions.clear()
        }

        private fun ensureEventLoop(
            key: SessionKey,
            state: SessionState,
        ) {
            if (state.eventJob?.isActive == true) return
            state.eventJob =
                liveStoreScope.coroutineScope.launch {
                    eventStream.events.collect { event ->
                        when {
                            event.type == "todo.updated" && event.sessionId == key.sessionId -> {
                                adapter.toTodosFromEventProperties(event.rawProperties)?.let { todos ->
                                    replace(state, todos, markCache = true)
                                }
                            }
                            event.type == "stream.reconnected" || event.type == "server.connected" -> {
                                fetch(key, state, force = true)
                            }
                        }
                    }
                }
        }

        private suspend fun fetch(
            key: SessionKey,
            state: SessionState,
            force: Boolean,
        ) {
            if (state.hasCache && !force) return
            val directory = key.directory.ifEmpty { null }
            val todos =
                runCatching {
                    sessionRepository.listSessionTodos(key.sessionId, directory)
                }.getOrNull() ?: return
            replace(state, todos, markCache = true)
        }

        private suspend fun replace(
            state: SessionState,
            todos: List<SessionTodo>,
            markCache: Boolean,
        ) {
            state.todos = todos
            state.hasCache = markCache
            state.updates.emit(todos)
        }
    }
