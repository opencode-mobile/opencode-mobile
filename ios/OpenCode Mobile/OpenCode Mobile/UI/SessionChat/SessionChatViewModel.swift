import Foundation
import Observation
import OSLog

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
    private let sessionRepository: any SessionRepository
    private let permissionLiveStore: any PermissionLiveStore
    private let questionLiveStore: any QuestionLiveStore
    private let sessionTodoLiveStore: any SessionTodoLiveStore
    private let sessionReadyStore: any SessionReadyStore
    private let foregroundEventStream: any ForegroundEventStream
    private var permissionTask: Task<Void, Never>?
    private var questionTask: Task<Void, Never>?
    private var todoTask: Task<Void, Never>?
    private var permissionSessions: [Session] = []
    private var permissionRequests: [PermissionRequest] = []
    private var permissionSubmittingID: String?
    private var permissionError: String?
    private var permissionTopologyError: String?
    private var questionSessions: [Session] = []
    private var questionRequests: [QuestionRequest] = []
    private var questionSubmittingID: String?
    private var questionError: String?
    private var questionTopologyError: String?
    private var questionDraftRequestID: String?
    private var questionWizardIndex = 0
    private var questionDrafts: [Int: QuestionAnswerDraft] = [:]
    private var sessionTodos: [SessionTodo] = []
    private var lastTodoLive: Bool?
    private var lastSnackbarKey: String?
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
        activeConnectionManager: ActiveConnectionManager,
        newSessionModelSelectionResolver: NewSessionModelSelectionResolver
    ) {
        self.sessionID = sessionID
        self.sessionTitle = sessionTitle
        self.worktree = worktree
        self.newSessionModelSelectionResolver = newSessionModelSelectionResolver
        self.activeConnectionManager = activeConnectionManager
        self.uiState = .initial(
            sessionID: sessionID,
            sessionTitle: sessionTitle,
            timeline: OptimisticSessionCreate.isPlaceholder(sessionID) ? .creating : .loading
        )
        self.sessionRepository = sessionRepository
        self.permissionLiveStore = permissionLiveStore ?? EmptyPermissionLiveStore()
        self.questionLiveStore = questionLiveStore ?? EmptyQuestionLiveStore()
        self.sessionTodoLiveStore = sessionTodoLiveStore ?? EmptySessionTodoLiveStore()
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
            activeConnectionManager: activeConnectionManager
        )
        self.sessionInfo = SessionInfo(
            worktree: worktree,
            sessionRepository: sessionRepository,
            sessionStatusLiveStore: sessionStatusLiveStore,
            foregroundEventStream: foregroundEventStream
        )
        self.reviewChanges = SessionChatReviewChanges(
            sessionID: sessionID,
            worktree: worktree,
            sessionRepository: sessionRepository
        )
        self.commands = SessionChatCommands(
            sessionID: sessionID,
            worktree: worktree,
            sessionRepository: sessionRepository,
            newSessionModelSelectionResolver: newSessionModelSelectionResolver,
            activeConnectionManager: activeConnectionManager
        )
        lastSnackbarKey = nil
        suppressedSnackbarKey = nil
        snackbarSequence = 0
        OpenCodeLog.sessionChat.info("bind session=\(sessionID, privacy: .public) worktree=\(worktree, privacy: .private) title=\(sessionTitle, privacy: .public)")
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
        publishUiState()
    }

    func startObserving() {
        if OptimisticSessionCreate.isPlaceholder(sessionID) {
            beginOptimisticCreate()
            return
        }
        OpenCodeLog.sessionChat.info("start observing session=\(self.sessionID, privacy: .public)")
        sessionReadyStore.setFocusedSession(sessionID: sessionID, directory: worktree)
        composer.startCatalogLoads()
        commands.start()
        sessionInfo.startObserving()
        timeline.startObserving()
        startPermissionObservation()
        startQuestionObservation()
        startTodoObservation()
    }

    func loadMessages() async {
        if OptimisticSessionCreate.isPlaceholder(sessionID) {
            await optimisticCreateTask?.value
            guard !OptimisticSessionCreate.isPlaceholder(sessionID) else { return }
        }
        async let catalogLoad: Void = composer.loadCatalog()
        _ = await catalogLoad
        await waitForTimelineResolution()
        await sessionInfo.refreshStatus()
    }

    func refreshMessages() async {
        await timeline.refreshMessages()
        composer.loadServerCommands(force: true)
        composer.loadMentionableAgents(force: true)
    }

    func archiveSession() {
        sessionInfo.archiveSession()
    }

    func deleteSession() {
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
        case .compact:
            guard let model = composer.state.composer.selectedModel else { return }
            let beforeFraction = uiState.contextUsageFraction.map { Float($0) }
            commands.runCompact(
                providerID: model.option.providerID,
                modelID: model.option.modelID,
                beforeContextFraction: beforeFraction,
                awaitAfterContextFraction: { [weak self] in
                    guard let self else { return nil }
                    // SSE already streamed the compacted transcript. Do not await a full
                    // refreshDisplayMessages + MainActor timeline rebuild here — that froze
                    // Session Chat with Compacting… still showing until kill/relaunch.
                    await self.sessionInfo.refreshStatus()
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
        permissionTopologyError = nil
        questionTopologyError = nil
        publishUiState()
    }

    func openRenameSession() {
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

    func sendPrompt() async {
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
        guard permissionRequests.isEmpty, questionRequests.isEmpty else {
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
                self.sessionReadyStore.notifyPromptSent(sessionID: self.sessionID, directory: self.worktree)
                await self.sessionInfo.refreshStatus()
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
            }
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
    }

    func allowPermissionOnce(requestID: String, sessionID: String) async {
        logChatAction(
            ProductAnalytics.Action.permissionAllowOnce,
            parameters: [ProductAnalytics.Param.permissionDecision: "allow_once"]
        )
        await respondToPermission(requestID: requestID, sessionID: sessionID, decision: .allowOnce)
    }

    func denyPermission(requestID: String, sessionID: String) async {
        logChatAction(
            ProductAnalytics.Action.permissionDeny,
            parameters: [ProductAnalytics.Param.permissionDecision: "deny"]
        )
        await respondToPermission(requestID: requestID, sessionID: sessionID, decision: .deny)
    }

    func toggleQuestionOption(requestID: String, sessionID: String, label: String) {
        guard let request = visibleQuestionRequest(),
              request.id == requestID, request.sessionID == sessionID, questionSubmittingID == nil,
              request.questions.indices.contains(questionWizardIndex)
        else { return }
        logChatAction(ProductAnalytics.Action.questionToggleOption)
        let question = request.questions[questionWizardIndex]
        let draft = questionDrafts[questionWizardIndex] ?? QuestionAnswerDraft()
        let selected = question.multiple
            ? (draft.selectedLabels.contains(label) ? draft.selectedLabels.filter { $0 != label } : draft.selectedLabels + [label])
            : [label]
        questionDrafts[questionWizardIndex] = QuestionAnswerDraft(
            selectedLabels: selected, customText: draft.customText, customSelected: false
        )
        publishUiState()
    }

    func toggleQuestionCustom(requestID: String, sessionID: String) {
        guard let request = visibleQuestionRequest(),
              request.id == requestID, request.sessionID == sessionID, questionSubmittingID == nil,
              request.questions.indices.contains(questionWizardIndex),
              request.questions[questionWizardIndex].custom
        else { return }
        logChatAction(ProductAnalytics.Action.questionToggleCustom)
        var draft = questionDrafts[questionWizardIndex] ?? QuestionAnswerDraft()
        draft.customSelected.toggle()
        if draft.customSelected { draft.selectedLabels = [] }
        questionDrafts[questionWizardIndex] = draft
        publishUiState()
    }

    func updateQuestionCustomText(requestID: String, sessionID: String, text: String) {
        guard let request = visibleQuestionRequest(),
              request.id == requestID, request.sessionID == sessionID, questionSubmittingID == nil
        else { return }
        // Once per question step; the typed answer never becomes a parameter.
        let editKey = "\(requestID)#\(questionWizardIndex)"
        if loggedQuestionCustomTextEdit != editKey {
            loggedQuestionCustomTextEdit = editKey
            logChatAction(ProductAnalytics.Action.questionEditCustomText)
        }
        questionDrafts[questionWizardIndex] = QuestionAnswerDraft(selectedLabels: [], customText: text, customSelected: true)
        publishUiState()
    }

    func questionBack(requestID: String, sessionID: String) {
        guard visibleQuestionRequest()?.id == requestID, visibleQuestionRequest()?.sessionID == sessionID,
              questionSubmittingID == nil, questionWizardIndex > 0 else { return }
        logChatAction(ProductAnalytics.Action.questionBack)
        questionWizardIndex -= 1
        publishUiState()
    }

    func questionNext(requestID: String, sessionID: String) {
        guard let request = visibleQuestionRequest(), request.id == requestID, request.sessionID == sessionID,
              questionSubmittingID == nil, request.questions.indices.contains(questionWizardIndex),
              isQuestionAnswerValid(question: request.questions[questionWizardIndex], draft: questionDrafts[questionWizardIndex] ?? QuestionAnswerDraft()),
              questionWizardIndex < request.questions.count - 1 else { return }
        logChatAction(ProductAnalytics.Action.questionNext)
        questionWizardIndex += 1
        publishUiState()
    }

    func submitQuestion(requestID: String, sessionID: String) async {
        guard let request = questionRequests.first(where: { $0.id == requestID && $0.sessionID == sessionID }),
              questionSubmittingID == nil, let answers = buildQuestionAnswers(questions: request.questions, drafts: questionDrafts)
        else { return }
        // Shape only: whether custom text was used, never the answers themselves.
        let usedCustomAnswer = questionDrafts.values.contains { $0.customSelected }
        logChatAction(
            ProductAnalytics.Action.questionSubmit,
            parameters: [
                ProductAnalytics.Param.questionResponse: usedCustomAnswer ? "custom" : "option",
            ]
        )
        questionSubmittingID = request.id
        questionError = nil
        OpenCodeLog.sessionChat.info("question reply request=\(request.id, privacy: .public) session=\(request.sessionID, privacy: .public)")
        publishUiState()
        do {
            _ = try await sessionRepository.replyToQuestion(questionID: request.id, answers: answers, directory: worktree)
        } catch {
            await handleQuestionMutationFailure(requestID: request.id, error: error, dismiss: false)
        }
    }

    func dismissQuestion(requestID: String, sessionID: String) async {
        guard let request = questionRequests.first(where: { $0.id == requestID && $0.sessionID == sessionID }),
              questionSubmittingID == nil else { return }
        logChatAction(ProductAnalytics.Action.questionDismiss)
        questionSubmittingID = request.id
        questionError = nil
        OpenCodeLog.sessionChat.info("question dismiss request=\(request.id, privacy: .public) session=\(request.sessionID, privacy: .public)")
        publishUiState()
        do {
            _ = try await sessionRepository.rejectQuestion(questionID: request.id, directory: worktree)
        } catch {
            await handleQuestionMutationFailure(requestID: request.id, error: error, dismiss: true)
        }
    }

    private func startPermissionObservation() {
        permissionTask?.cancel()
        permissionTask = Task {
            for await requests in permissionLiveStore.observePermissions(directory: worktree) {
                await applyPermissionSnapshot(requests)
            }
        }
    }

    private func startQuestionObservation() {
        questionTask?.cancel()
        questionTask = Task {
            for await requests in questionLiveStore.observeQuestions(directory: worktree) {
                await applyQuestionSnapshot(requests)
            }
        }
    }

    private func startTodoObservation() {
        todoTask?.cancel()
        todoTask = Task {
            for await todos in sessionTodoLiveStore.observeTodos(sessionID: sessionID, directory: worktree) {
                sessionTodos = todos
                publishUiState()
            }
        }
    }

    private func respondToPermission(
        requestID: String,
        sessionID: String,
        decision: PermissionDecision
    ) async {
        guard isConnectionLive else { return }
        guard let request = permissionRequests.first(where: { $0.id == requestID && $0.sessionID == sessionID }),
              permissionSubmittingID == nil else { return }
        permissionSubmittingID = request.id
        permissionError = nil
        publishUiState()
        do {
            _ = try await sessionRepository.respondToPermission(
                sessionID: request.sessionID,
                permissionID: request.id,
                decision: decision,
                directory: worktree
            )
        } catch {
            permissionSubmittingID = nil
            if case let .http(statusCode, _) = error as? SessionError, statusCode == 404 {
                let refreshed = await refreshPermissionSnapshot()
                if refreshed, !permissionRequests.contains(where: { $0.id == request.id }) {
                    permissionError = nil
                    publishUiState()
                    return
                }
                permissionError = "Permission request is no longer available"
            } else {
                permissionError = error.localizedDescription
            }
            publishUiState()
        }
    }

    private func refreshPermissionSnapshot() async -> Bool {
        do {
            let requests = try await sessionRepository.listPendingPermissions(directory: worktree)
            await applyPermissionSnapshot(requests)
            return true
        } catch {
            return false
        }
    }

    private func refreshQuestionSnapshot() async -> Bool {
        do {
            await applyQuestionSnapshot(try await sessionRepository.listPendingQuestions(directory: worktree))
            return true
        } catch {
            return false
        }
    }

    private func applyPermissionSnapshot(_ requests: [PermissionRequest]) async {
        do {
            let sessions = try await sessionRepository.listSessions(directory: worktree)
            permissionSessions = sessions
            permissionTopologyError = nil
        } catch {
            permissionSessions = []
            permissionTopologyError = "Unable to refresh permission session tree"
        }
        permissionRequests = Self.orderedPermissionRequests(
            rootSessionID: sessionID,
            sessions: permissionSessions,
            requests: requests
        )
        if let submitting = permissionSubmittingID, !permissionRequests.contains(where: { $0.id == submitting }) {
            permissionSubmittingID = nil
            permissionError = nil
        }
        publishUiState()
    }

    private func applyQuestionSnapshot(_ requests: [QuestionRequest]) async {
        do {
            questionSessions = try await sessionRepository.listSessions(directory: worktree)
            questionTopologyError = nil
        } catch {
            questionSessions = []
            questionTopologyError = "Unable to refresh question session tree"
        }
        let previousVisibleID = questionRequests.first?.id
        questionRequests = dropOrphanQuestionRequests(
            orderedQuestionRequests(rootSessionID: sessionID, sessions: questionSessions, requests: requests),
            messages: timeline.loadedMessages()
        )
        syncQuestionDraftState()
        if let submitting = questionSubmittingID, !questionRequests.contains(where: { $0.id == submitting }) {
            questionSubmittingID = nil
            questionError = nil
        }
        let visibleID = questionRequests.first?.id
        if visibleID != previousVisibleID {
            OpenCodeLog.sessionChat.info("question visible=\(visibleID ?? "none", privacy: .public) queue=\(self.questionRequests.count, privacy: .public)")
        }
        publishUiState()
    }

    private func handleQuestionMutationFailure(requestID: String, error: Error, dismiss: Bool) async {
        if questionSubmittingID == requestID {
            questionSubmittingID = nil
        }
        if case let .http(statusCode, _) = error as? SessionError, statusCode == 404 {
            if await refreshQuestionSnapshot(), !questionRequests.contains(where: { $0.id == requestID }) {
                questionError = nil
                publishUiState()
                return
            }
            if visibleQuestionRequest()?.id == requestID {
                questionError = "Question request is no longer available"
            }
        } else if visibleQuestionRequest()?.id == requestID {
            questionError = error.localizedDescription.isEmpty
                ? (dismiss ? "Question dismiss failed" : "Question response failed")
                : error.localizedDescription
        }
        publishUiState()
    }

    private func syncQuestionDraftState() {
        guard let visible = questionRequests.first else {
            questionDraftRequestID = nil
            questionWizardIndex = 0
            questionDrafts.removeAll()
            return
        }
        if questionDraftRequestID != visible.id {
            questionDraftRequestID = visible.id
            questionWizardIndex = 0
            questionDrafts.removeAll()
        } else {
            questionWizardIndex = min(max(questionWizardIndex, 0), max(0, visible.questions.count - 1))
        }
    }

    private var isConnectionLive: Bool {
        // Prefer the live event-stream state over SessionInfo's mirrored status so
        // mutations are not blocked while connection observation is still catching up.
        foregroundEventStream.connectionState == .connected
    }

    private func visibleQuestionRequest() -> QuestionRequest? {
        permissionRequests.isEmpty ? questionRequests.first : nil
    }

    private func refilterQuestionOrphans() {
        let filtered = dropOrphanQuestionRequests(questionRequests, messages: timeline.loadedMessages())
        guard filtered != questionRequests else { return }
        questionRequests = filtered
        syncQuestionDraftState()
        if let submitting = questionSubmittingID, !questionRequests.contains(where: { $0.id == submitting }) {
            questionSubmittingID = nil
            questionError = nil
        }
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

    func stopObserving() {
        OpenCodeLog.sessionChat.info("stop observing session=\(self.sessionID, privacy: .public)")
        optimisticCreateTask?.cancel()
        optimisticCreateTask = nil
        timeline.stopObserving()
        sessionInfo.stopObserving()
        permissionTask?.cancel()
        questionTask?.cancel()
        todoTask?.cancel()
        sessionTodoLiveStore.setLive(false, sessionID: sessionID, directory: worktree)
        sessionReadyStore.setFocusedSession(sessionID: nil, directory: nil)
        reviewChanges.stop()
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
        OpenCodeLog.sessionChat.info("optimistic create start worktree=\(self.worktree, privacy: .private)")
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
                OpenCodeLog.sessionChat.info("optimistic create success id=\(session.id, privacy: .public)")
                optimisticCreateTask = nil
                promoteOptimisticSession(id: session.id, title: session.title)
            } catch {
                guard !Task.isCancelled else { return }
                OpenCodeLog.sessionChat.warning(
                    "optimistic create failed error=\(error.localizedDescription, privacy: .public)"
                )
                optimisticCreateTask = nil
                OptimisticSessionCreateCoordinator.shared.reportCreateFailed(
                    error.localizedDescription
                )
                sessionInfo.requestLeaveSession()
                publishUiState()
            }
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
        commands.start()
        sessionInfo.startObserving()
        timeline.startObserving()
        startPermissionObservation()
        startQuestionObservation()
        startTodoObservation()
        publishUiState()
    }
    private func publishUiState() {
        refilterQuestionOrphans()
        let timelineState = timeline.state
        let composerState = composer.state
        let infoState = sessionInfo.state
        let commandsState = commands.state
        let isCompacting = effectiveCompacting(
            localCompacting: commandsState.isCompacting,
            sessionStatusLabel: infoState.sessionStatusLabel
        )
        let isLive =
            isCompacting
            || infoState.sessionStatusLabel != nil
            || !permissionRequests.isEmpty
            || !questionRequests.isEmpty
        if lastTodoLive != isLive {
            lastTodoLive = isLive
            sessionTodoLiveStore.setLive(isLive, sessionID: sessionID, directory: worktree)
        }
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
        commands.updateVisibility(
            messages: timelineState.messages,
            hasSelectedComposerModel: composerState.composer.selectedModel != nil
        )
        let reviewChangesState = reviewChanges.state
        let visibleQuestion = visibleQuestionRequest()
        let timelineEmptyError: String? = timelineState.timeline == .error ? timelineState.errorMessage : nil
        let transientError: String? = {
            if timelineState.timeline == .error { return nil }
            if let error = infoState.lifecycleErrorMessage { return error }
            if let error = composerState.errorMessage { return error }
            if let error = composerState.abortErrorMessage { return error }
            if let error = commandsState.actionErrorMessage { return error }
            if let error = infoState.errorMessage { return error }
            if permissionRequests.isEmpty, let error = permissionTopologyError { return error }
            if questionRequests.isEmpty, let error = questionTopologyError { return error }
            return timelineState.errorMessage
        }()
        let snackbar = resolveSnackbar(
            transientError: transientError,
            successNotice: commandsState.actionSuccessMessage
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
        uiState = SessionChatUiState(
            sessionID: infoState.sessionID.isEmpty ? sessionID : infoState.sessionID,
            sessionTitle: infoState.sessionTitle.isEmpty ? sessionTitle : infoState.sessionTitle,
            timeline: timelineState.timeline,
            messages: timelineState.messages,
            timelineFrame: timelineFrame,
            composer: composer.state.composer,
            isSending: composerState.isSending,
            isRefreshing: timelineState.isRefreshing,
            isAborting: composerState.isAborting,
            sessionStatusLabel: sessionStatusLabel,
            workingIndicatorLabel: WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: sessionStatusLabel,
                messages: timelineState.messages,
                isCompacting: isCompacting
            ),
            syncingIndicatorLabel: timelineState.isSyncingMessages
                ? SessionMessageSyncChrome.statusLabel
                : nil,
            sessionTodos: sessionTodos,
            sessionTodosVisible: SessionTodoList.isVisible(todos: sessionTodos, isLive: isLive),
            sessionTodosFinished: SessionTodoList.isFinished(sessionTodos),
            sessionTodoActivePreview: SessionTodoList.activePreview(in: sessionTodos),
            sessionTodoCompletedCount: SessionTodoList.completedCount(in: sessionTodos),
            sessionTodoTotalCount: sessionTodos.count,
            connectionStatus: infoState.connectionStatus,
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
            permissionRequest: permissionRequests.first,
            permissionQueueCount: permissionRequests.count,
            permissionSubmitting: permissionSubmittingID != nil,
            permissionErrorMessage: permissionError ?? permissionTopologyError,
            questionRequest: visibleQuestion,
            questionWizardIndex: questionWizardIndex,
            questionSelectedLabels: questionDrafts[questionWizardIndex]?.selectedLabels ?? [],
            questionCustomText: questionDrafts[questionWizardIndex]?.customText ?? "",
            questionCustomSelected: questionDrafts[questionWizardIndex]?.customSelected ?? false,
            questionSubmitting: questionSubmittingID != nil && questionSubmittingID == visibleQuestion?.id,
            questionErrorMessage: visibleQuestion != nil
                ? (questionError ?? questionTopologyError)
                : nil,
            reviewChangesMessageID: reviewChangesState.messageID,
            reviewChangesSummary: reviewChangesState.summary,
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
            pendingSessionReplace: commandsState.pendingSessionReplace
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
