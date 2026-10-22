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
import dev.opencode.mobile.domain.event.namedProjectId
import dev.opencode.mobile.domain.live.EmptySessionReadyStore
import dev.opencode.mobile.domain.live.EmptySessionTodoLiveStore
import dev.opencode.mobile.domain.live.NoOpSessionViewReporter
import dev.opencode.mobile.domain.live.PermissionLiveStore
import dev.opencode.mobile.domain.live.QuestionLiveStore
import dev.opencode.mobile.domain.live.SessionReadyStore
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.live.SessionTodoLiveStore
import dev.opencode.mobile.domain.live.SessionViewReporter
import dev.opencode.mobile.domain.mcp.McpRepository
import dev.opencode.mobile.domain.mcp.McpServer
import dev.opencode.mobile.domain.message.Attachment
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.model.ModelCatalogStore
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.NewSessionModelSelectionResolver
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.preferences.ChipTimelineIntro
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.domain.project.UnavailableProjectRepository
import dev.opencode.mobile.domain.project.smartProjectLabel
import dev.opencode.mobile.domain.worktree.UnavailableWorktreeRepository
import dev.opencode.mobile.domain.worktree.WorktreeInventory
import dev.opencode.mobile.domain.worktree.WorktreeLoad
import dev.opencode.mobile.domain.worktree.WorktreeMark
import dev.opencode.mobile.domain.worktree.WorktreeRepository
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
import dev.opencode.mobile.ui.sessionchat.region.SessionChatRepoStrip
import dev.opencode.mobile.ui.sessionchat.region.SessionChatReviewChanges
import dev.opencode.mobile.ui.sessionchat.region.SessionChatTimeline
import dev.opencode.mobile.ui.sessionchat.region.SessionInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/** Minimum time a child session's current action stays on screen before the next one replaces it. */
private const val ACTIVITY_CHILD_UPDATE_HOLD_MS = 800L

@HiltViewModel
class SessionChatViewModel
    @Inject
    constructor(
        messageRepository: MessageRepository,
        modelCatalogStore: ModelCatalogStore,
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
        projectFilesRepository: dev.opencode.mobile.domain.filesystem.ProjectFilesRepository,
        newSessionModelSelectionResolver: NewSessionModelSelectionResolver,
        private val connectionProfileStore: ConnectionProfileStore,
        private val activeConnectionManager: ActiveConnectionManager,
        lastTurnReviewChangesStore: LastTurnReviewChangesStore = LastTurnReviewChangesStore(),
        liveUpdates: LiveUpdates = FixedLiveUpdates(),
        private val syncCoordinator: SyncCoordinator = NoOpSyncCoordinator,
        mcpRepository: McpRepository = EmptyMcpRepository,
        private val sessionViewReporter: SessionViewReporter = NoOpSessionViewReporter,
        private val worktreeRepository: WorktreeRepository = UnavailableWorktreeRepository,
        private val projectRepository: ProjectRepository = UnavailableProjectRepository,
    ) : ViewModel() {
        internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
            set(value) {
                field = value
                timeline.ioDispatcher = value
                composer.ioDispatcher = value
                reviewChanges.ioDispatcher = value
                repoStrip.ioDispatcher = value
                commands.ioDispatcher = value
                inbox.ioDispatcher = value
            }

        internal var nowMs: () -> Long = { System.currentTimeMillis() }

        internal var catalogRetryDelaysMs: List<Long>
            get() = composer.catalogRetryDelaysMs
            set(value) {
                composer.catalogRetryDelaysMs = value
            }

        internal var fileSearchDelayMs: Long
            get() = composer.fileSearchDelayMs
            set(value) {
                composer.fileSearchDelayMs = value
            }

        private val timeline =
            SessionChatTimeline(
                messageRepository = messageRepository,
                scope = viewModelScope,
                listChildSessions = { id -> sessionRepository.listChildSessions(id) },
            )
        private val composer =
            SessionChatComposer(
                messageRepository = messageRepository,
                modelCatalogStore = modelCatalogStore,
                commandRepository = commandRepository,
                agentRepository = agentRepository,
                sessionRepository = sessionRepository,
                connectionSessionCoordinator = connectionSessionCoordinator,
                sessionModelSelectionStore = sessionModelSelectionStore,
                composerDraftStore = composerDraftStore,
                scope = viewModelScope,
                foregroundEventStream = foregroundEventStream,
                projectFilesRepository = projectFilesRepository,
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
        private val repoStrip =
            SessionChatRepoStrip(
                sessionRepository = sessionRepository,
                foregroundEventStream = foregroundEventStream,
                scope = viewModelScope,
            )
        private val commands =
            SessionChatCommands(
                sessionRepository = sessionRepository,
                newSessionModelSelectionResolver = newSessionModelSelectionResolver,
                connectionSessionCoordinator = connectionSessionCoordinator,
                mcpRepository = mcpRepository,
                composerDraftStore = composerDraftStore,
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
        private var worktreeCheckout = false
        private var projectIdentityJob: kotlinx.coroutines.Job? = null
        private val checkoutProjectIds = mutableSetOf<String>()
        private var lastSnackbarKey: String? = null
        private var loggedWorkingLamp = false
        private var lastWorkingLamp: String? = null
        private var workingChrome: WorkingTurnIndicator.Chrome = WorkingTurnIndicator.Chrome.Unmounted
        private var workingHold: WorkingTurnIndicator.Hold? = null
        private var workingHoldJob: Job? = null
        private var suppressedSnackbarKey: String? = null
        private var snackbarSequence: Long = 0L

        // Chip-timeline paragraphs the user expanded past `+N more`, keyed by
        // expansion id with the chip count at tap time; reset per session.
        private var expandedStepParagraphs: Map<String, Int> = emptyMap()

        // Step rails the user unfolded past `N earlier steps`, keyed by rail id; reset per session.
        private var unfoldedStepRails: Set<String> = emptySet()

        // Activity card whose sheet is open, by block id; reset per session (#779).
        private var presentedActivityCard: String? = null

        // Step of the presented card whose detail the sheet shows in place of its list (#779).
        private var activityCardDetailStepId: String? = null

        // Child sessions observed for activity cards: their jobs and what they are doing (#779).
        private val activityMessageRepository = messageRepository
        private val activityChildJobs = mutableMapOf<String, Job>()
        private var activityCardChildren: Map<String, SessionChatChildActivity> = emptyMap()
        private var liveRailSource: List<SessionChatMessageItem>? = null
        private var liveRailMarked: List<SessionChatMessageItem> = emptyList()
        private var liveRailActive = false

        // Composer dictation card. An active card is cancelled without inserting when the
        // mode, background, or screen goes away.
        private var dictation: ComposerDictationUiState? = null
        private var dictationGeneration = 0
        private var dictationInsertJob: Job? = null
        internal var dictationFinishDelayMs: Long = 350

        // One-time Chip timeline intro (OPE-270): offered after a long turn finishes.
        private var chipTimelineIntroVisible = false
        private var chipTimelineIntroNotice: String? = null
        private var chipTimelineIntroTurnActive: Boolean? = null
        private var chipTimelineIntroJob: Job? = null

        // Title / custom-answer fields change per keystroke; report the first edit per
        // dialog appearance so analytics measures engagement instead of typing volume.
        private var renameDraftEditReported = false
        private var forkDraftEditReported = false
        private var questionCustomEditReportedRequestId: String? = null
        private var optimisticCreateJob: kotlinx.coroutines.Job? = null
        private var pendingSend: PendingSend? = null
        private var armedSendAcceptance: ArmedSendAcceptance? = null
        private val newSessionModelSelectionResolver = newSessionModelSelectionResolver
        private val connectionSessionCoordinator = connectionSessionCoordinator

        init {
            timeline.ioDispatcher = ioDispatcher
            composer.ioDispatcher = ioDispatcher
            viewModelScope.launch {
                foregroundEventStream.events.collect { event ->
                    eventDomainAdapter.sessionRevertUpdate(event)?.let { update ->
                        commands.applyRevertUpdate(update)
                        return@collect
                    }
                    if (event.type != "worktree.updated") return@collect
                    val projectId = event.namedProjectId() ?: return@collect
                    if (projectId !in checkoutProjectIds) return@collect
                    boundWorktree?.let { scheduleProjectIdentityRefine(it) }
                }
            }
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
                    timeline.updateRevertBoundary(commandsState.revertMessageId)
                    composer.tryApplyInitialSelection(timelineState, timeline.modelSelectionHistoryHints())
                    val isTurnActive =
                        infoState.sessionStatusLabel != null ||
                            effectiveCompacting(
                                localCompacting = commandsState.isCompacting,
                                sessionStatusLabel = infoState.sessionStatusLabel,
                            )
                    reviewChanges.updateCandidate(
                        messages = timelineState.messages,
                        hasResolvedSessionStatus = infoState.hasResolvedSessionStatus,
                        isTurnActive = isTurnActive,
                        isSending = composerState.isSending,
                    )
                    if (infoState.hasResolvedSessionStatus) {
                        repoStrip.updateTurnActive(isTurnActive)
                        updateChipTimelineIntroTurnActive(isTurnActive)
                    }
                    commands.updateVisibility(
                        messages = timelineState.messages,
                        hasSelectedComposerModel = composerState.composer.selectedModel != null,
                    )
                    commands.observeLoadedMessages(
                        messages = timeline.loadedMessages(),
                        isSessionBusy = infoState.sessionStatusLabel != null,
                    )
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
                repoStrip.state.collect { publishUiState() }
            }
            viewModelScope.launch {
                snapshotFlow { SessionChatDisplayPreferences.collapseConsecutiveToolCalls }
                    .distinctUntilChanged()
                    .collect { enabled ->
                        timeline.updateCollapseConsecutiveToolCalls(enabled)
                    }
            }
            viewModelScope.launch {
                snapshotFlow { SessionChatDisplayPreferences.chipTimeline }
                    .distinctUntilChanged()
                    .collect { enabled ->
                        timeline.updateChipTimeline(enabled)
                    }
            }
            viewModelScope.launch {
                snapshotFlow {
                    SessionChatDisplayPreferences.stepRailTimeline to SessionChatDisplayPreferences.activityCardTimeline
                }.distinctUntilChanged()
                    .collect { (stepRail, activityCard) ->
                        timeline.updateStepTimelines(stepRail = stepRail, activityCard = activityCard)
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
            sessionViewReporter.focus(sessionId)
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
            expandedStepParagraphs = emptyMap()
            unfoldedStepRails = emptySet()
            resetActivityCards()
            resetChipTimelineIntro()
            resetWorkingFooter()
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
            repoStrip.bind(worktree, startExpanded = false)
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
            expandedStepParagraphs = emptyMap()
            unfoldedStepRails = emptySet()
            resetActivityCards()
            resetChipTimelineIntro()
            resetWorkingFooter()
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
            repoStrip.bind(worktree, startExpanded = true)
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
            resetWorkingFooter()
            sessionReadyStore.setFocusedSession(sessionId, worktree)
            sessionViewReporter.focus(sessionId)
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
            // Only release our own focus: the next Session Chat (child, parent, fork) may
            // already have focused itself before this screen leaves composition.
            boundSessionId?.let {
                sessionReadyStore.releaseFocusedSession(it)
                sessionViewReporter.release(it)
            }
        }

        fun loadMessages() {
            timeline.loadMessages()
        }

        /** Error-state Retry. Create failures start the session again. Load failures refetch the timeline. */
        fun retryFailedTimeline() {
            val sessionId = boundSessionId
            if (sessionId == null || OptimisticSessionCreate.isPlaceholder(sessionId)) {
                val worktree = boundWorktree ?: return
                boundSessionId = null
                bindOptimisticCreate(boundSessionTitle.orEmpty(), worktree)
                return
            }
            loadMessages()
        }

        /**
         * Shows the [chipCount] chips a chip-timeline paragraph had when its
         * `+N more` was tapped; chips that arrive later still collapse.
         */
        fun expandStepParagraph(
            expansionId: String,
            chipCount: Int,
        ) {
            if ((expandedStepParagraphs[expansionId] ?: -1) >= chipCount) return
            expandedStepParagraphs = expandedStepParagraphs + (expansionId to chipCount)
            _uiState.value = _uiState.value.copy(expandedStepParagraphs = expandedStepParagraphs)
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

        fun exitShellMode() {
            composer.exitShellMode()
        }

        fun beginDictation(
            languageLabel: String,
            onDevice: Boolean,
        ) {
            invalidateDictationInsert()
            dictation =
                ComposerDictationUiState(
                    phase = ComposerDictationPhase.Listening,
                    languageLabel = languageLabel,
                    onDevice = onDevice,
                    startedAtEpochMs = nowMs(),
                )
            publishUiState()
        }

        fun beginDictationUnavailable(
            phase: ComposerDictationPhase,
            message: String? = null,
            languageLabel: String = "",
        ) {
            if (!phase.isUnavailable) return
            // A recognizer error can arrive after Insert has already been confirmed.
            // Dropping the card here would cancel the splice.
            if (dictation?.phase == ComposerDictationPhase.Finishing) return
            invalidateDictationInsert()
            dictation =
                ComposerDictationUiState(
                    phase = phase,
                    languageLabel = languageLabel,
                    startedAtEpochMs = nowMs(),
                    errorMessage = message,
                )
            publishUiState()
        }

        /** Applies a model download update only while the card still shows the model. */
        fun dictationModelDownload(
            phase: ComposerDictationPhase,
            progress: Float? = null,
        ) {
            val current = dictation ?: return
            if (!phase.isModelPhase || !current.phase.isModelPhase) return
            dictation = current.copy(phase = phase, downloadProgress = progress)
            publishUiState()
        }

        fun dictationPartial(
            committed: String,
            volatile: String,
        ) {
            val current = dictation ?: return
            if (current.phase != ComposerDictationPhase.Listening) return
            dictation = current.copy(committed = committed, volatile = volatile)
            publishUiState()
        }

        fun dictationLevel(level: Float) {
            val current = dictation ?: return
            if (current.phase != ComposerDictationPhase.Listening) return
            dictation = current.copy(level = level.coerceIn(0f, 1f))
            publishUiState()
        }

        /** Listening ended but the transcript stays on the card so Insert still works. */
        fun dictationStopped() {
            val current = dictation ?: return
            if (current.phase != ComposerDictationPhase.Listening) return
            dictation = current.copy(phase = ComposerDictationPhase.Stopped, level = 0f)
            publishUiState()
        }

        fun dictationInsert() {
            val current = dictation ?: return
            if (
                current.phase != ComposerDictationPhase.Listening &&
                current.phase != ComposerDictationPhase.Stopped &&
                current.phase != ComposerDictationPhase.Finishing
            ) {
                return
            }
            val transcript = current.committed + current.volatile
            if (transcript.trim().isEmpty()) return
            if (current.phase == ComposerDictationPhase.Finishing) return
            val generation = ++dictationGeneration
            dictation = current.copy(phase = ComposerDictationPhase.Finishing)
            publishUiState()
            dictationInsertJob?.cancel()
            dictationInsertJob =
                viewModelScope.launch {
                    delay(dictationFinishDelayMs)
                    if (generation != dictationGeneration) return@launch
                    val latest = dictation ?: return@launch
                    if (latest.phase != ComposerDictationPhase.Finishing) return@launch
                    spliceDictationTranscript(latest.committed + latest.volatile)
                    dictation = null
                    publishUiState()
                }
        }

        fun dictationCancel() {
            cancelActiveDictation()
        }

        fun cancelDictationForModeChange() {
            if (dictation != null) cancelActiveDictation()
        }

        fun cancelDictationForBackground() {
            if (dictation != null) cancelActiveDictation()
        }

        fun cancelDictationForDismiss() {
            if (dictation != null) cancelActiveDictation()
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

        fun closeMcpServers() {
            logAction(
                actionId = ProductAnalytics.Action.MCP_SERVERS_BACK,
                screenId = ProductAnalytics.Screen.SESSION_COMMANDS,
            )
            commands.closeMcpServers()
        }

        fun runClientSlashAction(action: ClientSlashAction) {
            if (action != ClientSlashAction.Mcp && !hasCreatedSession()) return
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
                ClientSlashAction.Files -> commands.runFiles()
                ClientSlashAction.Mcp -> commands.openMcpServers()
                ClientSlashAction.Compact -> compactCurrentSession()
            }
        }

        fun compactCurrentSession() {
            if (!hasCreatedSession()) return
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

        /** Overflow menu "Fork session": forks the whole session (no message id). */
        fun openForkSession() {
            if (!hasCreatedSession()) return
            forkDraftEditReported = false
            logAction(
                actionId = ProductAnalytics.Action.CHAT_OVERFLOW_FORK,
                screenId = ProductAnalytics.Screen.SESSION_OVERFLOW,
            )
            commands.openForkDialog(currentTitle = sessionInfo.state.value.sessionTitle)
        }

        /**
         * Message actions "Fork from here": forks history before [messageId] and prefills the
         * fork's composer with [messageText] (analytics are logged by the sheet).
         */
        fun forkFromMessage(
            messageId: String,
            messageText: String,
        ) {
            if (!hasCreatedSession()) return
            forkDraftEditReported = false
            commands.openForkDialog(
                currentTitle = sessionInfo.state.value.sessionTitle,
                atMessageId = messageId,
                messageText = messageText,
            )
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

        fun consumePendingFilesOpen() {
            commands.consumePendingFilesOpen()
        }

        fun enableChipTimelineFromIntro() {
            hideChipTimelineIntro(ProductAnalytics.Action.CHIP_TIMELINE_INTRO_ENABLE)
            SessionChatDisplayPreferences.updateChipTimeline(true)
            chipTimelineIntroNotice = CHIP_TIMELINE_INTRO_ENABLED_NOTICE
            publishUiState()
        }

        /** Later button, swipe, scrim, or back. */
        fun postponeChipTimelineIntro() {
            hideChipTimelineIntro(ProductAnalytics.Action.CHIP_TIMELINE_INTRO_LATER)
            SessionChatDisplayPreferences.postponeChipTimelineIntro(nowMs())
            publishUiState()
        }

        fun dismissChipTimelineIntro() {
            hideChipTimelineIntro(ProductAnalytics.Action.CHIP_TIMELINE_INTRO_NEVER)
            SessionChatDisplayPreferences.finishChipTimelineIntro()
            publishUiState()
        }

        private fun hideChipTimelineIntro(actionId: String) {
            if (!chipTimelineIntroVisible) return
            chipTimelineIntroVisible = false
            logAction(actionId, screenId = ProductAnalytics.Screen.SESSION_CHIP_TIMELINE_INTRO)
        }

        private fun resetChipTimelineIntro() {
            chipTimelineIntroJob?.cancel()
            chipTimelineIntroJob = null
            chipTimelineIntroVisible = false
            chipTimelineIntroNotice = null
            chipTimelineIntroTurnActive = null
        }

        /** Offers the intro once a long turn goes busy → idle and stays settled. */
        private fun updateChipTimelineIntroTurnActive(isTurnActive: Boolean) {
            val previous = chipTimelineIntroTurnActive
            chipTimelineIntroTurnActive = isTurnActive
            if (isTurnActive) {
                chipTimelineIntroJob?.cancel()
                return
            }
            if (previous != true || chipTimelineIntroVisible) return
            val toolCalls = ChipTimelineIntro.lastTurnToolCalls(timeline.loadedMessages())
            if (!SessionChatDisplayPreferences.shouldOfferChipTimelineIntro(toolCalls, nowMs())) return
            chipTimelineIntroJob?.cancel()
            chipTimelineIntroJob =
                viewModelScope.launch {
                    delay(ChipTimelineIntro.SETTLE_DELAY_MILLIS)
                    if (isSessionBusy() || _uiState.value.blocksChipTimelineIntro()) return@launch
                    if (!SessionChatDisplayPreferences.shouldOfferChipTimelineIntro(toolCalls, nowMs())) return@launch
                    chipTimelineIntroVisible = true
                    publishUiState()
                }
        }

        fun consumeSnackbar() {
            logAction(ProductAnalytics.Action.CHAT_DISMISS_SNACKBAR)
            suppressedSnackbarKey = lastSnackbarKey
            chipTimelineIntroNotice = null
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
                    armSendAcceptance()
                    if (shouldQueue) {
                        pendingSend = PendingSend(queuedAtMs = System.currentTimeMillis())
                    }
                    timeline.beginOptimisticSend(rows)
                },
                onSendAccepted = {
                    armedSendAcceptance = null
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
                    armedSendAcceptance = null
                    pendingSend = null
                    timeline.failOptimisticSend(optimisticId, error)
                    publishUiState()
                },
                serverAlreadyAccepted = ::serverAlreadyAccepted,
                beforePrompt = { commands.commitPendingRevert(sessionId, worktree) },
            )
        }

        private fun armSendAcceptance() {
            val sessionId = boundSessionId ?: return
            armedSendAcceptance =
                ArmedSendAcceptance(
                    sessionId = sessionId,
                    wasBusy = sessionInfo.state.value.sessionStatusLabel != null,
                    serverMessages = timeline.loadedMessages().toList(),
                )
        }

        private fun serverAlreadyAccepted(): Boolean {
            val armed = armedSendAcceptance ?: return false
            if (armed.sessionId != boundSessionId) return false
            val becameBusy = !armed.wasBusy && sessionInfo.state.value.sessionStatusLabel != null
            val serverUpdated = timeline.loadedMessages() != armed.serverMessages
            return becameBusy || serverUpdated
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

        fun alwaysAllowPermission(
            requestId: String,
            sessionId: String,
        ) {
            logAction(
                actionId = ProductAnalytics.Action.PERMISSION_ALWAYS,
                parameters = mapOf(ProductAnalytics.Param.PERMISSION_DECISION to "always"),
            )
            inbox.alwaysAllowPermission(requestId, sessionId)
        }

        /** Collapse the dock so the composer is reachable; the request stays pending. */
        fun hidePermissionDock(requestId: String) {
            logAction(ProductAnalytics.Action.PERMISSION_HIDE)
            inbox.hidePermissionDock(requestId)
        }

        fun showPermissionDock() {
            logAction(ProductAnalytics.Action.PERMISSION_SHOW)
            inbox.showPermissionDock()
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
                worktreeCheckout = false
                return
            }
            val displayName = ProjectDomainAdapter.displayNameFromWorktree(worktree)
            projectLabel = displayName
            projectLetter = letterCharacterForProject(displayName)
            projectLetterAccent = letterAccentForWorktree(worktree)
            worktreeCheckout = false
            checkoutProjectIds.clear()
        }

        private fun scheduleProjectIdentityRefine(worktree: String) {
            if (worktree.isBlank()) return
            val displayName = ProjectDomainAdapter.displayNameFromWorktree(worktree)
            projectIdentityJob?.cancel()
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
                    coroutineContext.ensureActive()
                    if (boundWorktree != worktree) return@launch
                    val checkout = resolveCheckoutName(worktree, peers)
                    coroutineContext.ensureActive()
                    if (boundWorktree != worktree) return@launch
                    if (checkout != null) {
                        projectLabel = checkout
                        projectLetter = null
                        projectLetterAccent = null
                        worktreeCheckout = true
                    } else {
                        projectLabel = smartProjectLabel(worktree, peers)
                        projectLetter = letterCharacterForProject(displayName)
                        projectLetterAccent = letterAccentForWorktree(worktree)
                        worktreeCheckout = false
                    }
                    publishUiState()
                }
        }

        private suspend fun resolveCheckoutName(
            worktree: String,
            peers: List<String>,
        ): String? {
            if (peers.isEmpty()) return null
            val baseUrl =
                connectionSessionCoordinator.currentProfile()?.baseUrl
                    ?: connectionProfileStore.activeProfile()?.baseUrl
            if (!baseUrl.isNullOrBlank()) worktreeRepository.onConnection(baseUrl)
            val inventories = mutableMapOf<String, WorktreeInventory>()
            checkoutProjectIds.clear()
            for (added in peers) {
                coroutineContext.ensureActive()
                val project =
                    try {
                        projectRepository.getCurrentProject(added)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        null
                    }
                val projectId = project?.id?.takeIf { it.isNotBlank() }
                if (projectId != null) checkoutProjectIds += projectId
                val load = worktreeRepository.loadRefreshed(added, projectId, project?.worktree?.takeIf { it != "/" })
                inventories[added] =
                    when (load) {
                        is WorktreeLoad.Loaded -> WorktreeInventory.loaded(load.directories)
                        is WorktreeLoad.Failed -> WorktreeInventory.Failed
                        WorktreeLoad.Unloaded, WorktreeLoad.Stopped -> WorktreeInventory.Unloaded
                    }
            }
            return WorktreeMark.checkoutNameIfMarked(worktree, peers) { added ->
                inventories[added] ?: WorktreeInventory.Unloaded
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
            syncActivityChildren(_uiState.value.messages)
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
                markLiveStepRail(
                    messages =
                        if (timelineUnchanged) {
                            previous.messages
                        } else {
                            timelineState.messages
                        },
                    isTurnActive = infoState.sessionStatusLabel != null,
                )
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
                    composerState.followUpNotice ?: commandsState.actionSuccessMessage ?: chipTimelineIntroNotice,
                )
            val sessionStatusLabel =
                if (isCompacting) {
                    SessionCompactChrome.STATUS_LABEL
                } else {
                    infoState.sessionStatusLabel
                }
            val awaitingUser =
                inboxSnapshot.permissionRequests.isNotEmpty() || inboxSnapshot.questionRequests.isNotEmpty()
            val lastIsThinking =
                WorkingTurnIndicator.isStreamingThinking(timeline.loadedMessages()) ||
                    WorkingTurnIndicator.lastIsThinking(messages)
            val activeWorkingLabel =
                WorkingTurnIndicator.labelForActiveTurn(
                    statusLabel = sessionStatusLabel,
                    isCompacting = isCompacting,
                    isAwaitingUser = awaitingUser,
                    sendProgressPercent = composerState.sendProgressPercent,
                    isThinking = lastIsThinking,
                )
            val workingResolution =
                WorkingTurnIndicator.resolveFooter(
                    activeLabel = activeWorkingLabel,
                    lastIsThinking = lastIsThinking,
                    hideImmediately = awaitingUser || boundSessionId == null,
                    previous = workingChrome,
                    hold = workingHold,
                    nowMs = nowMs(),
                )
            workingChrome = workingResolution.chrome
            workingHold = workingResolution.hold
            scheduleWorkingFooterWake(workingResolution.wakeAfterMs)
            // Step rail / activity card: the live rail's or card's running or Thinking… line is the indicator (#779).
            val workingIndicatorLabel =
                workingResolution.chrome.label.takeUnless {
                    activeWorkingLabel != null && SessionChatStepRail.hasLiveRail(messages)
                }
            val workingIndicatorCollapsed = workingResolution.chrome.collapsed
            val workingIndicatorFading = workingResolution.chrome.fading
            val workingLampKey =
                "${workingIndicatorLabel ?: "none"}:" +
                    "${if (workingIndicatorCollapsed) 1 else 0}:" +
                    "${if (workingIndicatorFading) 1 else 0}:" +
                    "${if (workingHold != null) 1 else 0}"
            if (!loggedWorkingLamp || lastWorkingLamp != workingLampKey) {
                loggedWorkingLamp = true
                lastWorkingLamp = workingLampKey
                logI(
                    "workingLamp label=${workingIndicatorLabel ?: "none"} " +
                        "collapsed=${if (workingIndicatorCollapsed) 1 else 0} " +
                        "fading=${if (workingIndicatorFading) 1 else 0} " +
                        "hold=${if (workingHold != null) 1 else 0} " +
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
                retryCard = infoState.retryCard,
                workingIndicatorCollapsed = workingIndicatorCollapsed,
                workingIndicatorFading = workingIndicatorFading,
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
                permissionDockHidden = inboxSnapshot.isPermissionDockHidden,
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
                sessionParentId = infoState.parentId,
                supportsSessionMessageDiff = activeConnectionManager.currentCapabilities().supportsSessionMessageDiff,
                supportsSessionArchive = activeConnectionManager.currentCapabilities().supportsSessionArchive,
                rename = infoState.rename,
                slashSuggestionsVisible = composerState.slashSuggestionsVisible,
                slashSuggestions = composerState.slashSuggestions,
                atSuggestionsVisible = composerState.atSuggestionsVisible,
                atSuggestions = composerState.atSuggestions,
                commandsSheetVisible = commandsState.commandsSheetVisible,
                visibleClientSlashActions = commandsState.visibleActions,
                mcpPageVisible = commandsState.mcpPageVisible,
                mcpServers = commandsState.mcpServers,
                mcpLoading = commandsState.mcpLoading,
                mcpErrorMessage = commandsState.mcpErrorMessage,
                mcpSummary = commandsState.mcpSummary,
                forkDialogVisible = commandsState.forkDialogVisible,
                forkDraftTitle = commandsState.forkDraftTitle,
                forkSubmitting = commandsState.forkSubmitting,
                forkErrorMessage = commandsState.forkErrorMessage,
                forkResultVisible = commandsState.forkResultVisible,
                forkedSessionTitle = commandsState.forkedSessionTitle,
                pendingSessionReplace = commandsState.pendingSessionReplace,
                expandedStepParagraphs = expandedStepParagraphs,
                unfoldedStepRails = unfoldedStepRails,
                presentedActivityCard = presentedActivityCard,
                activityCardDetailStepId = activityCardDetailStepId,
                activityCardChildren = activityCardChildren,
                chipTimelineIntroVisible = chipTimelineIntroVisible,
                pendingTerminalOpen = commandsState.pendingTerminalOpen,
                pendingFilesOpen = commandsState.pendingFilesOpen,
                hasPendingRevert = commandsState.hasPendingRevert,
                projectLabel = projectLabel,
                projectLetter = projectLetter,
                projectLetterAccent = projectLetterAccent,
                worktreeCheckout = worktreeCheckout,
                sessionBranchPill = repoStrip.state.value.toSessionBranchPill(),
                dictation = dictation,
            )
        }

        /**
         * Marks the trailing rail of a working turn live. Cached on the source
         * list so a republish from another region keeps the same message list.
         */
        private fun markLiveStepRail(
            messages: List<SessionChatMessageItem>,
            isTurnActive: Boolean,
        ): List<SessionChatMessageItem> {
            if (messages === liveRailMarked && isTurnActive == liveRailActive) return messages
            if (messages === liveRailSource && isTurnActive == liveRailActive) return liveRailMarked
            // Re-mark from the built list: a marked list has settled one-step cards swapped
            // for standard blocks, which could not turn back into a live card.
            val source = if (messages === liveRailMarked) liveRailSource ?: messages else messages
            val marked = SessionChatStepRail.markLive(source, isTurnActive)
            liveRailSource = source
            liveRailActive = isTurnActive
            liveRailMarked = marked
            return marked
        }

        /** Shows every step of a folded step rail, or folds it again. */
        fun toggleStepRailFold(railId: String) {
            unfoldedStepRails = if (railId in unfoldedStepRails) unfoldedStepRails - railId else unfoldedStepRails + railId
            _uiState.value = _uiState.value.copy(unfoldedStepRails = unfoldedStepRails)
        }

        /** Opens the live activity sheet for card [blockId] (#779). */
        fun openActivityCard(blockId: String) {
            presentedActivityCard = blockId
            activityCardDetailStepId = null
            _uiState.value = _uiState.value.copy(presentedActivityCard = blockId, activityCardDetailStepId = null)
            syncActivityChildren(_uiState.value.messages)
        }

        fun dismissActivityCard() {
            if (presentedActivityCard == null) return
            presentedActivityCard = null
            activityCardDetailStepId = null
            _uiState.value = _uiState.value.copy(presentedActivityCard = null, activityCardDetailStepId = null)
            syncActivityChildren(_uiState.value.messages)
        }

        /** Shows step [stepId]'s detail inside the open activity sheet, in place of its list. */
        fun openActivityCardStep(stepId: String) {
            val presented = presentedActivityCard ?: return
            if (!SessionChatActivityCard.hasStep(_uiState.value.messages, presented, stepId)) return
            activityCardDetailStepId = stepId
            _uiState.value = _uiState.value.copy(activityCardDetailStepId = stepId)
        }

        /** Returns the open activity sheet from a step's detail to its list. */
        fun closeActivityCardStep() {
            if (activityCardDetailStepId == null) return
            activityCardDetailStepId = null
            _uiState.value = _uiState.value.copy(activityCardDetailStepId = null)
        }

        private fun resetActivityCards() {
            presentedActivityCard = null
            activityCardDetailStepId = null
            activityChildJobs.values.forEach { it.cancel() }
            activityChildJobs.clear()
            activityCardChildren = emptyMap()
        }

        /**
         * Observes the messages of running child sessions on the live card and
         * the presented one, through the same store and live events as the
         * child's own Session Chat, so agent rows show what each child does.
         * Closes the sheet once its card left the timeline.
         */
        private fun syncActivityChildren(messages: List<SessionChatMessageItem>) {
            val presented = presentedActivityCard
            if (presented != null && messages.isNotEmpty() && !SessionChatActivityCard.hasCard(messages, presented)) {
                presentedActivityCard = null
                activityCardDetailStepId = null
                _uiState.value = _uiState.value.copy(presentedActivityCard = null, activityCardDetailStepId = null)
            }
            // A detail whose step left the card goes back to the list.
            val detailStep = activityCardDetailStepId
            val card = presentedActivityCard
            val detailLeft =
                detailStep != null &&
                    messages.isNotEmpty() &&
                    (card == null || !SessionChatActivityCard.hasStep(messages, card, detailStep))
            if (detailLeft) {
                activityCardDetailStepId = null
                _uiState.value = _uiState.value.copy(activityCardDetailStepId = null)
            }
            val worktree = boundWorktree
            val wanted =
                if (worktree == null) emptySet() else SessionChatActivityCard.observedChildSessionIds(messages, presentedActivityCard)
            (activityChildJobs.keys - wanted).forEach { id -> activityChildJobs.remove(id)?.cancel() }
            for (childId in wanted - activityChildJobs.keys) {
                val parentId = boundSessionId
                activityChildJobs[childId] =
                    viewModelScope.launch {
                        // conflate + delay: a child's current action changes at most once per
                        // hold period, always settling on the latest value, so fast tool bursts
                        // read as a smooth update instead of flicker.
                        activityMessageRepository.observeMessages(childId, worktree).conflate().collect { update ->
                            if (boundSessionId != parentId || update !is MessageListUpdate.Messages) return@collect
                            val activity = SessionChatActivityCard.childActivity(update.messages)
                            if (activityCardChildren[childId] == activity) return@collect
                            activityCardChildren = activityCardChildren + (childId to activity)
                            _uiState.value = _uiState.value.copy(activityCardChildren = activityCardChildren)
                            delay(ACTIVITY_CHILD_UPDATE_HOLD_MS)
                        }
                    }
            }
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
            invalidateDictationInsert()
            dictation = null
            composer.flushDraft()
            inbox.stop()
            resetWorkingFooter()
            clearSessionReadyFocus()
            syncCoordinator.markFocused(null)
            super.onCleared()
        }

        private fun cancelActiveDictation() {
            invalidateDictationInsert()
            if (dictation == null) return
            dictation = null
            publishUiState()
        }

        private fun invalidateDictationInsert() {
            dictationGeneration += 1
            dictationInsertJob?.cancel()
            dictationInsertJob = null
        }

        private fun spliceDictationTranscript(transcript: String) {
            val composerState = composer.state.value.composer
            val text = composerState.text
            val cursor = composerState.cursor.coerceIn(0, text.length)
            val updated = text.substring(0, cursor) + transcript + text.substring(cursor)
            updateComposerText(updated, cursor + transcript.length)
        }

        private fun resetWorkingFooter() {
            workingHoldJob?.cancel()
            workingHoldJob = null
            workingHold = null
            workingChrome = WorkingTurnIndicator.Chrome.Unmounted
        }

        private fun scheduleWorkingFooterWake(wakeAfterMs: Long?) {
            workingHoldJob?.cancel()
            workingHoldJob = null
            if (wakeAfterMs == null) return
            workingHoldJob =
                viewModelScope.launch {
                    delay(wakeAfterMs)
                    publishUiState()
                }
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
            const val CHIP_TIMELINE_INTRO_ENABLED_NOTICE = "Chip timeline is on. Turn it off in Settings."
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
            ClientSlashAction.Files -> ProductAnalytics.Action.COMMANDS_FILES
            ClientSlashAction.Mcp -> ProductAnalytics.Action.COMMANDS_MCP
        }

private object EmptyMcpRepository : McpRepository {
    override suspend fun listServers(directory: String?): List<McpServer> = emptyList()
}

private object EmptyPermissionLiveStore : PermissionLiveStore {
    override fun observePermissions(directory: String) = kotlinx.coroutines.flow.flowOf(emptyList<PermissionRequest>())

    override fun clearCache() = Unit
}

private object EmptyQuestionLiveStore : QuestionLiveStore {
    override fun observeQuestions(directory: String) = kotlinx.coroutines.flow.flowOf(emptyList<QuestionRequest>())

    override fun clearCache() = Unit
}

private data class ArmedSendAcceptance(
    val sessionId: String,
    val wasBusy: Boolean,
    val serverMessages: List<Message>,
)

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

internal object EmptyProjectFilesRepository : dev.opencode.mobile.domain.filesystem.ProjectFilesRepository {
    override suspend fun listEntries(
        worktree: String,
        relativeDirectory: String,
    ) = emptyList<dev.opencode.mobile.domain.filesystem.ProjectEntry>()

    override suspend fun findFiles(
        worktree: String,
        query: String,
        limit: Int,
    ) = emptyList<dev.opencode.mobile.domain.filesystem.ProjectEntry>()

    override suspend fun readFile(
        worktree: String,
        relativePath: String,
    ): dev.opencode.mobile.domain.filesystem.ProjectFileContent =
        throw dev.opencode.mobile.domain.filesystem.ProjectFilesException("No project files", detail = "No project files")
}
