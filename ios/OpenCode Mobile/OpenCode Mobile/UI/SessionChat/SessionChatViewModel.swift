import Foundation
import Observation
import OSLog

@MainActor
@Observable
final class SessionChatViewModel {
    private(set) var uiState: SessionChatUiState

    private let sessionID: String
    private let sessionTitle: String
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
        self.uiState = .initial(sessionID: sessionID, sessionTitle: sessionTitle)
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
        OpenCodeLog.sessionChat.info("start observing session=\(self.sessionID, privacy: .public)")
        sessionReadyStore.setFocusedSession(sessionID: sessionID, directory: worktree)
        sessionInfo.startObserving()
        timeline.startObserving()
        startPermissionObservation()
        startQuestionObservation()
        startTodoObservation()
    }

    func loadMessages() async {
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
        composer.selectSlashSuggestion(commandName: commandName)
    }

    func selectAtSuggestion(agentName: String) {
        composer.selectAtSuggestion(agentName: agentName)
    }

    func refreshMentionableAgents() {
        composer.loadMentionableAgents(force: true)
    }

    func addAttachments(_ attachments: [Attachment]) {
        composer.addAttachments(attachments)
    }

    func removeAttachment(id: String) {
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
        await composer.loadCatalog()
    }

    func selectModel(_ option: ModelSelectionOption) {
        composer.selectModel(option)
    }

    func selectMode(_ mode: String) {
        composer.selectMode(mode)
    }

    func selectVariant(_ variant: String) {
        composer.selectVariant(variant)
    }

    func restoreComposerText(_ text: String) {
        composer.restoreComposerText(text)
    }

    func openCommands() {
        commands.openCommands()
        composer.dismissSuggestions()
    }

    func dismissCommands() {
        commands.dismissCommands()
    }

    func runClientSlashAction(_ action: ClientSlashAction) {
        switch action {
        case .new:
            guard isConnectionLive else { return }
            commands.runNew()
        case .fork:
            guard isConnectionLive else { return }
            commands.openForkDialog(currentTitle: uiState.sessionTitle)
        case .undo:
            commands.runUndo(
                isBusy: uiState.sessionStatusLabel != nil,
                restoreComposerText: { [weak self] text in self?.composer.restoreComposerText(text) }
            )
        case .redo:
            commands.runRedo(
                isBusy: uiState.sessionStatusLabel != nil,
                clearComposerRestore: { [weak self] in self?.composer.restoreComposerText("") }
            )
        case .compact:
            guard let model = composer.state.composer.selectedModel else { return }
            commands.runCompact(
                providerID: model.option.providerID,
                modelID: model.option.modelID,
                onSuccess: { [weak self] in
                    guard let self else { return }
                    Task {
                        await self.timeline.refreshMessages()
                        await self.sessionInfo.refreshStatus()
                    }
                }
            )
        }
    }

    func updateForkDraft(_ text: String) {
        commands.updateForkDraft(text)
    }

    func cancelForkDialog() {
        commands.cancelForkDialog()
    }

    func confirmFork() {
        guard isConnectionLive else { return }
        commands.confirmFork()
    }

    func chooseForkOpen() {
        commands.chooseForkOpen()
    }

    func chooseForkStay() {
        commands.chooseForkStay()
    }

    func consumePendingSessionReplace() {
        commands.consumePendingSessionReplace()
    }

    func consumeSnackbar() {
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
        sessionInfo.openRenameSession()
    }

    func cancelRenameSession() {
        sessionInfo.cancelRenameSession()
    }

    func updateRenameDraft(_ text: String) {
        sessionInfo.updateRenameDraft(text)
    }

    func confirmRenameSession() async {
        guard isConnectionLive else { return }
        await sessionInfo.confirmRenameSession()
    }

    func sendPrompt() async {
        guard isConnectionLive else { return }
        guard permissionRequests.isEmpty, questionRequests.isEmpty else { return }
        sessionInfo.markOptimisticBusy()
        await composer.sendPrompt(
            sessionID: sessionID,
            onOptimisticRows: { [weak self] rows in
                self?.timeline.beginOptimisticSend(rows)
            },
            onSendAccepted: { [weak self] in
                guard let self else { return }
                self.sessionReadyStore.notifyPromptSent(sessionID: self.sessionID, directory: self.worktree)
                await self.sessionInfo.refreshStatus()
                await self.timeline.reloadAfterSend(
                    onComplete: { [weak self] in self?.composer.clearSending() },
                    onReloadFailed: { [weak self] in self?.composer.clearSending() }
                )
            },
            onSendFailed: { [weak self] optimisticID, error in
                self?.sessionInfo.clearOptimisticBusy()
                self?.composer.setSendError(error)
                self?.timeline.failOptimisticSend(optimisticID: optimisticID, error: error)
            }
        )
    }

    func abortSession() async {
        guard isConnectionLive else { return }
        await composer.abortSession(timelineIsReady: timeline.state.timeline == .ready)
    }

    func allowPermissionOnce(requestID: String, sessionID: String) async {
        await respondToPermission(requestID: requestID, sessionID: sessionID, decision: .allowOnce)
    }

    func denyPermission(requestID: String, sessionID: String) async {
        await respondToPermission(requestID: requestID, sessionID: sessionID, decision: .deny)
    }

    func toggleQuestionOption(requestID: String, sessionID: String, label: String) {
        guard let request = visibleQuestionRequest(),
              request.id == requestID, request.sessionID == sessionID, questionSubmittingID == nil,
              request.questions.indices.contains(questionWizardIndex)
        else { return }
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
        questionDrafts[questionWizardIndex] = QuestionAnswerDraft(selectedLabels: [], customText: text, customSelected: true)
        publishUiState()
    }

    func questionBack(requestID: String, sessionID: String) {
        guard visibleQuestionRequest()?.id == requestID, visibleQuestionRequest()?.sessionID == sessionID,
              questionSubmittingID == nil, questionWizardIndex > 0 else { return }
        questionWizardIndex -= 1
        publishUiState()
    }

    func questionNext(requestID: String, sessionID: String) {
        guard let request = visibleQuestionRequest(), request.id == requestID, request.sessionID == sessionID,
              questionSubmittingID == nil, request.questions.indices.contains(questionWizardIndex),
              isQuestionAnswerValid(question: request.questions[questionWizardIndex], draft: questionDrafts[questionWizardIndex] ?? QuestionAnswerDraft()),
              questionWizardIndex < request.questions.count - 1 else { return }
        questionWizardIndex += 1
        publishUiState()
    }

    func submitQuestion(requestID: String, sessionID: String) async {
        guard let request = questionRequests.first(where: { $0.id == requestID && $0.sessionID == sessionID }),
              questionSubmittingID == nil, let answers = buildQuestionAnswers(questions: request.questions, drafts: questionDrafts)
        else { return }
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
        while Date() < deadline, timeline.state.timeline == .loading {
            try? await Task.sleep(for: .milliseconds(10))
        }
    }

    private func publishUiState() {
        refilterQuestionOrphans()
        let timelineState = timeline.state
        let composerState = composer.state
        let infoState = sessionInfo.state
        let isLive =
            infoState.sessionStatusLabel != nil
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
            isTurnActive: infoState.sessionStatusLabel != nil,
            isSending: composerState.isSending
        )
        commands.updateVisibility(
            messages: timelineState.messages,
            hasSelectedComposerModel: composerState.composer.selectedModel != nil
        )
        let reviewChangesState = reviewChanges.state
        let commandsState = commands.state
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
            sessionStatusLabel: infoState.sessionStatusLabel,
            workingIndicatorLabel: WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: infoState.sessionStatusLabel,
                messages: timelineState.messages
            ),
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
