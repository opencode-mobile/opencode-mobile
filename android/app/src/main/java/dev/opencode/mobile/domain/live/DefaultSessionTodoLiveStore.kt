package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.GapAuthority
import dev.opencode.mobile.domain.event.SilentWorldSignals
import dev.opencode.mobile.domain.event.StoreRefreshReason
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.event.asLiveConnection
import dev.opencode.mobile.domain.event.catchUpReason
import dev.opencode.mobile.domain.event.marksStale
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionTodo
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
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
        private val worldSignals: WorldSignals = SilentWorldSignals,
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
            val authority: GapAuthority = GapAuthority(),
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
                }
                .onCompletion {
                    state.observerCount = (state.observerCount - 1).coerceAtLeast(0)
                    if (state.observerCount == 0) {
                        state.eventJob?.cancel()
                        state.eventJob = null
                    }
                }
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
            if (wasLive == isLive || !isLive) return
            liveStoreScope.coroutineScope.launch {
                fetch(key, state, force = true)
            }
        }

        override fun clearCache() {
            sessions.values.forEach { it.eventJob?.cancel() }
            sessions.clear()
        }

        override fun invalidate(
            sessionId: String,
            directory: String?,
        ) {
            val key = SessionKey(sessionId, directory.orEmpty())
            val state = sessions[key] ?: return
            liveStoreScope.coroutineScope.launch {
                state.authority.invalidate(eventStream.asLiveConnection().snapshot.value)
                fetch(key, state, force = false, reason = StoreRefreshReason.PULL_TO_REFRESH)
            }
        }

        private fun ensureEventLoop(
            key: SessionKey,
            state: SessionState,
        ) {
            if (state.eventJob?.isActive == true) return
            state.eventJob =
                liveStoreScope.coroutineScope.launch {
                    val liveConnection = eventStream.asLiveConnection()
                    if (!state.hasCache) {
                        fetch(key, state, force = true, reason = StoreRefreshReason.INITIAL_SUBSCRIBE)
                    }
                    launch {
                        worldSignals.signals.collect { signal ->
                            if (state.observerCount <= 0) return@collect
                            if (signal.marksStale()) {
                                state.authority.invalidate(liveConnection.snapshot.value)
                                return@collect
                            }
                            val reason = signal.catchUpReason() ?: return@collect
                            fetch(key, state, force = false, reason = reason)
                        }
                    }
                    launch {
                        liveConnection.snapshot.collect {
                            if (state.observerCount <= 0) return@collect
                            fetch(key, state, force = false, reason = StoreRefreshReason.STREAM_RECONNECTED)
                        }
                    }
                    var reconnectRefresh: Job? = null
                    try {
                        eventStream.events.collect { event ->
                            if (state.observerCount <= 0) return@collect
                            when {
                                event.type == "todo.updated" && event.sessionId == key.sessionId -> {
                                    adapter.toTodosFromEventProperties(event.rawProperties)?.let { todos ->
                                        replace(state, todos, markCache = true)
                                    }
                                }
                                event.type == "stream.reconnected" || event.type == "server.connected" -> {
                                    reconnectRefresh?.cancel()
                                    reconnectRefresh =
                                        launch {
                                            delay(LiveStoreRefreshPolicy.STATUS_DEBOUNCE_MS)
                                            if (state.observerCount <= 0) return@launch
                                            fetch(key, state, force = false, reason = event.type)
                                        }
                                }
                            }
                        }
                    } finally {
                        reconnectRefresh?.cancel()
                    }
                }
        }

        private suspend fun fetch(
            key: SessionKey,
            state: SessionState,
            force: Boolean,
            reason: String? = null,
        ): Boolean {
            val liveConnection = eventStream.asLiveConnection()
            val snapshot = liveConnection.snapshot.value
            val generation =
                if (force) {
                    state.authority.captureBegin(snapshot)
                } else {
                    state.authority.tryBegin(snapshot) ?: return state.hasCache
                }
            val directory = key.directory.ifEmpty { null }
            val todos =
                runCatching {
                    sessionRepository.listSessionTodos(key.sessionId, directory)
                }.getOrNull()
            if (todos == null) {
                state.authority.fail(generation)
                return false
            }
            replace(state, todos, markCache = true)
            state.authority.acknowledge(generation)
            if (state.authority.needsReconcile(liveConnection.snapshot.value)) {
                fetch(key, state, force = false, reason = StoreRefreshReason.STREAM_RECONNECTED)
            }
            return true
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
