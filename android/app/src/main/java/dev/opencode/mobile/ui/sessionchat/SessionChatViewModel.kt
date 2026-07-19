package dev.opencode.mobile.ui.sessionchat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.live.PermissionLiveStore
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.command.CommandRepository
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.ui.sessionchat.region.SessionChatComposer
import dev.opencode.mobile.ui.sessionchat.region.SessionChatReviewChanges
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
        commandRepository: CommandRepository,
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
                reviewChanges.ioDispatcher = value
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
                commandRepository = commandRepository,
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
        private val reviewChanges =
            SessionChatReviewChanges(
                sessionRepository = sessionRepository,
                scope = viewModelScope,
            )

        private val _uiState =
            MutableStateFlow(SessionChatUiState.initial(sessionId = "", sessionTitle = ""))
        val uiState: StateFlow<SessionChatUiState> = _uiState.asStateFlow()

        private var boundSessionId: String? = null
        private var boundSessionTitle: String? = null
        private var boundWorktree: String? = null
        private var permissionSessions: List<Session> = emptyList()
        private var permissionRequests: List<PermissionRequest> = emptyList()
        private var permissionSubmittingId: String? = null
        private var permissionError: String? = null
        private var permissionTopologyError: String? = null
        private var permissionObservationJob: kotlinx.coroutines.Job? = null

        init {
            timeline.ioDispatcher = ioDispatcher
            composer.ioDispatcher = ioDispatcher
            viewModelScope.launch {
                combine(
                    timeline.state,
                    composer.state,
                    sessionInfo.state,
                    reviewChanges.state,
                ) { timelineState, composerState, infoState, reviewChangesState ->
                    SessionChatCombinedState(timelineState, composerState, infoState, reviewChangesState)
                }.collect { (timelineState, composerState, infoState, reviewChangesState) ->
                    composer.tryApplyInitialSelection(timelineState, timeline.modelSelectionHistoryHints())
                    reviewChanges.updateCandidate(
                        messages = timelineState.messages,
                        hasResolvedSessionStatus = infoState.hasResolvedSessionStatus,
                        isTurnActive = infoState.sessionStatusLabel != null,
                        isSending = composerState.isSending,
                    )
                    publishUiState(timelineState, composerState, infoState, reviewChangesState)
                }
            }
        }

        fun bind(
            sessionId: String,
            sessionTitle: String,
            worktree: String,
        ) {
            // Identity is session + worktree. Title may diverge after rename / session.updated;
            // do not full-reset when navigation still carries a stale title.
            if (boundSessionId == sessionId && boundWorktree == worktree) {
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
            reviewChanges.bind(sessionId, worktree)
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
            composer.loadServerCommands(force = true)
        }

        fun archiveSession() {
            sessionInfo.archiveSession()
        }

        fun deleteSession() {
            sessionInfo.deleteSession()
        }

        fun updateComposerText(text: String) {
            composer.updateComposerText(text)
        }

        fun selectSlashSuggestion(commandName: String) {
            composer.selectSlashSuggestion(commandName)
        }

        fun refreshServerCommands() {
            composer.loadServerCommands(force = true)
        }

        fun addAttachments(attachments: List<Attachment>) {
            composer.addAttachments(attachments)
        }

        fun removeAttachment(id: String) {
            composer.removeAttachment(id)
        }

        fun reportAttachmentError(message: String) {
            composer.reportAttachmentError(message)
        }

        fun retryModelCatalog() {
            composer.loadCatalog()
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

        fun openRenameSession() {
            sessionInfo.openRenameSession()
        }

        fun cancelRenameSession() {
            sessionInfo.cancelRenameSession()
        }

        fun updateRenameDraft(text: String) {
            sessionInfo.updateRenameDraft(text)
        }

        fun confirmRenameSession() {
            sessionInfo.confirmRenameSession()
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

        fun allowPermissionOnce(
            requestId: String,
            sessionId: String,
        ) = respondToPermission(requestId, sessionId, PermissionDecision.AllowOnce)

        fun denyPermission(
            requestId: String,
            sessionId: String,
        ) = respondToPermission(requestId, sessionId, PermissionDecision.Deny)

        private fun observePermissions(
            sessionId: String,
            worktree: String,
        ) {
            permissionObservationJob?.cancel()
            permissionObservationJob =
                viewModelScope.launch(ioDispatcher) {
                    permissionLiveStore.observePermissions(worktree).collect { requests ->
                        applyPermissionSnapshot(sessionId, worktree, requests)
                    }
                }
        }

        private fun respondToPermission(
            requestId: String,
            sessionId: String,
            decision: PermissionDecision,
        ) {
            val request =
                permissionRequests.firstOrNull { it.id == requestId && it.sessionId == sessionId }
                    ?: return
            if (permissionSubmittingId != null) return
            permissionSubmittingId = request.id
            permissionError = null
            publishUiState()
            viewModelScope.launch(ioDispatcher) {
                runCatching {
                    sessionRepository.respondToPermission(request.sessionId, request.id, decision, boundWorktree)
                }.onFailure {
                    permissionSubmittingId = null
                    if (it is SessionError.Http && it.statusCode == 404) {
                        val refreshed = refreshPermissionSnapshot()
                        if (refreshed && permissionRequests.none { pending -> pending.id == request.id }) {
                            permissionError = null
                            publishUiState()
                            return@onFailure
                        }
                        permissionError = "Permission request is no longer available"
                    } else {
                        permissionError = it.message ?: "Permission response failed"
                    }
                    publishUiState()
                }
            }
        }

        private suspend fun refreshPermissionSnapshot(): Boolean {
            val sessionId = boundSessionId ?: return false
            val worktree = boundWorktree ?: return false
            return runCatching { sessionRepository.listPendingPermissions(worktree) }
                .onSuccess { applyPermissionSnapshot(sessionId, worktree, it) }
                .isSuccess
        }

        private suspend fun applyPermissionSnapshot(
            sessionId: String,
            worktree: String,
            requests: List<PermissionRequest>,
        ) {
            runCatching { sessionRepository.listSessions(worktree) }
                .onSuccess {
                    permissionSessions = it
                    permissionTopologyError = null
                }.onFailure {
                    permissionSessions = emptyList()
                    permissionTopologyError = "Unable to refresh permission session tree"
                }
            permissionRequests = orderedPermissionRequests(sessionId, permissionSessions, requests)
            if (
                permissionSubmittingId != null &&
                permissionRequests.none { request -> request.id == permissionSubmittingId }
            ) {
                permissionSubmittingId = null
                permissionError = null
            }
            publishUiState()
        }

        internal fun toMessageItem(
            message: Message,
            previous: SessionChatMessageItem? = null,
        ): SessionChatMessageItem? = timeline.toMessageItem(message, previous)

        private fun publishUiState() {
            publishUiState(
                timeline.state.value,
                composer.state.value,
                sessionInfo.state.value,
                reviewChanges.state.value,
            )
        }

        private fun publishUiState(
            timelineState: SessionChatTimelineRegionState,
            composerState: SessionChatComposerRegionState,
            infoState: SessionInfoRegionState,
            reviewChangesState: SessionChatReviewChangesRegionState,
        ) {
            _uiState.value = mergeUiState(timelineState, composerState, infoState, reviewChangesState)
        }

        private fun mergeUiState(
            timelineState: SessionChatTimelineRegionState,
            composerState: SessionChatComposerRegionState,
            infoState: SessionInfoRegionState,
            reviewChangesState: SessionChatReviewChangesRegionState,
        ): SessionChatUiState {
            val errorMessage =
                when {
                    infoState.lifecycleErrorMessage != null -> infoState.lifecycleErrorMessage
                    timelineState.timeline == SessionChatTimelineState.Error -> timelineState.errorMessage
                    composerState.errorMessage != null -> composerState.errorMessage
                    composerState.abortErrorMessage != null -> composerState.abortErrorMessage
                    infoState.errorMessage != null -> infoState.errorMessage
                    permissionRequests.isEmpty() && permissionTopologyError != null -> permissionTopologyError
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
                isLifecycleActionInFlight = infoState.isLifecycleActionInFlight,
                shouldLeaveSession = infoState.shouldLeaveSession,
                permissionRequest = permissionRequests.firstOrNull(),
                permissionQueueCount = permissionRequests.size,
                permissionSubmitting = permissionSubmittingId != null,
                permissionErrorMessage = permissionError ?: permissionTopologyError,
                reviewChangesMessageId = reviewChangesState.messageId,
                reviewChangesSummary = reviewChangesState.summary,
                rename = infoState.rename,
                slashSuggestionsVisible = composerState.slashSuggestionsVisible,
                slashSuggestions = composerState.slashSuggestions,
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

private data class SessionChatCombinedState(
    val timeline: SessionChatTimelineRegionState,
    val composer: SessionChatComposerRegionState,
    val info: SessionInfoRegionState,
    val reviewChanges: SessionChatReviewChangesRegionState,
)

internal fun orderedPermissionRequests(
    rootSessionId: String,
    sessions: List<Session>,
    requests: List<PermissionRequest>,
): List<PermissionRequest> {
    val children = sessions.groupBy { it.parentId }
    val sessionOrder =
        buildList {
            val queue = ArrayDeque<String>()
            val visited = mutableSetOf<String>()
            queue.add(rootSessionId)
            while (queue.isNotEmpty()) {
                val sessionId = queue.removeFirst()
                if (!visited.add(sessionId)) continue
                add(sessionId)
                children[sessionId].orEmpty().forEach { queue.add(it.id) }
            }
        }
    val requestsBySession = requests.groupBy { it.sessionId }
    return sessionOrder.flatMap { requestsBySession[it].orEmpty() }
}
