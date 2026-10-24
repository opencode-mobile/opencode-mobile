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
    private let worktree: String

    private let timeline: SessionChatTimeline
    private let composer: SessionChatComposer
    private let sessionInfo: SessionInfo
    private let reviewChanges: SessionChatReviewChanges
    private let repoStrip: SessionChatRepoStrip
    private let commands: SessionChatCommands
    private let inbox: SessionChatInbox
    private let sessionRepository: any SessionRepository
    private let sessionReadyStore: any SessionReadyStore
    private let sessionViewReporter: any SessionViewReporter
    private let foregroundEventStream: any ForegroundEventStream
    private let eventAdapter = EventDomainAdapter()
    /// Invalidates in-flight `withObservationTracking` for the collapse preference.
    private var collapsePreferenceGeneration = 0
    private var lastSnackbarKey: String?
    private var loggedWorkingLamp = false
    private var lastWorkingLamp: String?
    private var workingChrome = WorkingTurnIndicator.Chrome.unmounted
    private var workingHold: WorkingTurnIndicator.Hold?
    private var workingHoldTask: Task<Void, Never>?
    private var pendingPromptsGate = PendingPromptsStripGate()
    private var pendingPromptsWakeTask: Task<Void, Never>?
    private var isSessionBound = true
    private var suppressedSnackbarKey: String?
    private var snackbarSequence: UInt64 = 0
    /// Free-text edits log once per dialog / question so keystrokes are not
    /// events and no typed content can reach Analytics.
    private var loggedRenameTitleEdit = false
    private var loggedForkTitleEdit = false
    private var loggedQuestionCustomTextEdit: String?
    private var optimisticCreateTask: Task<Void, Never>?
    private let newSessionModelSelectionResolver: NewSessionModelSelectionResolver
    private let activeConnectionManager: ActiveConnectionManager
    private let connectionProfileStore: any ConnectionProfileStore
    private let liveUpdates: any LiveUpdates
    private let syncCoordinator: (any SyncCoordinator)?
    private let clock: any SyncClock
    private var pendingSend: PendingSend?
    private var armedSendAcceptance: ArmedSendAcceptance?
    private var sendInFlight = false
    private var projectIdentity: SessionChatProjectIdentity
    private var checkoutProjectIDs: Set<String> = []
    private var worktreeEventTask: Task<Void, Never>?
    private let worktreeRepository: any WorktreeRepository
    /// New worktree form of the Move sheet.
    let worktreeCreate: WorktreeCreateModel
    private let worktreeInventoryCache: WorktreeInventoryCache
    private let projectRepository: any ProjectRepository
    private var projectIdentityTask: Task<Void, Never>?
    /// Move to worktree (#785).
    private var moveSheetVisible = false
    private var moveInFlight = false
    private var moveErrorMessage: String?
    private var moveNotice: String?
    /// Shown instead of opening Changes when the last turn moved folders.
    private var turnDiffNotice: String?
    private var movedDirectory: String?
    private var sessionMoveTask: Task<Void, Never>?
    /// Chip-timeline step paragraphs the user expanded past `+N more`:
    /// paragraph id to the chip count at tap time.
    private var expandedStepParagraphs: [String: Int] = [:]
    // One-time Chip timeline intro (OPE-270): offered after a long turn finishes.
    private var chipTimelineIntroVisible = false
    private var chipTimelineIntroNotice: String?
    private var chipTimelineIntroTurnActive: Bool?
    private var chipTimelineIntroTask: Task<Void, Never>?
    // One-time Timeline Style picker (#805): offered once per chat when history loads.
    private var timelineStylePickerVisible = false
    private var timelineStylePickerOffered = false
    private var timelineStylePickerNotice: String?
    private var timelineStylePickerTask: Task<Void, Never>?
    /// Composer dictation card. Kept off `uiState` so a republish does not drop it.
    private var dictation: ComposerDictationUiState?
    private var dictationInsertGeneration: UInt64 = 0
    private var dictationFinishTask: Task<Void, Never>?
    /// Tests set this to 0 to commit an insert synchronously.
    var dictationFinishDelayNs: UInt64 = 350_000_000

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
        syncCoordinator: (any SyncCoordinator)? = nil,
        clock: any SyncClock = SystemSyncClock(),
        mcpRepository: (any McpRepository)? = nil,
        projectFilesRepository: (any ProjectFilesRepository)? = nil,
        catalogRetryDelaysMs: [Int] = SessionChatComposer.defaultCatalogRetryDelaysMs,
        worktreeRepository: any WorktreeRepository = UnavailableWorktreeRepository(),
        projectRepository: any ProjectRepository = UnavailableProjectRepository(),
        worktreeInventoryCache: WorktreeInventoryCache = WorktreeInventoryCache()
    ) {
        self.sessionID = sessionID
        self.sessionTitle = sessionTitle
        self.worktree = worktree
        self.newSessionModelSelectionResolver = newSessionModelSelectionResolver
        self.activeConnectionManager = activeConnectionManager
        self.connectionProfileStore = connectionProfileStore
        self.liveUpdates = liveUpdates
        self.syncCoordinator = syncCoordinator
        self.clock = clock
        self.uiState = .initial(
            sessionID: sessionID,
            sessionTitle: sessionTitle,
            timeline: OptimisticSessionCreate.isPlaceholder(sessionID) ? .creating : .loading
        )
        self.sessionRepository = sessionRepository
        self.sessionReadyStore = sessionReadyStore ?? EmptySessionReadyStore()
        self.sessionViewReporter = sessionViewReporter ?? EmptySessionViewReporter()
        self.foregroundEventStream = foregroundEventStream
        self.worktreeRepository = worktreeRepository
        self.worktreeCreate = WorktreeCreateModel(repository: worktreeRepository)
        self.projectRepository = projectRepository
        self.worktreeInventoryCache = worktreeInventoryCache
        // What Home already loaded decides the identity before the first frame; the refresh
        // below only changes it when the server now answers differently.
        self.projectIdentity = Self.cachedProjectIdentity(
            worktree: worktree,
            serverBaseURL: activeConnectionManager.connection?.baseURL.absoluteString,
            cache: worktreeInventoryCache
        ) ?? .folder(worktree)
        self.timeline = SessionChatTimeline(
            sessionID: sessionID,
            worktree: worktree,
            messageRepository: messageRepository,
            listChildSessions: { id in try await sessionRepository.listChildSessions(id: id) }
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
            catalogRetryDelaysMs: catalogRetryDelaysMs
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
        let boundSessionInfo = self.sessionInfo
        let boundTimeline = self.timeline
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
        refreshProjectIdentity()
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
        startSessionEventObservation()
        startSessionMoveObservation()
        startCollapsePreferenceObservation()
        repoStrip.start()
        reviewChanges.start()
        if OptimisticSessionCreate.isPlaceholder(sessionID) {
            beginOptimisticCreate()
            return
        }
        OpenCodeLog.sessionChat.info("start observing session=\(self.sessionID)")
        sessionReadyStore.setFocusedSession(sessionID: sessionID, directory: worktree)
        sessionViewReporter.focus(sessionId: sessionID)
        syncCoordinator?.markFocused(.transcript(directory: worktree, sessionID: sessionID))
        // Peers may have changed while the screen was away; re-resolve smart label.
        refreshProjectIdentity()
        composer.startCatalogLoads()
        commands.start()
        sessionInfo.startObserving()
        timeline.startObserving()
        inbox.startObserving()
    }

    /// Error-state Retry. Create failures start the session again. Load failures refetch the timeline.
    func retryFailedTimeline() async {
        if OptimisticSessionCreate.isPlaceholder(sessionID) {
            beginOptimisticCreate()
            await optimisticCreateTask?.value
            return
        }
        await loadMessages()
    }

    func loadMessages() async {
        if OptimisticSessionCreate.isPlaceholder(sessionID) {
            await optimisticCreateTask?.value
            guard !OptimisticSessionCreate.isPlaceholder(sessionID) else { return }
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

    func deleteSession() {
        guard hasCreatedSession else { return }
        sessionInfo.deleteSession()
    }

    func updateComposerText(_ text: String, cursor: Int? = nil) {
        composer.updateComposerText(text, cursor: cursor)
    }

    func exitShellMode() {
        composer.exitShellMode()
    }

    func beginDictation(languageLabel: String, onDevice: Bool) {
        discardDictationInsert()
        dictation = ComposerDictationUiState(
            phase: .listening,
            committed: "",
            volatile: "",
            level: 0,
            languageLabel: languageLabel,
            onDevice: onDevice,
            startedAt: Date(),
            errorMessage: nil
        )
        publishUiState()
    }

    func beginDictationUnavailable(
        _ phase: ComposerDictationPhase,
        message: String? = nil,
        languageLabel: String = ""
    ) {
        // A recognizer error can arrive after Insert has already been confirmed.
        // Dropping the card here would cancel the splice.
        guard dictation?.phase != .finishing else { return }
        discardDictationInsert()
        dictation = ComposerDictationUiState(
            phase: phase,
            languageLabel: languageLabel,
            errorMessage: message
        )
        publishUiState()
    }

    /// Applies a model download update only while the card still shows the model.
    func dictationModelDownload(_ phase: ComposerDictationPhase, progress: Double? = nil) {
        guard phase.isModelPhase, var current = dictation, current.phase.isModelPhase else { return }
        current.phase = phase
        current.downloadProgress = progress
        dictation = current
        publishUiState()
    }

    func dictationPartial(committed: String, volatile: String) {
        guard var current = dictation, current.phase == .listening else { return }
        current.committed = committed
        current.volatile = volatile
        dictation = current
        publishUiState()
    }

    func dictationLevel(_ level: Double) {
        guard var current = dictation else { return }
        let clamped = min(1, max(0, level.isFinite ? level : 0))
        guard clamped != current.level else { return }
        current.level = clamped
        dictation = current
        uiState.dictation = current
    }

    func dictationInsert() {
        guard let current = dictation else { return }
        let transcript = current.committed + current.volatile
        guard !transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        dictationInsertGeneration &+= 1
        let generation = dictationInsertGeneration
        var finishing = current
        finishing.phase = .finishing
        dictation = finishing
        publishUiState()
        if dictationFinishDelayNs == 0 {
            commitDictationInsert(generation: generation)
            return
        }
        let delay = dictationFinishDelayNs
        dictationFinishTask?.cancel()
        dictationFinishTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled, let self else { return }
            self.commitDictationInsert(generation: generation)
        }
    }

    func dictationCancel() {
        cancelDictationCard()
    }

    func cancelDictationForModeChange() {
        cancelDictationCard()
    }

    func cancelDictationForBackground() {
        cancelDictationCard()
    }

    func cancelDictationForDismiss() {
        cancelDictationCard()
    }

    func selectSlashSuggestion(commandName: String) {
        logChatAction(ProductAnalytics.Action.composerSelectSlashSuggestion)
        composer.selectSlashSuggestion(commandName: commandName)
    }

    var fileSearchDelayMs: Int {
        get { composer.fileSearchDelayMs }
        set { composer.fileSearchDelayMs = newValue }
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

    func addAttachmentFiles(_ urls: [URL]) async {
        await composer.addAttachmentFiles(urls)
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
        worktreeCreate.dismiss()
        moveSheetVisible = true
        publishUiState()
    }

    func dismissMoveToWorktree() {
        worktreeCreate.dismiss()
        guard moveSheetVisible else { return }
        moveSheetVisible = false
        publishUiState()
    }

    /// False once a v1 server answered 404 for its worktree routes.
    var serverCanCreateWorktrees: Bool {
        worktreeRepository.canCreateWorktrees
    }

    /// Switches the Move sheet to the New worktree form, creating from the session's checkout.
    func openMoveToNewWorktree(projectID: String?, sourceDirectory: String, sourceBranch: String?) {
        guard hasCreatedSession, moveSheetVisible, !moveInFlight else { return }
        worktreeCreate.open(
            origin: .move(sessionID: sessionID),
            serverProtocol: activeConnectionManager.serverProtocol ?? .v1,
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
        guard case .move? = worktreeCreate.state?.origin else { return }
        guard let created = await worktreeCreate.submit() else { return }
        await refreshInventory(created)
        worktreeCreate.dismiss()
        moveSheetVisible = false
        publishUiState()
        await moveSession(to: created.directory, worktreeTitle: created.branch ?? created.name)
    }

    /// `POST /api/session/{id}/move`. The server moves an idle session at once and a busy one
    /// when its turn ends; `session.moved` then rebinds the screen to `directory`.
    func moveSession(to directory: String, worktreeTitle: String) async {
        guard hasCreatedSession, !moveInFlight else { return }
        moveInFlight = true
        moveErrorMessage = nil
        moveNotice = nil
        publishUiState()
        let wasBusy = uiState.sessionStatusLabel != nil || effectiveCompacting()
        do {
            try await sessionRepository.moveSession(id: sessionID, directory: directory)
            if wasBusy {
                moveNotice = SessionChatMoveToWorktree.movesAfterTurn(worktreeTitle)
            }
        } catch {
            moveErrorMessage = SessionChatMoveToWorktree.failure(SessionChatMoveToWorktree.serverMessage(error))
        }
        moveInFlight = false
        moveSheetVisible = false
        publishUiState()
    }

    private func startSessionMoveObservation() {
        sessionMoveTask?.cancel()
        sessionMoveTask = Task { [weak self] in
            guard let self else { return }
            for await event in self.foregroundEventStream.events() {
                guard let moved = self.eventAdapter.sessionMoved(from: event),
                      moved.sessionID == self.sessionID
                else { continue }
                self.applySessionMoved(to: moved.directory)
            }
        }
    }

    private func applySessionMoved(to directory: String) {
        let canonical = ServerPathPolicy.canonical(directory)
        guard canonical != ServerPathPolicy.canonical(worktree), canonical != movedDirectory else { return }
        OpenCodeLog.sessionChat.info("session moved session=\(self.sessionID) directory=\(canonical)")
        movedDirectory = canonical
        publishUiState()
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

    func consumeSnackbar() {
        if uiState.snackbar != nil {
            logChatAction(ProductAnalytics.Action.chatDismissSnackbar)
        }
        suppressedSnackbarKey = lastSnackbarKey
        chipTimelineIntroNotice = nil
        timelineStylePickerNotice = nil
        moveErrorMessage = nil
        moveNotice = nil
        turnDiffNotice = nil
        commands.clearActionFeedback()
        composer.clearTransientErrors()
        sessionInfo.clearTransientErrors()
        timeline.clearReadyError()
        inbox.clearTopologyErrors()
        publishUiState()
    }

    func enableChipTimelineFromIntro() {
        hideChipTimelineIntro(ProductAnalytics.Action.chipTimelineIntroEnable)
        SessionChatDisplayPreferences.shared.setChipTimeline(true)
        chipTimelineIntroNotice = Self.chipTimelineIntroEnabledNotice
        publishUiState()
    }

    /// Later button, swipe, or scrim.
    func postponeChipTimelineIntro() {
        hideChipTimelineIntro(ProductAnalytics.Action.chipTimelineIntroLater)
        SessionChatDisplayPreferences.shared.postponeChipTimelineIntro(nowMs: clock.nowMs())
        publishUiState()
    }

    func dismissChipTimelineIntro() {
        hideChipTimelineIntro(ProductAnalytics.Action.chipTimelineIntroNever)
        SessionChatDisplayPreferences.shared.finishChipTimelineIntro()
        publishUiState()
    }

    /// Use button of the Timeline Style picker.
    func chooseTimelineStyleFromPicker(_ style: SessionChatTimelineStyle) {
        hideTimelineStylePicker(
            ProductAnalytics.Action.timelineStylePickerUse,
            parameters: [ProductAnalytics.Param.mode: style.wireValue]
        )
        SessionChatDisplayPreferences.shared.setTimelineStyle(style)
        timelineStylePickerNotice = Self.timelineStylePickerNotice(style)
        publishUiState()
    }

    /// Swipe, scrim, or back on the Timeline Style picker: the update's default applies.
    func dismissTimelineStylePicker() {
        // The sheet also reports a close after Use hid it; that is not a dismiss.
        guard timelineStylePickerVisible else { return }
        hideTimelineStylePicker(ProductAnalytics.Action.timelineStylePickerDismiss, parameters: [:])
        let preferences = SessionChatDisplayPreferences.shared
        preferences.dismissTimelineStylePicker()
        timelineStylePickerNotice = Self.timelineStylePickerNotice(preferences.effectiveTimelineStyle)
        publishUiState()
    }

    private func hideTimelineStylePicker(_ actionId: String, parameters: [String: String]) {
        guard timelineStylePickerVisible else { return }
        timelineStylePickerVisible = false
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionTimelineStylePicker,
            actionId: actionId,
            parameters: parameters
        )
    }

    private func resetTimelineStylePicker() {
        timelineStylePickerTask?.cancel()
        timelineStylePickerTask = nil
        timelineStylePickerVisible = false
        timelineStylePickerOffered = false
        timelineStylePickerNotice = nil
    }

    /// Offers the picker once, a moment after this chat's history first loads,
    /// while an updated install has not picked a Timeline Style. Anything already
    /// open on the chat skips it until the next session open.
    private func offerTimelineStylePickerIfPending() {
        guard !timelineStylePickerOffered, isSessionBound else { return }
        let preferences = SessionChatDisplayPreferences.shared
        guard preferences.timelineStylePickerPending else { return }
        timelineStylePickerOffered = true
        timelineStylePickerTask = Task { [clock] in
            await clock.sleep(milliseconds: Self.timelineStylePickerDelayMs)
            guard !Task.isCancelled, isSessionBound, preferences.timelineStylePickerPending,
                  !chipTimelineIntroVisible, !uiState.blocksChipTimelineIntro else { return }
            timelineStylePickerVisible = true
            publishUiState()
        }
    }

    private func hideChipTimelineIntro(_ actionId: String) {
        guard chipTimelineIntroVisible else { return }
        chipTimelineIntroVisible = false
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionChipTimelineIntro,
            actionId: actionId
        )
    }

    private func resetChipTimelineIntro() {
        chipTimelineIntroTask?.cancel()
        chipTimelineIntroTask = nil
        chipTimelineIntroVisible = false
        chipTimelineIntroNotice = nil
        chipTimelineIntroTurnActive = nil
    }

    /// Offers the intro once a long turn goes busy → idle and stays settled.
    private func updateChipTimelineIntroTurnActive(_ isTurnActive: Bool) {
        let previous = chipTimelineIntroTurnActive
        chipTimelineIntroTurnActive = isTurnActive
        if isTurnActive {
            chipTimelineIntroTask?.cancel()
            chipTimelineIntroTask = nil
            return
        }
        guard previous == true, !chipTimelineIntroVisible, isSessionBound else { return }
        let toolCalls = ChipTimelineIntro.lastTurnToolCalls(timeline.loadedMessages())
        let preferences = SessionChatDisplayPreferences.shared
        guard preferences.shouldOfferChipTimelineIntro(turnToolCalls: toolCalls, nowMs: clock.nowMs()) else { return }
        chipTimelineIntroTask?.cancel()
        chipTimelineIntroTask = Task { [clock] in
            await clock.sleep(milliseconds: Int(ChipTimelineIntro.settleDelayMs))
            guard !Task.isCancelled, isSessionBound, uiState.sessionStatusLabel == nil,
                  !uiState.blocksChipTimelineIntro else { return }
            guard preferences.shouldOfferChipTimelineIntro(turnToolCalls: toolCalls, nowMs: clock.nowMs()) else { return }
            chipTimelineIntroVisible = true
            publishUiState()
        }
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
        guard expandedStepParagraphs[paragraphID, default: -1] < chipCount else { return }
        expandedStepParagraphs[paragraphID] = chipCount
        publishUiState()
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

    func toggleFollowUp() {
        guard activeConnectionManager.capabilities.supportsFollowUpDelivery else { return }
        composer.toggleFollowUp()
    }

    func steerPendingPrompt(_ id: String) {
        runPendingPromptAction(
            actionId: ProductAnalytics.Action.chatSteerPendingPrompt,
            inboxID: id,
            optimistic: { self.timeline.applyOptimisticPendingPrompt(messageID: id, delivery: .steer) }
        ) { sessionID in
            try await self.sessionRepository.steerPendingPrompt(sessionID: sessionID, inboxID: id)
        }
    }

    func queuePendingPrompt(_ id: String) {
        runPendingPromptAction(
            actionId: ProductAnalytics.Action.chatQueuePendingPrompt,
            inboxID: id,
            optimistic: { self.timeline.applyOptimisticPendingPrompt(messageID: id, delivery: .queue) }
        ) { sessionID in
            try await self.sessionRepository.queuePendingPrompt(sessionID: sessionID, inboxID: id)
        }
    }

    func cancelPendingPrompt(_ id: String) {
        runPendingPromptAction(
            actionId: ProductAnalytics.Action.chatCancelPendingPrompt,
            inboxID: id,
            optimistic: { self.timeline.applyOptimisticPendingPrompt(messageID: id, removed: true) }
        ) { sessionID in
            try await self.sessionRepository.cancelPendingPrompt(sessionID: sessionID, inboxID: id)
        }
    }

    func clearPendingPrompts() {
        for id in uiState.pendingPrompts.filter({ !$0.isSettling }).map(\.id) {
            cancelPendingPrompt(id)
        }
    }

    private func runPendingPromptAction(
        actionId: String,
        inboxID _: String,
        optimistic: @escaping () -> Void,
        call: @escaping (String) async throws -> Bool
    ) {
        guard activeConnectionManager.capabilities.supportsFollowUpDelivery else { return }
        logChatAction(actionId)
        let sessionID = sessionID
        Task {
            do {
                let ok = try await call(sessionID)
                if ok {
                    optimistic()
                } else {
                    commands.reportActionError(Self.pendingPromptFailure)
                }
            } catch is CancellationError {
                return
            } catch {
                DiagnosticLog.warning(.chat, "pending prompt update failed session=\(sessionID) error=\(error.localizedDescription)")
                commands.reportActionError(Self.pendingPromptFailure)
            }
        }
    }

    func sendPrompt() async {
        guard hasCreatedSession else { return }
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

    func denyPermission(requestID: String, sessionID: String) async {
        logChatAction(
            ProductAnalytics.Action.permissionDeny,
            parameters: [ProductAnalytics.Param.permissionDecision: "deny"]
        )
        await inbox.denyPermission(requestID: requestID, sessionID: sessionID)
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
        resetWorkingFooter()
        resetChipTimelineIntro()
        resetTimelineStylePicker()
        composer.flushDraft()
        pendingSend = nil
        optimisticCreateTask?.cancel()
        optimisticCreateTask = nil
        projectIdentityTask?.cancel()
        projectIdentityTask = nil
        worktreeEventTask?.cancel()
        worktreeEventTask = nil
        sessionMoveTask?.cancel()
        sessionMoveTask = nil
        timeline.stopObserving()
        sessionInfo.stopObserving()
        inbox.stop()
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

    /// Identity from the inventories Home (or an earlier Session Chat) loaded; nil when the
    /// added paths of this server are not known yet.
    private static func cachedProjectIdentity(
        worktree: String,
        serverBaseURL: String?,
        cache: WorktreeInventoryCache
    ) -> SessionChatProjectIdentity? {
        guard let serverBaseURL, let snapshot = cache.snapshot(server: serverBaseURL) else { return nil }
        return .resolve(worktree: worktree, addedPaths: snapshot.addedPaths, inventoryFor: snapshot.inventory(for:))
    }

    private var inventoryServerKey: String? {
        activeConnectionManager.connection?.baseURL.absoluteString
    }

    private func applyProjectIdentity(_ identity: SessionChatProjectIdentity) {
        guard identity != projectIdentity else { return }
        projectIdentity = identity
        publishUiState()
    }

    /// Re-resolves the identity from the server. Keeps the identity on screen until the answer
    /// lands, so an agreeing answer changes nothing.
    private func refreshProjectIdentity() {
        projectIdentityTask?.cancel()
        guard !worktree.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            applyProjectIdentity(.none)
            return
        }
        if let cached = Self.cachedProjectIdentity(
            worktree: worktree,
            serverBaseURL: inventoryServerKey,
            cache: worktreeInventoryCache
        ) {
            applyProjectIdentity(cached)
        }
        checkoutProjectIDs = []
        projectIdentityTask = Task { [worktree, connectionProfileStore, activeConnectionManager] in
            let baseURL: String?
            if let current = activeConnectionManager.currentProfile()?.baseURL, !current.isEmpty {
                baseURL = current
            } else if let stored = await connectionProfileStore.activeProfile()?.baseURL, !stored.isEmpty {
                baseURL = stored
            } else {
                baseURL = nil
            }
            let peers: [String]
            if let baseURL {
                peers = await connectionProfileStore.addedWorktrees(serverBaseURL: baseURL)
            } else {
                peers = []
            }
            guard !Task.isCancelled else { return }
            let inventories = await loadInventories(peers: peers)
            guard !Task.isCancelled, let inventories else { return }
            await MainActor.run {
                guard !Task.isCancelled else { return }
                self.applyProjectIdentity(
                    .resolve(worktree: worktree, addedPaths: peers) { inventories[$0] ?? .unloaded }
                )
            }
        }
    }

    /// Fresh inventory of every added path. A load that did not succeed falls back to the last
    /// known one, so a transient failure does not turn a checkout back into a project letter.
    /// Nil when cancelled.
    private func loadInventories(peers: [String]) async -> [String: WorktreeInventory]? {
        let server = inventoryServerKey
        let cached = server.flatMap { worktreeInventoryCache.snapshot(server: $0) }
        if let server {
            worktreeInventoryCache.setAddedPaths(peers, server: server)
        }
        guard !peers.isEmpty else { return [:] }
        let baseURL = await connectionProfileStore.activeProfile()?.baseURL ?? activeConnectionManager.currentProfile()?.baseURL
        if let baseURL, !baseURL.isEmpty {
            worktreeRepository.onConnection(key: baseURL)
        }
        var inventories: [String: WorktreeInventory] = [:]
        var projectIDs: Set<String> = []
        for added in peers {
            if Task.isCancelled { return nil }
            let projectID = try? await projectRepository.getCurrentProject(directory: added).id
            let usable = projectID?.trimmingCharacters(in: .whitespacesAndNewlines)
            if let usable, !usable.isEmpty {
                projectIDs.insert(usable)
                if Task.isCancelled { return nil }
                await MainActor.run {
                    guard !Task.isCancelled else { return }
                    checkoutProjectIDs.insert(usable)
                }
            }
            let loaded = await worktreeRepository.loadRefreshed(addedPath: added, projectID: usable)
            switch loaded {
            case let .loaded(directories, _):
                let inventory = WorktreeInventory.loaded(directories)
                inventories[added] = inventory
                if let server {
                    worktreeInventoryCache.record(inventory, addedPath: added, server: server)
                }
            case .failed:
                inventories[added] = cached?.inventories[added] ?? .failed
            case .unloaded, .stopped:
                inventories[added] = cached?.inventories[added] ?? .unloaded
            }
        }
        if Task.isCancelled { return nil }
        await MainActor.run {
            guard !Task.isCancelled else { return }
            checkoutProjectIDs = projectIDs
        }
        guard !Task.isCancelled else { return nil }
        return inventories
    }

    private func startSessionEventObservation() {
        worktreeEventTask?.cancel()
        worktreeEventTask = Task { [weak self] in
            guard let self else { return }
            for await event in self.foregroundEventStream.events() {
                if let revert = self.eventAdapter.sessionRevertUpdate(from: event) {
                    await MainActor.run { self.commands.applyRevertUpdate(revert) }
                    continue
                }
                guard event.type == "worktree.updated", let projectID = event.namedProjectID else { continue }
                let known = await MainActor.run { self.checkoutProjectIDs.contains(projectID) }
                guard known else { continue }
                await MainActor.run { self.refreshProjectIdentity() }
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

    private func beginOptimisticCreate() {
        if optimisticCreateTask != nil || !OptimisticSessionCreate.isPlaceholder(sessionID) {
            return
        }
        OpenCodeLog.sessionChat.info("optimistic create start worktree=\(self.worktree)")
        timeline.prepareForOptimisticCreate()
        composer.startCatalogLoads()
        publishUiState()
        optimisticCreateTask?.cancel()
        optimisticCreateTask = Task {
            do {
                let profile = activeConnectionManager.currentProfile()
                let session = try await sessionRepository.createSession(
                    parentID: nil,
                    title: nil,
                    directory: worktree
                )
                if let profile {
                    let selection = await newSessionModelSelectionResolver.resolve(
                        profile: profile,
                        worktree: worktree
                    )
                    if let selection {
                        await newSessionModelSelectionResolver.seedSession(
                            profile: profile,
                            worktree: worktree,
                            sessionID: session.id,
                            selection: selection
                        )
                    }
                }
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.info("optimistic create success id=\(session.id)")
                optimisticCreateTask = nil
                promoteOptimisticSession(id: session.id, title: session.title)
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.warning(
                    "optimistic create failed error=\(error.localizedDescription)"
                )
                optimisticCreateTask = nil
                timeline.showCreateFailed(createSessionErrorMessage(error))
                publishUiState()
            }
        }
    }

    private func createSessionErrorMessage(_ error: Error) -> String {
        guard let sessionError = error as? SessionError else {
            return error.localizedDescription
        }
        switch sessionError {
        case let .http(_, message):
            return message ?? "Unable to create session"
        case .transport:
            return "Unable to reach the server"
        case .decoding:
            return "Unable to read server response"
        }
    }

    private func promoteOptimisticSession(id: String, title: String) {
        sessionID = id
        sessionTitle = title
        expandedStepParagraphs = [:]
        resetWorkingFooter()
        sessionReadyStore.setFocusedSession(sessionID: id, directory: worktree)
        sessionViewReporter.focus(sessionId: id)
        timeline.rebind(sessionID: id)
        composer.rebind(sessionID: id)
        reviewChanges.rebind(sessionID: id)
        commands.rebind(sessionID: id)
        sessionInfo.bind(sessionID: id, sessionTitle: title)
        inbox.bind(sessionID: id)
        commands.start()
        sessionInfo.startObserving()
        startCollapsePreferenceObservation()
        timeline.startObserving()
        publishUiState()
    }
    private func publishUiState() {
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
            updateChipTimelineIntroTurnActive(isTurnActive)
        }
        switch timelineState.timeline {
        case .ready:
            offerTimelineStylePickerIfPending()
        case .creating, .empty:
            // A new or empty chat uses up the offer, so it never pops mid-turn.
            timelineStylePickerOffered = true
        case .loading, .error:
            break
        }
        let reviewChangesState = reviewChanges.state
        let repoStripState = repoStrip.state
        let visibleQuestion = inboxState.visibleQuestionRequest
        let timelineEmptyError: String? = timelineState.timeline == .error ? timelineState.errorMessage : nil
        let transientError: String? = {
            if timelineState.timeline == .error { return nil }
            if let error = infoState.lifecycleErrorMessage { return error }
            if let error = composerState.errorMessage { return error }
            if let error = composerState.abortErrorMessage { return error }
            if let error = commandsState.actionErrorMessage { return error }
            if let error = moveErrorMessage { return error }
            if let error = turnDiffNotice { return error }
            if let error = infoState.errorMessage { return error }
            if inboxState.permissionRequests.isEmpty, let error = inboxState.permissionTopologyError { return error }
            if inboxState.questionRequests.isEmpty, let error = inboxState.questionTopologyError { return error }
            return timelineState.errorMessage
        }()
        let snackbar = resolveSnackbar(
            transientError: transientError,
            successNotice: composerState.followUpNotice
                ?? commandsState.actionSuccessMessage
                ?? moveNotice
                ?? chipTimelineIntroNotice
                ?? timelineStylePickerNotice
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
        let awaitingUser = !inboxState.permissionRequests.isEmpty || !inboxState.questionRequests.isEmpty
        let lastIsThinking =
            WorkingTurnIndicator.isStreamingThinking(messages: timeline.loadedMessages())
            || WorkingTurnIndicator.lastIsThinking(messages: timelineState.messages)
        let activeWorkingLabel = WorkingTurnIndicator.labelForActiveTurn(
            statusLabel: sessionStatusLabel,
            isCompacting: isCompacting,
            isAwaitingUser: awaitingUser,
            sendProgressPercent: composerState.sendProgressPercent,
            isThinking: lastIsThinking
        )
        let workingResolution = WorkingTurnIndicator.resolveFooter(
            activeLabel: activeWorkingLabel,
            lastIsThinking: lastIsThinking,
            hideImmediately: awaitingUser || !isSessionBound,
            previous: workingChrome,
            hold: workingHold,
            nowMs: clock.nowMs()
        )
        workingChrome = workingResolution.chrome
        workingHold = workingResolution.hold
        scheduleWorkingFooterWake(workingResolution.wakeAfterMs)
        let workingIndicatorLabel = workingResolution.chrome.label
        let workingIndicatorCollapsed = workingResolution.chrome.collapsed
        let workingIndicatorFading = workingResolution.chrome.fading
        let workingLampKey =
            "\(workingIndicatorLabel ?? "none"):\(workingIndicatorCollapsed ? 1 : 0):\(workingIndicatorFading ? 1 : 0):\(workingHold != nil ? 1 : 0)"
        if !loggedWorkingLamp || lastWorkingLamp != workingLampKey {
            loggedWorkingLamp = true
            lastWorkingLamp = workingLampKey
            OpenCodeLog.sessionChat.info(
                "workingLamp label=\(workingIndicatorLabel ?? "none") collapsed=\(workingIndicatorCollapsed ? 1 : 0) fading=\(workingIndicatorFading ? 1 : 0) hold=\(workingHold != nil ? 1 : 0) status=\(sessionStatusLabel ?? "none") awaitingUser=\(awaitingUser ? 1 : 0) lastIsThinking=\(lastIsThinking ? 1 : 0)"
            )
        }
        let pendingResolution = pendingPromptsGate.resolve(
            Self.pendingPrompts(
                loaded: timeline.loadedMessages(),
                optimistic: timeline.pendingOptimisticItems()
            ),
            nowMs: clock.nowMs()
        )
        schedulePendingPromptsWake(pendingResolution.wakeAfterMs)
        uiState = SessionChatUiState(
            sessionID: infoState.sessionID.isEmpty ? sessionID : infoState.sessionID,
            sessionTitle: infoState.sessionTitle.isEmpty ? sessionTitle : infoState.sessionTitle,
            projectLabel: projectIdentity.label,
            projectLetter: projectIdentity.letter,
            projectLetterAccent: projectIdentity.accent,
            worktreeCheckout: projectIdentity.isWorktreeCheckout,
            sessionBranchPill: SessionChatBranchPillUiState.resolved(from: repoStripState),
            timeline: timelineState.timeline,
            messages: timelineState.messages,
            pendingPrompts: pendingResolution.prompts,
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
            workingIndicatorLabel: workingIndicatorLabel,
            retryCard: infoState.retryCard,
            workingIndicatorCollapsed: workingIndicatorCollapsed,
            workingIndicatorFading: workingIndicatorFading,
            syncingIndicatorLabel: timelineState.isSyncingMessages
                ? SessionMessageSyncChrome.statusLabel
                : nil,
            sessionTodos: inboxState.sessionTodos,
            sessionTodosVisible: SessionTodoList.isVisible(inboxState.sessionTodos),
            sessionTodosFinished: SessionTodoList.isFinished(inboxState.sessionTodos),
            sessionTodoActivePreview: SessionTodoList.activePreview(in: inboxState.sessionTodos),
            sessionTodoCompletedCount: SessionTodoList.completedCount(in: inboxState.sessionTodos),
            sessionTodoTotalCount: inboxState.sessionTodos.count,
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
            permissionRequest: inboxState.permissionRequests.first,
            permissionQueueCount: inboxState.permissionRequests.count,
            permissionDockHidden: inboxState.isPermissionDockHidden,
            permissionSubmitting: inboxState.permissionSubmittingID != nil,
            permissionErrorMessage: inboxState.permissionError ?? inboxState.permissionTopologyError,
            questionRequest: visibleQuestion,
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
            supportsSessionMove: activeConnectionManager.capabilities.supportsSessionMove,
            moveSheetVisible: moveSheetVisible,
            moveInFlight: moveInFlight,
            movedDirectory: movedDirectory,
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
            expandedStepParagraphs: expandedStepParagraphs,
            unfoldedStepRails: timelineState.unfoldedStepRails,
            presentedActivityCard: timelineState.presentedActivityCard,
            activityCardDetailStepId: timelineState.activityCardDetailStepId,
            childActivities: timelineState.childActivities,
            activityCardFooters: timelineState.activityCardFooters,
            debouncedStepIDs: timelineState.debouncedStepIDs,
            chipTimelineIntroVisible: chipTimelineIntroVisible,
            timelineStylePickerVisible: timelineStylePickerVisible,
            dictation: dictation
        )
    }

    /// Drops a listening card and any in-flight finishing insert. Does not touch composer text.
    private func cancelDictationCard() {
        let hadCard = dictation != nil || dictationFinishTask != nil
        discardDictationInsert()
        dictation = nil
        guard hadCard else { return }
        publishUiState()
    }

    private func discardDictationInsert() {
        dictationInsertGeneration &+= 1
        dictationFinishTask?.cancel()
        dictationFinishTask = nil
    }

    private func commitDictationInsert(generation: UInt64) {
        guard generation == dictationInsertGeneration, let current = dictation else { return }
        let transcript = current.committed + current.volatile
        let text = uiState.composer.text
        let cursor = min(max(0, uiState.composer.cursor), text.count)
        let updated = String(text.prefix(cursor)) + transcript + String(text.dropFirst(cursor))
        updateComposerText(updated, cursor: cursor + transcript.count)
        dictation = nil
        publishUiState()
    }

    private func resolveSnackbar(transientError: String?, successNotice: String?) -> SessionChatSnackbar? {
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
            tone: transientError != nil ? .error : .default
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

    private func resetWorkingFooter() {
        workingHoldTask?.cancel()
        workingHoldTask = nil
        workingHold = nil
        workingChrome = .unmounted
        pendingPromptsWakeTask?.cancel()
        pendingPromptsWakeTask = nil
        pendingPromptsGate = PendingPromptsStripGate()
    }

    private func schedulePendingPromptsWake(_ wakeAfterMs: Int64?) {
        pendingPromptsWakeTask?.cancel()
        pendingPromptsWakeTask = nil
        guard let wakeAfterMs else { return }
        pendingPromptsWakeTask = Task { [clock] in
            await clock.sleep(milliseconds: Int(wakeAfterMs))
            guard !Task.isCancelled else { return }
            publishUiState()
        }
    }

    private func scheduleWorkingFooterWake(_ wakeAfterMs: Int64?) {
        workingHoldTask?.cancel()
        workingHoldTask = nil
        guard let wakeAfterMs else { return }
        workingHoldTask = Task { [clock] in
            await clock.sleep(milliseconds: Int(wakeAfterMs))
            guard !Task.isCancelled else { return }
            publishUiState()
        }
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

    private static let pendingPromptFailure = "Couldn't update pending prompt"
    static let chipTimelineIntroEnabledNotice = "Chip timeline is on. Turn it off in Settings."
    /// Pause after history loads so the chat is on screen before the picker.
    static let timelineStylePickerDelayMs = 600

    static func timelineStylePickerNotice(_ style: SessionChatTimelineStyle) -> String {
        "Timeline: \(style.title). Change it any time in Settings."
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
