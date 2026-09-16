package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.domain.sync.ProjectionKey
import dev.opencode.mobile.domain.sync.SyncCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onCompletion
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
        private val syncCoordinator: SyncCoordinator,
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
            var observerCount: Int = 0,
            val updates: MutableSharedFlow<List<SessionTodo>> =
                MutableSharedFlow(
                    replay = 1,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                ),
            var eventJob: Job? = null,
            var registration: dev.opencode.mobile.domain.sync.SyncRegistration? = null,
        )

        private val sessions = ConcurrentHashMap<SessionKey, SessionState>()

        override fun observeTodos(
            sessionId: String,
            directory: String?,
        ): Flow<List<SessionTodo>> {
            val key = SessionKey(sessionId, directory.orEmpty())
            val state = sessions.getOrPut(key) { SessionState() }
            return state.updates
                .onStart {
                    state.observerCount += 1
                    ensureEventLoop(key, state)
                    emit(state.todos)
                }.onCompletion {
                    state.observerCount = (state.observerCount - 1).coerceAtLeast(0)
                    if (state.observerCount == 0) {
                        state.eventJob?.cancel()
                        state.eventJob = null
                        val projection = projection(key)
                        syncCoordinator.visible(projection, false)
                        state.registration?.let { syncCoordinator.unregister(it) }
                        state.registration = null
                    }
                }.distinctUntilChanged()
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
            if (wasLive == isLive || !isLive) return
            liveStoreScope.coroutineScope.launch {
                val projection = projection(key)
                syncCoordinator.invalidate(projection)
                syncCoordinator.ensureFresh(projection)
            }
        }

        override fun clearCache() {
            sessions.values.forEach { state ->
                state.eventJob?.cancel()
                state.registration?.let { syncCoordinator.unregister(it) }
            }
            sessions.clear()
        }

        override fun invalidate(
            sessionId: String,
            directory: String?,
        ) {
            val key = SessionKey(sessionId, directory.orEmpty())
            if (sessions[key] == null) return
            liveStoreScope.coroutineScope.launch {
                val projection = projection(key)
                syncCoordinator.invalidate(projection)
                syncCoordinator.ensureFresh(projection)
            }
        }

        private fun projection(key: SessionKey) = ProjectionKey.Todos(key.directory, key.sessionId)

        private fun ensureEventLoop(
            key: SessionKey,
            state: SessionState,
        ) {
            if (state.eventJob?.isActive == true) return
            val projection = projection(key)
            val registration =
                syncCoordinator.register(projection) {
                    val generation = syncCoordinator.profileGeneration
                    val directory = key.directory.ifEmpty { null }
                    val todos =
                        runCatching {
                            sessionRepository.listSessionTodos(key.sessionId, directory)
                        }.getOrElse { error ->
                            if (error is SessionError.Http && error.statusCode == 404) {
                                emptyList()
                            } else {
                                throw error
                            }
                        }
                    if (syncCoordinator.profileGeneration != generation) return@register
                    replace(state, todos, markCache = true)
                }
            state.registration = registration
            syncCoordinator.visible(projection, true)
            state.eventJob =
                liveStoreScope.coroutineScope.launch {
                    if (!state.hasCache) {
                        syncCoordinator.ensureFresh(projection)
                    }
                    eventStream.events.collect { event ->
                        if (state.observerCount <= 0) return@collect
                        if (event.type == "todo.updated" && event.sessionId == key.sessionId) {
                            adapter.toTodosFromEventProperties(event.rawProperties)?.let { todos ->
                                replace(state, todos, markCache = true)
                            }
                        }
                    }
                }
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
