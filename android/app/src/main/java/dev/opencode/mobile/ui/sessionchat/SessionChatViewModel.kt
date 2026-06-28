package dev.opencode.mobile.ui.sessionchat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.session.SessionRepository
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
        sessionRepository: SessionRepository,
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

        internal fun toMessageItem(
            message: Message,
            previous: SessionChatMessageItem? = null,
        ): SessionChatMessageItem = timeline.toMessageItem(message, previous)

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
