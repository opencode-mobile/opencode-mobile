import Foundation
import Observation

@MainActor
@Observable
final class SessionChatViewModel {
    private(set) var uiState: SessionChatUiState
    /// Set once `loadMessages()` ran to completion. A retained view model that
    /// reappears before then (its first `.task` was cancelled) loads again.
    @ObservationIgnored private(set) var hasCompletedInitialLoad = false
    /// Session Chat screens showing this view model; see `SessionChatHostLeases`.
    @ObservationIgnored private var hostLeases = SessionChatHostLeases()

    private var sessionID: String
    private var sessionTitle: String
    /// The checkout every region is bound to. A draft moves it; its first send may create it.
    private var worktree: String

    private let timeline: SessionChatTimeline
    private let composer: SessionChatComposer
    private let sessionInfo: SessionInfo
    private let reviewChanges: SessionChatReviewChanges
    private let repoStrip: SessionChatRepoStrip
    private let commands: SessionChatCommands
    private let inbox: SessionChatInbox
    private let sideQuestions: SessionChatSideQuestions
    private let shells: SessionChatShells
    private let goalRegion: SessionChatGoal
    private let turnStatus: SessionChatTurnStatus
    private let projectHeader: SessionChatProjectHeader
    /// Move to worktree (#785).
    private let worktreeMove: SessionChatWorktreeMove
    /// New session draft (#1004).
    private let newSessionDraft: SessionChatNewSessionDraft
    private let timelineOffers: SessionChatTimelineOffers
    private let dictation = SessionChatDictation()
    /// The loaded messages last pushed to `shells`, so unchanged publishes push nothing.
    @ObservationIgnored private var shellTranscript: [Message]?
    private let sessionRepository: any SessionRepository
    private let sessionReadyStore: any SessionReadyStore
    private let sessionViewReporter: any SessionViewReporter
    private let foregroundEventStream: any ForegroundEventStream
    /// Invalidates in-flight `withObservationTracking` for the collapse preference.
    private var collapsePreferenceGeneration = 0
    private var lastSnackbarKey: String?
    private var isSessionBound = true
    private var suppressedSnackbarKey: String?
    private var snackbarSequence: UInt64 = 0
    /// Free-text edits log once per dialog / question so keystrokes are not
    /// events and no typed content can reach Analytics.
    private var loggedRenameTitleEdit = false
    private var loggedForkTitleEdit = false
    private var loggedQuestionCustomTextEdit: String?
    /// Refreshes Home's checkouts of a directory after a draft's first send created a worktree.
    /// The screen sets it from Home's environment.
    var refreshWorktreeInventory: (@MainActor @Sendable (String) async -> Void)? {
        get { newSessionDraft.refreshWorktreeInventory }
        set { newSessionDraft.refreshWorktreeInventory = newValue }
    }
    private let activeConnectionManager: ActiveConnectionManager
    private let liveUpdates: any LiveUpdates
    private let syncCoordinator: (any SyncCoordinator)?
    private let clock: any SyncClock
    private var pendingSend: PendingSend?
    private var armedSendAcceptance: ArmedSendAcceptance?
    private var sendInFlight = false
    /// Loads timeline image attachments (inline data or server files); nil in tests.
    let attachmentImageLoader: (any AttachmentImageLoading)?
    /// Applies live revert updates (`session.revert` / `session.revert.staged`) to Commands.
    private var sessionEventTask: Task<Void, Never>?
    /// New worktree form of the Move sheet.
    var worktreeCreate: WorktreeCreateModel { worktreeMove.form }
    private var branchCopiedNotice: String?
    /// Shown instead of opening Changes when the last turn moved folders.
    private var turnDiffNotice: String?
    /// A sheet or dialog the view owns is up; see `setScreenSheetOpen`.
    @ObservationIgnored private var screenSheetOpen = false
    /// Tests set this to 0 to commit an insert synchronously.
    var dictationFinishDelayNs: UInt64 {
        get { dictation.finishDelayNs }
        set { dictation.finishDelayNs = newValue }
    }

    init(
        sessionID: String,
        sessionTitle: String,
        worktree: String,
        messageRepository: any MessageRepository,
        modelCatalogStore: ModelCatalogStore,
        commandRepository: any CommandRepository,
        agentRepository: any AgentRepository,
        sessionRepository: any SessionRepository,
        permissionLiveStore: (any PermissionLiveStore)? = nil,
        questionLiveStore: (any QuestionLiveStore)? = nil,
        sessionTodoLiveStore: (any SessionTodoLiveStore)? = nil,
        sessionReadyStore: (any SessionReadyStore)? = nil,
        sessionViewReporter: (any SessionViewReporter)? = nil,
        sessionStatusLiveStore: any SessionStatusLiveStore,
        foregroundEventStream: any ForegroundEventStream,
        sessionModelSelectionStore: any SessionModelSelectionStore,
        composerDraftStore: any ComposerDraftStore,
        activeConnectionManager: ActiveConnectionManager,
        connectionProfileStore: any ConnectionProfileStore,
        newSessionModelSelectionResolver: NewSessionModelSelectionResolver,
        liveUpdates: any LiveUpdates = FixedLiveUpdates(),
        lastTurnReviewChangesStore: LastTurnReviewChangesStore? = nil,
        sideQuestionStore: SideQuestionStore? = nil,
        syncCoordinator: (any SyncCoordinator)? = nil,
        clock: any SyncClock = SystemSyncClock(),
        mcpRepository: (any McpRepository)? = nil,
        projectFilesRepository: (any ProjectFilesRepository)? = nil,
        attachmentUploadRepository: (any AttachmentUploadRepository)? = nil,
        attachmentImageLoader: (any AttachmentImageLoading)? = nil,
        catalogRetryDelaysMs: [Int] = SessionChatComposer.defaultCatalogRetryDelaysMs,
        worktreeRepository: any WorktreeRepository = UnavailableWorktreeRepository(),
        projectRepository: any ProjectRepository = UnavailableProjectRepository(),
        worktreeInventoryCache: WorktreeInventoryCache = WorktreeInventoryCache(),
        agentShellRepository: any AgentShellRepository = UnavailableAgentShellRepository(),
        newSessionDraftStore: NewSessionDraftStore? = nil,
        goalRepository: any SessionGoalRepository = UnavailableSessionGoalRepository()
    ) {
        // A New session draft binds where its kept draft runs, whatever the route said.
        let draftStore = newSessionDraftStore ?? NewSessionDraftStore()
        let draftProfileKey = activeConnectionManager.currentProfile().map(NewSessionDraftStore.key(for:))
        let initialDraft: NewSessionDraft? = OptimisticSessionCreate.isPlaceholder(sessionID)
            ? draftProfileKey.flatMap { draftStore.draft(forProfile: $0) }
            ?? NewSessionDraft(projectKey: nil, projectDirectory: worktree)
            : nil
        let worktree = initialDraft?.directory ?? worktree
        self.sessionID = sessionID
        self.sessionTitle = sessionTitle
        self.worktree = worktree
        self.attachmentImageLoader = attachmentImageLoader
        self.activeConnectionManager = activeConnectionManager
        self.liveUpdates = liveUpdates
        self.syncCoordinator = syncCoordinator
        self.clock = clock
        self.uiState = .initial(
            sessionID: sessionID,
            sessionTitle: sessionTitle,
            timeline: OptimisticSessionCreate.isPlaceholder(sessionID) ? .empty : .loading
        )
        self.sessionRepository = sessionRepository
        self.sessionReadyStore = sessionReadyStore ?? EmptySessionReadyStore()
        self.sessionViewReporter = sessionViewReporter ?? EmptySessionViewReporter()
        self.foregroundEventStream = foregroundEventStream
        self.worktreeMove = SessionChatWorktreeMove(
            sessionID: sessionID,
            worktree: worktree,
            sessionRepository: sessionRepository,
            worktreeRepository: worktreeRepository,
            foregroundEventStream: foregroundEventStream,
            serverProtocol: { activeConnectionManager.serverProtocol }
        )
        // What Home already loaded decides the identity before the first frame; the refresh
        // below only changes it when the server now answers differently.
        self.projectHeader = SessionChatProjectHeader(
            worktree: worktree,
            worktreeInventoryCache: worktreeInventoryCache,
            connectionProfileStore: connectionProfileStore,
            projectRepository: projectRepository,
            worktreeRepository: worktreeRepository,
            foregroundEventStream: foregroundEventStream,
            currentProfile: { activeConnectionManager.currentProfile() },
            inventoryServerKey: { activeConnectionManager.connection?.baseURL.absoluteString }
        )
        self.timeline = SessionChatTimeline(
            sessionID: sessionID,
            worktree: worktree,
            messageRepository: messageRepository,
            listChildSessions: { id in try await sessionRepository.listChildSessions(id: id) },
            derivesChangeNotices: { !activeConnectionManager.capabilities.recordsTimelineChangeNotices }
        )
        self.composer = SessionChatComposer(
            sessionID: sessionID,
            worktree: worktree,
            messageRepository: messageRepository,
            modelCatalogStore: modelCatalogStore,
            commandRepository: commandRepository,
            agentRepository: agentRepository,
            sessionRepository: sessionRepository,
            sessionModelSelectionStore: sessionModelSelectionStore,
            composerDraftStore: composerDraftStore,
            activeConnectionManager: activeConnectionManager,
            foregroundEventStream: foregroundEventStream,
            projectFilesRepository: projectFilesRepository,
            attachmentUploadRepository: attachmentUploadRepository,
            catalogRetryDelaysMs: catalogRetryDelaysMs,
            draftModelSelection: initialDraft?.modelSelection
        )
        if let initialDraft {
            composer.restoreDraftContent(initialDraft.composer, attachments: initialDraft.attachments)
        }
        let boundComposer = self.composer
        self.newSessionDraft = SessionChatNewSessionDraft(
            draft: initialDraft,
            profileKey: draftProfileKey,
            store: draftStore,
            worktreeRepository: worktreeRepository,
            sessionRepository: sessionRepository,
            modelSelectionResolver: newSessionModelSelectionResolver,
            currentProfile: { activeConnectionManager.currentProfile() },
            currentProtocolOrProbe: { await activeConnectionManager.currentProtocolOrProbe() },
            serverProtocol: { activeConnectionManager.serverProtocol },
            composerContent: { boundComposer.draftContent },
            composerModelSelection: { boundComposer.draftModelSelection }
        )
        self.sessionInfo = SessionInfo(
            worktree: worktree,
            sessionRepository: sessionRepository,
            sessionStatusLiveStore: sessionStatusLiveStore,
            foregroundEventStream: foregroundEventStream,
            liveUpdates: liveUpdates
        )
        self.reviewChanges = SessionChatReviewChanges(
            sessionID: sessionID,
            worktree: worktree,
            supportsSessionMessageDiff: { activeConnectionManager.capabilities.supportsSessionMessageDiff },
            lastTurnReviewChangesStore: lastTurnReviewChangesStore ?? LastTurnReviewChangesStore()
        )
        self.repoStrip = SessionChatRepoStrip(
            worktree: worktree,
            startExpanded: OptimisticSessionCreate.isPlaceholder(sessionID),
            sessionRepository: sessionRepository,
            foregroundEventStream: foregroundEventStream
        )
        self.commands = SessionChatCommands(
            sessionID: sessionID,
            worktree: worktree,
            sessionRepository: sessionRepository,
            newSessionModelSelectionResolver: newSessionModelSelectionResolver,
            activeConnectionManager: activeConnectionManager,
            composerDraftStore: composerDraftStore,
            mcpRepository: mcpRepository ?? EmptyMcpRepository()
        )
        self.sideQuestions = SessionChatSideQuestions(
            sessionID: sessionID,
            store: sideQuestionStore ?? SideQuestionStore(sessionRepository: sessionRepository),
            supported: { activeConnectionManager.capabilities.supportsSideQuestion }
        )
        self.shells = SessionChatShells(sessionID: sessionID, worktree: worktree, repository: agentShellRepository)
        self.goalRegion = SessionChatGoal(
            sessionID: sessionID,
            worktree: worktree,
            repository: goalRepository,
            foregroundEventStream: foregroundEventStream
        )
        let boundSessionInfo = self.sessionInfo
        let boundTimeline = self.timeline
        let boundCommands = self.commands
        self.turnStatus = SessionChatTurnStatus(
            sessionRepository: sessionRepository,
            clock: clock,
            loadedMessages: { boundTimeline.loadedMessages() },
            childSessions: { boundTimeline.childSessions },
            applyOptimistic: { id, action in
                switch action {
                case .steer: boundTimeline.applyOptimisticPendingPrompt(messageID: id, delivery: .steer)
                case .queue: boundTimeline.applyOptimisticPendingPrompt(messageID: id, delivery: .queue)
                case .cancel: boundTimeline.applyOptimisticPendingPrompt(messageID: id, removed: true)
                }
            },
            reportError: { boundCommands.reportActionError($0) }
        )
        self.timelineOffers = SessionChatTimelineOffers(
            clock: clock,
            loadedMessages: { boundTimeline.loadedMessages() }
        )
        self.inbox = SessionChatInbox(
            sessionID: sessionID,
            worktree: worktree,
            sessionRepository: sessionRepository,
            permissionLiveStore: permissionLiveStore ?? EmptyPermissionLiveStore(),
            questionLiveStore: questionLiveStore ?? EmptyQuestionLiveStore(),
            sessionTodoLiveStore: sessionTodoLiveStore ?? EmptySessionTodoLiveStore(),
            canMutate: { boundSessionInfo.state.canMutate },
            loadedMessages: { boundTimeline.loadedMessages() }
        )
        lastSnackbarKey = nil
        suppressedSnackbarKey = nil
        snackbarSequence = 0
        turnDiffNotice = nil
        branchCopiedNotice = nil
        OpenCodeLog.sessionChat.info("bind session=\(sessionID) worktree=\(worktree) title=\(sessionTitle)")
        sessionInfo.bind(sessionID: sessionID, sessionTitle: sessionTitle)
        timeline.onChange = { [weak self] in self?.publishUiState() }
        composer.onChange = { [weak self] in
            guard let self else { return }
            self.timeline.updateServerCommands(self.composer.state.serverCommands)
            self.publishUiState()
        }
        sessionInfo.onChange = { [weak self] in self?.publishUiState() }
        reviewChanges.onChange = { [weak self] in self?.publishUiState() }
        repoStrip.onChange = { [weak self] in self?.publishUiState() }
        commands.onChange = { [weak self] in self?.publishUiState() }
        inbox.onChange = { [weak self] in self?.publishUiState() }
        sideQuestions.onChange = { [weak self] in self?.publishUiState() }
        shells.onChange = { [weak self] in self?.publishUiState() }
        goalRegion.onChange = { [weak self] in self?.publishUiState() }
        projectHeader.onChange = { [weak self] in self?.publishUiState() }
        dictation.onChange = { [weak self] in self?.publishUiState() }
        newSessionDraft.onChange = { [weak self] in self?.publishUiState() }
        worktreeMove.onChange = { [weak self] in self?.publishUiState() }
        turnStatus.onWake = { [weak self] in self?.publishUiState() }
        timelineOffers.onChange = { [weak self] in self?.publishUiState() }
        timelineOffers.isBound = { [weak self] in self?.isSessionBound ?? false }
        timelineOffers.isBlocked = { [weak self] in self?.uiState.blocksChipTimelineIntro ?? false }
        timelineOffers.isSessionBusy = { [weak self] in self?.uiState.sessionStatusLabel != nil }
        // The meter's level only: no full publish.
        dictation.onLevelChange = { [weak self] current in self?.uiState.dictation = current }
        dictation.onCommit = { [weak self] transcript in self?.spliceDictationTranscript(transcript) }
        sideQuestions.canPresentAnswer = { [weak self] in self?.canPresentSideQuestionAnswer ?? false }
        projectHeader.refresh()
        publishUiState()
    }

    /// A Session Chat screen started showing this view model. Starts observing for
    /// the first screen only, so a fold ↔ unfold hand-off keeps the live subscription.
    func attachHost(_ host: UUID) {
        if hostLeases.attach(host) {
            startObserving()
        }
    }

    /// A Session Chat screen stopped showing this view model. Stops observing once
    /// no screen shows it.
    func detachHost(_ host: UUID) {
        if hostLeases.detach(host) {
            stopObserving()
        }
    }

    func startObserving() {
        isSessionBound = true
        if isDraft {
            startDraftObserving()
            return
        }
        startSessionEventObservation()
        projectHeader.startObserving()
        worktreeMove.startObserving()
        startCollapsePreferenceObservation()
        repoStrip.start()
        reviewChanges.start()
        sideQuestions.start()
        OpenCodeLog.sessionChat.info("start observing session=\(self.sessionID)")
        sessionReadyStore.setFocusedSession(sessionID: sessionID, directory: worktree)
        sessionViewReporter.focus(sessionId: sessionID)
        syncCoordinator?.markFocused(.transcript(directory: worktree, sessionID: sessionID))
        // Peers may have changed while the screen was away; re-resolve smart label.
        projectHeader.refresh()
        composer.startCatalogLoads()
        commands.start()
        sessionInfo.startObserving()
        timeline.startObserving()
        inbox.startObserving()
        shells.start()
        goalRegion.start()
    }

    /// Error-state Retry: refetches the timeline. A draft has no timeline to fail.
    func retryFailedTimeline() async {
        guard !isDraft else { return }
        await loadMessages()
    }

    func loadMessages() async {
        if isDraft {
            // Nothing to load until the first send; the composer needs its models.
            await composer.loadCatalog()
            if !Task.isCancelled {
                hasCompletedInitialLoad = true
            }
            return
        }
        async let catalogLoad: Void = composer.loadCatalog()
        _ = await catalogLoad
        await waitForTimelineResolution()
        if !Task.isCancelled {
            hasCompletedInitialLoad = true
        }
    }

    func refreshMessages() async {
        await timeline.refreshMessages()
        composer.loadServerCommands(force: true)
        composer.loadMentionableAgents(force: true)
    }

    func loadOlderMessages() async {
        await timeline.loadOlderMessages()
    }

    func archiveSession() {
        guard hasCreatedSession else { return }
        sessionInfo.archiveSession()
    }

    /// The archived banner's Unarchive.
    func unarchiveSession() {
        guard hasCreatedSession else { return }
        sessionInfo.unarchiveSession()
    }

    func deleteSession() {
        guard hasCreatedSession else { return }
        sessionInfo.deleteSession()
    }

    func updateComposerText(_ text: String, cursor: Int? = nil) {
        // Typing `/btw ` switches to side-question mode and keeps only the question. Also strips
        // the command when it is already in that mode: a keystroke typed before the field took
        // the stripped draft brings it back.
        if hasCreatedSession, activeConnectionManager.capabilities.supportsSideQuestion,
           let question = SideQuestion.draftAfterCommand(text)
        {
            sideQuestions.enterMode()
            composer.restoreComposerText(question)
            return
        }
        composer.updateComposerText(text, cursor: cursor)
        sideQuestions.updateDraft(composer.state.composer.text)
    }

    func exitShellMode() {
        composer.exitShellMode()
    }

    func exitSideQuestionMode() {
        sideQuestions.exitMode()
    }

    /// The "answer ready" chip.
    func openSideQuestionAnswer() {
        logChatAction(ProductAnalytics.Action.sideQuestionOpenAnswer)
        sideQuestions.openReadyAnswer()
    }

    func closeSideQuestionAnswer() {
        logChatAction(ProductAnalytics.Action.sideQuestionClose)
        sideQuestions.closeAnswer()
    }

    /// The answer sheet copied its text to the pasteboard.
    func sideQuestionAnswerCopied() {
        logChatAction(ProductAnalytics.Action.sideQuestionCopy)
        sideQuestions.answerCopied()
    }

    /// The failed chip, or Retry on its snackbar when `id` is set.
    func retrySideQuestions(id: UUID? = nil) {
        logChatAction(ProductAnalytics.Action.sideQuestionRetry)
        if let id {
            sideQuestions.retry(id)
        } else {
            sideQuestions.retryFailed()
        }
    }

    func dismissFailedSideQuestions() {
        logChatAction(ProductAnalytics.Action.sideQuestionDismissFailed)
        sideQuestions.dismissFailed()
    }

    /// Sends the draft as a `/btw` side question: one `generate` request, nothing added to the
    /// timeline, and the draft cleared. A `/btw` this server cannot answer stays in the draft.
    private func askSideQuestion() {
        let draft = composer.state.composer.text
        // The Btw tile keeps the draft as it is, so it may still start with `/btw `.
        let question = sideQuestions.state.composerMode
            ? SideQuestion.draftAfterCommand(draft) ?? draft
            : SideQuestion.question(inDraft: draft) ?? ""
        guard activeConnectionManager.capabilities.supportsSideQuestion,
              !question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return }
        logChatAction(ProductAnalytics.Action.sideQuestionAsk)
        guard sideQuestions.ask(question) else { return }
        composer.restoreComposerText("")
        sideQuestions.updateDraft("")
    }

    /// Sheets and dialogs Session Chat's view owns itself are up; a new answer waits behind them.
    func setScreenSheetOpen(_ open: Bool) {
        screenSheetOpen = open
    }

    /// The mode is not kept, so a side-question draft gets its `/btw` back before leaving; it
    /// still asks a side question later instead of going out as a prompt.
    private func keepSideQuestionDraft() {
        guard sideQuestions.state.composerMode else { return }
        let draft = composer.state.composer.text
        guard !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              SideQuestion.question(inDraft: draft) == nil
        else { return }
        composer.restoreComposerText(SideQuestion.command + " " + draft)
    }

    /// A new answer opens its sheet only when no other sheet or dialog is up.
    private var canPresentSideQuestionAnswer: Bool {
        isSessionBound
            && !screenSheetOpen
            && !uiState.commandsSheetVisible
            && !uiState.forkDialogVisible
            && !uiState.moveSheetVisible
            && !uiState.rename.isDialogVisible
            && uiState.presentedActivityCard == nil
    }

    func beginDictation(languageLabel: String, onDevice: Bool) {
        dictation.begin(languageLabel: languageLabel, onDevice: onDevice)
    }

    func beginDictationUnavailable(
        _ phase: ComposerDictationPhase,
        message: String? = nil,
        languageLabel: String = ""
    ) {
        dictation.beginUnavailable(phase, message: message, languageLabel: languageLabel)
    }

    /// Applies a model download update only while the card still shows the model.
    func dictationModelDownload(_ phase: ComposerDictationPhase, progress: Double? = nil) {
        dictation.modelDownload(phase, progress: progress)
    }

    func dictationPartial(committed: String, volatile: String) {
        dictation.partial(committed: committed, volatile: volatile)
    }

    func dictationLevel(_ level: Double) {
        dictation.level(level)
    }

    func dictationInsert() {
        dictation.insert()
    }

    func dictationCancel() {
        dictation.cancel()
    }

    func cancelDictationForModeChange() {
        dictation.cancel()
    }

    func cancelDictationForBackground() {
        dictation.cancel()
    }

    func cancelDictationForDismiss() {
        dictation.cancel()
    }

    func selectSlashSuggestion(commandName: String) {
        logChatAction(ProductAnalytics.Action.composerSelectSlashSuggestion)
        composer.selectSlashSuggestion(commandName: commandName)
    }

    var fileSearchDelayMs: Int {
        get { composer.fileSearchDelayMs }
        set { composer.fileSearchDelayMs = newValue }
    }

    var sendStallDelayMs: Int {
        get { composer.sendStallDelayMs }
        set { composer.sendStallDelayMs = newValue }
    }

    func selectAtSuggestion(agentName: String) {
        logChatAction(ProductAnalytics.Action.composerSelectAtSuggestion)
        composer.selectAtSuggestion(agentName: agentName)
    }

    func refreshMentionableAgents() {
        composer.loadMentionableAgents(force: true)
    }

    func addAttachments(_ attachments: [Attachment]) {
        composer.addAttachments(attachments)
    }

    func removeAttachment(id: String) {
        logChatAction(ProductAnalytics.Action.composerRemoveAttachment)
        composer.removeAttachment(id: id)
    }

    func addAttachmentData(_ data: Data, filename: String, declaredMime: String?) {
        composer.addAttachmentData(data, filename: filename, declaredMime: declaredMime)
    }

    func reportAttachmentReadFailure() {
        composer.reportAttachmentReadFailure()
    }

    /// Reads whether the server streams uploads, so the composer's attach sources and file routing
    /// match the connection.
    func refreshAttachmentUploadAvailability() async {
        await composer.refreshUploadAvailability()
    }

    func addAttachmentFiles(_ urls: [URL]) async {
        await composer.addAttachmentFiles(urls)
    }

    func addPastedOrDroppedFiles(_ urls: [URL], skippedFolders: Int, unreadable: Int) async -> Bool {
        await composer.addPastedOrDroppedFiles(urls, skippedFolders: skippedFolders, unreadable: unreadable)
    }

    func retryModelCatalog() async {
        logChatAction(ProductAnalytics.Action.composerRetryModelCatalog)
        await composer.loadCatalog(force: true)
    }

    func selectModel(_ option: ModelSelectionOption) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionModelPicker,
            actionId: ProductAnalytics.Action.modelPickerSelectModel
        )
        composer.selectModel(option)
    }

    func selectMode(_ mode: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionModePicker,
            actionId: ProductAnalytics.Action.modePickerSelect,
            parameters: [ProductAnalytics.Param.mode: mode]
        )
        composer.selectMode(mode)
    }

    func selectVariant(_ variant: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionVariantPicker,
            actionId: ProductAnalytics.Action.variantPickerSelect
        )
        composer.selectVariant(variant)
    }

    func restoreComposerText(_ text: String) {
        composer.restoreComposerText(text)
    }

    func revertMessage(_ messageID: String) {
        commands.revertMessage(
            id: messageID,
            isBusy: uiState.sessionStatusLabel != nil || effectiveCompacting(),
            restoreComposerText: { [weak self] text in self?.composer.restoreComposerText(text) }
        )
    }

    func cancelRevert() {
        commands.cancelRevert(
            isBusy: uiState.sessionStatusLabel != nil || effectiveCompacting(),
            clearComposerRestore: { [weak self] in self?.composer.restoreComposerText("") }
        )
    }

    func openCommands() {
        logChatAction(ProductAnalytics.Action.composerOpenCommands)
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.sessionCommands,
            presentation: ProductAnalytics.Presentation.sheet,
            trigger: ProductAnalytics.Trigger.present
        )
        commands.openCommands()
        composer.dismissSuggestions()
    }

    func dismissCommands() {
        if uiState.commandsSheetVisible {
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.sessionCommands,
                actionId: ProductAnalytics.Action.commandsDismiss
            )
        }
        commands.dismissCommands()
    }

    func closeMcpServers() {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionCommands,
            actionId: ProductAnalytics.Action.mcpServersBack
        )
        commands.closeMcpServers()
    }

    func runClientSlashAction(_ action: ClientSlashAction) {
        guard action == .mcp || hasCreatedSession else { return }
        logCommandsAction(action)
        switch action {
        case .new:
            guard isConnectionLive else { return }
            commands.runNew()
        case .fork:
            openForkDialog()
        case .undo:
            commands.runUndo(
                isBusy: uiState.sessionStatusLabel != nil || effectiveCompacting(),
                restoreComposerText: { [weak self] text in self?.composer.restoreComposerText(text) }
            )
        case .redo:
            commands.runRedo(
                isBusy: uiState.sessionStatusLabel != nil || effectiveCompacting(),
                clearComposerRestore: { [weak self] in self?.composer.restoreComposerText("") }
            )
        case .terminal:
            commands.runTerminal(projectTitle: uiState.projectLabel ?? uiState.sessionTitle)
        case .files:
            commands.runFiles()
        case .mcp:
            commands.openMcpServers()
        case .compact:
            compactCurrentSession()
        case .btw:
            commands.dismissCommands()
            sideQuestions.enterMode()
        }
    }

    func compactCurrentSession() {
        guard hasCreatedSession, let model = composer.state.composer.selectedModel else { return }
        let beforeFraction = uiState.contextUsageFraction.map { Float($0) }
        commands.runCompact(
            providerID: model.option.providerID,
            modelID: model.option.modelID,
            beforeContextFraction: beforeFraction,
            awaitAfterContextFraction: { [weak self] in
                guard let self else { return nil }
                guard let after = self.sessionInfo.contextUsageFraction(
                    messages: self.timeline.state.messages,
                    modelOptions: self.composer.state.composer.modelOptions,
                    selectedModel: self.composer.state.composer.selectedModel
                ) else { return nil }
                return Float(after)
            }
        )
    }

    /// Screen-level Analytics helper: only taxonomy ids, never message text.
    private func logChatAction(_ actionId: String, parameters: [String: String] = [:]) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionChat,
            actionId: actionId,
            parameters: parameters
        )
    }

    private func logCommandsAction(_ action: ClientSlashAction) {
        let actionId: String =
            switch action {
            case .new: ProductAnalytics.Action.commandsNew
            case .fork: ProductAnalytics.Action.commandsFork
            case .undo: ProductAnalytics.Action.commandsUndo
            case .redo: ProductAnalytics.Action.commandsRedo
            case .compact: ProductAnalytics.Action.commandsCompact
            case .terminal: ProductAnalytics.Action.commandsTerminal
            case .files: ProductAnalytics.Action.commandsFiles
            case .mcp: ProductAnalytics.Action.commandsMcp
            case .btw: ProductAnalytics.Action.commandsBtw
            }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionCommands,
            actionId: actionId,
            parameters: [ProductAnalytics.Param.clientSlash: action.rawValue]
        )
    }

    /// One fork flow for the Commands sheet, the overflow menu (tip, `messageID == nil`) and
    /// message actions ("Fork from here", forks before that user message).
    /// Opens while offline too (matching Android): the dialog's Fork button follows
    /// `canMutate`, and `confirmFork` refuses to fork until the connection is live.
    func openForkDialog(atMessageID messageID: String? = nil) {
        guard hasCreatedSession else { return }
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.sessionFork,
            presentation: ProductAnalytics.Presentation.dialog,
            trigger: ProductAnalytics.Trigger.present
        )
        loggedForkTitleEdit = false
        commands.openForkDialog(currentTitle: uiState.sessionTitle, atMessageID: messageID)
    }

    // MARK: Move to worktree

    func openMoveToWorktree() {
        guard hasCreatedSession, activeConnectionManager.capabilities.supportsSessionMove else { return }
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.sessionMoveToWorktree,
            presentation: ProductAnalytics.Presentation.sheet,
            trigger: ProductAnalytics.Trigger.present
        )
        worktreeMove.openSheet()
    }

    func dismissMoveToWorktree() {
        worktreeMove.dismissSheet()
    }

    /// False once a v1 server answered 404 for its worktree routes.
    var serverCanCreateWorktrees: Bool {
        worktreeMove.serverCanCreateWorktrees
    }

    /// Switches the Move sheet to the New worktree form, creating from the session's checkout.
    func openMoveToNewWorktree(projectID: String?, sourceDirectory: String, sourceBranch: String?) {
        guard hasCreatedSession else { return }
        worktreeMove.openNewWorktreeForm(
            projectID: projectID,
            sourceDirectory: sourceDirectory,
            sourceBranch: sourceBranch
        )
    }

    /// Creates the worktree, lets Home refresh its checkouts, closes the sheet and moves the
    /// session there. A failed create keeps the form; a failed move uses the move error path.
    func submitMoveToNewWorktree(
        refreshInventory: @MainActor (CreatedWorktree) async -> Void = { _ in }
    ) async {
        guard let created = await worktreeMove.submitNewWorktree(refreshInventory: refreshInventory) else { return }
        await moveSession(to: created.directory, worktreeTitle: created.branch ?? created.name)
    }

    /// `POST /api/session/{id}/move`. The server moves an idle session at once and a busy one
    /// when its turn ends; `session.moved` then rebinds the screen to `directory`.
    func moveSession(to directory: String, worktreeTitle: String) async {
        guard hasCreatedSession else { return }
        await worktreeMove.moveSession(to: directory, worktreeTitle: worktreeTitle) {
            uiState.sessionStatusLabel != nil || effectiveCompacting()
        }
    }

    func updateForkDraft(_ text: String) {
        if !loggedForkTitleEdit {
            loggedForkTitleEdit = true
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.sessionFork,
                actionId: ProductAnalytics.Action.chatForkEditTitle
            )
        }
        commands.updateForkDraft(text)
    }

    func cancelForkDialog() {
        if uiState.forkDialogVisible {
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.sessionFork,
                actionId: ProductAnalytics.Action.chatForkCancel
            )
        }
        commands.cancelForkDialog()
    }

    func confirmFork() {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionFork,
            actionId: ProductAnalytics.Action.chatForkConfirm,
            parameters: isConnectionLive
                ? [:]
                : [ProductAnalytics.Param.outcome: ProductAnalytics.Outcome.blocked]
        )
        guard isConnectionLive else { return }
        commands.confirmFork()
    }

    func chooseForkOpen() {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionForkResult,
            actionId: ProductAnalytics.Action.chatForkOpen
        )
        commands.chooseForkOpen()
    }

    func chooseForkStay() {
        if uiState.forkResultVisible {
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.sessionForkResult,
                actionId: ProductAnalytics.Action.chatForkStay
            )
        }
        commands.chooseForkStay()
    }

    func consumePendingTerminalOpen() {
        commands.consumePendingTerminalOpen()
    }

    func consumePendingFilesOpen() {
        commands.consumePendingFilesOpen()
    }

    func consumePendingSessionReplace() {
        commands.consumePendingSessionReplace()
    }

    /// The last turn moved folders, so its combined diff cannot be shown. Stay on this screen.
    func presentTurnDiffUnavailable() {
        turnDiffNotice = TurnDiffUnavailable.message
        lastSnackbarKey = nil
        suppressedSnackbarKey = nil
        publishUiState()
    }

    /// The branch pill copied the branch name; confirm it with a snackbar.
    // MARK: Goal

    func openGoalSheet() {
        goalRegion.openSheet()
    }

    func closeGoalSheet() {
        goalRegion.closeSheet()
    }

    func performGoalAction(_ action: SessionGoalAction) {
        goalRegion.perform(action)
    }

    /// New goal…: the composer gets "/goal " so the user types the next objective.
    func startNewGoal() {
        goalRegion.closeSheet()
        let draft = "/\(SessionGoalKickoff.commandName) "
        updateComposerText(draft, cursor: draft.count)
    }

    func branchNameCopied() {
        branchCopiedNotice = Self.branchCopiedMessage
        // A repeat copy shows the snackbar again even if the last one was dismissed.
        lastSnackbarKey = nil
        suppressedSnackbarKey = nil
        publishUiState()
    }

    static let branchCopiedMessage = "Branch name copied"
    static let sendStalledMessage = "Still sending…"

    /// Retry on the "Still sending…" snackbar.
    func retryStalledSend() {
        composer.retryStalledSend()
    }

    func consumeSnackbar() {
        if uiState.snackbar != nil {
            logChatAction(ProductAnalytics.Action.chatDismissSnackbar)
        }
        suppressedSnackbarKey = lastSnackbarKey
        newSessionDraft.clearError()
        timelineOffers.clearNotices()
        worktreeMove.clearMessages()
        branchCopiedNotice = nil
        turnDiffNotice = nil
        commands.clearActionFeedback()
        sideQuestions.clearNotice()
        goalRegion.clearNotice()
        composer.clearTransientErrors()
        sessionInfo.clearTransientErrors()
        timeline.clearReadyError()
        inbox.clearTopologyErrors()
        publishUiState()
    }

    func enableChipTimelineFromIntro() {
        logChipTimelineIntroHidden(ProductAnalytics.Action.chipTimelineIntroEnable)
        timelineOffers.enableChipTimeline()
    }

    /// Later button, swipe, or scrim.
    func postponeChipTimelineIntro() {
        logChipTimelineIntroHidden(ProductAnalytics.Action.chipTimelineIntroLater)
        timelineOffers.postponeChipTimelineIntro()
    }

    func dismissChipTimelineIntro() {
        logChipTimelineIntroHidden(ProductAnalytics.Action.chipTimelineIntroNever)
        timelineOffers.dismissChipTimelineIntro()
    }

    /// Use button of the Timeline Style picker.
    func chooseTimelineStyleFromPicker(_ style: SessionChatTimelineStyle) {
        logTimelineStylePickerHidden(
            ProductAnalytics.Action.timelineStylePickerUse,
            parameters: [ProductAnalytics.Param.mode: style.wireValue]
        )
        timelineOffers.chooseTimelineStyle(style)
    }

    /// Swipe, scrim, or back on the Timeline Style picker: the update's default applies.
    func dismissTimelineStylePicker() {
        // The sheet also reports a close after Use hid it; that is not a dismiss.
        guard timelineOffers.timelineStylePickerVisible else { return }
        logTimelineStylePickerHidden(ProductAnalytics.Action.timelineStylePickerDismiss, parameters: [:])
        timelineOffers.dismissTimelineStylePicker()
    }

    private func logTimelineStylePickerHidden(_ actionId: String, parameters: [String: String]) {
        guard timelineOffers.hideTimelineStylePicker() else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionTimelineStylePicker,
            actionId: actionId,
            parameters: parameters
        )
    }

    private func logChipTimelineIntroHidden(_ actionId: String) {
        guard timelineOffers.hideChipTimelineIntro() else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionChipTimelineIntro,
            actionId: actionId
        )
    }

    func openRenameSession() {
        guard hasCreatedSession else { return }
        ProductAnalytics.logScreenView(
            screenId: ProductAnalytics.Screen.sessionRename,
            presentation: ProductAnalytics.Presentation.dialog,
            trigger: ProductAnalytics.Trigger.present
        )
        loggedRenameTitleEdit = false
        sessionInfo.openRenameSession()
    }

    func cancelRenameSession() {
        if uiState.rename.isDialogVisible {
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.sessionRename,
                actionId: ProductAnalytics.Action.chatRenameCancel
            )
        }
        sessionInfo.cancelRenameSession()
    }

    func updateRenameDraft(_ text: String) {
        if !loggedRenameTitleEdit {
            loggedRenameTitleEdit = true
            ProductAnalytics.logUserAction(
                screenId: ProductAnalytics.Screen.sessionRename,
                actionId: ProductAnalytics.Action.chatRenameEditTitle
            )
        }
        sessionInfo.updateRenameDraft(text)
    }

    func confirmRenameSession() async {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionRename,
            actionId: ProductAnalytics.Action.chatRenameSave,
            parameters: isConnectionLive
                ? [:]
                : [ProductAnalytics.Param.outcome: ProductAnalytics.Outcome.blocked]
        )
        guard isConnectionLive else { return }
        await sessionInfo.confirmRenameSession()
    }

    /// Chip timeline: show the `chipCount` chips one step paragraph had when
    /// its `+N more` was tapped; chips that arrive later still collapse.
    func expandStepParagraph(_ paragraphID: String, chipCount: Int) {
        timeline.expandStepParagraph(paragraphID, chipCount: chipCount)
    }

    /// Activity card: open its live activity sheet.
    func openActivityCard(_ cardID: String) {
        timeline.openActivityCard(cardID)
    }

    func dismissActivityCard() {
        timeline.dismissActivityCard()
    }

    /// Activity sheet: show one step's detail in the same sheet.
    func openActivityCardStep(_ stepID: String) {
        timeline.openActivityCardStep(stepID)
    }

    /// Activity sheet: back to the step list.
    func closeActivityCardStep() {
        timeline.closeActivityCardStep()
    }

    /// Step rail: show or fold one rail's earlier settled steps.
    func toggleStepRailFold(_ railID: String) {
        timeline.toggleStepRailFold(railID)
    }

    /// Gallery: open the image viewer over a group at the tapped image.
    func openImageViewer(images: [FilePart], index: Int) {
        timeline.openImageViewer(images: images, index: index)
    }

    func showImageViewerPage(_ index: Int) {
        timeline.showImageViewerPage(index)
    }

    func closeImageViewer() {
        timeline.closeImageViewer()
    }

    func toggleFollowUp() {
        guard activeConnectionManager.capabilities.supportsFollowUpDelivery else { return }
        composer.toggleFollowUp()
    }

    func steerPendingPrompt(_ id: String) {
        runPendingPromptAction(.steer, id: id, actionId: ProductAnalytics.Action.chatSteerPendingPrompt)
    }

    func queuePendingPrompt(_ id: String) {
        runPendingPromptAction(.queue, id: id, actionId: ProductAnalytics.Action.chatQueuePendingPrompt)
    }

    func cancelPendingPrompt(_ id: String) {
        runPendingPromptAction(.cancel, id: id, actionId: ProductAnalytics.Action.chatCancelPendingPrompt)
    }

    func clearPendingPrompts() {
        for id in uiState.pendingPrompts.filter({ !$0.isSettling }).map(\.id) {
            cancelPendingPrompt(id)
        }
    }

    private func runPendingPromptAction(
        _ action: SessionChatTurnStatus.PendingPromptAction,
        id: String,
        actionId: String
    ) {
        guard activeConnectionManager.capabilities.supportsFollowUpDelivery else { return }
        logChatAction(actionId)
        turnStatus.updatePendingPrompt(action, id: id, sessionID: sessionID)
    }

    func sendPrompt() async {
        guard hasCreatedSession else {
            await sendDraft()
            return
        }
        let hasAttachments = !composer.state.composer.attachments.isEmpty
        guard sessionInfo.state.composerEnabled else {
            logChatAction(
                ProductAnalytics.Action.composerSend,
                parameters: [
                    ProductAnalytics.Param.hasAttachments: String(hasAttachments),
                    ProductAnalytics.Param.outcome: ProductAnalytics.Outcome.blocked,
                ]
            )
            return
        }
        if pendingSend != nil || sendInFlight || composer.state.isSending {
            return
        }
        // A side question never becomes a prompt, including a typed `/btw` this server cannot answer.
        if sideQuestions.state.composerMode || SideQuestion.question(inDraft: composer.state.composer.text) != nil {
            askSideQuestion()
            return
        }
        sendInFlight = true
        defer { sendInFlight = false }
        guard inbox.state.permissionRequests.isEmpty, inbox.state.questionRequests.isEmpty else {
            logChatAction(
                ProductAnalytics.Action.composerSend,
                parameters: [
                    ProductAnalytics.Param.hasAttachments: String(hasAttachments),
                    ProductAnalytics.Param.outcome: ProductAnalytics.Outcome.blocked,
                ]
            )
            return
        }
        // Prompt text and attachment contents are never reported.
        logChatAction(
            ProductAnalytics.Action.composerSend,
            parameters: [ProductAnalytics.Param.hasAttachments: String(hasAttachments)]
        )
        DiagnosticLog.info(.chat, "sendPrompt start session=\(sessionID)")
        let waitToSend: (() async -> Bool)?
        if isConnectionLive {
            waitToSend = nil
        } else {
            waitToSend = { [weak self] in
                await self?.waitForLiveToSend() ?? false
            }
        }
        await composer.sendPrompt(
            sessionID: sessionID,
            onOptimisticRows: { [weak self] rows in
                DiagnosticLog.info(.chat, "sendPrompt optimistic rows=\(rows.count)")
                if let self, !self.isConnectionLive {
                    self.pendingSend = PendingSend(queuedAtMs: self.clock.nowMs())
                }
                self?.armSendAcceptance()
                self?.timeline.beginOptimisticSend(rows)
            },
            onSendAccepted: { [weak self] in
                guard let self else { return }
                DiagnosticLog.info(.chat, "sendPrompt accepted session=\(self.sessionID)")
                self.armedSendAcceptance = nil
                self.pendingSend = nil
                self.sessionInfo.setOptimisticActivity(.running)
                self.commands.consumePendingRevert()
                self.sessionReadyStore.notifyPromptSent(sessionID: self.sessionID, directory: self.worktree)
                DiagnosticLog.info(.chat, "sendPrompt reloadAfterSend start")
                await self.timeline.reloadAfterSend(
                    onComplete: { [weak self] in
                        DiagnosticLog.info(.chat, "sendPrompt reloadAfterSend complete")
                        self?.composer.clearSending()
                    },
                    onReloadFailed: { [weak self] in
                        DiagnosticLog.warning(.chat, "sendPrompt reloadAfterSend failed")
                        self?.composer.clearSending()
                    }
                )
            },
            onSendFailed: { [weak self] optimisticID, error in
                self?.armedSendAcceptance = nil
                self?.pendingSend = nil
                self?.composer.setSendError(error)
                self?.timeline.failOptimisticSend(optimisticID: optimisticID, error: error)
            },
            isSessionBusy: sessionInfo.state.sessionStatusLabel != nil,
            supportsFollowUp: activeConnectionManager.capabilities.supportsFollowUpDelivery,
            commandKeepsClientMessageID: activeConnectionManager.capabilities.commandKeepsClientMessageID,
            waitToSend: waitToSend,
            serverAlreadyAccepted: { [weak self] in
                self?.serverAlreadyAccepted() ?? false
            },
            beforePrompt: { [weak self] in
                try await self?.commands.commitPendingRevert()
            }
        )
        DiagnosticLog.info(.chat, "sendPrompt end session=\(sessionID)")
    }

    private func armSendAcceptance() {
        armedSendAcceptance = ArmedSendAcceptance(
            sessionID: sessionID,
            wasBusy: sessionInfo.state.sessionStatusLabel != nil,
            serverMessages: timeline.loadedMessages()
        )
    }

    private func serverAlreadyAccepted() -> Bool {
        guard let armed = armedSendAcceptance, armed.sessionID == sessionID else { return false }
        let becameBusy = !armed.wasBusy && sessionInfo.state.sessionStatusLabel != nil
        let serverUpdated = timeline.loadedMessages() != armed.serverMessages
        return becameBusy || serverUpdated
    }

    func abortSession() async {
        logChatAction(
            ProductAnalytics.Action.composerAbort,
            parameters: isConnectionLive
                ? [:]
                : [ProductAnalytics.Param.outcome: ProductAnalytics.Outcome.blocked]
        )
        guard isConnectionLive else { return }
        await composer.abortSession(timelineIsReady: timeline.state.timeline == .ready)
        if composer.state.abortErrorMessage == nil {
            sessionInfo.setOptimisticActivity(
                .idle,
                ttlMs: SessionActivityPolicy.abortOptimisticTtlMs
            )
        }
    }

    func allowPermissionOnce(requestID: String, sessionID: String) async {
        logChatAction(
            ProductAnalytics.Action.permissionAllowOnce,
            parameters: [ProductAnalytics.Param.permissionDecision: "allow_once"]
        )
        await inbox.allowPermissionOnce(requestID: requestID, sessionID: sessionID)
    }

    func alwaysAllowPermission(requestID: String, sessionID: String) async {
        logChatAction(
            ProductAnalytics.Action.permissionAlways,
            parameters: [ProductAnalytics.Param.permissionDecision: "always"]
        )
        await inbox.alwaysAllowPermission(requestID: requestID, sessionID: sessionID)
    }

    /// Collapse the dock so the composer is reachable; the request stays pending.
    func hidePermissionDock(requestID: String) {
        logChatAction(ProductAnalytics.Action.permissionHide)
        inbox.hidePermissionDock(requestID: requestID)
    }

    func showPermissionDock() {
        logChatAction(ProductAnalytics.Action.permissionShow)
        inbox.showPermissionDock()
    }

    /// Opens the panel on the request of the subagent the needs-input pill names.
    /// Returns false when no subagent request waits, so the caller opens the subagents panel.
    func openWaitingSubagentRequest() -> Bool {
        let inboxState = inbox.state
        guard let target = SessionChatBackgroundWork.needsInputTarget(
            subagents: uiState.subagents,
            sessions: inboxState.permissionSessions + inboxState.questionSessions,
            permissions: inboxState.permissionRequests,
            questions: inboxState.questionRequests,
            shownPermissionID: inboxState.visiblePermissionRequest?.id
        ) else { return false }
        switch target {
        case .permission: logChatAction(ProductAnalytics.Action.permissionShow)
        case .question: logChatAction(ProductAnalytics.Action.questionShow)
        }
        inbox.focus(target)
        return true
    }

    /// The background work panel shows (or stops showing) its Done tab (#966). While it
    /// shows, every finished row counts as seen and the tab's dot clears.
    func setBackgroundWorkDoneVisible(_ visible: Bool) {
        if turnStatus.setDoneVisible(visible) { publishUiState() }
    }

    /// Opens the output sheet of a running shell from the background work panel.
    func openShellOutput(id: String) {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionSubagents,
            actionId: ProductAnalytics.Action.chatOpenShellOutput
        )
        shells.openOutput(shellID: id)
    }

    func closeShellOutput() {
        guard uiState.shellOutput != nil else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionShellOutput,
            actionId: ProductAnalytics.Action.shellOutputDismiss
        )
        shells.closeOutput()
    }

    /// Reads the open sheet's output until cancelled. The view runs it only while the sheet
    /// is up and the app is active, so output is never polled in the background.
    func watchShellOutput() async {
        await shells.watchOutput()
    }

    /// The output sheet copied its text to the pasteboard.
    func shellOutputCopied() {
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionShellOutput,
            actionId: ProductAnalytics.Action.shellOutputCopy
        )
    }

    /// Stop shell: only offered while the server can stop it (`canStop`).
    func stopShell(id: String) {
        guard uiState.shellOutput?.shell.id == id, uiState.shellOutput?.canStop == true else { return }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionShellOutput,
            actionId: ProductAnalytics.Action.shellStop
        )
        shells.stopShell(shellID: id)
    }

    func pagePermission(by delta: Int) {
        logChatAction(ProductAnalytics.Action.inboxRequestPage)
        inbox.pagePermission(by: delta)
    }

    func pageQuestion(by delta: Int) {
        logChatAction(ProductAnalytics.Action.inboxRequestPage)
        inbox.pageQuestion(by: delta)
    }

    /// Collapse the question panel so the composer is reachable; the question stays pending.
    func hideQuestionDock(requestID: String) {
        logChatAction(ProductAnalytics.Action.questionHide)
        inbox.hideQuestionDock(requestID: requestID)
    }

    func showQuestionDock() {
        logChatAction(ProductAnalytics.Action.questionShow)
        inbox.showQuestionDock()
    }

    func denyPermission(requestID: String, sessionID: String) async {
        logChatAction(
            ProductAnalytics.Action.permissionDeny,
            parameters: [ProductAnalytics.Param.permissionDecision: "deny"]
        )
        await inbox.denyPermission(requestID: requestID, sessionID: sessionID)
    }

    /// "Deny with a note…": swaps the permission prompt for the note step.
    func openPermissionNote(requestID: String, sessionID: String) {
        logChatAction(ProductAnalytics.Action.permissionNoteOpen)
        inbox.openPermissionNote(requestID: requestID, sessionID: sessionID)
    }

    func updatePermissionNote(requestID: String, text: String) {
        inbox.updatePermissionNote(requestID: requestID, text: text)
    }

    /// Leaves the note step without answering.
    func cancelPermissionNote(requestID: String) {
        logChatAction(ProductAnalytics.Action.permissionNoteCancel)
        inbox.cancelPermissionNote(requestID: requestID)
    }

    /// Denies with the note, so the agent gets it and keeps going.
    func sendPermissionNote(requestID: String, sessionID: String) async {
        logChatAction(
            ProductAnalytics.Action.permissionDeny,
            parameters: [ProductAnalytics.Param.permissionDecision: "deny_with_note"]
        )
        await inbox.sendPermissionNote(requestID: requestID, sessionID: sessionID)
    }

    func toggleQuestionOption(requestID: String, sessionID: String, label: String) {
        logChatAction(ProductAnalytics.Action.questionToggleOption)
        inbox.toggleQuestionOption(requestID: requestID, sessionID: sessionID, label: label)
    }

    func toggleQuestionCustom(requestID: String, sessionID: String) {
        logChatAction(ProductAnalytics.Action.questionToggleCustom)
        inbox.toggleQuestionCustom(requestID: requestID, sessionID: sessionID)
    }

    func updateQuestionCustomText(requestID: String, sessionID: String, text: String) {
        let editKey = "\(requestID)#\(inbox.state.questionWizardIndex)"
        if loggedQuestionCustomTextEdit != editKey {
            loggedQuestionCustomTextEdit = editKey
            logChatAction(ProductAnalytics.Action.questionEditCustomText)
        }
        inbox.updateQuestionCustomText(requestID: requestID, sessionID: sessionID, text: text)
    }

    func questionBack(requestID: String, sessionID: String) {
        logChatAction(ProductAnalytics.Action.questionBack)
        inbox.questionBack(requestID: requestID, sessionID: sessionID)
    }

    func questionNext(requestID: String, sessionID: String) {
        logChatAction(ProductAnalytics.Action.questionNext)
        inbox.questionNext(requestID: requestID, sessionID: sessionID)
    }

    func submitQuestion(requestID: String, sessionID: String) async {
        logChatAction(
            ProductAnalytics.Action.questionSubmit,
            parameters: [
                ProductAnalytics.Param.questionResponse: inbox.usedCustomQuestionAnswer ? "custom" : "option",
            ]
        )
        await inbox.submitQuestion(requestID: requestID, sessionID: sessionID)
    }

    func dismissQuestion(requestID: String, sessionID: String) async {
        logChatAction(ProductAnalytics.Action.questionDismiss)
        await inbox.dismissQuestion(requestID: requestID, sessionID: sessionID)
    }

    nonisolated static func orderedPermissionRequests(
        rootSessionID: String,
        sessions: [Session],
        requests: [PermissionRequest]
    ) -> [PermissionRequest] {
        let children = Dictionary(grouping: sessions.compactMap { session in
            session.parentID.map { ($0, session.id) }
        }, by: \.0)
        var sessionOrder: [String] = []
        var visited: Set<String> = []
        var queue = [rootSessionID]
        while let sessionID = queue.first {
            queue.removeFirst()
            guard visited.insert(sessionID).inserted else { continue }
            sessionOrder.append(sessionID)
            queue.append(contentsOf: (children[sessionID] ?? []).map(\.1))
        }
        let requestsBySession = Dictionary(grouping: requests, by: \.sessionID)
        return sessionOrder.flatMap { requestsBySession[$0] ?? [] }
    }

    private func startCollapsePreferenceObservation() {
        collapsePreferenceGeneration += 1
        timeline.updateCollapseConsecutiveToolCalls(
            SessionChatDisplayPreferences.shared.collapseConsecutiveToolCalls
        )
        timeline.updateChipTimeline(SessionChatDisplayPreferences.shared.chipTimeline)
        timeline.updateStepRailTimeline(SessionChatDisplayPreferences.shared.stepRailTimeline)
        timeline.updateActivityCardTimeline(SessionChatDisplayPreferences.shared.activityCardTimeline)
        observeCollapsePreference()
    }

    /// Re-projects an open timeline when the collapse preference flips while
    /// Session Chat is on screen. The recursive `withObservationTracking` skips
    /// work once the generation is bumped by `stopObserving`.
    private func observeCollapsePreference() {
        let generation = collapsePreferenceGeneration
        withObservationTracking {
            _ = SessionChatDisplayPreferences.shared.collapseConsecutiveToolCalls
            _ = SessionChatDisplayPreferences.shared.chipTimeline
            _ = SessionChatDisplayPreferences.shared.stepRailTimeline
            _ = SessionChatDisplayPreferences.shared.activityCardTimeline
        } onChange: { [weak self] in
            Task { @MainActor in
                guard let self, self.collapsePreferenceGeneration == generation else { return }
                self.timeline.updateCollapseConsecutiveToolCalls(
                    SessionChatDisplayPreferences.shared.collapseConsecutiveToolCalls
                )
                self.timeline.updateChipTimeline(SessionChatDisplayPreferences.shared.chipTimeline)
                self.timeline.updateStepRailTimeline(SessionChatDisplayPreferences.shared.stepRailTimeline)
                self.timeline.updateActivityCardTimeline(
                    SessionChatDisplayPreferences.shared.activityCardTimeline
                )
                self.publishUiState()
                self.observeCollapsePreference()
            }
        }
    }

    private var isConnectionLive: Bool {
        sessionInfo.state.canMutate
    }

    private func waitForLiveToSend() async -> Bool {
        if liveUpdates.chrome.canMutate { return true }
        return await withTaskGroup(of: Bool.self) { group in
            group.addTask { [clock] in
                await clock.sleep(milliseconds: Int(SessionActivityPolicy.pendingSendTimeoutMs))
                return false
            }
            group.addTask { [liveUpdates] in
                for await chrome in liveUpdates.updates() {
                    if chrome.canMutate { return true }
                }
                return false
            }
            let result = await group.next() ?? false
            group.cancelAll()
            return result
        }
    }

    private var hasCreatedSession: Bool {
        !OptimisticSessionCreate.isPlaceholder(sessionID)
    }

    func flushComposerDraft() {
        composer.flushDraft()
    }

    func stopObserving() {
        OpenCodeLog.sessionChat.info("stop observing session=\(self.sessionID)")
        cancelDictationForDismiss()
        isSessionBound = false
        turnStatus.reset()
        timelineOffers.reset()
        keepSideQuestionDraft()
        composer.flushDraft()
        newSessionDraft.stop()
        pendingSend = nil
        projectHeader.stopObserving()
        sessionEventTask?.cancel()
        sessionEventTask = nil
        worktreeMove.stopObserving()
        timeline.stopObserving()
        sessionInfo.stopObserving()
        inbox.stop()
        sideQuestions.stop()
        shells.stop()
        goalRegion.stop()
        collapsePreferenceGeneration += 1
        // Only release our own focus: the next Session Chat (child, parent, fork) may already
        // have focused itself before this screen disappears.
        sessionReadyStore.releaseFocusedSession(sessionID: sessionID)
        sessionViewReporter.release(sessionId: sessionID)
        syncCoordinator?.markFocused(nil)
        reviewChanges.stop()
        repoStrip.stop()
        composer.stopCatalogEventRefresh()
    }

    private func startSessionEventObservation() {
        sessionEventTask?.cancel()
        sessionEventTask = Task { [weak self] in
            guard let self else { return }
            for await event in self.foregroundEventStream.events() {
                guard let revert = event.sessionRevertUpdate else { continue }
                await MainActor.run { self.commands.applyRevertUpdate(revert) }
            }
        }
    }

    func row(from message: Message, previous: SessionChatRow? = nil) -> SessionChatRow? {
        timeline.row(from: message, previous: previous)
    }

    private func waitForTimelineResolution() async {
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline, timeline.state.timeline == .loading || timeline.state.timeline == .creating {
            try? await Task.sleep(for: .milliseconds(10))
        }
    }

    // MARK: New session draft (#1004)

    /// Nothing exists on the server yet: the first send creates the worktree (if asked for),
    /// then the session, then sends the prompt.
    var isDraft: Bool { newSessionDraft.isActive }

    private func startDraftObserving() {
        OpenCodeLog.sessionChat.info("draft observing worktree=\(self.worktree)")
        timeline.prepareForDraft()
        sessionInfo.startObservingConnection()
        composer.startCatalogLoads()
        publishUiState()
        newSessionDraft.startObserving()
    }

    /// Home's projects, the Worktrees setting and running counts, for the card and its sheets.
    func updateDraftContext(_ context: NewSessionDraftContext) {
        newSessionDraft.updateContext(context)
    }

    /// Project sheet pick: another project starts on its main checkout.
    func selectDraftProject(key: String) {
        guard newSessionDraft.selectProject(key: key) else { return }
        applyDraftPlace()
    }

    /// A project added from the Project sheet's Add project… becomes the draft's project.
    func selectDraftProject(addedPath path: String) {
        guard newSessionDraft.selectProject(addedPath: path) else { return }
        applyDraftPlace()
    }

    /// Worktree sheet pick. Picking New worktree again keeps its Starts from branch.
    func selectDraftPlace(_ place: NewSessionPlace) {
        guard newSessionDraft.selectPlace(place) else { return }
        applyDraftPlace()
    }

    /// Starts from sheet pick; nil (or the main checkout's own branch) is the default.
    func selectDraftBaseBranch(_ branch: String?) {
        newSessionDraft.selectBaseBranch(branch)
    }

    /// The Starts from sheet opened: loads the v2 branches of the main checkout once.
    func loadDraftBaseBranches() {
        newSessionDraft.loadBaseBranches()
    }

    /// Discard draft (⋯): forgets the draft and everything in it. The screen leaves.
    func discardDraft() {
        guard newSessionDraft.discard() else { return }
        // Resetting the composer deletes its attachment files.
        composer.reset()
        sessionInfo.requestLeaveSession()
    }

    /// The draft's place changed: every region binds to its checkout, and the composer loads
    /// that checkout's commands, agents and models.
    private func applyDraftPlace() {
        guard let current = newSessionDraft.draft else { return }
        newSessionDraft.save()
        let directory = current.directory
        if directory != worktree {
            OpenCodeLog.sessionChat.info("draft retarget worktree=\(directory)")
            worktree = directory
            composer.retargetDraft(worktree: directory)
            retargetRegions(directory)
            projectHeader.refresh()
            Task { [weak self] in
                await self?.composer.loadCatalog()
            }
        }
        publishUiState()
    }

    /// Regions other than the composer: idle while drafting, so they only take the checkout.
    private func retargetRegions(_ directory: String) {
        timeline.retargetDraft(worktree: directory)
        sessionInfo.retarget(worktree: directory)
        reviewChanges.retarget(worktree: directory)
        repoStrip.retarget(worktree: directory)
        commands.retarget(worktree: directory)
        shells.retarget(worktree: directory)
        inbox.retarget(worktree: directory)
        goalRegion.retarget(worktree: directory)
        projectHeader.retarget(worktree: directory)
        worktreeMove.retarget(worktree: directory)
    }

    /// First send: creates the worktree (New worktree; reused after a failed attempt), then the
    /// session in that checkout, seeds its model selection, promotes the screen, and sends the
    /// prompt through the normal send path. A failure keeps the draft and offers Retry.
    private func sendDraft() async {
        guard newSessionDraft.isActive, !newSessionDraft.state.isCreating, !sendInFlight,
              !composer.state.isSending
        else { return }
        let composerState = composer.state.composer
        guard composerState.hasSendableContent, !composerState.hasPendingUploads,
              composerState.selectedModel != nil, composerState.isModelSelectionReady
        else { return }
        guard liveUpdates.chrome.canMutate else {
            newSessionDraft.showSendError(
                String(localized: "home.error.reconnect_to_create", defaultValue: "Reconnect to create a session")
            )
            publishUiState()
            return
        }
        newSessionDraft.beginCreate()
        composer.setDraftSending(true)
        publishUiState()
        let created: (directory: String, session: Session)
        do {
            created = try await newSessionDraft.create(currentModelSelection: { composer.currentModelSelection })
        } catch {
            composer.setDraftSending(false)
            newSessionDraft.showCreateError(error)
            publishUiState()
            return
        }
        // Ends drafting first, so nothing writes the draft back to the store.
        promoteDraft(id: created.session.id, title: created.session.title, directory: created.directory)
        composer.setDraftSending(false)
        await sendPrompt()
    }

    /// Retry on the draft's error snackbar.
    func retryDraftSend() async {
        newSessionDraft.clearError()
        await sendPrompt()
    }

    /// The first send created the session: every region binds to it (and to the checkout it
    /// was created in) and starts what a created session observes. The composer keeps the draft.
    private func promoteDraft(id: String, title: String, directory: String) {
        let movedCheckout = directory != worktree
        newSessionDraft.end()
        sessionID = id
        sessionTitle = title
        timeline.resetExpandedStepParagraphs()
        turnStatus.reset()
        if movedCheckout {
            worktree = directory
            retargetRegions(directory)
            projectHeader.refresh()
        }
        composer.promoteDraft(sessionID: id, worktree: directory)
        timeline.rebind(sessionID: id)
        reviewChanges.rebind(sessionID: id)
        commands.rebind(sessionID: id)
        sideQuestions.rebind(sessionID: id)
        sessionInfo.bind(sessionID: id, sessionTitle: title)
        inbox.bind(sessionID: id)
        shells.rebind(sessionID: id)
        goalRegion.rebind(sessionID: id)
        worktreeMove.rebind(sessionID: id)
        // Left before the create finished: the next screen to show it starts observing.
        if isSessionBound {
            startObserving()
            timeline.showCreatedSessionEmpty()
        }
        publishUiState()
    }

    private func publishUiState() {
        if isDraft {
            newSessionDraft.save()
        }
        inbox.refilterOrphans()
        let inboxState = inbox.state
        let timelineState = timeline.state
        let infoState = sessionInfo.state
        composer.tryApplyInitialSelection(
            timelineState: timelineState,
            historyHints: timeline.modelSelectionHistoryHints()
        )
        let composerState = composer.state
        commands.updateVisibility(
            messages: timelineState.messages,
            hasSelectedComposerModel: composerState.composer.selectedModel != nil
        )
        commands.observeLoadedMessages(
            timeline.loadedMessages(),
            isSessionBusy: infoState.sessionStatusLabel != nil
        )
        let commandsState = commands.state
        timeline.updateRevertBoundary(commands.revertMessageID)
        let isCompacting = effectiveCompacting(
            localCompacting: commandsState.isCompacting,
            sessionStatusLabel: infoState.sessionStatusLabel
        )
        let isLive =
            isCompacting
            || infoState.sessionStatusLabel != nil
            || !inboxState.permissionRequests.isEmpty
            || !inboxState.questionRequests.isEmpty
        inbox.setLive(isLive)
        let isTurnActive = infoState.sessionStatusLabel != nil || isCompacting
        reviewChanges.updateCandidate(
            messages: timelineState.messages,
            hasResolvedSessionStatus: infoState.hasResolvedSessionStatus,
            isTurnActive: isTurnActive,
            isSending: composerState.isSending
        )
        if infoState.hasResolvedSessionStatus {
            repoStrip.updateTurnActive(isTurnActive)
            timelineOffers.updateTurnActive(isTurnActive)
        }
        timelineOffers.observeTimelineLoad(timelineState.timeline)
        let reviewChangesState = reviewChanges.state
        let repoStripState = repoStrip.state
        let timelineOffersState = timelineOffers.state
        let projectIdentity = projectHeader.identity
        let worktreeMoveState = worktreeMove.state
        let visibleQuestion = inboxState.visibleQuestionRequest
        let timelineEmptyError: String? = timelineState.timeline == .error ? timelineState.errorMessage : nil
        let draftErrorMessage = newSessionDraft.state.sendError
        let transientError: String? = {
            if let draftErrorMessage { return draftErrorMessage }
            if timelineState.timeline == .error { return nil }
            if let error = infoState.lifecycleErrorMessage { return error }
            if let error = composerState.errorMessage { return error }
            if let error = composerState.abortErrorMessage { return error }
            if let error = commandsState.actionErrorMessage { return error }
            if let error = goalRegion.state.errorNotice { return error }
            if let error = worktreeMoveState.moveErrorMessage { return error }
            if let error = turnDiffNotice { return error }
            if let error = infoState.errorMessage { return error }
            if inboxState.permissionRequests.isEmpty, let error = inboxState.permissionTopologyError { return error }
            if inboxState.questionRequests.isEmpty, let error = inboxState.questionTopologyError { return error }
            return timelineState.errorMessage
        }()
        let snackbar = resolveSnackbar(
            transientError: transientError,
            transientAction: draftErrorMessage != nil && transientError == draftErrorMessage ? .retryNewSession : nil,
            sendStallNotice: composerState.sendStallNotice,
            sideQuestionNotice: sideQuestions.notice,
            successNotice: composerState.followUpNotice
                ?? commandsState.actionSuccessMessage
                ?? worktreeMoveState.moveNotice
                ?? branchCopiedNotice
                ?? timelineOffersState.chipTimelineIntroNotice
                ?? timelineOffersState.timelineStylePickerNotice
        )
        // Reuse the current TimelineFrame when only composer / session-info /
        // review / command republish (same revision → no O(n) remap).
        let nextTimelineFrame = timeline.timelineFrame
        let timelineFrame =
            nextTimelineFrame.frameRevision == uiState.timelineFrame.frameRevision
            ? uiState.timelineFrame
            : nextTimelineFrame
        let sessionStatusLabel =
            isCompacting ? SessionCompactChrome.statusLabel : infoState.sessionStatusLabel
        let turnStatusResolution = turnStatus.resolve(
            SessionChatTurnStatusInputs(
                sessionStatusLabel: sessionStatusLabel,
                isCompacting: isCompacting,
                isSessionBound: isSessionBound,
                messages: timelineState.messages,
                sendProgressPercent: composerState.sendProgressPercent,
                pendingPrompts: Self.pendingPrompts(
                    loaded: timeline.loadedMessages(),
                    optimistic: timeline.pendingOptimisticItems()
                ),
                previousSubagents: uiState.subagents,
                inboxState: inboxState,
                infoState: infoState
            )
        )
        let subagents = turnStatusResolution.subagents
        let loadedMessages = timeline.loadedMessages()
        if loadedMessages != shellTranscript {
            shellTranscript = loadedMessages
            shells.transcriptChanged(loadedMessages)
        }
        let shellsState = shells.state
        let runningShells = SessionChatBackgroundWork.runningShells(shellsState.shells)
        goalRegion.setConnected(infoState.connectionStatus == .live)
        let goalState = SessionChatGoalUiState.resolve(
            region: goalRegion.state,
            isChildSession: false,
            isSessionBusy: isTurnActive,
            hasPermission: !inboxState.permissionRequests.isEmpty,
            hasQuestion: !inboxState.questionRequests.isEmpty,
            runningShellCommand: runningShells.first?.command,
            hasRunningSubagent: subagents.contains { $0.isActive },
            canMutate: infoState.canMutate
        )
        uiState = SessionChatUiState(
            sessionID: infoState.sessionID.isEmpty ? sessionID : infoState.sessionID,
            sessionTitle: infoState.sessionTitle.isEmpty ? sessionTitle : infoState.sessionTitle,
            projectLabel: projectIdentity.label,
            projectLetter: projectIdentity.letter,
            projectLetterAccent: projectIdentity.accent,
            worktreeCheckout: projectIdentity.isWorktreeCheckout,
            sessionBranchPill: SessionChatBranchPillUiState.resolved(
                from: repoStripState,
                isWorktree: projectIdentity.isWorktreeCheckout
            ),
            timeline: timelineState.timeline,
            messages: timelineState.messages,
            pendingPrompts: turnStatusResolution.pendingPrompts,
            timelineFrame: timelineFrame,
            composer: {
                var next = composer.state.composer
                next.supportsFollowUpDelivery = activeConnectionManager.capabilities.supportsFollowUpDelivery
                return next
            }(),
            isSending: composerState.isSending,
            isRefreshing: timelineState.isRefreshing,
            hasOlder: timelineState.hasOlder,
            isLoadingOlder: timelineState.isLoadingOlder,
            isAborting: composerState.isAborting,
            sessionStatusLabel: sessionStatusLabel,
            workingIndicatorLabel: turnStatusResolution.workingIndicatorLabel,
            retryCard: infoState.retryCard,
            isRetrying: infoState.isRetrying,
            workingIndicatorCollapsed: turnStatusResolution.workingIndicatorCollapsed,
            workingIndicatorFading: turnStatusResolution.workingIndicatorFading,
            syncingIndicatorLabel: timelineState.isSyncingMessages
                ? SessionMessageSyncChrome.statusLabel
                : nil,
            sessionTodos: inboxState.sessionTodos,
            sessionTodosVisible: SessionTodoList.isVisible(inboxState.sessionTodos),
            sessionTodosFinished: SessionTodoList.isFinished(inboxState.sessionTodos),
            sessionTodoActivePreview: SessionTodoList.activePreview(in: inboxState.sessionTodos),
            sessionTodoCompletedCount: SessionTodoList.completedCount(in: inboxState.sessionTodos),
            sessionTodoTotalCount: inboxState.sessionTodos.count,
            subagents: subagents,
            backgroundWorkPill: SessionChatBackgroundWork.pill(for: subagents, shells: runningShells),
            backgroundWorkDoneUnseen: turnStatusResolution.backgroundWorkDoneUnseen,
            shells: runningShells,
            shellOutput: shellsState.output,
            connectionStatus: infoState.connectionStatus,
            canMutate: infoState.canMutate,
            composerEnabled: infoState.composerEnabled,
            pendingSend: pendingSend,
            messagesFromCache: timelineState.messagesFromCache,
            contextUsageFraction: sessionInfo.contextUsageFraction(
                messages: timelineState.messages,
                modelOptions: composerState.composer.modelOptions,
                selectedModel: composerState.composer.selectedModel
            ),
            errorMessage: timelineEmptyError,
            snackbar: snackbar,
            isLifecycleActionInFlight: infoState.isLifecycleActionInFlight,
            shouldLeaveSession: infoState.shouldLeaveSession,
            permissionRequest: inboxState.visiblePermissionRequest,
            permissionQueuePosition: inboxState.permissionPageIndex + 1,
            permissionQueueCount: inboxState.permissionRequests.count,
            permissionAttribution: inboxState.visiblePermissionAttribution,
            permissionDockHidden: inboxState.isPermissionDockHidden,
            permissionSubmitting: inboxState.permissionSubmittingID != nil,
            permissionErrorMessage: inboxState.permissionError ?? inboxState.permissionTopologyError,
            permissionNote: inboxState.permissionNote,
            permissionOtherPendingInSessionCount: inboxState.otherPendingPermissionsInVisibleSession,
            questionRequest: visibleQuestion,
            questionQueuePosition: inboxState.questionPageIndex + 1,
            questionQueueCount: inboxState.questionRequests.count,
            questionAttribution: inboxState.visibleQuestionAttribution,
            questionDockHidden: inboxState.isQuestionDockHidden,
            inboxPanelPulse: inboxState.panelPulse,
            questionWizardIndex: inboxState.questionWizardIndex,
            questionSelectedLabels: inboxState.questionDraft.selectedLabels,
            questionCustomText: inboxState.questionDraft.customText,
            questionCustomSelected: inboxState.questionDraft.customSelected,
            questionSubmitting: inboxState.questionSubmittingID != nil && inboxState.questionSubmittingID == visibleQuestion?.id,
            questionErrorMessage: visibleQuestion != nil
                ? (inboxState.questionError ?? inboxState.questionTopologyError)
                : nil,
            reviewChangesMessageID: reviewChangesState.messageID,
            reviewChangesSummary: reviewChangesState.summary,
            supportsSessionMessageDiff: activeConnectionManager.capabilities.supportsSessionMessageDiff,
            supportsSessionArchive: activeConnectionManager.capabilities.supportsSessionArchive,
            archivedAtEpochMs: infoState.archivedAtEpochMs,
            supportsArchivedSessions: activeConnectionManager.capabilities.supportsArchivedSessions,
            supportsSessionMove: activeConnectionManager.capabilities.supportsSessionMove,
            moveSheetVisible: worktreeMoveState.moveSheetVisible,
            moveInFlight: worktreeMoveState.moveInFlight,
            movedDirectory: worktreeMoveState.movedDirectory,
            rename: infoState.rename,
            slashSuggestionsVisible: composerState.slashSuggestionsVisible,
            slashSuggestions: composerState.slashSuggestions,
            atSuggestionsVisible: composerState.atSuggestionsVisible,
            atSuggestions: composerState.atSuggestions,
            commandsSheetVisible: commandsState.commandsSheetVisible,
            visibleClientSlashActions: commandsState.visibleActions,
            mcpPageVisible: commandsState.mcpPageVisible,
            mcpServers: commandsState.mcpServers,
            mcpLoading: commandsState.mcpLoading,
            mcpErrorMessage: commandsState.mcpErrorMessage,
            mcpSummary: commandsState.mcpSummary,
            forkDialogVisible: commandsState.forkDialogVisible,
            forkDraftTitle: commandsState.forkDraftTitle,
            forkSubmitting: commandsState.forkSubmitting,
            forkErrorMessage: commandsState.forkErrorMessage,
            forkResultVisible: commandsState.forkResultVisible,
            forkedSessionTitle: commandsState.forkedSessionTitle,
            pendingSessionReplace: commandsState.pendingSessionReplace,
            pendingTerminalOpen: commandsState.pendingTerminalOpen,
            pendingFilesOpen: commandsState.pendingFilesOpen,
            hasPendingRevert: commandsState.hasPendingRevert,
            expandedStepParagraphs: timelineState.expandedStepParagraphs,
            unfoldedStepRails: timelineState.unfoldedStepRails,
            presentedActivityCard: timelineState.presentedActivityCard,
            activityCardDetailStepId: timelineState.activityCardDetailStepId,
            childActivities: timelineState.childActivities,
            activityCardFooters: timelineState.activityCardFooters,
            debouncedStepIDs: timelineState.debouncedStepIDs,
            imageViewer: timelineState.imageViewer,
            chipTimelineIntroVisible: timelineOffersState.chipTimelineIntroVisible,
            timelineStylePickerVisible: timelineOffersState.timelineStylePickerVisible,
            dictation: dictation.state.dictation,
            sideQuestions: {
                var next = sideQuestions.state
                next.supported = activeConnectionManager.capabilities.supportsSideQuestion
                next.composerMode = next.composerMode && next.supported
                return next
            }(),
            draft: newSessionDraft.uiState(),
            directory: worktree,
            goal: goalState,
            goalCommandAvailable: composerState.serverCommands.contains { $0.name == SessionGoalKickoff.commandName }
        )
    }

    /// Dictation's Insert: splices the transcript at the composer cursor through
    /// `updateComposerText`, so `/btw` detection still runs.
    private func spliceDictationTranscript(_ transcript: String) {
        let text = uiState.composer.text
        let cursor = min(max(0, uiState.composer.cursor), text.count)
        let updated = String(text.prefix(cursor)) + transcript + String(text.dropFirst(cursor))
        updateComposerText(updated, cursor: cursor + transcript.count)
    }

    private func resolveSnackbar(
        transientError: String?,
        transientAction: SessionChatSnackbarAction? = nil,
        sendStallNotice: Int? = nil,
        sideQuestionNotice: SessionChatSideQuestionNotice? = nil,
        successNotice: String?
    ) -> SessionChatSnackbar? {
        // A prompt the server has not answered outranks notices and yields to errors. Each
        // notice value is a new key, so a dismissed one comes back if the send stays stuck.
        if transientError == nil, let sendStallNotice {
            let key = "sendStall:\(sendStallNotice)"
            if key != suppressedSnackbarKey {
                if key != lastSnackbarKey {
                    snackbarSequence += 1
                    lastSnackbarKey = key
                }
                return SessionChatSnackbar(
                    id: snackbarSequence,
                    message: Self.sendStalledMessage,
                    tone: .default,
                    action: .retryStalledSend
                )
            }
        }
        // A `/btw` notice outranks other success notices and yields to errors; its id keeps a
        // repeated failure of the same question from deduping into the earlier snackbar.
        if transientError == nil, let sideQuestionNotice {
            let key = "sideQuestion:\(sideQuestionNotice.id)"
            if key == suppressedSnackbarKey { return nil }
            if key != lastSnackbarKey {
                snackbarSequence += 1
                lastSnackbarKey = key
            }
            return SessionChatSnackbar(
                id: snackbarSequence,
                message: sideQuestionNotice.message,
                tone: sideQuestionNotice.isError ? .error : .default,
                action: sideQuestionNotice.retryID.map { .retrySideQuestion($0) }
            )
        }
        let key: String?
        if let transientError {
            key = "error:\(transientError)"
        } else if let successNotice {
            key = "success:\(successNotice)"
        } else {
            key = nil
        }
        guard let key else {
            lastSnackbarKey = nil
            suppressedSnackbarKey = nil
            return nil
        }
        if key == suppressedSnackbarKey {
            return nil
        }
        if key != lastSnackbarKey {
            snackbarSequence += 1
            lastSnackbarKey = key
        }
        return SessionChatSnackbar(
            id: snackbarSequence,
            message: transientError ?? successNotice!,
            tone: transientError != nil ? .error : .default,
            action: transientError != nil ? transientAction : nil
        )
    }

    private func effectiveCompacting() -> Bool {
        effectiveCompacting(
            localCompacting: commands.state.isCompacting,
            sessionStatusLabel: sessionInfo.state.sessionStatusLabel
        )
    }

    private func effectiveCompacting(localCompacting: Bool, sessionStatusLabel: String?) -> Bool {
        if localCompacting { return true }
        return SessionCompactionPresence.isActive(
            messages: timeline.loadedMessages(),
            isSessionBusy: sessionStatusLabel != nil
        )
    }

    /// Union of server inbox rows and still-unmerged optimistic busy sends.
    /// `optimistic` must already be the merge leftover (echo dropped the matching
    /// optimistic row, often under a different id). This function only skips
    /// optimistic ids that are already in `loaded`.
    static func pendingPrompts(
        loaded: [Message],
        optimistic: [SessionChatRow]
    ) -> [PendingPrompt] {
        let fromLoaded = loaded
            .filter { $0.role == .user && $0.isWaitingForPickup && $0.followUpDelivery != nil }
            .compactMap { message -> PendingPrompt? in
                guard let delivery = message.followUpDelivery else { return nil }
                return PendingPrompt(id: message.id, text: message.text, delivery: delivery, isSettling: false)
            }
        let loadedIDs = Set(fromLoaded.map(\.id))
        let fromOptimistic = optimistic
            .filter { row in
                row.role == .user
                    && row.isPending
                    && row.pendingDelivery != nil
                    && !loadedIDs.contains(row.id)
            }
            .compactMap { row -> PendingPrompt? in
                guard let delivery = row.pendingDelivery else { return nil }
                return PendingPrompt(id: row.id, text: row.text, delivery: delivery, isSettling: true)
            }
        let steers = fromLoaded.filter { $0.delivery == .steer } + fromOptimistic.filter { $0.delivery == .steer }
        let queued = fromLoaded.filter { $0.delivery == .queue } + fromOptimistic.filter { $0.delivery == .queue }
        return steers + queued
    }

    static let chipTimelineIntroEnabledNotice = SessionChatTimelineOffers.chipTimelineIntroEnabledNotice
    /// Pause after history loads so the chat is on screen before the picker.
    static let timelineStylePickerDelayMs = SessionChatTimelineOffers.timelineStylePickerDelayMs

    static func timelineStylePickerNotice(_ style: SessionChatTimelineStyle) -> String {
        SessionChatTimelineOffers.timelineStylePickerNotice(style)
    }

    private struct ArmedSendAcceptance {
        let sessionID: String
        let wasBusy: Bool
        let serverMessages: [Message]
    }
}

private final class EmptyPermissionLiveStore: PermissionLiveStore {
    func observePermissions(directory: String) -> AsyncStream<[PermissionRequest]> {
        AsyncStream { $0.yield([]); $0.finish() }
    }
    func clearCache() {}
}

@MainActor
private final class EmptyQuestionLiveStore: QuestionLiveStore {
    func observeQuestions(directory: String) -> AsyncStream<[QuestionRequest]> {
        AsyncStream { $0.yield([]); $0.finish() }
    }
    func clearCache() {}
}
