package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.R
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.agent.AgentRepository
import dev.opencode.mobile.domain.command.ClientSlashAction
import dev.opencode.mobile.domain.command.ComposerShellCommand
import dev.opencode.mobile.domain.command.CommandRepository
import dev.opencode.mobile.domain.composer.ComposerDraft
import dev.opencode.mobile.domain.composer.ComposerDraftStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.FixedLiveUpdates
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.LiveUpdates
import dev.opencode.mobile.domain.event.isWorktreeUpdated
import dev.opencode.mobile.domain.event.namedProjectId
import dev.opencode.mobile.domain.event.sessionMoved
import dev.opencode.mobile.domain.event.sessionRevertUpdate
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.goal.EmptySessionGoalRepository
import dev.opencode.mobile.domain.goal.SessionGoalAction
import dev.opencode.mobile.domain.goal.SessionGoalDraft
import dev.opencode.mobile.domain.goal.SessionGoalLimits
import dev.opencode.mobile.domain.goal.SessionGoalRepository
import dev.opencode.mobile.domain.home.HomeProjectsSnapshot
import dev.opencode.mobile.domain.home.HomeWorktreesFeed
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
import dev.opencode.mobile.domain.message.AttachmentLimits
import dev.opencode.mobile.domain.message.AttachmentUpload
import dev.opencode.mobile.domain.message.AttachmentIngestionResult
import dev.opencode.mobile.domain.message.AttachmentUploadService
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ServerFilePreviews
import dev.opencode.mobile.domain.message.UnavailableAttachmentUploadService
import dev.opencode.mobile.domain.message.UnavailableServerFilePreviews
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.ui.changes.TurnDiffUnavailable
import dev.opencode.mobile.domain.model.ModelCatalogStore
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.NewSessionModelSelectionResolver
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.preferences.ChipTimelineIntro
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.domain.preferences.SessionChatTimelineStyle
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.domain.project.UnavailableProjectRepository
import dev.opencode.mobile.domain.session.NewSessionCreatedSession
import dev.opencode.mobile.domain.session.NewSessionDraft
import dev.opencode.mobile.domain.session.NewSessionDraftStart
import dev.opencode.mobile.domain.session.NewSessionDraftStore
import dev.opencode.mobile.domain.session.NewSessionPlace
import dev.opencode.mobile.domain.session.OptimisticSessionCreate
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionTodoList
import dev.opencode.mobile.domain.session.SideQuestion
import dev.opencode.mobile.domain.session.SideQuestionStore
import dev.opencode.mobile.domain.shell.AgentShellRepository
import dev.opencode.mobile.domain.shell.EmptyAgentShellRepository
import dev.opencode.mobile.domain.sync.NoOpSyncCoordinator
import dev.opencode.mobile.domain.sync.ProjectionKey
import dev.opencode.mobile.domain.sync.SyncCoordinator
import dev.opencode.mobile.domain.worktree.CreateWorktreeRequest
import dev.opencode.mobile.domain.worktree.UnavailableWorktreeRepository
import dev.opencode.mobile.domain.worktree.WorktreeCreateError
import dev.opencode.mobile.domain.worktree.WorktreeInventory
import dev.opencode.mobile.domain.worktree.WorktreeInventoryCache
import dev.opencode.mobile.domain.worktree.WorktreeLoad
import dev.opencode.mobile.domain.worktree.WorktreeRepository
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.ui.sessionchat.region.SessionChatCommands
import dev.opencode.mobile.ui.sessionchat.region.SessionChatComposer
import dev.opencode.mobile.ui.sessionchat.region.SessionChatDictation
import dev.opencode.mobile.ui.sessionchat.region.SessionChatGoal
import dev.opencode.mobile.ui.sessionchat.region.SessionChatImageViewer
import dev.opencode.mobile.ui.sessionchat.region.SessionChatSideQuestionNotice
import dev.opencode.mobile.ui.sessionchat.region.SessionChatShells
import dev.opencode.mobile.ui.sessionchat.region.SessionChatSideQuestions
import dev.opencode.mobile.ui.sessionchat.region.SessionChatInbox
import dev.opencode.mobile.ui.sessionchat.region.SessionChatNewSessionDraft
import dev.opencode.mobile.ui.sessionchat.region.SessionChatProjectHeader
import dev.opencode.mobile.ui.sessionchat.region.SessionChatRepoStrip
import dev.opencode.mobile.ui.sessionchat.region.SessionChatReviewChanges
import dev.opencode.mobile.ui.sessionchat.region.SessionChatTimeline
import dev.opencode.mobile.ui.sessionchat.region.SessionChatTimelinePresentationHost
import dev.opencode.mobile.ui.sessionchat.region.SessionChatTimelineOffers
import dev.opencode.mobile.ui.sessionchat.region.SessionChatPendingPromptAction
import dev.opencode.mobile.ui.sessionchat.region.SessionChatTurnStatus
import dev.opencode.mobile.ui.sessionchat.region.SessionChatWorktreeMove
import dev.opencode.mobile.ui.sessionchat.region.SessionChatWorktreeMoveAvailability
import dev.opencode.mobile.ui.sessionchat.region.SessionChatWorktreeMoveRow
import dev.opencode.mobile.ui.sessionchat.region.SessionChatWorktreeMoveUiState
import dev.opencode.mobile.ui.sessionchat.region.SessionInfo
import dev.opencode.mobile.ui.worktree.NewWorktreeAvailability
import dev.opencode.mobile.ui.worktree.WorktreeCreateForm
import dev.opencode.mobile.ui.worktree.WorktreeBaseOptions
import dev.opencode.mobile.ui.worktree.WorktreeCreateOrigin
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

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
        private val homeProjectsSnapshot: HomeProjectsSnapshot = HomeProjectsSnapshot(),
        private val worktreeInventoryCache: WorktreeInventoryCache = WorktreeInventoryCache(),
        sideQuestionStore: SideQuestionStore? = null,
        private val serverFilePreviews: ServerFilePreviews = UnavailableServerFilePreviews,
        private val attachmentUploads: AttachmentUploadService = UnavailableAttachmentUploadService,
        agentShellRepository: AgentShellRepository = EmptyAgentShellRepository,
        private val newSessionDraftStore: NewSessionDraftStore = NewSessionDraftStore(),
        private val homeWorktreesFeed: HomeWorktreesFeed = HomeWorktreesFeed(),
        sessionGoalRepository: SessionGoalRepository = EmptySessionGoalRepository,
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
                worktreeMove.ioDispatcher = value
                projectHeader.ioDispatcher = value
                turnStatus.ioDispatcher = value
                newSessionDraft.ioDispatcher = value
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
                derivesChangeNotices = { !activeConnectionManager.currentCapabilities().recordsTimelineChangeNotices },
                serverFilePreviews = serverFilePreviews,
                nowMs = { nowMs() },
                presentationHost =
                    object : SessionChatTimelinePresentationHost {
                        override val publishedUiState: SessionChatUiState get() = _uiState.value

                        override fun patchUiState(state: SessionChatUiState) {
                            _uiState.value = state
                        }

                        override val boundSessionId: String? get() = this@SessionChatViewModel.boundSessionId
                        override val boundWorktree: String? get() = this@SessionChatViewModel.boundWorktree
                    },
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
                attachmentUploads = attachmentUploads,
            )
        private val sessionInfo =
            SessionInfo(
                sessionRepository = sessionRepository,
                sessionStatusLiveStore = sessionStatusLiveStore,
                foregroundEventStream = foregroundEventStream,
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
                supportsSideQuestion = { activeConnectionManager.currentCapabilities().supportsSideQuestion },
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

        private val sideQuestions =
            SessionChatSideQuestions(
                store =
                    sideQuestionStore ?: SideQuestionStore(
                        request = { sessionId, question -> sessionRepository.askSideQuestion(sessionId, question) },
                        scope = viewModelScope,
                    ),
                supported = { activeConnectionManager.currentCapabilities().supportsSideQuestion },
                scope = viewModelScope,
            ).also { region ->
                region.canPresentAnswer = {
                    val state = _uiState.value
                    !screenSheetOpen && !state.blocksChipTimelineIntro() && state.presentedActivityCard == null
                }
            }

        /** The open session's loaded messages, for servers that read shells from `bash` tool parts (v1). */
        private val shellMessages = MutableStateFlow<List<Message>>(emptyList())

        private val shells =
            SessionChatShells(
                repository = agentShellRepository,
                scope = viewModelScope,
                nowMs = { nowMs() },
            )

        /** The session's `/goal` (#1007): pill, sheet and status row. */
        private val goal =
            SessionChatGoal(
                repository = sessionGoalRepository,
                connectionState = foregroundEventStream.connectionState,
                scope = viewModelScope,
            )

        /** Composer dictation card; Insert splices through [updateComposerText]. */
        private val dictation =
            SessionChatDictation(
                scope = viewModelScope,
                nowMs = { nowMs() },
                onChange = { publishUiState() },
                onCommit = { transcript -> spliceDictationTranscript(transcript) },
            )

        /** The one-time Chip timeline intro (OPE-270) and Timeline Style picker (#805). */
        private val timelineOffers =
            SessionChatTimelineOffers(
                scope = viewModelScope,
                nowMs = { nowMs() },
                loadedMessages = { timeline.loadedMessages() },
                isBlocked = { _uiState.value.blocksChipTimelineIntro() },
                isSessionBusy = { isSessionBusy() },
                onChange = { publishUiState() },
            )

        /** The header's project label, letter, accent and worktree-checkout flag. */
        private val projectHeader =
            SessionChatProjectHeader(
                worktreeInventoryCache = worktreeInventoryCache,
                connectionProfileStore = connectionProfileStore,
                projectRepository = projectRepository,
                worktreeRepository = worktreeRepository,
                currentProfileBaseUrl = { connectionSessionCoordinator.currentProfile()?.baseUrl },
                inventoryServerKey = { activeConnectionManager.current()?.baseUrl.orEmpty() },
                boundWorktree = { boundWorktree },
                scope = viewModelScope,
                onChange = { publishUiState() },
            )

        /** Working footer, pending-prompts strip, and background work rows. */
        private val turnStatus =
            SessionChatTurnStatus(
                sessionRepository = sessionRepository,
                scope = viewModelScope,
                nowMs = { nowMs() },
                applyOptimistic = { id, delivery, removed -> timeline.applyOptimisticPendingPrompt(id, delivery, removed) },
                reportError = { text -> commands.reportActionError(text) },
                onWake = { publishUiState() },
            )

        /** New session draft (#1004): Session Chat before its first send. */
        private val newSessionDraft =
            SessionChatNewSessionDraft(
                owner = this,
                newSessionDraftStore = newSessionDraftStore,
                worktreeRepository = worktreeRepository,
                sessionRepository = sessionRepository,
                newSessionModelSelectionResolver = newSessionModelSelectionResolver,
                homeProjectsSnapshot = homeProjectsSnapshot,
                homeWorktreesFeed = homeWorktreesFeed,
                currentProfile = { connectionSessionCoordinator.currentProfile() },
                protocolHint = { activeConnectionManager.protocolHint() },
                composerState = { composer.state.value.composer },
                scope = viewModelScope,
                nowMs = { nowMs() },
                onChange = { publishUiState() },
            )

        private val worktreeMove =
            SessionChatWorktreeMove(
                moveSession = { id, directory -> sessionRepository.moveSession(id, directory) },
                scope = viewModelScope,
                worktreeRepository = worktreeRepository,
                homeProjectsSnapshot = homeProjectsSnapshot,
                supportsSessionMove = { activeConnectionManager.currentCapabilities().supportsSessionMove },
                protocolHint = { activeConnectionManager.protocolHint() },
                boundSessionId = { boundSessionId },
                isSessionBusy = { isSessionBusy() },
            )

        private val _uiState =
            MutableStateFlow(SessionChatUiState.initial(sessionId = "", sessionTitle = ""))
        val uiState: StateFlow<SessionChatUiState> = _uiState.asStateFlow()

        private var boundSessionId: String? = null

        /** A sheet or dialog the screen owns is up; see [setScreenSheetOpen]. */
        private var screenSheetOpen = false
        private var boundSessionTitle: String? = null
        private var boundWorktree: String? = null

        // The worktree the route bound with. After `session.moved` [boundWorktree] differs, and
        // the route (which still names this one) must not rebind to the old directory.
        private var routeWorktree: String? = null

        // The directory the transcript (timeline cache and sync projection) is keyed by; a move
        // keeps it so the timeline does not reload.
        private var transcriptWorktree: String? = null
        private var lastSnackbarKey: String? = null
        private var suppressedSnackbarKey: String? = null
        private var snackbarSequence: Long = 0L
        private var turnDiffNotice: String? = null
        private var branchCopiedNotice: String? = null

        internal var dictationFinishDelayMs: Long
            get() = dictation.finishDelayMs
            set(value) {
                dictation.finishDelayMs = value
            }

        // Title / custom-answer fields change per keystroke; report the first edit per
        // dialog appearance so analytics measures engagement instead of typing volume.
        private var renameDraftEditReported = false
        private var forkDraftEditReported = false
        private var questionCustomEditReportedRequestId: String? = null

        private var pendingSend: PendingSend? = null
        private var armedSendAcceptance: ArmedSendAcceptance? = null

        init {
            timeline.ioDispatcher = ioDispatcher
            composer.ioDispatcher = ioDispatcher
            viewModelScope.launch {
                foregroundEventStream.events.collect { event ->
                    event.sessionRevertUpdate()?.let { update ->
                        commands.applyRevertUpdate(update)
                        return@collect
                    }
                    event.sessionMoved()?.let { moved ->
                        worktreeMove.onSessionMoved(moved, boundSessionId, boundWorktree)?.let(::rebindMovedSession)
                        return@collect
                    }
                    if (!event.isWorktreeUpdated) return@collect
                    val projectId = event.namedProjectId() ?: return@collect
                    projectHeader.onWorktreeUpdated(projectId)
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
                        timelineOffers.updateTurnActive(isTurnActive)
                    }
                    commands.updateVisibility(
                        messages = timelineState.messages,
                        hasSelectedComposerModel = composerState.composer.selectedModel != null,
                    )
                    commands.observeLoadedMessages(
                        messages = timeline.loadedMessages(),
                        isSessionBusy = infoState.sessionStatusLabel != null,
                    )
                    timelineOffers.observeTimelineLoad(timelineState.timeline)
                    shellMessages.value = timeline.loadedMessages()
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
                sideQuestions.state.collect { publishUiState() }
            }
            viewModelScope.launch {
                shells.state.collect { publishUiState() }
            }
            viewModelScope.launch {
                goal.state.collect { publishUiState() }
            }
            viewModelScope.launch {
                worktreeMove.changes.collect { publishUiState() }
            }
            viewModelScope.launch {
                // The draft's Worktree sheet shows running sessions per worktree.
                newSessionDraft.runningSessions.collect { if (newSessionDraft.isActive) publishUiState() }
            }
            viewModelScope.launch {
                composer.state.collect { newSessionDraft.syncFromComposer() }
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
                bindNewSessionDraft(sessionTitle, worktree)
                return
            }
            if (boundSessionId == sessionId && routeWorktree == worktree) {
                bindCurrent(sessionId, sessionTitle)
                return
            }
            // Identity is session + worktree. Title may diverge after rename / session.updated;
            // do not full-reset when navigation still carries a stale title.
            // Always re-assert Ready focus: nav can dispose/clear focus then re-enter the same
            // bound ViewModel without a full reset, and Ready must clear on open.
            sessionReadyStore.setFocusedSession(sessionId, worktree)
            sessionViewReporter.focus(sessionId)
            boundSessionId = sessionId
            // Clear so bindTitleIfChanged always rebinds sessionInfo for a new identity,
            // even when the previous session shared the same title.
            boundSessionTitle = null
            boundWorktree = worktree
            routeWorktree = worktree
            transcriptWorktree = worktree
            lastSnackbarKey = null
            suppressedSnackbarKey = null
            snackbarSequence = 0L
            turnDiffNotice = null
            branchCopiedNotice = null
            timeline.resetPresentation()
            timelineOffers.reset()
            turnStatus.reset()
            worktreeMove.reset()
            logI("bind session=$sessionId worktree=$worktree title=$sessionTitle")
            // A new session starts from no messages, so no shell carries over from the last one.
            shellMessages.value = emptyList()
            projectHeader.seed(worktree)
            val projectIdentity = projectHeader.identity
            _uiState.value =
                SessionChatUiState.initial(sessionId = sessionId, sessionTitle = sessionTitle).copy(
                    projectLabel = projectIdentity.label,
                    projectLetter = projectIdentity.letter,
                    projectLetterAccent = projectIdentity.accent,
                    worktreeCheckout = projectIdentity.isWorktreeCheckout,
                )
            projectHeader.refine(worktree)
            timeline.bind(sessionId, worktree)
            composer.bind(sessionId, worktree)
            bindTitleIfChanged(sessionId, sessionTitle, worktree)
            reviewChanges.bind(sessionId, worktree)
            repoStrip.bind(worktree, startExpanded = false)
            commands.bind(sessionId, worktree)
            inbox.bind(sessionId, worktree)
            sideQuestions.bind(sessionId)
            bindShells(sessionId, worktree)
            goal.bind(sessionId, worktree)
            syncCoordinator.markFocused(ProjectionKey.Transcript(worktree, sessionId))
            viewModelScope.launch {
                composer.loadCatalog()
            }
        }

        /**
         * The route binds the session this ViewModel already shows. After a move the route still
         * names the old worktree, so everything uses the current one.
         */
        private fun bindCurrent(
            sessionId: String,
            sessionTitle: String,
        ) {
            val worktree = boundWorktree ?: return
            sessionReadyStore.setFocusedSession(sessionId, worktree)
            sessionViewReporter.focus(sessionId)
            bindTitleIfChanged(sessionId, sessionTitle, worktree)
            // Peers may have changed while the screen was away; re-resolve smart label.
            projectHeader.refine(worktree)
            timeline.restartLiveObservation()
            sideQuestions.bind(sessionId)
            // Goal changes while the screen was away were missed.
            goal.refresh()
            syncCoordinator.markFocused(ProjectionKey.Transcript(transcriptWorktree ?: worktree, sessionId))
        }

        /**
         * v2 `session.moved` for the open session: Session Chat follows it to [directory] in place.
         * The transcript stays (v2 scopes messages by session only, so its cache key keeps the old
         * directory), which keeps the timeline and scroll position; the header, composer, inbox,
         * repo strip and commands rebind to the new directory.
         */
        private fun rebindMovedSession(directory: String) {
            val sessionId = boundSessionId?.takeIf { hasCreatedSession() } ?: return
            val previous = boundWorktree ?: return
            logI("session moved session=$sessionId from=$previous to=$directory")
            boundWorktree = directory
            sessionReadyStore.setFocusedSession(sessionId, directory)
            projectHeader.seed(directory)
            projectHeader.refine(directory)
            composer.moveDirectory(directory)
            sessionInfo.moveDirectory(directory)
            commands.moveDirectory(directory)
            inbox.moveDirectory(directory)
            bindShells(sessionId, directory)
            goal.moveDirectory(directory)
            repoStrip.moveTo(directory, worktreeMove.knownBranch(directory))
            publishUiState()
        }

        /**
         * New session (#1004): the profile's draft, or a fresh one at [worktree]. Nothing is created
         * on the server until the first send. A route that binds again (or after the first send
         * promoted this screen to the created session) changes nothing.
         */
        private fun bindNewSessionDraft(
            sessionTitle: String,
            worktree: String,
        ) {
            if (routeWorktree == worktree && boundSessionId != null) return
            val draft = newSessionDraft.claim(worktree)
            boundSessionTitle = sessionTitle
            routeWorktree = worktree
            lastSnackbarKey = null
            suppressedSnackbarKey = null
            snackbarSequence = 0L
            turnDiffNotice = null
            branchCopiedNotice = null
            timeline.resetPresentation()
            timelineOffers.reset()
            turnStatus.reset()
            worktreeMove.reset()
            newSessionDraft.resetForBind()
            shellMessages.value = emptyList()
            logI("bind new session draft directory=${draft.directory}")
            _uiState.value =
                SessionChatUiState.initial(
                    sessionId = OptimisticSessionCreate.PLACEHOLDER_SESSION_ID,
                    sessionTitle = sessionTitle,
                    timeline = SessionChatTimelineState.Draft,
                )
            newSessionDraft.adopt(draft)
            bindDraftDirectory(draft, force = true)
            publishUiState()
        }

        /** Binds the regions to [draft]'s checkout (models, commands and files come from there). */
        private fun bindDraftDirectory(
            draft: NewSessionDraft,
            force: Boolean = false,
        ) {
            val directory = draft.directory
            if (!force && boundWorktree == directory) return
            boundSessionId = OptimisticSessionCreate.PLACEHOLDER_SESSION_ID
            boundWorktree = directory
            transcriptWorktree = directory
            newSessionDraft.withSyncPaused {
                timeline.bindDraft(directory)
                composer.bindDraft(directory, draft.composer, draft.attachments, draft.modelSelection)
                sessionInfo.bindDraft(boundSessionTitle.orEmpty(), directory)
                reviewChanges.bind(OptimisticSessionCreate.PLACEHOLDER_SESSION_ID, directory)
                commands.bind(OptimisticSessionCreate.PLACEHOLDER_SESSION_ID, directory)
                shells.reset()
                goal.reset()
            }
            viewModelScope.launch { composer.loadCatalog() }
        }

        /**
         * The draft's first send created session [sessionId] in [directory]: this screen becomes
         * that session, as if it had been opened from Home, and the draft is done.
         */
        private fun promoteNewSessionDraft(
            sessionId: String,
            sessionTitle: String,
            directory: String,
        ) {
            newSessionDraft.release()
            boundSessionId = sessionId
            boundSessionTitle = sessionTitle
            boundWorktree = directory
            transcriptWorktree = directory
            turnStatus.resetFooter()
            sessionReadyStore.setFocusedSession(sessionId, directory)
            sessionViewReporter.focus(sessionId)
            projectHeader.seed(directory)
            projectHeader.refine(directory)
            timeline.bind(sessionId, directory)
            composer.adoptCreatedSession(sessionId, directory)
            sessionInfo.bind(sessionId, sessionTitle, directory)
            reviewChanges.bind(sessionId, directory)
            repoStrip.bind(directory, startExpanded = false)
            commands.bind(sessionId, directory)
            inbox.bind(sessionId, directory)
            sideQuestions.bind(sessionId)
            bindShells(sessionId, directory)
            goal.bind(sessionId, directory)
            syncCoordinator.markFocused(ProjectionKey.Transcript(directory, sessionId))
            publishUiState()
        }

        private fun bindShells(
            sessionId: String,
            directory: String,
        ) {
            shells.bind(sessionId, directory, shellMessages)
        }

        // Shells (#907): the Shells section of the background work panel and the output sheet.

        /** A row in the Shells section: opens its output sheet. */
        fun openShellOutput(id: String) {
            logAction(ProductAnalytics.Action.CHAT_OPEN_SHELL_OUTPUT, screenId = ProductAnalytics.Screen.SESSION_SUBAGENTS)
            shells.open(id)
        }

        /**
         * The background work panel shows (or stops showing) its Done tab (#966). While it
         * shows, every finished row counts as seen and the tab's dot clears.
         */
        fun setBackgroundWorkDoneVisible(visible: Boolean) {
            if (turnStatus.setDoneVisible(visible)) publishUiState()
        }

        // Goal (#1007): the goal pill, sheet and status row.

        fun openGoalSheet() {
            goal.openSheet()
        }

        fun closeGoalSheet() {
            goal.closeSheet()
        }

        fun toggleGoalObjective() {
            goal.toggleObjective()
        }

        /** Pause, Resume or Clear: sends the goal command; progress shows until the goal changes. */
        fun sendGoalAction(action: SessionGoalAction) {
            goal.send(action)
        }

        /** New goal…: the composer starts a `/goal ` command, keeping any draft as the objective. */
        fun startNewGoal() {
            goal.closeSheet()
            val draft = composer.state.value.composer.text
            val command = "/${SessionGoalLimits.COMMAND} "
            val text = if (SessionGoalDraft.objective(draft) != null) draft else command + draft.trimStart()
            updateComposerText(text, cursor = text.length)
        }

        fun closeShellOutput() {
            logAction(ProductAnalytics.Action.SHELL_OUTPUT_DISMISS, screenId = ProductAnalytics.Screen.SESSION_SHELL_OUTPUT)
            shells.close()
        }

        /** The output sheet is visible and the app is in the foreground; output is read only then. */
        fun setShellOutputObserved(observed: Boolean) {
            shells.setOutputObserved(observed)
        }

        /** Stop shell in the output sheet; only offered where the server can stop it. */
        fun stopShell(id: String) {
            logAction(ProductAnalytics.Action.SHELL_STOP, screenId = ProductAnalytics.Screen.SESSION_SHELL_OUTPUT)
            shells.stop(id)
        }

        /** The output sheet copied its output to the clipboard. */
        fun copyShellOutput() {
            logAction(ProductAnalytics.Action.SHELL_OUTPUT_COPY, screenId = ProductAnalytics.Screen.SESSION_SHELL_OUTPUT)
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
            boundSessionId?.takeIf { hasCreatedSession() }?.let {
                sessionReadyStore.releaseFocusedSession(it)
                sessionViewReporter.release(it)
            }
        }

        fun loadMessages() {
            timeline.loadMessages()
        }

        /** Error-state Retry: refetches the timeline. */
        fun retryFailedTimeline() {
            val sessionId = boundSessionId
            if (sessionId == null || OptimisticSessionCreate.isPlaceholder(sessionId)) return
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
            timeline.expandStepParagraph(expansionId, chipCount)
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

        /** The archived banner's Unarchive. */
        fun unarchiveSession() {
            if (!hasCreatedSession()) return
            sessionInfo.unarchiveSession()
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
            // Typing `/btw ` switches to side-question mode and keeps only the question. Also strips
            // the command when it is already in that mode: a keystroke typed before the field took
            // the stripped draft brings it back.
            val question = SideQuestion.draftAfterCommand(text)
            if (question != null && supportsSideQuestion()) {
                sideQuestions.enterMode()
                composer.restoreComposerText(question)
                return
            }
            composer.updateComposerText(text, cursor)
            sideQuestions.updateDraft(composer.state.value.composer.text)
        }

        fun exitShellMode() {
            composer.exitShellMode()
        }

        fun exitSideQuestionMode() {
            sideQuestions.exitMode()
        }

        /** The "answer ready" chip. */
        fun openSideQuestionAnswer() {
            logAction(ProductAnalytics.Action.SIDE_QUESTION_OPEN_ANSWER)
            sideQuestions.openReadyAnswer()
        }

        fun closeSideQuestionAnswer() {
            logAction(ProductAnalytics.Action.SIDE_QUESTION_CLOSE)
            sideQuestions.closeAnswer()
        }

        /** The answer sheet copied its text to the clipboard. */
        fun sideQuestionAnswerCopied() {
            logAction(ProductAnalytics.Action.SIDE_QUESTION_COPY)
            sideQuestions.answerCopied()
        }

        /** The failed chip, or Retry on its snackbar when [id] is set. */
        fun retrySideQuestions(id: String? = null) {
            logAction(ProductAnalytics.Action.SIDE_QUESTION_RETRY)
            if (id != null) sideQuestions.retry(id) else sideQuestions.retryFailed()
        }

        fun dismissFailedSideQuestions() {
            logAction(ProductAnalytics.Action.SIDE_QUESTION_DISMISS_FAILED)
            sideQuestions.dismissFailed()
        }

        /** A draft has no session to ask about. */
        private fun supportsSideQuestion(): Boolean =
            !newSessionDraft.isActive && activeConnectionManager.currentCapabilities().supportsSideQuestion

        /** Session Chat left the screen: the open answer was read; requests keep running. */
        fun leaveSideQuestions() {
            keepSideQuestionDraft()
            sideQuestions.stop()
        }

        /** Sheets and dialogs Session Chat owns itself are up; a new answer waits behind them. */
        fun setScreenSheetOpen(open: Boolean) {
            screenSheetOpen = open
        }

        /**
         * The mode is not kept, so a side-question draft gets its `/btw` back before leaving; it
         * still asks a side question later instead of going out as a prompt.
         */
        private fun keepSideQuestionDraft() {
            if (!sideQuestions.state.value.ui.composerMode) return
            val draft = composer.state.value.composer.text
            if (draft.isBlank() || SideQuestion.questionInDraft(draft) != null) return
            composer.restoreComposerText("${SideQuestion.COMMAND} $draft")
        }

        /**
         * Sends the draft as a `/btw` side question: one `generate` request, nothing added to the
         * timeline, and the draft cleared. A `/btw` this server cannot answer stays in the draft.
         */
        private fun askSideQuestion() {
            val side = sideQuestions.state.value.ui
            val draft = composer.state.value.composer.text
            // The Btw tile keeps the draft as it is, so it may still start with `/btw `.
            val question =
                if (side.composerMode) SideQuestion.draftAfterCommand(draft) ?: draft else SideQuestion.questionInDraft(draft).orEmpty()
            if (!supportsSideQuestion() || question.isBlank()) return
            logAction(ProductAnalytics.Action.SIDE_QUESTION_ASK)
            if (!sideQuestions.ask(question)) return
            composer.restoreComposerText("")
            sideQuestions.updateDraft("")
        }

        fun beginDictation(
            languageLabel: String,
            onDevice: Boolean,
        ) {
            dictation.begin(languageLabel, onDevice)
        }

        fun beginDictationUnavailable(
            phase: ComposerDictationPhase,
            message: String? = null,
            languageLabel: String = "",
        ) {
            dictation.beginUnavailable(phase, message, languageLabel)
        }

        /** Applies a model download update only while the card still shows the model. */
        fun dictationModelDownload(
            phase: ComposerDictationPhase,
            progress: Float? = null,
        ) {
            dictation.modelDownload(phase, progress)
        }

        fun dictationPartial(
            committed: String,
            volatile: String,
        ) {
            dictation.partial(committed, volatile)
        }

        fun dictationLevel(level: Float) {
            dictation.level(level)
        }

        /** Listening ended but the transcript stays on the card so Insert still works. */
        fun dictationStopped() {
            dictation.stopped()
        }

        fun dictationInsert() {
            dictation.insert()
        }

        fun dictationCancel() {
            dictation.cancel()
        }

        fun cancelDictationForModeChange() {
            dictation.cancelIfActive()
        }

        fun cancelDictationForBackground() {
            dictation.cancelIfActive()
        }

        fun cancelDictationForDismiss() {
            dictation.cancelIfActive()
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

        /** Whether new picks should be staged for streaming (no size cap, any type). */
        fun attachmentUploadAvailable(): Boolean = attachmentUploads.isAvailable()

        /** Bytes of an image that lives on the server, for a timeline preview; null on any failure. */
        suspend fun loadAttachmentPreview(serverPath: String): ByteArray? = timeline.loadAttachmentPreview(serverPath)

        fun addAttachments(attachments: List<Attachment>) {
            composer.addAttachments(attachments)
        }

        fun addPastedOrDroppedAttachments(
            result: AttachmentIngestionResult,
            skippedFolders: Int,
        ): Boolean = composer.addPastedOrDroppedAttachments(result, skippedFolders)

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
                ClientSlashAction.New -> commands.runNew(prepareNewSessionDraft())
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
                ClientSlashAction.Btw -> {
                    commands.dismissCommands()
                    sideQuestions.enterMode()
                }
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

        /** Session Chat ⋯ → "Move to worktree…". */
        fun openMoveToWorktree() {
            if (!moveToWorktreeUiState().availability.enabled) return
            logAction(
                actionId = ProductAnalytics.Action.CHAT_OVERFLOW_MOVE_TO_WORKTREE,
                screenId = ProductAnalytics.Screen.SESSION_OVERFLOW,
            )
            worktreeMove.openSheet()
        }

        fun dismissMoveToWorktree() {
            worktreeMove.dismissSheet()
        }

        /** Move sheet → "New worktree…": the form, made from the session's current checkout. */
        fun openMoveToNewWorktree() {
            worktreeMove.openNewWorktreeForm(sessionId = boundSessionId?.takeIf { hasCreatedSession() }, boundWorktree = boundWorktree)
        }

        fun updateNewWorktreeName(name: String) = worktreeMove.updateName(name)

        fun showNewWorktreeBasePicker() = worktreeMove.showBasePicker()

        fun hideNewWorktreeBasePicker() = worktreeMove.hideBasePicker()

        fun selectNewWorktreeBase(ref: String?) = worktreeMove.selectBase(ref)

        fun submitNewWorktree() = worktreeMove.submit()

        /** Form → Back: the Move sheet's list again. */
        fun closeNewWorktreeForm() = worktreeMove.closeForm()

        fun moveToWorktree(directory: String) {
            val sessionId = boundSessionId?.takeIf { hasCreatedSession() } ?: return
            worktreeMove.moveToDirectory(sessionId, directory, boundWorktree)
        }

        private fun moveToWorktreeUiState(): SessionChatWorktreeMoveUiState = worktreeMove.uiState(boundWorktree, hasCreatedSession())

        fun consumePendingTerminalOpen() {
            commands.consumePendingTerminalOpen()
        }

        fun consumePendingFilesOpen() {
            commands.consumePendingFilesOpen()
        }

        fun enableChipTimelineFromIntro() {
            if (timelineOffers.enableChipTimeline()) logChipTimelineIntro(ProductAnalytics.Action.CHIP_TIMELINE_INTRO_ENABLE)
            publishUiState()
        }

        /** Later button, swipe, scrim, or back. */
        fun postponeChipTimelineIntro() {
            if (timelineOffers.postponeChipTimelineIntro(nowMs())) logChipTimelineIntro(ProductAnalytics.Action.CHIP_TIMELINE_INTRO_LATER)
            publishUiState()
        }

        fun dismissChipTimelineIntro() {
            if (timelineOffers.dismissChipTimelineIntro()) logChipTimelineIntro(ProductAnalytics.Action.CHIP_TIMELINE_INTRO_NEVER)
            publishUiState()
        }

        /** Use button of the Timeline Style picker. */
        fun chooseTimelineStyleFromPicker(style: SessionChatTimelineStyle) {
            if (!timelineOffers.chooseTimelineStyle(style)) return
            logAction(
                ProductAnalytics.Action.TIMELINE_STYLE_PICKER_USE,
                screenId = ProductAnalytics.Screen.SESSION_TIMELINE_STYLE_PICKER,
                parameters = mapOf(ProductAnalytics.Param.MODE to style.wireValue),
            )
            publishUiState()
        }

        /** Timeline Style picker swipe, scrim, back, or close: keeps the recommended Activity Card. */
        fun dismissTimelineStylePicker() {
            if (!timelineOffers.dismissTimelineStylePicker()) return
            logAction(
                ProductAnalytics.Action.TIMELINE_STYLE_PICKER_DISMISS,
                screenId = ProductAnalytics.Screen.SESSION_TIMELINE_STYLE_PICKER,
            )
            publishUiState()
        }

        private fun logChipTimelineIntro(actionId: String) {
            logAction(actionId, screenId = ProductAnalytics.Screen.SESSION_CHIP_TIMELINE_INTRO)
        }

        /** The last turn moved folders, so its combined diff cannot be shown. Stay on this screen. */
        fun presentTurnDiffUnavailable() {
            turnDiffNotice = TurnDiffUnavailable.MESSAGE
            lastSnackbarKey = null
            suppressedSnackbarKey = null
            publishUiState()
        }

        /** The branch pill copied the branch name; confirm it with a snackbar. */
        fun branchNameCopied() {
            branchCopiedNotice = BRANCH_COPIED_MESSAGE
            // A repeat copy shows the snackbar again even if the last one was dismissed.
            lastSnackbarKey = null
            suppressedSnackbarKey = null
            publishUiState()
        }

        fun consumeSnackbar() {
            logAction(ProductAnalytics.Action.CHAT_DISMISS_SNACKBAR)
            suppressedSnackbarKey = lastSnackbarKey
            newSessionDraft.clearSendError()
            turnDiffNotice = null
            branchCopiedNotice = null
            timelineOffers.clearNotices()
            commands.clearActionFeedback()
            sideQuestions.clearNotice()
            goal.clearNotice()
            worktreeMove.clearMessages()
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
            runPendingPromptAction(ProductAnalytics.Action.CHAT_STEER_PENDING_PROMPT, SessionChatPendingPromptAction.Steer, id)
        }

        fun queuePendingPrompt(id: String) {
            runPendingPromptAction(ProductAnalytics.Action.CHAT_QUEUE_PENDING_PROMPT, SessionChatPendingPromptAction.Queue, id)
        }

        fun cancelPendingPrompt(id: String) {
            runPendingPromptAction(ProductAnalytics.Action.CHAT_CANCEL_PENDING_PROMPT, SessionChatPendingPromptAction.Cancel, id)
        }

        fun clearPendingPrompts() {
            _uiState.value.pendingPrompts
                .filter { !it.isSettling }
                .map { it.id }
                .forEach(::cancelPendingPrompt)
        }

        private fun runPendingPromptAction(
            actionId: String,
            action: SessionChatPendingPromptAction,
            inboxId: String,
        ) {
            if (!activeConnectionManager.currentCapabilities().supportsFollowUpDelivery) return
            logAction(actionId)
            turnStatus.updatePendingPrompt(action, inboxId, logLabel = actionId, sessionId = boundSessionId, worktree = boundWorktree)
        }

        fun sendPrompt() {
            if (newSessionDraft.isActive) {
                sendNewSessionDraft()
                return
            }
            sendToBoundSession()
        }

        /** Sends the composer to the bound session; false when nothing was sent. */
        private fun sendToBoundSession(): Boolean {
            if (!hasCreatedSession()) return false
            // A side question never becomes a prompt, including a typed `/btw` this server cannot answer.
            if (sideQuestions.state.value.ui.composerMode ||
                SideQuestion.questionInDraft(composer.state.value.composer.text) != null
            ) {
                if (sessionInfo.state.value.composerEnabled) askSideQuestion()
                return false
            }
            val composerState = composer.state.value.composer
            logAction(
                actionId = ProductAnalytics.Action.COMPOSER_SEND,
                parameters =
                    mapOf(
                        ProductAnalytics.Param.HAS_ATTACHMENTS to composerState.attachments.isNotEmpty().toString(),
                        ProductAnalytics.Param.MODE to composerState.selectedMode,
                    ),
            )
            if (!sessionInfo.state.value.composerEnabled) return false
            if (pendingSend != null || composer.state.value.isSending) return false
            if (inbox.state.value.permissionRequests
                    .isNotEmpty() ||
                inbox.state.value.questionRequests
                    .isNotEmpty()
            ) {
                return false
            }
            val sessionId = boundSessionId ?: return false
            val worktree = boundWorktree ?: return false
            val shouldQueue = !sessionInfo.state.value.canMutate
            val capabilities = activeConnectionManager.currentCapabilities()
            return composer.sendPrompt(
                sessionId = sessionId,
                worktree = worktree,
                isSessionBusy = sessionInfo.state.value.sessionStatusLabel != null,
                supportsFollowUp = capabilities.supportsFollowUpDelivery,
                commandKeepsClientMessageId = capabilities.commandKeepsClientMessageId,
                holdUntil = {
                    if (!shouldQueue) {
                        true
                    } else {
                        awaitLive(PENDING_SEND_TIMEOUT_MS)
                    }
                },
                onOptimisticRows = { rows ->
                    // A draft's first prompt shows: the draft's sending state hands over to the send's.
                    newSessionDraft.endCreating()
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
                    newSessionDraft.endCreating()
                    armedSendAcceptance = null
                    pendingSend = null
                    // Server and transport errors stay the timeline error; app text goes to the snackbar.
                    timeline.failOptimisticSend(optimisticId, (error as? UiText.Verbatim)?.value)
                    if (error !is UiText.Verbatim) commands.reportActionError(error)
                    publishUiState()
                },
                serverAlreadyAccepted = ::serverAlreadyAccepted,
                beforePrompt = { commands.commitPendingRevert(sessionId, worktree) },
            )
        }

        // New session draft (#1004): the Run session in card, its sheets, and the first send.

        /** Card → Project row. */
        fun openNewSessionProjectSheet() {
            if (newSessionDraft.openProjectSheet()) publishUiState()
        }

        /** Card → Worktree row; a project that is not a Git repository has none to pick. */
        fun openNewSessionWorktreeSheet() {
            if (newSessionDraft.openWorktreeSheet()) publishUiState()
        }

        /** Card → Starts from row (a new worktree on v2). */
        fun openNewSessionBaseBranchSheet() {
            if (newSessionDraft.openBaseBranchSheet()) publishUiState()
        }

        fun dismissNewSessionSheet() {
            if (newSessionDraft.dismissSheet()) publishUiState()
        }

        fun updateNewSessionProjectQuery(query: String) {
            newSessionDraft.updateProjectQuery(query)
            publishUiState()
        }

        /** Project sheet → a project: it runs on that project's main checkout. */
        fun selectNewSessionProject(projectKey: String) {
            if (newSessionDraft.selectProject(projectKey) { moved -> bindDraftDirectory(moved) }) publishUiState()
        }

        /** Worktree sheet → Local, New worktree, or an existing worktree. */
        fun selectNewSessionPlace(place: NewSessionPlace) {
            if (newSessionDraft.selectPlace(place) { moved -> bindDraftDirectory(moved) }) publishUiState()
        }

        /** Starts from sheet → a branch; null (or the main checkout's own branch) is the default. */
        fun selectNewSessionBaseBranch(ref: String?) {
            if (newSessionDraft.selectBaseBranch(ref)) publishUiState()
        }

        /** Project sheet → Add project…: the project Home's Add project flow adds becomes the draft's. */
        fun requestNewSessionAddProject() {
            if (newSessionDraft.requestAddProject()) publishUiState()
        }

        /** ⋯ → Discard draft: the draft is gone; the screen goes back. */
        fun discardNewSessionDraft() {
            if (newSessionDraft.discard()) publishUiState()
        }

        /**
         * Ctrl+N or `/new` from a session: the profile's draft (it wins), else a fresh one in this
         * session's place. Returns the draft's checkout, which the route names.
         */
        fun prepareNewSessionDraft(): String = newSessionDraft.prepare(boundWorktree.orEmpty())

        /**
         * The draft's first send: creates the new worktree (if chosen), then the session in its
         * checkout, then sends the prompt through the normal path. A failure keeps the draft and
         * shows the error with Retry; what was already created is reused on the next try.
         */
        private fun sendNewSessionDraft() {
            if (!newSessionDraft.isActive || newSessionDraft.isCreating) return
            val composerState = composer.state.value.composer
            if (!composerState.canSend || composerState.selectedModel == null || !composerState.isModelSelectionReady) return
            if (!sessionInfo.state.value.composerEnabled) return
            newSessionDraft.syncFromComposer()
            newSessionDraft.beginCreate()
            publishUiState()
            val job =
                viewModelScope.launch {
                    val created =
                        try {
                            newSessionDraft.create()
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
                            logI("new session draft create failed error=${error.message}")
                            newSessionDraft.failCreate(error)
                            publishUiState()
                            return@launch
                        }
                    logI("new session draft created session=${created.id}")
                    val shellCommand = ComposerShellCommand.isShellMode(composer.state.value.composer.text)
                    promoteNewSessionDraft(created.id, created.title, created.directory)
                    // The prompt's own sending state takes over once its row shows.
                    if (!sendToBoundSession() || shellCommand) {
                        newSessionDraft.endCreating()
                        publishUiState()
                    }
                }
            newSessionDraft.trackSubmit(job)
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

        /**
         * Opens the panel on the request of the subagent the needs-input pill names.
         * Returns false when no subagent request waits, so the caller opens the subagents panel.
         */
        fun openWaitingSubagentRequest(): Boolean {
            val inboxState = inbox.state.value
            val target =
                SessionChatBackgroundWork.needsInputTarget(
                    subagents = _uiState.value.subagents,
                    sessions = inboxState.permissionSessions + inboxState.questionSessions,
                    permissions = inboxState.permissionRequests,
                    questions = inboxState.questionRequests,
                    shownPermissionId = inboxState.visiblePermissionRequest?.id,
                ) ?: return false
            logAction(
                when (target) {
                    is SessionChatInboxTarget.Permission -> ProductAnalytics.Action.PERMISSION_SHOW
                    is SessionChatInboxTarget.Question -> ProductAnalytics.Action.QUESTION_SHOW
                },
            )
            inbox.focus(target)
            return true
        }

        fun pagePermission(delta: Int) {
            logAction(ProductAnalytics.Action.INBOX_REQUEST_PAGE)
            inbox.pagePermission(delta)
        }

        fun pageQuestion(delta: Int) {
            logAction(ProductAnalytics.Action.INBOX_REQUEST_PAGE)
            inbox.pageQuestion(delta)
        }

        /** Collapse the question panel so the composer is reachable; the question stays pending. */
        fun hideQuestionDock(requestId: String) {
            logAction(ProductAnalytics.Action.QUESTION_HIDE)
            inbox.hideQuestionDock(requestId)
        }

        fun showQuestionDock() {
            logAction(ProductAnalytics.Action.QUESTION_SHOW)
            inbox.showQuestionDock()
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

        /** "Deny with a note…": swaps the permission prompt for the note step. */
        fun openPermissionNote(
            requestId: String,
            sessionId: String,
        ) {
            logAction(actionId = ProductAnalytics.Action.PERMISSION_NOTE_OPEN)
            inbox.openPermissionNote(requestId, sessionId)
        }

        fun updatePermissionNote(
            requestId: String,
            text: String,
        ) {
            inbox.updatePermissionNote(requestId, text)
        }

        /** Leaves the note step for the permission prompt without answering. */
        fun cancelPermissionNote(requestId: String) {
            logAction(actionId = ProductAnalytics.Action.PERMISSION_NOTE_CANCEL)
            inbox.cancelPermissionNote(requestId)
        }

        /** Denies the permission request with the trimmed note; ignored while the note is blank. */
        fun sendPermissionNote(
            requestId: String,
            sessionId: String,
        ) {
            val note = inbox.state.value.visiblePermissionNote
            if (note == null || note.requestId != requestId || note.trimmedText.isEmpty()) return
            logAction(
                actionId = ProductAnalytics.Action.PERMISSION_DENY,
                parameters = mapOf(ProductAnalytics.Param.PERMISSION_DECISION to "deny_with_note"),
            )
            inbox.sendPermissionNote(requestId, sessionId)
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

        private fun publishUiState(
            timelineState: SessionChatTimelineRegionState,
            composerState: SessionChatComposerRegionState,
            infoState: SessionInfoRegionState,
            reviewChangesState: SessionChatReviewChangesRegionState,
            commandsState: SessionChatCommandsRegionState,
            inboxState: SessionChatInboxRegionState,
        ) {
            val merged =
                mergeUiState(
                    timelineState,
                    composerState,
                    infoState,
                    reviewChangesState,
                    commandsState,
                    inboxState,
                )
            timeline.syncStepTiming(merged.messages, nowMs())
            _uiState.value =
                merged.copy(
                    activityCardFooters = timeline.activityCardFooters,
                    debouncedStepIds = timeline.debouncedStepIds,
                    imageViewer = timeline.reconcileImageViewer(merged.messages),
                )
            timeline.syncActivityChildren(_uiState.value.messages)
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
                timeline.presentMessages(
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
            val draftSendError = newSessionDraft.state.value.sendError
            val transientError =
                if (timelineState.timeline == SessionChatTimelineState.Error) {
                    null
                } else {
                    // Other regions still report plain strings; they are shown as is.
                    when {
                        draftSendError != null -> draftSendError
                        infoState.unarchiveFailed -> UiText.resource(R.string.archived_sessions_unarchive_failed)
                        infoState.lifecycleErrorMessage != null -> UiText.verbatim(infoState.lifecycleErrorMessage)
                        worktreeMove.state.value.errorMessage != null -> TimelineText.verbatim(worktreeMove.state.value.errorMessage)
                        composerState.errorMessage != null -> UiText.verbatim(composerState.errorMessage)
                        composerState.abortErrorMessage != null -> UiText.verbatim(composerState.abortErrorMessage)
                        commandsState.actionErrorMessage != null -> commandsState.actionErrorMessage
                        goal.state.value.notice != null -> goal.state.value.notice
                        turnDiffNotice != null -> TimelineText.verbatim(turnDiffNotice)
                        infoState.errorMessage != null -> UiText.verbatim(infoState.errorMessage)
                        inboxSnapshot.permissionRequests.isEmpty() &&
                            inboxSnapshot.permissionTopologyError != null -> TimelineText.verbatim(inboxSnapshot.permissionTopologyError)
                        inboxSnapshot.questionRequests.isEmpty() &&
                            inboxSnapshot.questionTopologyError != null -> TimelineText.verbatim(inboxSnapshot.questionTopologyError)
                        timelineState.errorMessage != null -> UiText.verbatim(timelineState.errorMessage)
                        else -> null
                    }
                }
            val timelineOffersState = timelineOffers.state.value
            val snackbar =
                resolveSnackbar(
                    transientError,
                    retryNewSessionDraft = transientError != null && transientError == draftSendError,
                    sideQuestions.state.value.notice,
                    TimelineText.verbatim(composerState.followUpNotice ?: worktreeMove.state.value.notice)
                        ?: commandsState.actionSuccessMessage
                        ?: TimelineText.verbatim(branchCopiedNotice ?: timelineOffersState.chipTimelineIntroNotice),
                )
            val sessionStatusLabel =
                if (isCompacting) {
                    SessionCompactChrome.STATUS_LABEL
                } else {
                    infoState.sessionStatusLabel
                }
            val turn =
                turnStatus.resolve(
                    sessionStatusLabel = sessionStatusLabel,
                    isCompacting = isCompacting,
                    sendProgressPercent = composerState.sendProgressPercent,
                    isBound = boundSessionId != null,
                    messages = messages,
                    loadedMessages = { timeline.loadedMessages() },
                    pendingOptimistic = { timeline.pendingOptimisticItems() },
                    childSessions = { timeline.childSessions },
                    inboxState = inboxSnapshot,
                    infoState = infoState,
                    previousSubagents = _uiState.value.subagents,
                )
            val syncingIndicatorLabel =
                if (timelineState.isSyncingMessages) {
                    SessionMessageSyncChrome.statusLabel
                } else {
                    null
                }
            val subagents = turn.subagents
            val shellsState = shells.state.value
            val projectIdentity = projectHeader.identity
            return SessionChatUiState(
                sessionId = infoState.sessionId,
                sessionTitle = infoState.sessionTitle,
                timeline = timelineState.timeline,
                messages = messages,
                pendingPrompts = turn.pendingPrompts,
                timelineFrame = timelineFrame,
                composer =
                    composerState.composer.copy(
                        supportsFollowUpDelivery =
                            activeConnectionManager.currentCapabilities().supportsFollowUpDelivery,
                        attachmentUploadAvailable = attachmentUploads.isAvailable(),
                    ),
                isSending = composerState.isSending || newSessionDraft.isCreating,
                isRefreshing = timelineState.isRefreshing,
                hasOlder = timelineState.hasOlder,
                isLoadingOlder = timelineState.isLoadingOlder,
                isAborting = composerState.isAborting,
                sessionStatusLabel = sessionStatusLabel,
                workingIndicatorLabel = turn.workingIndicatorLabel,
                retryCard = infoState.retryCard,
                isRetrying = infoState.isRetrying,
                workingIndicatorCollapsed = turn.workingIndicatorCollapsed,
                workingIndicatorFading = turn.workingIndicatorFading,
                syncingIndicatorLabel = syncingIndicatorLabel,
                sessionTodos = inboxSnapshot.sessionTodos,
                sessionTodosVisible = SessionTodoList.isVisible(inboxSnapshot.sessionTodos),
                sessionTodosFinished = SessionTodoList.isFinished(inboxSnapshot.sessionTodos),
                sessionTodoActivePreview = SessionTodoList.activePreview(inboxSnapshot.sessionTodos),
                sessionTodoCompletedCount = SessionTodoList.completedCount(inboxSnapshot.sessionTodos),
                sessionTodoTotalCount = inboxSnapshot.sessionTodos.size,
                subagents = subagents,
                shells = shellsState.shells,
                shellOutput = shellsState.output,
                backgroundWorkPill = SessionChatBackgroundWork.pill(subagents, shellsState.shells),
                backgroundWorkDoneUnseen = turn.backgroundWorkDoneUnseen,
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
                permissionRequest = inboxSnapshot.visiblePermissionRequest,
                permissionQueuePosition = inboxSnapshot.permissionPageIndex + 1,
                permissionQueueCount = inboxSnapshot.permissionRequests.size,
                permissionAttribution = inboxSnapshot.visiblePermissionAttribution,
                permissionDockHidden = inboxSnapshot.isPermissionDockHidden,
                permissionSubmitting = inboxSnapshot.permissionSubmittingId != null,
                permissionErrorMessage = inboxSnapshot.permissionError ?: inboxSnapshot.permissionTopologyError,
                permissionNote = inboxSnapshot.visiblePermissionNote,
                permissionOtherPendingInSession = inboxSnapshot.otherPendingPermissionsInVisibleSession,
                questionRequest = visibleQuestion,
                questionQueuePosition = inboxSnapshot.questionPageIndex + 1,
                questionQueueCount = inboxSnapshot.questionRequests.size,
                questionAttribution = inboxSnapshot.visibleQuestionAttribution,
                questionDockHidden = inboxSnapshot.isQuestionDockHidden,
                inboxPanelPulse = inboxSnapshot.panelPulse,
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
                archivedAtEpochMs = infoState.archivedAtEpochMs,
                supportsArchivedSessions = activeConnectionManager.currentCapabilities().supportsArchivedSessions,
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
                moveToWorktree = moveToWorktreeUiState(),
                expandedStepParagraphs = timeline.expandedStepParagraphs,
                unfoldedStepRails = timeline.unfoldedStepRails,
                presentedActivityCard = timeline.presentedActivityCard,
                activityCardDetailStepId = timeline.activityCardDetailStepId,
                activityCardChildren = timeline.activityCardChildren,
                activityCardFooters = timeline.activityCardFooters,
                debouncedStepIds = timeline.debouncedStepIds,
                chipTimelineIntroVisible = timelineOffersState.chipTimelineIntroVisible,
                timelineStylePickerVisible = timelineOffersState.timelineStylePickerVisible,
                timelineStylePickerOptions = timelineOffersState.timelineStylePickerOptions,
                pendingTerminalOpen = commandsState.pendingTerminalOpen,
                pendingFilesOpen = commandsState.pendingFilesOpen,
                hasPendingRevert = commandsState.hasPendingRevert,
                projectLabel = projectIdentity.label,
                projectLetter = projectIdentity.letter,
                projectLetterAccent = projectIdentity.accent,
                worktreeCheckout = projectIdentity.isWorktreeCheckout,
                sessionBranchPill =
                    repoStrip.state.value.toSessionBranchPill(isWorktree = projectIdentity.isWorktreeCheckout),
                goal =
                    SessionChatGoalUiState.resolve(
                        region = goal.state.value,
                        isChildSession = infoState.parentId != null,
                        isSessionBusy = sessionStatusLabel != null,
                        hasPermission = inboxSnapshot.permissionRequests.isNotEmpty(),
                        hasQuestion = inboxSnapshot.questionRequests.isNotEmpty(),
                        runningShellCommand = shellsState.shells.firstOrNull { it.isRunning }?.command,
                        hasRunningSubagent = subagents.any { it.status == SessionChatSubagentStatus.Running },
                        canMutate = infoState.canMutate,
                    ),
                goalCommandAvailable = composerState.serverCommands.any { it.name == SessionGoalLimits.COMMAND },
                worktree = boundWorktree.orEmpty(),
                dictation = dictation.state.value.dictation,
                sideQuestions =
                    sideQuestions.state.value.ui.let { side ->
                        val supported = supportsSideQuestion()
                        side.copy(supported = supported, composerMode = side.composerMode && supported)
                    },
                newSessionDraft = newSessionDraft.uiState(),
            )
        }

        /** Opens the full-screen viewer on image [index] of timeline gallery [galleryId] (#898). */
        fun openImageViewer(
            galleryId: String,
            index: Int,
        ) {
            timeline.openImageViewer(galleryId, index)
        }

        fun showImageViewerPage(index: Int) {
            timeline.showImageViewerPage(index)
        }

        fun dismissImageViewer() {
            timeline.dismissImageViewer()
        }

        /** Shows every step of a folded step rail, or folds it again. */
        fun toggleStepRailFold(railId: String) {
            timeline.toggleStepRailFold(railId)
        }

        /** Opens the live activity sheet for card [blockId] (#779). */
        fun openActivityCard(blockId: String) {
            timeline.openActivityCard(blockId)
        }

        fun dismissActivityCard() {
            timeline.dismissActivityCard()
        }

        /** Shows step [stepId]'s detail inside the open activity sheet, in place of its list. */
        fun openActivityCardStep(stepId: String) {
            timeline.openActivityCardStep(stepId)
        }

        /** Returns the open activity sheet from a step's detail to its list. */
        fun closeActivityCardStep() {
            timeline.closeActivityCardStep()
        }

        private fun resolveSnackbar(
            transientError: UiText?,
            retryNewSessionDraft: Boolean,
            sideQuestionNotice: SessionChatSideQuestionNotice?,
            successNotice: UiText?,
        ): SessionChatSnackbar? {
            // A `/btw` notice outranks other success notices and yields to errors; its id keeps a
            // repeated failure of the same question from deduping into the earlier snackbar.
            if (transientError == null && sideQuestionNotice != null) {
                val key = "sideQuestion:${sideQuestionNotice.id}"
                if (key == suppressedSnackbarKey) return null
                if (key != lastSnackbarKey) {
                    snackbarSequence += 1
                    lastSnackbarKey = key
                }
                return SessionChatSnackbar(
                    message = UiText.verbatim(sideQuestionNotice.message),
                    tone = if (sideQuestionNotice.isError) SessionChatSnackbarTone.Error else SessionChatSnackbarTone.Default,
                    id = snackbarSequence,
                    action = sideQuestionNotice.retryId?.let { SessionChatSnackbarAction.RetrySideQuestion(it) },
                )
            }
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
                action = SessionChatSnackbarAction.RetryNewSessionDraft.takeIf { retryNewSessionDraft },
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
            newSessionDraft.leave()
            dictation.discardInsert()
            composer.flushDraft()
            inbox.stop()
            turnStatus.resetFooter()
            clearSessionReadyFocus()
            syncCoordinator.markFocused(null)
            super.onCleared()
        }

        private fun spliceDictationTranscript(transcript: String) {
            val composerState = composer.state.value.composer
            val text = composerState.text
            val cursor = composerState.cursor.coerceIn(0, text.length)
            val updated = text.substring(0, cursor) + transcript + text.substring(cursor)
            updateComposerText(updated, cursor + transcript.length)
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
            const val CHIP_TIMELINE_INTRO_ENABLED_NOTICE = SessionChatTimelineOffers.CHIP_TIMELINE_INTRO_ENABLED_NOTICE
            const val BRANCH_COPIED_MESSAGE = "Branch name copied"

            /** Pause after the first history load before the Timeline Style picker rises (#805). */
            const val TIMELINE_STYLE_PICKER_DELAY_MILLIS = SessionChatTimelineOffers.TIMELINE_STYLE_PICKER_DELAY_MILLIS

            fun timelineStylePickerNotice(style: SessionChatTimelineStyle): String =
                SessionChatTimelineOffers.timelineStylePickerNotice(style)
        }
    }

/**
 * Union of server inbox rows and still-unmerged optimistic busy sends.
 * [optimistic] must already be the merge leftover (echo dropped the matching
 * optimistic row). This function only skips optimistic ids that are already in
 * [loaded], as a client-id send's inbox echo is.
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
            ClientSlashAction.Btw -> ProductAnalytics.Action.COMMANDS_BTW
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
