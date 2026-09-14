package dev.opencode.mobile.ui.sessionchat

import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.agent.AgentRepository
import dev.opencode.mobile.domain.command.ClientSlashAction
import dev.opencode.mobile.domain.command.CommandRepository
import dev.opencode.mobile.domain.composer.ComposerDraftStore
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.FixedLiveUpdates
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.LiveUpdates
import dev.opencode.mobile.domain.live.EmptySessionReadyStore
import dev.opencode.mobile.domain.live.EmptySessionTodoLiveStore
import dev.opencode.mobile.domain.live.PermissionLiveStore
import dev.opencode.mobile.domain.live.QuestionLiveStore
import dev.opencode.mobile.domain.live.SessionReadyStore
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.live.SessionTodoLiveStore
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.NewSessionModelSelectionResolver
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.smartProjectLabel
import dev.opencode.mobile.domain.session.OptimisticSessionCreate
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionTodoList
import dev.opencode.mobile.domain.sync.NoOpSyncCoordinator
import dev.opencode.mobile.domain.sync.ProjectionKey
import dev.opencode.mobile.domain.sync.SyncCoordinator
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.ui.sessionchat.region.SessionChatCommands
import dev.opencode.mobile.ui.sessionchat.region.SessionChatComposer
import dev.opencode.mobile.ui.sessionchat.region.SessionChatInbox
import dev.opencode.mobile.ui.sessionchat.region.SessionChatReviewChanges
import dev.opencode.mobile.ui.sessionchat.region.SessionChatTimeline
import dev.opencode.mobile.ui.sessionchat.region.SessionInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        private val connectionProfileStore: ConnectionProfileStore,
        private val activeConnectionManager: ActiveConnectionManager,
        lastTurnReviewChangesStore: LastTurnReviewChangesStore = LastTurnReviewChangesStore(),
        liveUpdates: LiveUpdates = FixedLiveUpdates(),
        private val syncCoordinator: SyncCoordinator = NoOpSyncCoordinator,
    ) : ViewModel() {
        internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
            set(value) {
                field = value
                timeline.ioDispatcher = value
                composer.ioDispatcher = value
                reviewChanges.ioDispatcher = value
                commands.ioDispatcher = value
                inbox.ioDispatcher = value
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
                liveUpdates = liveUpdates,
            )
        private val reviewChanges =
            SessionChatReviewChanges(
                scope = viewModelScope,
                supportsSessionMessageDiff = {
                    activeConnectionManager.currentCapabilities().supportsSessionMessageDiff
                },
                lastTurnReviewChangesStore = lastTurnReviewChangesStore,
            )
        private val commands =
            SessionChatCommands(
                sessionRepository = sessionRepository,
                newSessionModelSelectionResolver = newSessionModelSelectionResolver,
                connectionSessionCoordinator = connectionSessionCoordinator,
                scope = viewModelScope,
            )
        private val inbox =
            SessionChatInbox(
                sessionRepository = sessionRepository,
                permissionLiveStore = permissionLiveStore,
                questionLiveStore = questionLiveStore,
                sessionTodoLiveStore = sessionTodoLiveStore,
                scope = viewModelScope,
                canMutate = { sessionInfo.state.value.canMutate },
                loadedMessages = { timeline.loadedMessages() },
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
        private var lastSnackbarKey: String? = null
        private var loggedWorkingLamp = false
        private var lastWorkingLamp: String? = null
        private var suppressedSnackbarKey: String? = null
        private var snackbarSequence: Long = 0L

        // Title / custom-answer fields change per keystroke; report the first edit per
        // dialog appearance so analytics measures engagement instead of typing volume.
        private var renameDraftEditReported = false
        private var forkDraftEditReported = false
        private var questionCustomEditReportedRequestId: String? = null
        private var optimisticCreateJob: kotlinx.coroutines.Job? = null
        private var pendingSend: PendingSend? = null
        private val newSessionModelSelectionResolver = newSessionModelSelectionResolver
        private val connectionSessionCoordinator = connectionSessionCoordinator

        init {
            timeline.ioDispatcher = ioDispatcher
            composer.ioDispatcher = ioDispatcher
            viewModelScope.launch {
                combine(
                    combine(timeline.state, composer.state, sessionInfo.state, ::Triple),
                    combine(reviewChanges.state, commands.state, inbox.state, ::Triple),
                ) { first, second ->
                    SessionChatCombinedState(
                        first.first,
                        first.second,
                        first.third,
                        second.first,
                        second.second,
                        second.third,
                    )
                }.collect { (timelineState, composerState, infoState, reviewChangesState, commandsState, inboxState) ->
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
                    commands.observeLoadedMessages(timeline.loadedMessages())
                    publishUiState(
                        timelineState,
                        composerState,
                        infoState,
                        reviewChangesState,
                        commandsState,
                        inboxState,
                    )
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
                timeline.restartLiveObservation()
                syncCoordinator.markFocused(ProjectionKey.Transcript(worktree, sessionId))
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
            inbox.bind(sessionId, worktree)
            syncCoordinator.markFocused(ProjectionKey.Transcript(worktree, sessionId))
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
                SessionChatUiState
                    .initial(
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
                        if (error is CancellationException) throw error
                        logI("optimistic create failed error=${error.message}")
                        timeline.showCreateFailed(createSessionErrorMessage(error))
                    }
                }
        }

        private fun createSessionErrorMessage(error: Throwable): String =
            when (error) {
                is SessionError.Http -> error.message ?: "Unable to create session"
                is SessionError.Transport -> "Unable to reach the server"
                is SessionError.Decoding -> "Unable to read server response"
                else -> error.message ?: "Unable to create session"
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
            inbox.bind(sessionId, worktree)
            syncCoordinator.markFocused(ProjectionKey.Transcript(worktree, sessionId))
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

        fun loadOlderMessages() {
            timeline.loadOlder()
        }

        fun archiveSession() {
            if (!hasCreatedSession()) return
            logAction(
                actionId = ProductAnalytics.Action.CHAT_OVERFLOW_ARCHIVE,
                screenId = ProductAnalytics.Screen.SESSION_OVERFLOW,
            )
            sessionInfo.archiveSession()
        }

        fun deleteSession() {
            if (!hasCreatedSession()) return
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

        fun revertMessage(messageId: String) {
            commands.revertMessage(
                id = messageId,
                isBusy = isSessionBusy(),
                restoreComposerText = composer::restoreComposerText,
            )
        }

        fun cancelRevert() {
            commands.cancelRevert(
                isBusy = isSessionBusy(),
                clearComposerRestore = { composer.restoreComposerText("") },
            )
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
            if (!hasCreatedSession()) return
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
                        isBusy = isSessionBusy(),
                        restoreComposerText = composer::restoreComposerText,
                    )
                ClientSlashAction.Redo ->
                    commands.runRedo(
                        isBusy = isSessionBusy(),
                        clearComposerRestore = { composer.restoreComposerText("") },
                    )
                ClientSlashAction.Terminal ->
                    commands.runTerminal(
                        projectTitle = _uiState.value.projectLabel ?: sessionInfo.state.value.sessionTitle,
                    )
                ClientSlashAction.Compact -> {
                    val model = composer.state.value.composer.selectedModel ?: return
                    val beforeFraction = _uiState.value.contextUsageFraction
                    commands.runCompact(
                        providerId = model.option.providerId,
                        modelId = model.option.modelId,
                        beforeContextFraction = beforeFraction,
                        awaitAfterContextFraction = {
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

        fun consumePendingTerminalOpen() {
            commands.consumePendingTerminalOpen()
        }

        fun consumeSnackbar() {
            logAction(ProductAnalytics.Action.CHAT_DISMISS_SNACKBAR)
            suppressedSnackbarKey = lastSnackbarKey
            commands.clearActionFeedback()
            composer.clearTransientErrors()
            sessionInfo.clearTransientErrors()
            timeline.clearReadyError()
            inbox.clearTopologyErrors()
            publishUiState()
        }

        fun openRenameSession() {
            if (!hasCreatedSession()) return
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

        fun toggleFollowUp() {
            if (!activeConnectionManager.currentCapabilities().supportsFollowUpDelivery) return
            composer.toggleFollowUp()
        }

        fun steerPendingPrompt(id: String) {
            runPendingPromptAction(
                actionId = ProductAnalytics.Action.CHAT_STEER_PENDING_PROMPT,
                inboxId = id,
                optimistic = { timeline.applyOptimisticPendingPrompt(id, FollowUpDelivery.Steer) },
            ) { sessionId, worktree ->
                sessionRepository.steerPendingPrompt(sessionId, id, worktree)
            }
        }

        fun queuePendingPrompt(id: String) {
            runPendingPromptAction(
                actionId = ProductAnalytics.Action.CHAT_QUEUE_PENDING_PROMPT,
                inboxId = id,
                optimistic = { timeline.applyOptimisticPendingPrompt(id, FollowUpDelivery.Queue) },
            ) { sessionId, worktree ->
                sessionRepository.queuePendingPrompt(sessionId, id, worktree)
            }
        }

        fun cancelPendingPrompt(id: String) {
            runPendingPromptAction(
                actionId = ProductAnalytics.Action.CHAT_CANCEL_PENDING_PROMPT,
                inboxId = id,
                optimistic = {
                    timeline.applyOptimisticPendingPrompt(id, removed = true)
                },
            ) { sessionId, worktree ->
                sessionRepository.cancelPendingPrompt(sessionId, id, worktree)
            }
        }

        fun clearPendingPrompts() {
            _uiState.value.pendingPrompts
                .filter { !it.isSettling }
                .map { it.id }
                .forEach(::cancelPendingPrompt)
        }

        private fun runPendingPromptAction(
            actionId: String,
            inboxId: String,
            optimistic: () -> Unit,
            call: suspend (sessionId: String, worktree: String) -> Boolean,
        ) {
            if (!activeConnectionManager.currentCapabilities().supportsFollowUpDelivery) return
            logAction(actionId)
            val sessionId = boundSessionId ?: return
            val worktree = boundWorktree ?: return
            viewModelScope.launch {
                runCatching {
                    withContext(ioDispatcher) { call(sessionId, worktree) }
                }.onSuccess { ok ->
                    if (ok) {
                        optimistic()
                    } else {
                        commands.reportActionError(PENDING_PROMPT_FAILURE)
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    DiagnosticLog.warning(
                        DiagnosticLogArea.Chat,
                        "pending prompt $actionId failed id=$inboxId",
                        error,
                    )
                    commands.reportActionError(PENDING_PROMPT_FAILURE)
                }
            }
        }

        fun sendPrompt() {
            if (!hasCreatedSession()) return
            val composerState = composer.state.value.composer
            logAction(
                actionId = ProductAnalytics.Action.COMPOSER_SEND,
                parameters =
                    mapOf(
                        ProductAnalytics.Param.HAS_ATTACHMENTS to composerState.attachments.isNotEmpty().toString(),
                        ProductAnalytics.Param.MODE to composerState.selectedMode,
                    ),
            )
            if (!sessionInfo.state.value.composerEnabled) return
            if (pendingSend != null || composer.state.value.isSending) return
            if (inbox.state.value.permissionRequests
                    .isNotEmpty() ||
                inbox.state.value.questionRequests
                    .isNotEmpty()
            ) {
                return
            }
            val sessionId = boundSessionId ?: return
            val worktree = boundWorktree ?: return
            val shouldQueue = !sessionInfo.state.value.canMutate
            composer.sendPrompt(
                sessionId = sessionId,
                worktree = worktree,
                isSessionBusy = sessionInfo.state.value.sessionStatusLabel != null,
                supportsFollowUp = activeConnectionManager.currentCapabilities().supportsFollowUpDelivery,
                holdUntil = {
                    if (!shouldQueue) {
                        true
                    } else {
                        awaitLive(PENDING_SEND_TIMEOUT_MS)
                    }
                },
                onOptimisticRows = { rows ->
                    if (shouldQueue) {
                        pendingSend = PendingSend(queuedAtMs = System.currentTimeMillis())
                    }
                    timeline.beginOptimisticSend(rows)
                },
                onSendAccepted = {
                    pendingSend = null
                    commands.consumePendingRevert()
                    sessionInfo.setOptimistic(SessionActivity.Running)
                    sessionReadyStore.notifyPromptSent(sessionId, worktree)
                    timeline.reloadAfterSend(
                        onComplete = { composer.clearSending() },
                        onReloadFailed = { composer.clearSending() },
                    )
                    publishUiState()
                },
                onSendFailed = { optimisticId, error ->
                    pendingSend = null
                    timeline.failOptimisticSend(optimisticId, error)
                    publishUiState()
                },
            )
        }

        fun abort() {
            logAction(ProductAnalytics.Action.COMPOSER_ABORT)
            logI("abort tap session=$boundSessionId live=${isConnectionLive()}")
            if (!isConnectionLive()) return
            composer.abort { success ->
                if (!success) return@abort
                sessionInfo.setOptimistic(
                    SessionActivity.Idle,
                    ttlMs = SessionStatusLiveStore.ABORT_OPTIMISTIC_TTL_MS,
                )
            }
        }

        fun allowPermissionOnce(
            requestId: String,
            sessionId: String,
        ) {
            logAction(
                actionId = ProductAnalytics.Action.PERMISSION_ALLOW_ONCE,
                parameters = mapOf(ProductAnalytics.Param.PERMISSION_DECISION to "allowonce"),
            )
            inbox.allowPermissionOnce(requestId, sessionId)
        }

        fun denyPermission(
            requestId: String,
            sessionId: String,
        ) {
            logAction(
                actionId = ProductAnalytics.Action.PERMISSION_DENY,
                parameters = mapOf(ProductAnalytics.Param.PERMISSION_DECISION to "deny"),
            )
            inbox.denyPermission(requestId, sessionId)
        }

        fun toggleQuestionOption(
            requestId: String,
            sessionId: String,
            label: String,
        ) {
            logAction(ProductAnalytics.Action.QUESTION_TOGGLE_OPTION)
            inbox.toggleQuestionOption(requestId, sessionId, label)
        }

        fun toggleQuestionCustom(
            requestId: String,
            sessionId: String,
        ) {
            logAction(ProductAnalytics.Action.QUESTION_TOGGLE_CUSTOM)
            inbox.toggleQuestionCustom(requestId, sessionId)
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
            inbox.updateQuestionCustomText(requestId, sessionId, text)
        }

        fun questionBack(
            requestId: String,
            sessionId: String,
        ) {
            logAction(ProductAnalytics.Action.QUESTION_BACK)
            inbox.questionBack(requestId, sessionId)
        }

        fun questionNext(
            requestId: String,
            sessionId: String,
        ) {
            logAction(ProductAnalytics.Action.QUESTION_NEXT)
            inbox.questionNext(requestId, sessionId)
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
                            if (inbox.state.value.questionDrafts.values
                                    .any { it.customSelected }
                            ) {
                                "custom"
                            } else {
                                "options"
                            },
                    ),
            )
            inbox.submitQuestion(requestId, sessionId)
        }

        fun dismissQuestion(
            requestId: String,
            sessionId: String,
        ) {
            logAction(ProductAnalytics.Action.QUESTION_DISMISS)
            inbox.dismissQuestion(requestId, sessionId)
        }

        private fun isConnectionLive(): Boolean = sessionInfo.state.value.canMutate

        private suspend fun awaitLive(timeoutMs: Long): Boolean {
            if (sessionInfo.state.value.canMutate) return true
            return withTimeoutOrNull(timeoutMs) {
                sessionInfo.state.first { it.canMutate }
                true
            } ?: false
        }

        private fun hasCreatedSession(): Boolean {
            val sessionId = boundSessionId ?: return false
            return !OptimisticSessionCreate.isPlaceholder(sessionId)
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
                inbox.state.value,
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
            inboxState: SessionChatInboxRegionState,
        ) {
            _uiState.value =
                mergeUiState(
                    timelineState,
                    composerState,
                    infoState,
                    reviewChangesState,
                    commandsState,
                    inboxState,
                )
        }

        private fun mergeUiState(
            timelineState: SessionChatTimelineRegionState,
            composerState: SessionChatComposerRegionState,
            infoState: SessionInfoRegionState,
            reviewChangesState: SessionChatReviewChangesRegionState,
            commandsState: SessionChatCommandsRegionState,
            inboxState: SessionChatInboxRegionState,
        ): SessionChatUiState {
            inbox.refilterOrphans()
            val inboxSnapshot = inbox.state.value
            val visibleQuestion = inboxSnapshot.visibleQuestionRequest
            val questionDraft = inboxSnapshot.questionDraft
            val isCompacting =
                effectiveCompacting(
                    localCompacting = commandsState.isCompacting,
                    sessionStatusLabel = infoState.sessionStatusLabel,
                )
            val isLive =
                isCompacting ||
                    infoState.sessionStatusLabel != null ||
                    inboxSnapshot.permissionRequests.isNotEmpty() ||
                    inboxSnapshot.questionRequests.isNotEmpty()
            val sessionId = infoState.sessionId.ifEmpty { boundSessionId.orEmpty() }
            val worktree = boundWorktree
            if (worktree != null) {
                inbox.setLive(isLive, sessionId, worktree)
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
                        inboxSnapshot.permissionRequests.isEmpty() &&
                            inboxSnapshot.permissionTopologyError != null -> inboxSnapshot.permissionTopologyError
                        inboxSnapshot.questionRequests.isEmpty() &&
                            inboxSnapshot.questionTopologyError != null -> inboxSnapshot.questionTopologyError
                        timelineState.errorMessage != null -> timelineState.errorMessage
                        else -> null
                    }
                }
            val snackbar =
                resolveSnackbar(
                    transientError,
                    composerState.followUpNotice ?: commandsState.actionSuccessMessage,
                )
            val sessionStatusLabel =
                if (isCompacting) {
                    SessionCompactChrome.STATUS_LABEL
                } else {
                    infoState.sessionStatusLabel
                }
            val awaitingUser =
                inboxSnapshot.permissionRequests.isNotEmpty() || inboxSnapshot.questionRequests.isNotEmpty()
            val lastIsThinking = WorkingTurnIndicator.lastIsThinking(messages)
            val workingIndicatorLabel =
                WorkingTurnIndicator.labelForActiveTurn(
                    statusLabel = sessionStatusLabel,
                    isCompacting = isCompacting,
                    isAwaitingUser = awaitingUser,
                    sendProgressPercent = composerState.sendProgressPercent,
                )
            val workingIndicatorCollapsed =
                WorkingTurnIndicator.shouldCollapseReservedRow(
                    lastIsThinking = lastIsThinking,
                    label = workingIndicatorLabel,
                )
            val workingLampKey =
                "${workingIndicatorLabel ?: "none"}:${if (workingIndicatorCollapsed) 1 else 0}"
            if (!loggedWorkingLamp || lastWorkingLamp != workingLampKey) {
                loggedWorkingLamp = true
                lastWorkingLamp = workingLampKey
                logI(
                    "workingLamp label=${workingIndicatorLabel ?: "none"} " +
                        "collapsed=${if (workingIndicatorCollapsed) 1 else 0} " +
                        "status=${sessionStatusLabel ?: "none"} " +
                        "awaitingUser=${if (awaitingUser) 1 else 0} " +
                        "lastIsThinking=${if (lastIsThinking) 1 else 0}",
                )
            }
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
                pendingPrompts =
                    pendingPromptsFrom(
                        loaded = timeline.loadedMessages(),
                        optimistic = timeline.pendingOptimisticItems(),
                    ),
                timelineFrame = timelineFrame,
                composer =
                    composerState.composer.copy(
                        supportsFollowUpDelivery =
                            activeConnectionManager.currentCapabilities().supportsFollowUpDelivery,
                    ),
                isSending = composerState.isSending,
                isRefreshing = timelineState.isRefreshing,
                hasOlder = timelineState.hasOlder,
                isLoadingOlder = timelineState.isLoadingOlder,
                isAborting = composerState.isAborting,
                sessionStatusLabel = sessionStatusLabel,
                workingIndicatorLabel = workingIndicatorLabel,
                workingIndicatorCollapsed = workingIndicatorCollapsed,
                syncingIndicatorLabel = syncingIndicatorLabel,
                sessionTodos = inboxSnapshot.sessionTodos,
                sessionTodosVisible = SessionTodoList.isVisible(inboxSnapshot.sessionTodos),
                sessionTodosFinished = SessionTodoList.isFinished(inboxSnapshot.sessionTodos),
                sessionTodoActivePreview = SessionTodoList.activePreview(inboxSnapshot.sessionTodos),
                sessionTodoCompletedCount = SessionTodoList.completedCount(inboxSnapshot.sessionTodos),
                sessionTodoTotalCount = inboxSnapshot.sessionTodos.size,
                connectionStatus = infoState.connectionStatus,
                canMutate = infoState.canMutate,
                composerEnabled = infoState.composerEnabled,
                pendingSend = pendingSend,
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
                permissionRequest = inboxSnapshot.permissionRequests.firstOrNull(),
                permissionQueueCount = inboxSnapshot.permissionRequests.size,
                permissionSubmitting = inboxSnapshot.permissionSubmittingId != null,
                permissionErrorMessage = inboxSnapshot.permissionError ?: inboxSnapshot.permissionTopologyError,
                questionRequest = visibleQuestion,
                questionWizardIndex = inboxSnapshot.questionWizardIndex,
                questionSelectedLabels = questionDraft.selectedLabels,
                questionCustomText = questionDraft.customText,
                questionCustomSelected = questionDraft.customSelected,
                questionSubmitting =
                    inboxSnapshot.questionSubmittingId != null &&
                        inboxSnapshot.questionSubmittingId == visibleQuestion?.id,
                questionErrorMessage =
                    if (visibleQuestion != null) {
                        inboxSnapshot.questionError ?: inboxSnapshot.questionTopologyError
                    } else {
                        null
                    },
                reviewChangesMessageId = reviewChangesState.messageId,
                reviewChangesSummary = reviewChangesState.summary,
                supportsSessionMessageDiff = activeConnectionManager.currentCapabilities().supportsSessionMessageDiff,
                supportsSessionArchive = activeConnectionManager.currentCapabilities().supportsSessionArchive,
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
                pendingTerminalOpen = commandsState.pendingTerminalOpen,
                hasPendingRevert = commandsState.hasPendingRevert,
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

        private fun isSessionBusy(): Boolean =
            sessionInfo.state.value.sessionStatusLabel != null ||
                effectiveCompacting(
                    localCompacting = commands.state.value.isCompacting,
                    sessionStatusLabel = sessionInfo.state.value.sessionStatusLabel,
                )

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
            inbox.stop()
            clearSessionReadyFocus()
            syncCoordinator.markFocused(null)
            super.onCleared()
        }

        private fun logI(message: String) {
            DiagnosticLog.info(DiagnosticLogArea.Chat, message)
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
            const val ModelFilterThreshold: Int = SessionChatComposer.ModelFilterThreshold
            const val PENDING_SEND_TIMEOUT_MS = 8_000L
            const val PENDING_PROMPT_FAILURE = "Couldn't update pending prompt"
        }
    }

/**
 * Union of server inbox rows and still-unmerged optimistic busy sends.
 * [optimistic] must already be the merge leftover (echo dropped the matching
 * optimistic row, often under a different id). This function only skips
 * optimistic ids that are already in [loaded].
 */
internal fun pendingPromptsFrom(
    loaded: List<Message>,
    optimistic: List<SessionChatMessageItem>,
): List<PendingPrompt> {
    val fromLoaded =
        loaded
            .filter { message ->
                message.role == MessageRole.User &&
                    message.isWaitingForPickup &&
                    message.followUpDelivery != null
            }.map { message ->
                PendingPrompt(
                    id = message.id,
                    text = message.text,
                    delivery = requireNotNull(message.followUpDelivery),
                    isSettling = false,
                )
            }
    val loadedIds = fromLoaded.map { it.id }.toSet()
    val fromOptimistic =
        optimistic
            .filter { item ->
                item.role == MessageRole.User &&
                    item.isPending &&
                    item.pendingDelivery != null &&
                    item.id !in loadedIds
            }.map { item ->
                PendingPrompt(
                    id = item.id,
                    text = item.text,
                    delivery = requireNotNull(item.pendingDelivery),
                    isSettling = true,
                )
            }
    val steers =
        fromLoaded.filter { it.delivery == FollowUpDelivery.Steer } +
            fromOptimistic.filter { it.delivery == FollowUpDelivery.Steer }
    val queued =
        fromLoaded.filter { it.delivery == FollowUpDelivery.Queue } +
            fromOptimistic.filter { it.delivery == FollowUpDelivery.Queue }
    return steers + queued
}

private val ClientSlashAction.productActionId: String
    get() =
        when (this) {
            ClientSlashAction.New -> ProductAnalytics.Action.COMMANDS_NEW
            ClientSlashAction.Fork -> ProductAnalytics.Action.COMMANDS_FORK
            ClientSlashAction.Undo -> ProductAnalytics.Action.COMMANDS_UNDO
            ClientSlashAction.Redo -> ProductAnalytics.Action.COMMANDS_REDO
            ClientSlashAction.Compact -> ProductAnalytics.Action.COMMANDS_COMPACT
            ClientSlashAction.Terminal -> ProductAnalytics.Action.COMMANDS_TERMINAL
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
    val inbox: SessionChatInboxRegionState,
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
