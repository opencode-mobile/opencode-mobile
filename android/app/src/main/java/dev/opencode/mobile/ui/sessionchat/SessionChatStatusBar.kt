package dev.opencode.mobile.ui.sessionchat

import android.util.Log
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.message.MessageError
import dev.opencode.mobile.domain.session.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SessionChatStatusBar(
    private val sessionRepository: SessionRepository,
    private val sessionStatusLiveStore: SessionStatusLiveStore,
    private val foregroundEventStream: ForegroundEventStream,
    private val eventDomainAdapter: EventDomainAdapter,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SessionChatStatusBarState())
    val state: StateFlow<SessionChatStatusBarState> = _state.asStateFlow()

    private var boundSessionId: String? = null
    private var boundWorktree: String? = null
    private var observationJob: Job? = null

    fun bind(
        sessionId: String,
        worktree: String,
    ) {
        if (boundSessionId == sessionId && boundWorktree == worktree) return
        stopObserving()
        boundSessionId = sessionId
        boundWorktree = worktree
        _state.value = SessionChatStatusBarState()
        logI("bind session=$sessionId worktree=$worktree")
        startObserving(sessionId, worktree)
    }

    fun stopObserving() {
        observationJob?.cancel()
        observationJob = null
    }

    fun refreshStatus() {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        scope.launch {
            runCatching { sessionRepository.getAllSessionStatuses(directory = worktree) }
                .onSuccess { statuses ->
                    if (!isCurrentBinding(sessionId, worktree)) return@onSuccess
                    val status = statuses.firstOrNull { it.sessionId == sessionId }
                    val label = status?.let(eventDomainAdapter::statusLabel)
                    _state.update { it.copy(sessionStatusLabel = label) }
                }
        }
    }

    fun abort() {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        scope.launch {
            _state.update { it.copy(isAborting = true, abortErrorMessage = null) }
            runCatching { sessionRepository.abortSession(sessionId, directory = worktree) }
                .onFailure { throwable ->
                    if (isCurrentBinding(sessionId, worktree)) {
                        _state.update {
                            it.copy(
                                isAborting = false,
                                abortErrorMessage = errorMessage(throwable),
                            )
                        }
                    }
                }.onSuccess {
                    if (isCurrentBinding(sessionId, worktree)) {
                        _state.update { it.copy(isAborting = false, abortErrorMessage = null) }
                    }
                }
        }
    }

    private fun startObserving(
        sessionId: String,
        worktree: String,
    ) {
        observationJob =
            scope.launch {
                coroutineScope {
                    launch {
                        sessionStatusLiveStore.observeStatuses(worktree).collect { statuses ->
                            if (!isCurrentBinding(sessionId, worktree)) return@collect
                            val status = statuses[sessionId]
                            logD(
                                "live status session=$sessionId type=${status?.raw?.get("type") ?: "-"}",
                            )
                            _state.update {
                                it.copy(sessionStatusLabel = status?.let(eventDomainAdapter::statusLabel))
                            }
                        }
                    }
                    launch {
                        foregroundEventStream.connectionState.collect { connectionState ->
                            if (!isCurrentBinding(sessionId, worktree)) return@collect
                            logD("live connection session=$sessionId state=$connectionState")
                            _state.update {
                                it.copy(connectionStatus = connectionState.toSessionConnectionStatus())
                            }
                        }
                    }
                    launch {
                        foregroundEventStream.events.collect { event ->
                            val eventScope =
                                when (event.sessionId) {
                                    sessionId -> "bound"
                                    null -> "global"
                                    else -> "other"
                                }
                            logD(
                                "SSE event observed session=$sessionId type=${event.type} " +
                                    "eventSession=${event.sessionId ?: "-"} scope=$eventScope",
                            )
                        }
                    }
                }
            }
    }

    private fun isCurrentBinding(
        sessionId: String,
        worktree: String,
    ): Boolean = boundSessionId == sessionId && boundWorktree == worktree

    private fun errorMessage(throwable: Throwable): String =
        when (throwable) {
            is MessageError.Http -> throwable.message ?: "Request failed"
            is MessageError.Transport -> "Unable to reach the server"
            is MessageError.Decoding -> "Unable to read server response"
            else -> throwable.message ?: "Something went wrong"
        }

    private fun logI(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logD(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    companion object {
        private const val TAG = "SessionChatStatusBar"
    }
}
