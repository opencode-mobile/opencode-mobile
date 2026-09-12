import Foundation
import Observation

@MainActor
@Observable
final class SessionChatViewModel {
    private(set) var uiState: SessionChatUiState

    private var sessionID: String
    private var sessionTitle: String
    private let worktree: String

    private let timeline: SessionChatTimeline
    private let composer: SessionChatComposer
    private let sessionInfo: SessionInfo
    private let reviewChanges: SessionChatReviewChanges
    private let commands: SessionChatCommands
    private let inbox: SessionChatInbox
    private let sessionRepository: any SessionRepository
    private let sessionReadyStore: any SessionReadyStore
    private let foregroundEventStream: any ForegroundEventStream
    /// Invalidates in-flight `withObservationTracking` for the collapse preference.
    private var collapsePreferenceGeneration = 0
    private var lastSnackbarKey: String?
    private var loggedWorkingLamp = false
    private var lastWorkingLamp: String?
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
    private var projectLabel: String?
    private var projectLetter: Character?
    private var projectLetterAccent: LetterAccent?
    private var projectIdentityTask: Task<Void, Never>?

    init(
        sessionID: String,
        sessionTitle: String,
        worktree: String,
        messageRepository: any MessageRepository,
        modelRepository: any ModelRepository,
        commandRepository: any CommandRepository,
        agentRepository: any AgentRepository,
        sessionRepository: any SessionRepository,
        permissionLiveStore: (any PermissionLiveStore)? = nil,
        questionLiveStore: (any QuestionLiveStore)? = nil,
        sessionTodoLiveStore: (any SessionTodoLiveStore)? = nil,
        sessionReadyStore: (any SessionReadyStore)? = nil,
        sessionStatusLiveStore: any SessionStatusLiveStore,
        foregroundEventStream: any ForegroundEventStream,
        sessionModelSelectionStore: any SessionModelSelectionStore,
        composerDraftStore: any ComposerDraftStore,
        activeConnectionManager: ActiveConnectionManager,
        connectionProfileStore: any ConnectionProfileStore,
        newSessionModelSelectionResolver: NewSessionModelSelectionResolver,
        liveUpdates: any LiveUpdates = FixedLiveUpdates(),
        lastTurnReviewChangesStore: LastTurnReviewChangesStore? = nil
    ) {
        self.sessionID = sessionID
        self.sessionTitle = sessionTitle
        self.worktree = worktree
        self.newSessionModelSelectionResolver = newSessionModelSelectionResolver
        self.activeConnectionManager = activeConnectionManager
        self.connectionProfileStore = connectionProfileStore
        self.liveUpdates = liveUpdates
        self.uiState = .initial(
            sessionID: sessionID,
            sessionTitle: sessionTitle,
            timeline: OptimisticSessionCreate.isPlaceholder(sessionID) ? .creating : .loading
        )
        self.sessionRepository = sessionRepository
        self.sessionReadyStore = sessionReadyStore ?? EmptySessionReadyStore()
        self.foregroundEventStream = foregroundEventStream
        self.timeline = SessionChatTimeline(
            sessionID: sessionID,
            worktree: worktree,
            messageRepository: messageRepository
        )
        self.composer = SessionChatComposer(
            sessionID: sessionID,
            worktree: worktree,
            messageRepository: messageRepository,
            modelRepository: modelRepository,
            commandRepository: commandRepository,
            agentRepository: agentRepository,
            sessionRepository: sessionRepository,
            sessionModelSelectionStore: sessionModelSelectionStore,
            composerDraftStore: composerDraftStore,
            activeConnectionManager: activeConnectionManager
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
            sessionRepository: sessionRepository,
            supportsSessionMessageDiff: { activeConnectionManager.capabilities.supportsSessionMessageDiff },
            lastTurnReviewChangesStore: lastTurnReviewChangesStore ?? LastTurnReviewChangesStore()
        )
        self.commands = SessionChatCommands(
            sessionID: sessionID,
            worktree: worktree,
            sessionRepository: sessionRepository,
            newSessionModelSelectionResolver: newSessionModelSelectionResolver,
            activeConnectionManager: activeConnectionManager
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
        commands.onChange = { [weak self] in self?.publishUiState() }
        inbox.onChange = { [weak self] in self?.publishUiState() }
        refreshProjectIdentity()
        publishUiState()
    }

    func startObserving() {
        startCollapsePreferenceObservation()
        if OptimisticSessionCreate.isPlaceholder(sessionID) {
            beginOptimisticCreate()
            return
        }
        OpenCodeLog.sessionChat.info("start observing session=\(self.sessionID)")
        sessionReadyStore.setFocusedSession(sessionID: sessionID, directory: worktree)
        // Peers may have changed while the screen was away; re-resolve smart label.
        refreshProjectIdentity()
        composer.startCatalogLoads()
        commands.start()
        sessionInfo.startObserving()
        timeline.startObserving()
        inbox.startObserving()
    }

    func loadMessages() async {
        if OptimisticSessionCreate.isPlaceholder(sessionID) {
            await optimisticCreateTask?.value
            guard !OptimisticSessionCreate.isPlaceholder(sessionID) else { return }
        }
        async let catalogLoad: Void = composer.loadCatalog()
        _ = await catalogLoad
        await waitForTimelineResolution()
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

    func selectSlashSuggestion(commandName: String) {
        logChatAction(ProductAnalytics.Action.composerSelectSlashSuggestion)
        composer.selectSlashSuggestion(commandName: commandName)
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
        await composer.loadCatalog()
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

    func runClientSlashAction(_ action: ClientSlashAction) {
        guard hasCreatedSession else { return }
        logCommandsAction(action)
        switch action {
        case .new:
            guard isConnectionLive else { return }
            commands.runNew()
        case .fork:
            guard isConnectionLive else { return }
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.sessionFork,
                presentation: ProductAnalytics.Presentation.dialog,
                trigger: ProductAnalytics.Trigger.present
            )
            loggedForkTitleEdit = false
            commands.openForkDialog(currentTitle: uiState.sessionTitle)
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
        case .compact:
            guard let model = composer.state.composer.selectedModel else { return }
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
            }
        ProductAnalytics.logUserAction(
            screenId: ProductAnalytics.Screen.sessionCommands,
            actionId: actionId,
            parameters: [ProductAnalytics.Param.clientSlash: action.rawValue]
        )
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

    func consumePendingSessionReplace() {
        commands.consumePendingSessionReplace()
    }

    func consumeSnackbar() {
        if uiState.snackbar != nil {
            logChatAction(ProductAnalytics.Action.chatDismissSnackbar)
        }
        suppressedSnackbarKey = lastSnackbarKey
        commands.clearActionFeedback()
        composer.clearTransientErrors()
        sessionInfo.clearTransientErrors()
        timeline.clearReadyError()
        inbox.clearTopologyErrors()
        publishUiState()
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

    func toggleFollowUp() {
        guard activeConnectionManager.capabilities.supportsFollowUpDelivery else { return }
        composer.toggleFollowUp()
    }

    func sendPrompt() async {
        guard hasCreatedSession else { return }
        let hasAttachments = !composer.state.composer.attachments.isEmpty
        guard isConnectionLive else {
            logChatAction(
                ProductAnalytics.Action.composerSend,
                parameters: [
                    ProductAnalytics.Param.hasAttachments: String(hasAttachments),
                    ProductAnalytics.Param.outcome: ProductAnalytics.Outcome.blocked,
                ]
            )
            return
        }
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
        await composer.sendPrompt(
            sessionID: sessionID,
            onOptimisticRows: { [weak self] rows in
                DiagnosticLog.info(.chat, "sendPrompt optimistic rows=\(rows.count)")
                // Mark only after composer passes early-return checks.
                self?.sessionInfo.markOptimisticBusy()
                self?.timeline.beginOptimisticSend(rows)
            },
            onSendAccepted: { [weak self] in
                guard let self else { return }
                DiagnosticLog.info(.chat, "sendPrompt accepted session=\(self.sessionID)")
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
                DiagnosticLog.warning(.chat, "sendPrompt failed error=\(error)")
                self?.sessionInfo.clearOptimisticBusy()
                self?.composer.setSendError(error)
                self?.timeline.failOptimisticSend(optimisticID: optimisticID, error: error)
            },
            isSessionBusy: sessionInfo.state.sessionStatusLabel != nil,
            supportsFollowUp: activeConnectionManager.capabilities.supportsFollowUpDelivery
        )
        DiagnosticLog.info(.chat, "sendPrompt end session=\(sessionID)")
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
            sessionInfo.markIdle()
        }
    }

    func allowPermissionOnce(requestID: String, sessionID: String) async {
        logChatAction(
            ProductAnalytics.Action.permissionAllowOnce,
            parameters: [ProductAnalytics.Param.permissionDecision: "allow_once"]
        )
        await inbox.allowPermissionOnce(requestID: requestID, sessionID: sessionID)
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
        observeCollapsePreference()
    }

    /// Re-projects an open timeline when the collapse preference flips while
    /// Session Chat is on screen. The recursive `withObservationTracking` skips
    /// work once the generation is bumped by `stopObserving`.
    private func observeCollapsePreference() {
        let generation = collapsePreferenceGeneration
        withObservationTracking {
            _ = SessionChatDisplayPreferences.shared.collapseConsecutiveToolCalls
        } onChange: { [weak self] in
            Task { @MainActor in
                guard let self, self.collapsePreferenceGeneration == generation else { return }
                self.timeline.updateCollapseConsecutiveToolCalls(
                    SessionChatDisplayPreferences.shared.collapseConsecutiveToolCalls
                )
                self.observeCollapsePreference()
            }
        }
    }

    private var isConnectionLive: Bool {
        sessionInfo.state.canMutate
    }

    private var hasCreatedSession: Bool {
        !OptimisticSessionCreate.isPlaceholder(sessionID)
    }

    func flushComposerDraft() {
        composer.flushDraft()
    }

    func stopObserving() {
        OpenCodeLog.sessionChat.info("stop observing session=\(self.sessionID)")
        composer.flushDraft()
        optimisticCreateTask?.cancel()
        optimisticCreateTask = nil
        projectIdentityTask?.cancel()
        projectIdentityTask = nil
        timeline.stopObserving()
        sessionInfo.stopObserving()
        inbox.stop()
        collapsePreferenceGeneration += 1
        sessionReadyStore.setFocusedSession(sessionID: nil, directory: nil)
        reviewChanges.stop()
    }

    private func refreshProjectIdentity() {
        projectIdentityTask?.cancel()
        guard !worktree.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            projectLabel = nil
            projectLetter = nil
            projectLetterAccent = nil
            return
        }
        let displayName = ProjectDomainAdapter.displayName(from: worktree)
        projectLabel = displayName
        projectLetter = letterCharacter(for: displayName)
        projectLetterAccent = letterAccent(for: worktree)
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
            await MainActor.run {
                self.projectLabel = smartProjectLabel(focusWorktree: worktree, peerWorktrees: peers)
                self.projectLetter = letterCharacter(for: displayName)
                self.projectLetterAccent = letterAccent(for: worktree)
                self.publishUiState()
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
        sessionReadyStore.setFocusedSession(sessionID: id, directory: worktree)
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
        let composerState = composer.state
        let infoState = sessionInfo.state
        commands.updateVisibility(
            messages: timelineState.messages,
            hasSelectedComposerModel: composerState.composer.selectedModel != nil
        )
        commands.observeLoadedMessages(timeline.loadedMessages())
        let commandsState = commands.state
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
        composer.tryApplyInitialSelection(
            timelineState: timelineState,
            historyHints: timeline.modelSelectionHistoryHints()
        )
        reviewChanges.updateCandidate(
            messages: timelineState.messages,
            hasResolvedSessionStatus: infoState.hasResolvedSessionStatus,
            isTurnActive: infoState.sessionStatusLabel != nil || isCompacting,
            isSending: composerState.isSending
        )
        let reviewChangesState = reviewChanges.state
        let visibleQuestion = inboxState.visibleQuestionRequest
        let timelineEmptyError: String? = timelineState.timeline == .error ? timelineState.errorMessage : nil
        let transientError: String? = {
            if timelineState.timeline == .error { return nil }
            if let error = infoState.lifecycleErrorMessage { return error }
            if let error = composerState.errorMessage { return error }
            if let error = composerState.abortErrorMessage { return error }
            if let error = commandsState.actionErrorMessage { return error }
            if let error = infoState.errorMessage { return error }
            if inboxState.permissionRequests.isEmpty, let error = inboxState.permissionTopologyError { return error }
            if inboxState.questionRequests.isEmpty, let error = inboxState.questionTopologyError { return error }
            return timelineState.errorMessage
        }()
        let snackbar = resolveSnackbar(
            transientError: transientError,
            successNotice: composerState.followUpNotice ?? commandsState.actionSuccessMessage
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
        let lastIsThinking = WorkingTurnIndicator.lastIsThinking(messages: timelineState.messages)
        let workingIndicatorLabel = WorkingTurnIndicator.labelForActiveTurn(
            statusLabel: sessionStatusLabel,
            isCompacting: isCompacting,
            isAwaitingUser: awaitingUser,
            sendProgressPercent: composerState.sendProgressPercent
        )
        let workingIndicatorCollapsed = WorkingTurnIndicator.shouldCollapseReservedRow(
            lastIsThinking: lastIsThinking,
            label: workingIndicatorLabel
        )
        let workingLampKey =
            "\(workingIndicatorLabel ?? "none"):\(workingIndicatorCollapsed ? 1 : 0)"
        if !loggedWorkingLamp || lastWorkingLamp != workingLampKey {
            loggedWorkingLamp = true
            lastWorkingLamp = workingLampKey
            OpenCodeLog.sessionChat.info(
                "workingLamp label=\(workingIndicatorLabel ?? "none") collapsed=\(workingIndicatorCollapsed ? 1 : 0) status=\(sessionStatusLabel ?? "none") awaitingUser=\(awaitingUser ? 1 : 0) lastIsThinking=\(lastIsThinking ? 1 : 0)"
            )
        }
        uiState = SessionChatUiState(
            sessionID: infoState.sessionID.isEmpty ? sessionID : infoState.sessionID,
            sessionTitle: infoState.sessionTitle.isEmpty ? sessionTitle : infoState.sessionTitle,
            projectLabel: projectLabel,
            projectLetter: projectLetter,
            projectLetterAccent: projectLetterAccent,
            timeline: timelineState.timeline,
            messages: timelineState.messages,
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
            workingIndicatorCollapsed: workingIndicatorCollapsed,
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
            rename: infoState.rename,
            slashSuggestionsVisible: composerState.slashSuggestionsVisible,
            slashSuggestions: composerState.slashSuggestions,
            atSuggestionsVisible: composerState.atSuggestionsVisible,
            atSuggestions: composerState.atSuggestions,
            commandsSheetVisible: commandsState.commandsSheetVisible,
            visibleClientSlashActions: commandsState.visibleActions,
            forkDialogVisible: commandsState.forkDialogVisible,
            forkDraftTitle: commandsState.forkDraftTitle,
            forkSubmitting: commandsState.forkSubmitting,
            forkErrorMessage: commandsState.forkErrorMessage,
            forkResultVisible: commandsState.forkResultVisible,
            forkedSessionTitle: commandsState.forkedSessionTitle,
            pendingSessionReplace: commandsState.pendingSessionReplace,
            pendingTerminalOpen: commandsState.pendingTerminalOpen,
            hasPendingRevert: commandsState.hasPendingRevert
        )
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
