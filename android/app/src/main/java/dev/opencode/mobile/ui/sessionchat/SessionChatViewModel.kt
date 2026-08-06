package dev.opencode.mobile.ui.sessionchat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.live.EmptySessionReadyStore
import dev.opencode.mobile.domain.live.EmptySessionTodoLiveStore
import dev.opencode.mobile.domain.live.PermissionLiveStore
import dev.opencode.mobile.domain.live.QuestionLiveStore
import dev.opencode.mobile.domain.live.SessionReadyStore
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.live.SessionTodoLiveStore
import dev.opencode.mobile.domain.agent.AgentRepository
import dev.opencode.mobile.domain.command.ClientSlashAction
import dev.opencode.mobile.domain.command.CommandRepository
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.NewSessionModelSelectionResolver
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.domain.session.SessionTodoList
import dev.opencode.mobile.ui.sessionchat.region.SessionChatCommands
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
        agentRepository: AgentRepository,
        private val sessionRepository: SessionRepository,
        private val permissionLiveStore: PermissionLiveStore = EmptyPermissionLiveStore,
        private val questionLiveStore: QuestionLiveStore = EmptyQuestionLiveStore,
        private val sessionTodoLiveStore: SessionTodoLiveStore = EmptySessionTodoLiveStore,
        private val sessionReadyStore: SessionReadyStore = EmptySessionReadyStore,
        sessionStatusLiveStore: SessionStatusLiveStore,
        foregroundEventStream: ForegroundEventStream,
        eventDomainAdapter: EventDomainAdapter,
        connectionSessionCoordinator: ConnectionSessionCoordinator,
        sessionModelSelectionStore: SessionModelSelectionStore,
        newSessionModelSelectionResolver: NewSessionModelSelectionResolver,
    ) : ViewModel() {
        internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
            set(value) {
                field = value
                timeline.ioDispatcher = value
                composer.ioDispatcher = value
                reviewChanges.ioDispatcher = value
                commands.ioDispatcher = value
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
                agentRepository = agentRepository,
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
        private val commands =
            SessionChatCommands(
                sessionRepository = sessionRepository,
                newSessionModelSelectionResolver = newSessionModelSelectionResolver,
                connectionSessionCoordinator = connectionSessionCoordinator,
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
        private var questionSessions: List<Session> = emptyList()
        private var questionRequests: List<QuestionRequest> = emptyList()
        private var questionSubmittingId: String? = null
        private var questionError: String? = null
        private var questionTopologyError: String? = null
        private var questionObservationJob: kotlinx.coroutines.Job? = null
        private var todoObservationJob: kotlinx.coroutines.Job? = null
        private var questionDraftRequestId: String? = null
        private var questionWizardIndex: Int = 0
        private val questionDrafts = mutableMapOf<Int, QuestionAnswerDraft>()
        private var sessionTodos: List<SessionTodo> = emptyList()
        private var lastTodoLive: Boolean? = null
        private var lastSnackbarKey: String? = null
        private var suppressedSnackbarKey: String? = null
        private var snackbarSequence: Long = 0L

        init {
            timeline.ioDispatcher = ioDispatcher
            composer.ioDispatcher = ioDispatcher
            viewModelScope.launch {
                combine(
                    timeline.state,
                    composer.state,
                    sessionInfo.state,
                    reviewChanges.state,
                    commands.state,
                ) { timelineState, composerState, infoState, reviewChangesState, commandsState ->
                    SessionChatCombinedState(timelineState, composerState, infoState, reviewChangesState, commandsState)
                }.collect { (timelineState, composerState, infoState, reviewChangesState, commandsState) ->
                    timeline.updateServerCommands(composerState.serverCommands)
                    composer.tryApplyInitialSelection(timelineState, timeline.modelSelectionHistoryHints())
                    reviewChanges.updateCandidate(
                        messages = timelineState.messages,
                        hasResolvedSessionStatus = infoState.hasResolvedSessionStatus,
                        isTurnActive = infoState.sessionStatusLabel != null,
                        isSending = composerState.isSending,
                    )
                    commands.updateVisibility(
                        messages = timelineState.messages,
                        hasSelectedComposerModel = composerState.composer.selectedModel != null,
                    )
                    publishUiState(timelineState, composerState, infoState, reviewChangesState, commandsState)
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
            // Always re-assert Ready focus: nav can dispose/clear focus then re-enter the same
            // bound ViewModel without a full reset, and Ready must clear on open.
            sessionReadyStore.setFocusedSession(sessionId, worktree)
            if (boundSessionId == sessionId && boundWorktree == worktree) {
                if (boundSessionTitle != sessionTitle) {
                    boundSessionTitle = sessionTitle
                    sessionInfo.bind(sessionId, sessionTitle, worktree)
                }
                return
            }
            boundSessionId = sessionId
            boundSessionTitle = sessionTitle
            boundWorktree = worktree
            lastSnackbarKey = null
            suppressedSnackbarKey = null
            snackbarSequence = 0L
            logI("bind session=$sessionId worktree=$worktree title=$sessionTitle")
            _uiState.value = SessionChatUiState.initial(sessionId = sessionId, sessionTitle = sessionTitle)
            timeline.bind(sessionId, worktree)
            composer.bind(sessionId, worktree)
            sessionInfo.bind(sessionId, sessionTitle, worktree)
            reviewChanges.bind(sessionId, worktree)
            commands.bind(sessionId, worktree)
            observePermissions(sessionId, worktree)
            observeQuestions(sessionId, worktree)
            observeTodos(sessionId, worktree)
            viewModelScope.launch {
                composer.loadCatalog()
            }
        }

        fun clearSessionReadyFocus() {
            sessionReadyStore.setFocusedSession(null)
        }

        fun loadMessages() {
            timeline.loadMessages()
        }

        fun refreshMessages() {
            timeline.refreshMessages()
            composer.loadServerCommands(force = true)
            composer.loadMentionableAgents(force = true)
        }

        fun archiveSession() {
            sessionInfo.archiveSession()
        }

        fun deleteSession() {
            sessionInfo.deleteSession()
        }

        fun updateComposerText(
            text: String,
            cursor: Int = text.length,
        ) {
            composer.updateComposerText(text, cursor)
        }

        fun selectSlashSuggestion(commandName: String) {
            composer.selectSlashSuggestion(commandName)
        }

        fun selectAtSuggestion(agentName: String) {
            composer.selectAtSuggestion(agentName)
        }

        fun refreshServerCommands() {
            composer.loadServerCommands(force = true)
        }

        fun refreshMentionableAgents() {
            composer.loadMentionableAgents(force = true)
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

        fun openCommands() {
            commands.openCommands()
            composer.dismissSuggestions()
        }

        fun dismissCommands() {
            commands.dismissCommands()
        }

        fun runClientSlashAction(action: ClientSlashAction) {
            when (action) {
                ClientSlashAction.New -> commands.runNew()
                ClientSlashAction.Fork -> commands.openForkDialog(currentTitle = sessionInfo.state.value.sessionTitle)
                ClientSlashAction.Undo ->
                    commands.runUndo(
                        isBusy = sessionInfo.state.value.sessionStatusLabel != null,
                        restoreComposerText = composer::restoreComposerText,
                    )
                ClientSlashAction.Redo ->
                    commands.runRedo(
                        isBusy = sessionInfo.state.value.sessionStatusLabel != null,
                        clearComposerRestore = { composer.restoreComposerText("") },
                    )
                ClientSlashAction.Compact -> {
                    val model = composer.state.value.composer.selectedModel ?: return
                    commands.runCompact(
                        providerId = model.option.providerId,
                        modelId = model.option.modelId,
                        onSuccess = {
                            timeline.refreshMessages()
                            sessionInfo.refreshStatus()
                        },
                    )
                }
            }
        }

        fun updateForkDraft(text: String) {
            commands.updateForkDraft(text)
        }

        fun cancelForkDialog() {
            commands.cancelForkDialog()
        }

        fun confirmFork() {
            commands.confirmFork()
        }

        fun chooseForkOpen() {
            commands.chooseForkOpen()
        }

        fun chooseForkStay() {
            commands.chooseForkStay()
        }

        fun consumePendingSessionReplace() {
            commands.consumePendingSessionReplace()
        }

        fun consumeSnackbar() {
            suppressedSnackbarKey = lastSnackbarKey
            commands.clearActionFeedback()
            composer.clearTransientErrors()
            sessionInfo.clearTransientErrors()
            timeline.clearReadyError()
            permissionTopologyError = null
            questionTopologyError = null
            publishUiState(
                timeline.state.value,
                composer.state.value,
                sessionInfo.state.value,
                reviewChanges.state.value,
                commands.state.value,
            )
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
            if (permissionRequests.isNotEmpty() || questionRequests.isNotEmpty()) return
            val sessionId = boundSessionId ?: return
            val worktree = boundWorktree ?: return
            composer.sendPrompt(
                sessionId = sessionId,
                worktree = worktree,
                onOptimisticRows = { rows -> timeline.beginOptimisticSend(rows) },
                onSendAccepted = {
                    sessionReadyStore.notifyPromptSent(sessionId, worktree)
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

        fun toggleQuestionOption(
            requestId: String,
            sessionId: String,
            label: String,
        ) {
            val request = visibleQuestionRequest() ?: return
            if (request.id != requestId || request.sessionId != sessionId || questionSubmittingId != null) return
            val question = request.questions.getOrNull(questionWizardIndex) ?: return
            val current = questionDrafts[questionWizardIndex] ?: QuestionAnswerDraft()
            val selected =
                if (question.multiple) {
                    if (label in current.selectedLabels) current.selectedLabels - label else current.selectedLabels + label
                } else {
                    listOf(label)
                }
            questionDrafts[questionWizardIndex] =
                current.copy(selectedLabels = selected, customSelected = false)
            publishUiState()
        }

        fun toggleQuestionCustom(
            requestId: String,
            sessionId: String,
        ) {
            val request = visibleQuestionRequest() ?: return
            if (request.id != requestId || request.sessionId != sessionId || questionSubmittingId != null) return
            val question = request.questions.getOrNull(questionWizardIndex) ?: return
            if (!question.custom) return
            val current = questionDrafts[questionWizardIndex] ?: QuestionAnswerDraft()
            val customSelected = !current.customSelected
            questionDrafts[questionWizardIndex] =
                current.copy(
                    customSelected = customSelected,
                    selectedLabels = if (customSelected) emptyList() else current.selectedLabels,
                )
            publishUiState()
        }

        fun updateQuestionCustomText(
            requestId: String,
            sessionId: String,
            text: String,
        ) {
            val request = visibleQuestionRequest() ?: return
            if (request.id != requestId || request.sessionId != sessionId || questionSubmittingId != null) return
            val current = questionDrafts[questionWizardIndex] ?: QuestionAnswerDraft()
            questionDrafts[questionWizardIndex] = current.copy(customText = text, customSelected = true, selectedLabels = emptyList())
            publishUiState()
        }

        fun questionBack(
            requestId: String,
            sessionId: String,
        ) {
            val request = visibleQuestionRequest() ?: return
            if (request.id != requestId || request.sessionId != sessionId || questionSubmittingId != null) return
            if (questionWizardIndex <= 0) return
            questionWizardIndex -= 1
            publishUiState()
        }

        fun questionNext(
            requestId: String,
            sessionId: String,
        ) {
            val request = visibleQuestionRequest() ?: return
            if (request.id != requestId || request.sessionId != sessionId || questionSubmittingId != null) return
            val question = request.questions.getOrNull(questionWizardIndex) ?: return
            val draft = questionDrafts[questionWizardIndex] ?: QuestionAnswerDraft()
            if (!isQuestionAnswerValid(question, draft)) return
            if (questionWizardIndex >= request.questions.lastIndex) return
            questionWizardIndex += 1
            publishUiState()
        }

        fun submitQuestion(
            requestId: String,
            sessionId: String,
        ) {
            val request =
                questionRequests.firstOrNull { it.id == requestId && it.sessionId == sessionId } ?: return
            if (questionSubmittingId != null) return
            val answers = buildQuestionAnswers(request.questions, questionDrafts) ?: return
            questionSubmittingId = request.id
            questionError = null
            logI("question reply request=${request.id} session=${request.sessionId}")
            publishUiState()
            viewModelScope.launch(ioDispatcher) {
                runCatching {
                    sessionRepository.replyToQuestion(request.id, answers, boundWorktree)
                }.onFailure { handleQuestionMutationFailure(request.id, it, dismiss = false) }
            }
        }

        fun dismissQuestion(
            requestId: String,
            sessionId: String,
        ) {
            val request =
                questionRequests.firstOrNull { it.id == requestId && it.sessionId == sessionId } ?: return
            if (questionSubmittingId != null) return
            questionSubmittingId = request.id
            questionError = null
            logI("question dismiss request=${request.id} session=${request.sessionId}")
            publishUiState()
            viewModelScope.launch(ioDispatcher) {
                runCatching {
                    sessionRepository.rejectQuestion(request.id, boundWorktree)
                }.onFailure { handleQuestionMutationFailure(request.id, it, dismiss = true) }
            }
        }

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

        private fun observeQuestions(
            sessionId: String,
            worktree: String,
        ) {
            questionObservationJob?.cancel()
            questionObservationJob =
                viewModelScope.launch(ioDispatcher) {
                    questionLiveStore.observeQuestions(worktree).collect { requests ->
                        applyQuestionSnapshot(sessionId, worktree, requests)
                    }
                }
        }

        private fun observeTodos(
            sessionId: String,
            worktree: String,
        ) {
            todoObservationJob?.cancel()
            todoObservationJob =
                viewModelScope.launch(ioDispatcher) {
                    sessionTodoLiveStore.observeTodos(sessionId, worktree).collect { todos ->
                        sessionTodos = todos
                        publishUiState()
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

        private suspend fun refreshQuestionSnapshot(): Boolean {
            val sessionId = boundSessionId ?: return false
            val worktree = boundWorktree ?: return false
            return runCatching { sessionRepository.listPendingQuestions(worktree) }
                .onSuccess { applyQuestionSnapshot(sessionId, worktree, it) }
                .isSuccess
        }

        private suspend fun applyQuestionSnapshot(
            sessionId: String,
            worktree: String,
            requests: List<QuestionRequest>,
        ) {
            runCatching { sessionRepository.listSessions(worktree) }
                .onSuccess {
                    questionSessions = it
                    questionTopologyError = null
                }.onFailure {
                    questionSessions = emptyList()
                    questionTopologyError = "Unable to refresh question session tree"
                }
            val ordered = orderedQuestionRequests(sessionId, questionSessions, requests)
            val previousVisibleId = questionRequests.firstOrNull()?.id
            questionRequests = dropOrphanQuestionRequests(ordered, timeline.loadedMessages())
            syncQuestionDraftState()
            if (
                questionSubmittingId != null &&
                questionRequests.none { request -> request.id == questionSubmittingId }
            ) {
                questionSubmittingId = null
                questionError = null
            }
            val visibleId = questionRequests.firstOrNull()?.id
            if (visibleId != previousVisibleId) {
                logI("question visible=${visibleId ?: "none"} queue=${questionRequests.size}")
            }
            publishUiState()
        }

        private fun handleQuestionMutationFailure(
            requestId: String,
            error: Throwable,
            dismiss: Boolean,
        ) {
            if (questionSubmittingId == requestId) {
                questionSubmittingId = null
            }
            if (error is SessionError.Http && error.statusCode == 404) {
                viewModelScope.launch(ioDispatcher) {
                    val refreshed = refreshQuestionSnapshot()
                    if (refreshed && questionRequests.none { pending -> pending.id == requestId }) {
                        questionError = null
                        publishUiState()
                        return@launch
                    }
                    if (visibleQuestionRequest()?.id == requestId) {
                        questionError = "Question request is no longer available"
                    }
                    publishUiState()
                }
                return
            }
            if (visibleQuestionRequest()?.id == requestId) {
                questionError =
                    error.message
                        ?: if (dismiss) "Question dismiss failed" else "Question response failed"
            }
            publishUiState()
        }

        private fun syncQuestionDraftState() {
            val visible = questionRequests.firstOrNull()
            if (visible == null) {
                questionDraftRequestId = null
                questionWizardIndex = 0
                questionDrafts.clear()
                return
            }
            if (questionDraftRequestId != visible.id) {
                questionDraftRequestId = visible.id
                questionWizardIndex = 0
                questionDrafts.clear()
            } else {
                questionWizardIndex = questionWizardIndex.coerceIn(0, (visible.questions.size - 1).coerceAtLeast(0))
            }
        }

        private fun visibleQuestionRequest(): QuestionRequest? =
            if (permissionRequests.isNotEmpty()) null else questionRequests.firstOrNull()

        private fun refilterQuestionOrphans() {
            val before = questionRequests
            val filtered = dropOrphanQuestionRequests(before, timeline.loadedMessages())
            if (filtered != before) {
                questionRequests = filtered
                syncQuestionDraftState()
                if (
                    questionSubmittingId != null &&
                    questionRequests.none { request -> request.id == questionSubmittingId }
                ) {
                    questionSubmittingId = null
                    questionError = null
                }
            }
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
                commands.state.value,
            )
        }

        private fun publishUiState(
            timelineState: SessionChatTimelineRegionState,
            composerState: SessionChatComposerRegionState,
            infoState: SessionInfoRegionState,
            reviewChangesState: SessionChatReviewChangesRegionState,
            commandsState: SessionChatCommandsRegionState,
        ) {
            _uiState.value = mergeUiState(timelineState, composerState, infoState, reviewChangesState, commandsState)
        }

        private fun mergeUiState(
            timelineState: SessionChatTimelineRegionState,
            composerState: SessionChatComposerRegionState,
            infoState: SessionInfoRegionState,
            reviewChangesState: SessionChatReviewChangesRegionState,
            commandsState: SessionChatCommandsRegionState,
        ): SessionChatUiState {
            refilterQuestionOrphans()
            val visibleQuestion = visibleQuestionRequest()
            val questionDraft = questionDrafts[questionWizardIndex] ?: QuestionAnswerDraft()
            val isLive =
                infoState.sessionStatusLabel != null ||
                    permissionRequests.isNotEmpty() ||
                    questionRequests.isNotEmpty()
            val sessionId = infoState.sessionId.ifEmpty { boundSessionId.orEmpty() }
            val worktree = boundWorktree
            if (worktree != null && lastTodoLive != isLive) {
                lastTodoLive = isLive
                sessionTodoLiveStore.setLive(isLive, sessionId, worktree)
            }
            val previous = _uiState.value
            // Reuse the same TimelineFrame + message list when only composer / session-info /
            // review / command republish — avoids O(n) remap solely from those regions.
            val timelineUnchanged =
                timelineState.timelineFrame === previous.timelineFrame &&
                    timelineState.messages === previous.messages
            val messages =
                if (timelineUnchanged) {
                    previous.messages
                } else {
                    timelineState.messages
                }
            val timelineFrame =
                if (timelineUnchanged) {
                    previous.timelineFrame
                } else {
                    timelineState.timelineFrame
                }
            val timelineEmptyError =
                if (timelineState.timeline == SessionChatTimelineState.Error) {
                    timelineState.errorMessage
                } else {
                    null
                }
            val transientError =
                if (timelineState.timeline == SessionChatTimelineState.Error) {
                    null
                } else {
                    when {
                        infoState.lifecycleErrorMessage != null -> infoState.lifecycleErrorMessage
                        composerState.errorMessage != null -> composerState.errorMessage
                        composerState.abortErrorMessage != null -> composerState.abortErrorMessage
                        commandsState.actionErrorMessage != null -> commandsState.actionErrorMessage
                        infoState.errorMessage != null -> infoState.errorMessage
                        permissionRequests.isEmpty() && permissionTopologyError != null -> permissionTopologyError
                        questionRequests.isEmpty() && questionTopologyError != null -> questionTopologyError
                        timelineState.errorMessage != null -> timelineState.errorMessage
                        else -> null
                    }
                }
            val snackbar = resolveSnackbar(transientError, commandsState.actionSuccessMessage)
            return SessionChatUiState(
                sessionId = infoState.sessionId,
                sessionTitle = infoState.sessionTitle,
                timeline = timelineState.timeline,
                messages = messages,
                timelineFrame = timelineFrame,
                composer = composerState.composer,
                isSending = composerState.isSending,
                isRefreshing = timelineState.isRefreshing,
                isAborting = composerState.isAborting,
                sessionStatusLabel = infoState.sessionStatusLabel,
                sessionTodos = sessionTodos,
                sessionTodosVisible = SessionTodoList.isVisible(sessionTodos, isLive),
                sessionTodosFinished = SessionTodoList.isFinished(sessionTodos),
                sessionTodoActivePreview = SessionTodoList.activePreview(sessionTodos),
                sessionTodoCompletedCount = SessionTodoList.completedCount(sessionTodos),
                sessionTodoTotalCount = sessionTodos.size,
                connectionStatus = infoState.connectionStatus,
                contextUsageFraction =
                    sessionInfo.contextUsageFraction(
                        timelineState.messages,
                        composerState.composer.modelOptions,
                        composerState.composer.selectedModel,
                    ),
                errorMessage = timelineEmptyError,
                snackbar = snackbar,
                isLifecycleActionInFlight = infoState.isLifecycleActionInFlight,
                shouldLeaveSession = infoState.shouldLeaveSession,
                permissionRequest = permissionRequests.firstOrNull(),
                permissionQueueCount = permissionRequests.size,
                permissionSubmitting = permissionSubmittingId != null,
                permissionErrorMessage = permissionError ?: permissionTopologyError,
                questionRequest = visibleQuestion,
                questionWizardIndex = questionWizardIndex,
                questionSelectedLabels = questionDraft.selectedLabels,
                questionCustomText = questionDraft.customText,
                questionCustomSelected = questionDraft.customSelected,
                questionSubmitting = questionSubmittingId != null && questionSubmittingId == visibleQuestion?.id,
                questionErrorMessage =
                    if (visibleQuestion != null) {
                        questionError ?: questionTopologyError
                    } else {
                        null
                    },
                reviewChangesMessageId = reviewChangesState.messageId,
                reviewChangesSummary = reviewChangesState.summary,
                rename = infoState.rename,
                slashSuggestionsVisible = composerState.slashSuggestionsVisible,
                slashSuggestions = composerState.slashSuggestions,
                atSuggestionsVisible = composerState.atSuggestionsVisible,
                atSuggestions = composerState.atSuggestions,
                commandsSheetVisible = commandsState.commandsSheetVisible,
                visibleClientSlashActions = commandsState.visibleActions,
                forkDialogVisible = commandsState.forkDialogVisible,
                forkDraftTitle = commandsState.forkDraftTitle,
                forkSubmitting = commandsState.forkSubmitting,
                forkErrorMessage = commandsState.forkErrorMessage,
                forkResultVisible = commandsState.forkResultVisible,
                forkedSessionTitle = commandsState.forkedSessionTitle,
                pendingSessionReplace = commandsState.pendingSessionReplace,
            )
        }

        private fun resolveSnackbar(
            transientError: String?,
            successNotice: String?,
        ): SessionChatSnackbar? {
            val key =
                when {
                    transientError != null -> "error:$transientError"
                    successNotice != null -> "success:$successNotice"
                    else -> null
                }
            if (key == null) {
                lastSnackbarKey = null
                suppressedSnackbarKey = null
                return null
            }
            if (key == suppressedSnackbarKey) {
                return null
            }
            if (key != lastSnackbarKey) {
                snackbarSequence += 1
                lastSnackbarKey = key
            }
            return SessionChatSnackbar(
                message = transientError ?: requireNotNull(successNotice),
                tone =
                    if (transientError != null) {
                        SessionChatSnackbarTone.Error
                    } else {
                        SessionChatSnackbarTone.Default
                    },
                id = snackbarSequence,
            )
        }

        override fun onCleared() {
            clearSessionReadyFocus()
            super.onCleared()
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

private object EmptyQuestionLiveStore : QuestionLiveStore {
    override fun observeQuestions(directory: String) = kotlinx.coroutines.flow.flowOf(emptyList<QuestionRequest>())

    override fun clearCache() = Unit
}

private data class SessionChatCombinedState(
    val timeline: SessionChatTimelineRegionState,
    val composer: SessionChatComposerRegionState,
    val info: SessionInfoRegionState,
    val reviewChanges: SessionChatReviewChangesRegionState,
    val commands: SessionChatCommandsRegionState,
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
