package dev.opencode.mobile.ui.sessionchat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.live.PermissionLiveStore
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.ui.sessionchat.region.SessionChatComposer
import dev.opencode.mobile.ui.sessionchat.region.SessionChatTimeline
import dev.opencode.mobile.ui.sessionchat.region.SessionInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionChatViewModel
    @Inject
    constructor(
        messageRepository: MessageRepository,
        modelRepository: ModelRepository,
        private val sessionRepository: SessionRepository,
        private val permissionLiveStore: PermissionLiveStore = EmptyPermissionLiveStore,
        sessionStatusLiveStore: SessionStatusLiveStore,
        foregroundEventStream: ForegroundEventStream,
        eventDomainAdapter: EventDomainAdapter,
        connectionSessionCoordinator: ConnectionSessionCoordinator,
        sessionModelSelectionStore: SessionModelSelectionStore,
    ) : ViewModel() {
        internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
            set(value) {
                field = value
                timeline.ioDispatcher = value
                composer.ioDispatcher = value
            }

        private val timeline =
            SessionChatTimeline(
                messageRepository = messageRepository,
                scope = viewModelScope,
            )
        private val composer =
            SessionChatComposer(
                messageRepository = messageRepository,
                modelRepository = modelRepository,
                sessionRepository = sessionRepository,
                connectionSessionCoordinator = connectionSessionCoordinator,
                sessionModelSelectionStore = sessionModelSelectionStore,
                scope = viewModelScope,
            )
        private val sessionInfo =
            SessionInfo(
                sessionRepository = sessionRepository,
                sessionStatusLiveStore = sessionStatusLiveStore,
                foregroundEventStream = foregroundEventStream,
                eventDomainAdapter = eventDomainAdapter,
                scope = viewModelScope,
            )

        private val _uiState =
            MutableStateFlow(SessionChatUiState.initial(sessionId = "", sessionTitle = ""))
        val uiState: StateFlow<SessionChatUiState> = _uiState.asStateFlow()

        private var boundSessionId: String? = null
        private var boundSessionTitle: String? = null
        private var boundWorktree: String? = null
        private var permissionRequests: List<PermissionRequest> = emptyList()
        private var permissionSessionIds: Set<String> = emptySet()
        private var permissionSubmittingId: String? = null
        private var permissionError: String? = null
        private var permissionObservationJob: kotlinx.coroutines.Job? = null

        init {
            timeline.ioDispatcher = ioDispatcher
            composer.ioDispatcher = ioDispatcher
            viewModelScope.launch {
                combine(timeline.state, composer.state, sessionInfo.state) { timelineState, composerState, infoState ->
                    Triple(timelineState, composerState, infoState)
                }.collect { (timelineState, composerState, infoState) ->
                    composer.tryApplyInitialSelection(timelineState, timeline.latestModelHint())
                    _uiState.value = mergeUiState(timelineState, composerState, infoState)
                }
            }
        }

        fun bind(
            sessionId: String,
            sessionTitle: String,
            worktree: String,
        ) {
            if (boundSessionId == sessionId &&
                boundWorktree == worktree &&
                boundSessionTitle == sessionTitle
            ) {
                return
            }
            boundSessionId = sessionId
            boundSessionTitle = sessionTitle
            boundWorktree = worktree
            logI("bind session=$sessionId worktree=$worktree title=$sessionTitle")
            _uiState.value = SessionChatUiState.initial(sessionId = sessionId, sessionTitle = sessionTitle)
            timeline.bind(sessionId, worktree)
            composer.bind(sessionId, worktree)
            sessionInfo.bind(sessionId, sessionTitle, worktree)
            observePermissions(sessionId, worktree)
            viewModelScope.launch {
                composer.loadCatalog()
            }
        }

        fun loadMessages() {
            timeline.loadMessages()
        }

        fun refreshMessages() {
            timeline.refreshMessages()
        }

        fun updateComposerText(text: String) {
            composer.updateComposerText(text)
        }

        fun selectModel(option: ModelSelectionOption) {
            composer.selectModel(option)
        }

        fun selectMode(mode: String) {
            composer.selectMode(mode)
        }

        fun selectVariant(variant: String) {
            composer.selectVariant(variant)
        }

        fun restoreComposerText(text: String) {
            composer.restoreComposerText(text)
        }

        fun sendPrompt() {
            val sessionId = boundSessionId ?: return
            val worktree = boundWorktree ?: return
            composer.sendPrompt(
                sessionId = sessionId,
                worktree = worktree,
                onOptimisticRows = { rows -> timeline.beginOptimisticSend(rows) },
                onSendAccepted = {
                    sessionInfo.refreshStatus()
                    timeline.reloadAfterSend(
                        onComplete = { composer.clearSending() },
                        onReloadFailed = { composer.clearSending() },
                    )
                },
                onSendFailed = { optimisticId, error ->
                    timeline.failOptimisticSend(optimisticId, error)
                },
            )
        }

        fun abort() {
            composer.abort()
        }

        fun allowPermissionOnce() = respondToPermission(PermissionDecision.AllowOnce)

        fun denyPermission() = respondToPermission(PermissionDecision.Deny)

        private fun observePermissions(sessionId: String, worktree: String) {
            permissionObservationJob?.cancel()
            permissionObservationJob = viewModelScope.launch(ioDispatcher) {
                permissionSessionIds = runCatching { sessionRepository.listSessions(worktree) }
                    .getOrDefault(emptyList())
                    .let { sessions ->
                        val children = sessions.groupBy { it.parentId }
                        buildSet {
                            val queue = ArrayDeque<String>()
                            add(sessionId); queue.add(sessionId)
                            while (queue.isNotEmpty()) children[queue.removeFirst()].orEmpty().forEach { if (add(it.id)) queue.add(it.id) }
                        }
                    }
                permissionLiveStore.observePermissions(worktree).collect {
                    permissionRequests = it.filter { request -> request.sessionId in permissionSessionIds }
                    if (permissionSubmittingId != null && permissionRequests.none { request -> request.id == permissionSubmittingId }) {
                        permissionSubmittingId = null
                        permissionError = null
                    }
                    publishUiState()
                }
            }
        }

        private fun respondToPermission(decision: PermissionDecision) {
            val request = permissionRequests.firstOrNull() ?: return
            if (permissionSubmittingId != null) return
            permissionSubmittingId = request.id
            permissionError = null
            publishUiState()
            viewModelScope.launch(ioDispatcher) {
                runCatching { sessionRepository.respondToPermission(request.sessionId, request.id, decision, boundWorktree) }
                    .onFailure {
                        permissionSubmittingId = null
                        permissionError = it.message ?: "Permission response failed"
                        publishUiState()
                    }
            }
        }

        internal fun toMessageItem(
            message: Message,
            previous: SessionChatMessageItem? = null,
        ): SessionChatMessageItem = timeline.toMessageItem(message, previous)

        private fun publishUiState() {
            _uiState.value = mergeUiState(timeline.state.value, composer.state.value, sessionInfo.state.value)
        }

        private fun mergeUiState(
            timelineState: SessionChatTimelineRegionState,
            composerState: SessionChatComposerRegionState,
            infoState: SessionInfoRegionState,
        ): SessionChatUiState {
            val errorMessage =
                when {
                    timelineState.timeline == SessionChatTimelineState.Error -> timelineState.errorMessage
                    composerState.errorMessage != null -> composerState.errorMessage
                    composerState.abortErrorMessage != null -> composerState.abortErrorMessage
                    else -> timelineState.errorMessage
                }
            return SessionChatUiState(
                sessionId = infoState.sessionId,
                sessionTitle = infoState.sessionTitle,
                timeline = timelineState.timeline,
                messages = timelineState.messages,
                composer = composerState.composer,
                isSending = composerState.isSending,
                isRefreshing = timelineState.isRefreshing,
                isAborting = composerState.isAborting,
                sessionStatusLabel = infoState.sessionStatusLabel,
                connectionStatus = infoState.connectionStatus,
                contextUsageFraction =
                    sessionInfo.contextUsageFraction(
                        timelineState.messages,
                        composerState.composer.modelOptions,
                        composerState.composer.selectedModel,
                    ),
                errorMessage = errorMessage,
                permissionRequest = permissionRequests.firstOrNull(),
                permissionQueueCount = permissionRequests.size,
                permissionSubmitting = permissionSubmittingId != null,
                permissionErrorMessage = permissionError,
            )
        }

        private fun logI(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        companion object {
            private const val TAG = "SessionChat"
            val AvailableModes: List<String> = SessionChatComposer.AvailableModes
            const val ModelFilterThreshold: Int = SessionChatComposer.ModelFilterThreshold
        }
    }

private object EmptyPermissionLiveStore : PermissionLiveStore {
    override fun observePermissions(directory: String) = kotlinx.coroutines.flow.flowOf(emptyList<PermissionRequest>())
    override fun clearCache() = Unit
}
