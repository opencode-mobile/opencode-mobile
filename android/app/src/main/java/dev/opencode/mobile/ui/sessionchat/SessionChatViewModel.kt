package dev.opencode.mobile.ui.sessionchat

import android.util.Log
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.connection.DeviceNetworkPath
import dev.opencode.mobile.domain.connection.ReadyDeviceNetworkPath
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.smartProjectLabel
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
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
import dev.opencode.mobile.domain.composer.ComposerDraftStore
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.OptimisticSessionCreate
import dev.opencode.mobile.domain.session.OptimisticSessionCreateCoordinator
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.domain.session.SessionTodoList
import dev.opencode.mobile.network.ActiveConnectionManager
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        composerDraftStore: ComposerDraftStore,
        newSessionModelSelectionResolver: NewSessionModelSelectionResolver,
        private val optimisticSessionCreateCoordinator: OptimisticSessionCreateCoordinator,
        private val connectionProfileStore: ConnectionProfileStore,
        private val activeConnectionManager: ActiveConnectionManager,
        deviceNetworkPath: DeviceNetworkPath = ReadyDeviceNetworkPath,
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
                composerDraftStore = composerDraftStore,
                scope = viewModelScope,
            )
        private val sessionInfo =
            SessionInfo(
                sessionRepository = sessionRepository,
                sessionStatusLiveStore = sessionStatusLiveStore,
                foregroundEventStream = foregroundEventStream,
                eventDomainAdapter = eventDomainAdapter,
                scope = viewModelScope,
                deviceNetworkPath = deviceNetworkPath,
            )
        private val reviewChanges =
            SessionChatReviewChanges(
                sessionRepository = sessionRepository,
                scope = viewModelScope,
                supportsSessionMessageDiff = {
                    activeConnectionManager.currentCapabilities().supportsSessionMessageDiff
                },
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
        private var projectLabel: String? = null
        private var projectLetter: Char? = null
        private var projectLetterAccent: LetterAccent? = null
        private var projectIdentityJob: kotlinx.coroutines.Job? = null
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

        // Title / custom-answer fields change per keystroke; report the first edit per
        // dialog appearance so analytics measures engagement instead of typing volume.
        private var renameDraftEditReported = false
        private var forkDraftEditReported = false
        private var questionCustomEditReportedRequestId: String? = null
        private var optimisticCreateJob: kotlinx.coroutines.Job? = null
        private val newSessionModelSelectionResolver = newSessionModelSelectionResolver
        private val connectionSessionCoordinator = connectionSessionCoordinator

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
                        isTurnActive =
                            infoState.sessionStatusLabel != null ||
                                effectiveCompacting(
                                    localCompacting = commandsState.isCompacting,
                                    sessionStatusLabel = infoState.sessionStatusLabel,
                                ),
                        isSending = composerState.isSending,
                    )
                    commands.updateVisibility(
                        messages = timelineState.messages,
                        hasSelectedComposerModel = composerState.composer.selectedModel != null,
                    )
                    publishUiState(timelineState, composerState, infoState, reviewChangesState, commandsState)
                }
            }
            viewModelScope.launch {
                snapshotFlow { SessionChatDisplayPreferences.collapseConsecutiveToolCalls }
                    .distinctUntilChanged()
                    .collect { enabled ->
                        timeline.updateCollapseConsecutiveToolCalls(enabled)
                    }
            }
        }

        fun bind(
            sessionId: String,
            sessionTitle: String,
            worktree: String,
        ) {
            if (OptimisticSessionCreate.isPlaceholder(sessionId)) {
                bindOptimisticCreate(sessionTitle, worktree)
                return
            }
            // Identity is session + worktree. Title may diverge after rename / session.updated;
            // do not full-reset when navigation still carries a stale title.
            // Always re-assert Ready focus: nav can dispose/clear focus then re-enter the same
            // bound ViewModel without a full reset, and Ready must clear on open.
            sessionReadyStore.setFocusedSession(sessionId, worktree)
            if (boundSessionId == sessionId && boundWorktree == worktree) {
                bindTitleIfChanged(sessionId, sessionTitle, worktree)
                // Peers may have changed while the screen was away; re-resolve smart label.
                scheduleProjectIdentityRefine(worktree)
                return
            }
            boundSessionId = sessionId
            // Clear so bindTitleIfChanged always rebinds sessionInfo for a new identity,
            // even when the previous session shared the same title.
            boundSessionTitle = null
            boundWorktree = worktree
            lastSnackbarKey = null
            suppressedSnackbarKey = null
            snackbarSequence = 0L
            logI("bind session=$sessionId worktree=$worktree title=$sessionTitle")
            seedProjectIdentity(worktree)
            _uiState.value =
                SessionChatUiState.initial(sessionId = sessionId, sessionTitle = sessionTitle).copy(
                    projectLabel = projectLabel,
                    projectLetter = projectLetter,
                    projectLetterAccent = projectLetterAccent,
                )
            scheduleProjectIdentityRefine(worktree)
            timeline.bind(sessionId, worktree)
            composer.bind(sessionId, worktree)
            bindTitleIfChanged(sessionId, sessionTitle, worktree)
            reviewChanges.bind(sessionId, worktree)
            commands.bind(sessionId, worktree)
            observePermissions(sessionId, worktree)
            observeQuestions(sessionId, worktree)
            observeTodos(sessionId, worktree)
            viewModelScope.launch {
                composer.loadCatalog()
            }
        }

        private fun bindOptimisticCreate(
            sessionTitle: String,
            worktree: String,
        ) {
            if (boundWorktree == worktree && boundSessionId != null) {
                return
            }
            optimisticCreateJob?.cancel()
            boundSessionId = OptimisticSessionCreate.PLACEHOLDER_SESSION_ID
            boundSessionTitle = sessionTitle
            boundWorktree = worktree
            lastSnackbarKey = null
            suppressedSnackbarKey = null
            snackbarSequence = 0L
            logI("bind optimistic create worktree=$worktree title=$sessionTitle")
            seedProjectIdentity(worktree)
            _uiState.value =
                SessionChatUiState.initial(
                    sessionId = OptimisticSessionCreate.PLACEHOLDER_SESSION_ID,
                    sessionTitle = sessionTitle,
                    timeline = SessionChatTimelineState.Creating,
                ).copy(
                    projectLabel = projectLabel,
                    projectLetter = projectLetter,
                    projectLetterAccent = projectLetterAccent,
                )
            scheduleProjectIdentityRefine(worktree)
            timeline.bindCreating(worktree)
            composer.bind(OptimisticSessionCreate.PLACEHOLDER_SESSION_ID, worktree)
            sessionInfo.bindCreating(sessionTitle, worktree)
            reviewChanges.bind(OptimisticSessionCreate.PLACEHOLDER_SESSION_ID, worktree)
            commands.bind(OptimisticSessionCreate.PLACEHOLDER_SESSION_ID, worktree)
            optimisticCreateJob =
                viewModelScope.launch {
                    runCatching {
                        withContext(ioDispatcher) {
                            val profile = connectionSessionCoordinator.currentProfile()
                            val session = sessionRepository.createSession(directory = worktree)
                            val selection = profile?.let { newSessionModelSelectionResolver.resolve(it, worktree) }
                            if (profile != null && selection != null) {
                                newSessionModelSelectionResolver.seedSession(profile, worktree, session.id, selection)
                            }
                            session
                        }
                    }.onSuccess { session ->
                        logI("optimistic create success id=${session.id}")
                        promoteOptimisticSession(session.id, session.title, worktree)
                    }.onFailure { error ->
                        logI("optimistic create failed error=${error.message}")
                        optimisticSessionCreateCoordinator.reportCreateFailed(
                            error.message ?: "Unable to create session",
                        )
                        sessionInfo.requestLeaveSession()
                    }
                }
        }

        private fun promoteOptimisticSession(
            sessionId: String,
            sessionTitle: String,
            worktree: String,
        ) {
            boundSessionId = sessionId
            boundSessionTitle = sessionTitle
            sessionReadyStore.setFocusedSession(sessionId, worktree)
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

        private fun bindTitleIfChanged(
            sessionId: String,
            sessionTitle: String,
            worktree: String,
        ) {
            if (boundSessionId == sessionId && boundSessionTitle == sessionTitle) return
            boundSessionTitle = sessionTitle
            sessionInfo.bind(sessionId, sessionTitle, worktree)
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
            logAction(
                actionId = ProductAnalytics.Action.CHAT_OVERFLOW_ARCHIVE,
                screenId = ProductAnalytics.Screen.SESSION_OVERFLOW,
            )
            sessionInfo.archiveSession()
        }

        fun deleteSession() {
            logAction(
                actionId = ProductAnalytics.Action.CHAT_DELETE_CONFIRM,
                screenId = ProductAnalytics.Screen.SESSION_DELETE_CONFIRM,
            )
            sessionInfo.deleteSession()
        }

        fun updateComposerText(
            text: String,
            cursor: Int = text.length,
        ) {
            composer.updateComposerText(text, cursor)
        }

        fun selectSlashSuggestion(commandName: String) {
            // Command and agent names can be user-defined, so only the fact is reported.
            logAction(ProductAnalytics.Action.COMPOSER_SELECT_SLASH_SUGGESTION)
            composer.selectSlashSuggestion(commandName)
        }

        fun selectAtSuggestion(agentName: String) {
            logAction(ProductAnalytics.Action.COMPOSER_SELECT_AT_SUGGESTION)
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
            logAction(ProductAnalytics.Action.COMPOSER_REMOVE_ATTACHMENT)
            composer.removeAttachment(id)
        }

        fun reportAttachmentError(message: String) {
            composer.reportAttachmentError(message)
        }

        fun retryModelCatalog() {
            logAction(ProductAnalytics.Action.COMPOSER_RETRY_MODEL_CATALOG)
            composer.loadCatalog()
        }

        fun selectModel(option: ModelSelectionOption) {
            logAction(
                actionId = ProductAnalytics.Action.MODEL_PICKER_SELECT_MODEL,
                screenId = ProductAnalytics.Screen.SESSION_MODEL_PICKER,
            )
            composer.selectModel(option)
        }

        fun selectMode(mode: String) {
            logAction(
                actionId = ProductAnalytics.Action.MODE_PICKER_SELECT,
                screenId = ProductAnalytics.Screen.SESSION_MODE_PICKER,
                parameters = mapOf(ProductAnalytics.Param.MODE to mode),
            )
            composer.selectMode(mode)
        }

        fun selectVariant(variant: String) {
            logAction(
                actionId = ProductAnalytics.Action.VARIANT_PICKER_SELECT,
                screenId = ProductAnalytics.Screen.SESSION_VARIANT_PICKER,
            )
            composer.selectVariant(variant)
        }

        fun restoreComposerText(text: String) {
            composer.restoreComposerText(text)
        }

        fun openCommands() {
            logAction(ProductAnalytics.Action.COMPOSER_OPEN_COMMANDS)
            commands.openCommands()
            composer.dismissSuggestions()
        }

        fun dismissCommands() {
            logAction(
                actionId = ProductAnalytics.Action.COMMANDS_DISMISS,
                screenId = ProductAnalytics.Screen.SESSION_COMMANDS,
            )
            commands.dismissCommands()
        }

        fun runClientSlashAction(action: ClientSlashAction) {
            logAction(
                actionId = action.productActionId,
                screenId = ProductAnalytics.Screen.SESSION_COMMANDS,
                parameters = mapOf(ProductAnalytics.Param.CLIENT_SLASH to action.name.lowercase()),
            )
            when (action) {
                ClientSlashAction.New -> {
                    if (!isConnectionLive()) return
                    commands.runNew()
                }
                ClientSlashAction.Fork -> {
                    if (!isConnectionLive()) return
                    forkDraftEditReported = false
                    commands.openForkDialog(currentTitle = sessionInfo.state.value.sessionTitle)
                }
                ClientSlashAction.Undo ->
                    commands.runUndo(
                        isBusy =
                            sessionInfo.state.value.sessionStatusLabel != null ||
                                effectiveCompacting(
                                    localCompacting = commands.state.value.isCompacting,
                                    sessionStatusLabel = sessionInfo.state.value.sessionStatusLabel,
                                ),
                        restoreComposerText = composer::restoreComposerText,
                    )
                ClientSlashAction.Redo ->
                    commands.runRedo(
                        isBusy =
                            sessionInfo.state.value.sessionStatusLabel != null ||
                                effectiveCompacting(
                                    localCompacting = commands.state.value.isCompacting,
                                    sessionStatusLabel = sessionInfo.state.value.sessionStatusLabel,
                                ),
                        clearComposerRestore = { composer.restoreComposerText("") },
                    )
                ClientSlashAction.Compact -> {
                    val model = composer.state.value.composer.selectedModel ?: return
                    val beforeFraction = _uiState.value.contextUsageFraction
                    commands.runCompact(
                        providerId = model.option.providerId,
                        modelId = model.option.modelId,
                        beforeContextFraction = beforeFraction,
                        awaitAfterContextFraction = {
                            // Prefer SSE-streamed timeline for the after fraction. A full
                            // refresh is optional and must not gate clearing Compacting…
                            // (see iOS freeze when refresh rebuilt the transcript on MainActor).
                            sessionInfo.refreshStatusSuspending()
                            sessionInfo.contextUsageFraction(
                                messages = timeline.state.value.messages,
                                modelOptions = composer.state.value.composer.modelOptions,
                                selectedModel = composer.state.value.composer.selectedModel,
                            )
                        },
                    )
                }
            }
        }

        fun updateForkDraft(text: String) {
            if (!forkDraftEditReported) {
                forkDraftEditReported = true
                logAction(
                    actionId = ProductAnalytics.Action.CHAT_FORK_EDIT_TITLE,
                    screenId = ProductAnalytics.Screen.SESSION_FORK,
                )
            }
            commands.updateForkDraft(text)
        }

        fun cancelForkDialog() {
            forkDraftEditReported = false
            logAction(
                actionId = ProductAnalytics.Action.CHAT_FORK_CANCEL,
                screenId = ProductAnalytics.Screen.SESSION_FORK,
            )
            commands.cancelForkDialog()
        }

        fun confirmFork() {
            forkDraftEditReported = false
            logAction(
                actionId = ProductAnalytics.Action.CHAT_FORK_CONFIRM,
                screenId = ProductAnalytics.Screen.SESSION_FORK,
            )
            if (!isConnectionLive()) return
            commands.confirmFork()
        }

        fun chooseForkOpen() {
            logAction(
                actionId = ProductAnalytics.Action.CHAT_FORK_OPEN,
                screenId = ProductAnalytics.Screen.SESSION_FORK_RESULT,
            )
            commands.chooseForkOpen()
        }

        fun chooseForkStay() {
            logAction(
                actionId = ProductAnalytics.Action.CHAT_FORK_STAY,
                screenId = ProductAnalytics.Screen.SESSION_FORK_RESULT,
            )
            commands.chooseForkStay()
        }

        fun consumePendingSessionReplace() {
            commands.consumePendingSessionReplace()
        }

        fun consumeSnackbar() {
            logAction(ProductAnalytics.Action.CHAT_DISMISS_SNACKBAR)
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
            renameDraftEditReported = false
            logAction(
                actionId = ProductAnalytics.Action.CHAT_OVERFLOW_RENAME,
                screenId = ProductAnalytics.Screen.SESSION_OVERFLOW,
            )
            sessionInfo.openRenameSession()
        }

        fun cancelRenameSession() {
            renameDraftEditReported = false
            logAction(
                actionId = ProductAnalytics.Action.CHAT_RENAME_CANCEL,
                screenId = ProductAnalytics.Screen.SESSION_RENAME,
            )
            sessionInfo.cancelRenameSession()
        }

        fun updateRenameDraft(text: String) {
            if (!renameDraftEditReported) {
                renameDraftEditReported = true
                logAction(
                    actionId = ProductAnalytics.Action.CHAT_RENAME_EDIT_TITLE,
                    screenId = ProductAnalytics.Screen.SESSION_RENAME,
                )
            }
            sessionInfo.updateRenameDraft(text)
        }

        fun confirmRenameSession() {
            renameDraftEditReported = false
            logAction(
                actionId = ProductAnalytics.Action.CHAT_RENAME_SAVE,
                screenId = ProductAnalytics.Screen.SESSION_RENAME,
            )
            if (!isConnectionLive()) return
            sessionInfo.confirmRenameSession()
        }

        fun sendPrompt() {
            val composerState = composer.state.value.composer
            logAction(
                actionId = ProductAnalytics.Action.COMPOSER_SEND,
                parameters =
                    mapOf(
                        ProductAnalytics.Param.HAS_ATTACHMENTS to composerState.attachments.isNotEmpty().toString(),
                        ProductAnalytics.Param.MODE to composerState.selectedMode,
                    ),
            )
            if (!isConnectionLive()) return
            if (permissionRequests.isNotEmpty() || questionRequests.isNotEmpty()) return
            val sessionId = boundSessionId ?: return
            val worktree = boundWorktree ?: return
            composer.sendPrompt(
                sessionId = sessionId,
                worktree = worktree,
                onOptimisticRows = { rows ->
                    // Mark only after composer passes early-return checks.
                    sessionInfo.markOptimisticBusy()
                    timeline.beginOptimisticSend(rows)
                },
                onSendAccepted = {
                    sessionReadyStore.notifyPromptSent(sessionId, worktree)
                    sessionInfo.refreshStatus()
                    timeline.reloadAfterSend(
                        onComplete = { composer.clearSending() },
                        onReloadFailed = { composer.clearSending() },
                    )
                },
                onSendFailed = { optimisticId, error ->
                    sessionInfo.clearOptimisticBusy()
                    timeline.failOptimisticSend(optimisticId, error)
                },
            )
        }

        fun abort() {
            logAction(ProductAnalytics.Action.COMPOSER_ABORT)
            if (!isConnectionLive()) return
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
            logAction(ProductAnalytics.Action.QUESTION_TOGGLE_OPTION)
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
            logAction(ProductAnalytics.Action.QUESTION_TOGGLE_CUSTOM)
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
            if (questionCustomEditReportedRequestId != requestId) {
                questionCustomEditReportedRequestId = requestId
                logAction(ProductAnalytics.Action.QUESTION_EDIT_CUSTOM_TEXT)
            }
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
            logAction(ProductAnalytics.Action.QUESTION_BACK)
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
            logAction(ProductAnalytics.Action.QUESTION_NEXT)
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
            logAction(
                actionId = ProductAnalytics.Action.QUESTION_SUBMIT,
                parameters =
                    mapOf(
                        ProductAnalytics.Param.QUESTION_RESPONSE to
                            if (questionDrafts.values.any { it.customSelected }) "custom" else "options",
                    ),
            )
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
                    sessionRepository.replyToQuestion(request.sessionId, request.id, answers, boundWorktree)
                }.onFailure { handleQuestionMutationFailure(request.id, it, dismiss = false) }
            }
        }

        fun dismissQuestion(
            requestId: String,
            sessionId: String,
        ) {
            logAction(ProductAnalytics.Action.QUESTION_DISMISS)
            val request =
                questionRequests.firstOrNull { it.id == requestId && it.sessionId == sessionId } ?: return
            if (questionSubmittingId != null) return
            questionSubmittingId = request.id
            questionError = null
            logI("question dismiss request=${request.id} session=${request.sessionId}")
            publishUiState()
            viewModelScope.launch(ioDispatcher) {
                runCatching {
                    sessionRepository.rejectQuestion(request.sessionId, request.id, boundWorktree)
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
            logAction(
                actionId =
                    when (decision) {
                        PermissionDecision.AllowOnce -> ProductAnalytics.Action.PERMISSION_ALLOW_ONCE
                        PermissionDecision.Deny -> ProductAnalytics.Action.PERMISSION_DENY
                    },
                parameters = mapOf(ProductAnalytics.Param.PERMISSION_DECISION to decision.name.lowercase()),
            )
            if (!isConnectionLive()) return
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

        private fun isConnectionLive(): Boolean =
            sessionInfo.state.value.connectionStatus == SessionConnectionStatus.Live

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

        private fun seedProjectIdentity(worktree: String) {
            projectIdentityJob?.cancel()
            if (worktree.isBlank()) {
                projectLabel = null
                projectLetter = null
                projectLetterAccent = null
                return
            }
            val displayName = ProjectDomainAdapter.displayNameFromWorktree(worktree)
            projectLabel = displayName
            projectLetter = letterCharacterForProject(displayName)
            projectLetterAccent = letterAccentForWorktree(worktree)
        }

        private fun scheduleProjectIdentityRefine(worktree: String) {
            if (worktree.isBlank()) return
            val displayName = ProjectDomainAdapter.displayNameFromWorktree(worktree)
            projectIdentityJob =
                viewModelScope.launch {
                    val peers =
                        withContext(ioDispatcher) {
                            val baseUrl =
                                connectionSessionCoordinator.currentProfile()?.baseUrl
                                    ?: connectionProfileStore.activeProfile()?.baseUrl
                            if (baseUrl.isNullOrBlank()) {
                                emptyList()
                            } else {
                                connectionProfileStore.addedWorktrees(baseUrl)
                            }
                        }
                    if (boundWorktree != worktree) return@launch
                    projectLabel = smartProjectLabel(worktree, peers)
                    projectLetter = letterCharacterForProject(displayName)
                    projectLetterAccent = letterAccentForWorktree(worktree)
                    publishUiState()
                }
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
            val isCompacting =
                effectiveCompacting(
                    localCompacting = commandsState.isCompacting,
                    sessionStatusLabel = infoState.sessionStatusLabel,
                )
            val isLive =
                isCompacting ||
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
            val sessionStatusLabel =
                if (isCompacting) {
                    SessionCompactChrome.STATUS_LABEL
                } else {
                    infoState.sessionStatusLabel
                }
            val workingIndicatorLabel =
                WorkingTurnIndicator.labelForActiveTurn(
                    statusLabel = sessionStatusLabel,
                    messages = messages,
                    isCompacting = isCompacting,
                )
            val syncingIndicatorLabel =
                if (timelineState.isSyncingMessages) {
                    SessionMessageSyncChrome.STATUS_LABEL
                } else {
                    null
                }
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
                sessionStatusLabel = sessionStatusLabel,
                workingIndicatorLabel = workingIndicatorLabel,
                syncingIndicatorLabel = syncingIndicatorLabel,
                sessionTodos = sessionTodos,
                sessionTodosVisible = SessionTodoList.isVisible(sessionTodos, isLive),
                sessionTodosFinished = SessionTodoList.isFinished(sessionTodos),
                sessionTodoActivePreview = SessionTodoList.activePreview(sessionTodos),
                sessionTodoCompletedCount = SessionTodoList.completedCount(sessionTodos),
                sessionTodoTotalCount = sessionTodos.size,
                connectionStatus = infoState.connectionStatus,
                messagesFromCache = timelineState.messagesFromCache,
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
                supportsSessionMessageDiff = activeConnectionManager.currentCapabilities().supportsSessionMessageDiff,
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
                projectLabel = projectLabel,
                projectLetter = projectLetter,
                projectLetterAccent = projectLetterAccent,
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

        private fun effectiveCompacting(
            localCompacting: Boolean,
            sessionStatusLabel: String?,
        ): Boolean {
            if (localCompacting) return true
            return SessionCompactionPresence.isActive(
                messages = timeline.loadedMessages(),
                isSessionBusy = sessionStatusLabel != null,
            )
        }

        override fun onCleared() {
            composer.flushDraft()
            clearSessionReadyFocus()
            super.onCleared()
        }

        private fun logI(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        private fun logAction(
            actionId: String,
            screenId: String = ProductAnalytics.Screen.SESSION_CHAT,
            parameters: Map<String, String> = emptyMap(),
        ) {
            ProductAnalytics.logUserAction(
                screenId = screenId,
                actionId = actionId,
                parameters = parameters,
            )
        }

        companion object {
            private const val TAG = "SessionChat"
            val AvailableModes: List<String> = SessionChatComposer.AvailableModes
            const val ModelFilterThreshold: Int = SessionChatComposer.ModelFilterThreshold
        }
    }

private val ClientSlashAction.productActionId: String
    get() =
        when (this) {
            ClientSlashAction.New -> ProductAnalytics.Action.COMMANDS_NEW
            ClientSlashAction.Fork -> ProductAnalytics.Action.COMMANDS_FORK
            ClientSlashAction.Undo -> ProductAnalytics.Action.COMMANDS_UNDO
            ClientSlashAction.Redo -> ProductAnalytics.Action.COMMANDS_REDO
            ClientSlashAction.Compact -> ProductAnalytics.Action.COMMANDS_COMPACT
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
