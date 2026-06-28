package dev.opencode.mobile.ui.sessionchat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelection
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.session.SessionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
                scope = viewModelScope,
            )
        private val statusBar =
            SessionChatStatusBar(
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
        private var appliedInitialModelHint = false

        init {
            timeline.ioDispatcher = ioDispatcher
            composer.ioDispatcher = ioDispatcher
            viewModelScope.launch {
                combine(timeline.state, composer.state, statusBar.state) { timelineState, composerState, statusBarState ->
                    mergeUiState(timelineState, composerState, statusBarState)
                }.collect { merged ->
                    _uiState.value = merged
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
            appliedInitialModelHint = false
            logI("bind session=$sessionId worktree=$worktree title=$sessionTitle")
            timeline.bind(sessionId, worktree)
            composer.bind(worktree)
            statusBar.bind(sessionId, worktree)
            viewModelScope.launch {
                composer.loadCatalog()
            }
            scheduleInitialModelHint()
        }

        fun loadMessages() {
            timeline.loadMessages()
            scheduleInitialModelHint()
        }

        fun refreshMessages() {
            timeline.refreshMessages()
        }

        fun updateComposerText(text: String) {
            composer.updateComposerText(text)
        }

        fun selectModel(model: String) {
            composer.selectModel(model)
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
                    statusBar.refreshStatus()
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
            statusBar.abort()
        }

        internal fun toMessageItem(
            message: Message,
            previous: SessionChatMessageItem? = null,
        ): SessionChatMessageItem = timeline.toMessageItem(message, previous)

        private fun scheduleInitialModelHint() {
            viewModelScope.launch {
                if (appliedInitialModelHint) return@launch
                val timelineState = timeline.state.first { it.timeline != SessionChatTimelineState.Loading }
                composer.state.first { it.composer.modelOptions.isNotEmpty() }
                if (appliedInitialModelHint) return@launch
                appliedInitialModelHint = true
                if (timelineState.timeline != SessionChatTimelineState.Error) {
                    composer.applyLatestModelHint(timeline.latestModelHint())
                }
            }
        }

        private fun mergeUiState(
            timelineState: SessionChatTimelineRegionState,
            composerState: SessionChatComposerRegionState,
            statusBarState: SessionChatStatusBarState,
        ): SessionChatUiState {
            val errorMessage =
                when {
                    timelineState.timeline == SessionChatTimelineState.Error -> timelineState.errorMessage
                    composerState.errorMessage != null -> composerState.errorMessage
                    statusBarState.abortErrorMessage != null -> statusBarState.abortErrorMessage
                    else -> timelineState.errorMessage
                }
            return SessionChatUiState(
                sessionId = boundSessionId.orEmpty(),
                sessionTitle = boundSessionTitle.orEmpty(),
                timeline = timelineState.timeline,
                messages = timelineState.messages,
                composer = composerState.composer,
                isSending = composerState.isSending,
                isRefreshing = timelineState.isRefreshing,
                isAborting = statusBarState.isAborting,
                sessionStatusLabel = statusBarState.sessionStatusLabel,
                connectionStatus = statusBarState.connectionStatus,
                contextUsageFraction =
                    contextUsageFraction(
                        timelineState.messages,
                        composerState.composer.modelOptions,
                        composerState.composer.selectedModel,
                    ),
                errorMessage = errorMessage,
            )
        }

        private fun contextUsageFraction(
            messages: List<SessionChatMessageItem>,
            modelOptions: List<ModelSelectionOption>,
            selectedModel: ModelSelection?,
        ): Float? {
            val latestAssistantWithUsage =
                messages
                    .asReversed()
                    .firstOrNull { it.role == dev.opencode.mobile.domain.message.MessageRole.Assistant && it.contextInputTokens != null }
                    ?: return null
            val inputTokens = latestAssistantWithUsage.contextInputTokens ?: return null
            val messageHasModel = latestAssistantWithUsage.modelProviderId != null && latestAssistantWithUsage.modelId != null
            val contextLimit: Long =
                modelLimit(modelOptions, latestAssistantWithUsage)
                    ?: (if (messageHasModel) null else selectedModel?.option?.contextLimitTokens)
                    ?: return null
            if (contextLimit <= 0L) return null
            return (inputTokens.toFloat() / contextLimit.toFloat()).coerceIn(0f, 1f)
        }

        private fun modelLimit(
            modelOptions: List<ModelSelectionOption>,
            message: SessionChatMessageItem,
        ): Long? {
            val providerId = message.modelProviderId ?: return null
            val modelId = message.modelId ?: return null
            return modelOptions
                .firstOrNull { it.providerId == providerId && it.modelId == modelId }
                ?.contextLimitTokens
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
