import Foundation

@MainActor
final class SessionChatInbox {
    var onChange: (() -> Void)?

    private(set) var state = SessionChatInboxRegionState()

    private var sessionID: String
    private let worktree: String
    private let sessionRepository: any SessionRepository
    private let permissionLiveStore: any PermissionLiveStore
    private let questionLiveStore: any QuestionLiveStore
    private let sessionTodoLiveStore: any SessionTodoLiveStore
    private let canMutate: () -> Bool
    private let loadedMessages: () -> [Message]

    private var permissionSessions: [Session] = []
    private var questionSessions: [Session] = []
    private var questionDraftRequestID: String?
    private var permissionTask: Task<Void, Never>?
    private var questionTask: Task<Void, Never>?
    private var todoTask: Task<Void, Never>?
    private var lastTodoLive: Bool?

    init(
        sessionID: String,
        worktree: String,
        sessionRepository: any SessionRepository,
        permissionLiveStore: any PermissionLiveStore,
        questionLiveStore: any QuestionLiveStore,
        sessionTodoLiveStore: any SessionTodoLiveStore,
        canMutate: @escaping () -> Bool,
        loadedMessages: @escaping () -> [Message]
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.sessionRepository = sessionRepository
        self.permissionLiveStore = permissionLiveStore
        self.questionLiveStore = questionLiveStore
        self.sessionTodoLiveStore = sessionTodoLiveStore
        self.canMutate = canMutate
        self.loadedMessages = loadedMessages
    }

    func bind(sessionID: String) {
        if self.sessionID == sessionID {
            startObserving()
            return
        }
        stop()
        self.sessionID = sessionID
        permissionSessions = []
        questionSessions = []
        questionDraftRequestID = nil
        lastTodoLive = nil
        state = SessionChatInboxRegionState()
        startObserving()
        notifyChange()
    }

    func startObserving() {
        permissionTask?.cancel()
        permissionTask = Task {
            for await requests in permissionLiveStore.observePermissions(directory: worktree) {
                await applyPermissionSnapshot(requests)
            }
        }
        questionTask?.cancel()
        questionTask = Task {
            for await requests in questionLiveStore.observeQuestions(directory: worktree) {
                await applyQuestionSnapshot(requests)
            }
        }
        todoTask?.cancel()
        todoTask = Task { [sessionID] in
            for await todos in sessionTodoLiveStore.observeTodos(sessionID: sessionID, directory: worktree) {
                state.sessionTodos = todos
                notifyChange()
            }
        }
    }

    func stop() {
        permissionTask?.cancel()
        questionTask?.cancel()
        todoTask?.cancel()
        permissionTask = nil
        questionTask = nil
        todoTask = nil
        sessionTodoLiveStore.setLive(false, sessionID: sessionID, directory: worktree)
        lastTodoLive = false
    }

    func setLive(_ isLive: Bool) {
        guard lastTodoLive != isLive else { return }
        lastTodoLive = isLive
        sessionTodoLiveStore.setLive(isLive, sessionID: sessionID, directory: worktree)
    }

    func clearTopologyErrors() {
        state.permissionTopologyError = nil
        state.questionTopologyError = nil
        notifyChange()
    }

    func refilterOrphans() {
        let filtered = dropOrphanQuestionRequests(state.questionRequests, messages: loadedMessages())
        guard filtered != state.questionRequests else { return }
        state.questionRequests = filtered
        syncQuestionDraftState()
        if let submitting = state.questionSubmittingID, !state.questionRequests.contains(where: { $0.id == submitting }) {
            state.questionSubmittingID = nil
            state.questionError = nil
        }
    }

    func allowPermissionOnce(requestID: String, sessionID: String) async {
        await respondToPermission(requestID: requestID, sessionID: sessionID, decision: .allowOnce)
    }

    func denyPermission(requestID: String, sessionID: String) async {
        await respondToPermission(requestID: requestID, sessionID: sessionID, decision: .deny)
    }

    func toggleQuestionOption(requestID: String, sessionID: String, label: String) {
        guard let request = state.visibleQuestionRequest,
              request.id == requestID, request.sessionID == sessionID, state.questionSubmittingID == nil,
              request.questions.indices.contains(state.questionWizardIndex)
        else { return }
        let question = request.questions[state.questionWizardIndex]
        let draft = state.questionDraft
        let selected = question.multiple
            ? (draft.selectedLabels.contains(label) ? draft.selectedLabels.filter { $0 != label } : draft.selectedLabels + [label])
            : [label]
        state.questionDrafts[state.questionWizardIndex] = QuestionAnswerDraft(
            selectedLabels: selected, customText: draft.customText, customSelected: false
        )
        notifyChange()
    }

    func toggleQuestionCustom(requestID: String, sessionID: String) {
        guard let request = state.visibleQuestionRequest,
              request.id == requestID, request.sessionID == sessionID, state.questionSubmittingID == nil,
              request.questions.indices.contains(state.questionWizardIndex),
              request.questions[state.questionWizardIndex].custom
        else { return }
        var draft = state.questionDraft
        draft.customSelected.toggle()
        if draft.customSelected { draft.selectedLabels = [] }
        state.questionDrafts[state.questionWizardIndex] = draft
        notifyChange()
    }

    func updateQuestionCustomText(requestID: String, sessionID: String, text: String) {
        guard let request = state.visibleQuestionRequest,
              request.id == requestID, request.sessionID == sessionID, state.questionSubmittingID == nil
        else { return }
        state.questionDrafts[state.questionWizardIndex] = QuestionAnswerDraft(
            selectedLabels: [], customText: text, customSelected: true
        )
        notifyChange()
    }

    func questionBack(requestID: String, sessionID: String) {
        guard state.visibleQuestionRequest?.id == requestID,
              state.visibleQuestionRequest?.sessionID == sessionID,
              state.questionSubmittingID == nil, state.questionWizardIndex > 0 else { return }
        state.questionWizardIndex -= 1
        notifyChange()
    }

    func questionNext(requestID: String, sessionID: String) {
        guard let request = state.visibleQuestionRequest,
              request.id == requestID, request.sessionID == sessionID,
              state.questionSubmittingID == nil,
              request.questions.indices.contains(state.questionWizardIndex),
              isQuestionAnswerValid(
                  question: request.questions[state.questionWizardIndex],
                  draft: state.questionDraft
              ),
              state.questionWizardIndex < request.questions.count - 1
        else { return }
        state.questionWizardIndex += 1
        notifyChange()
    }

    func submitQuestion(requestID: String, sessionID: String) async {
        guard canMutate() else { return }
        guard let request = state.questionRequests.first(where: { $0.id == requestID && $0.sessionID == sessionID }),
              state.questionSubmittingID == nil,
              let answers = buildQuestionAnswers(questions: request.questions, drafts: state.questionDrafts)
        else { return }
        state.questionSubmittingID = request.id
        state.questionError = nil
        OpenCodeLog.sessionChat.info("question reply request=\(request.id) session=\(request.sessionID)")
        notifyChange()
        do {
            _ = try await sessionRepository.replyToQuestion(
                questionID: request.id,
                answers: answers,
                directory: worktree,
                sessionID: request.sessionID,
                questions: request.questions
            )
        } catch {
            await handleQuestionMutationFailure(requestID: request.id, error: error, dismiss: false)
        }
    }

    func dismissQuestion(requestID: String, sessionID: String) async {
        guard canMutate() else { return }
        guard let request = state.questionRequests.first(where: { $0.id == requestID && $0.sessionID == sessionID }),
              state.questionSubmittingID == nil else { return }
        state.questionSubmittingID = request.id
        state.questionError = nil
        OpenCodeLog.sessionChat.info("question dismiss request=\(request.id) session=\(request.sessionID)")
        notifyChange()
        do {
            _ = try await sessionRepository.rejectQuestion(
                questionID: request.id,
                directory: worktree,
                sessionID: request.sessionID
            )
        } catch {
            await handleQuestionMutationFailure(requestID: request.id, error: error, dismiss: true)
        }
    }

    var usedCustomQuestionAnswer: Bool {
        state.questionDrafts.values.contains { $0.customSelected }
    }

    private func respondToPermission(
        requestID: String,
        sessionID: String,
        decision: PermissionDecision
    ) async {
        guard canMutate() else { return }
        guard let request = state.permissionRequests.first(where: { $0.id == requestID && $0.sessionID == sessionID }),
              state.permissionSubmittingID == nil else { return }
        state.permissionSubmittingID = request.id
        state.permissionError = nil
        notifyChange()
        do {
            _ = try await sessionRepository.respondToPermission(
                sessionID: request.sessionID,
                permissionID: request.id,
                decision: decision,
                directory: worktree
            )
        } catch {
            state.permissionSubmittingID = nil
            if case let .http(statusCode, _) = error as? SessionError, statusCode == 404 {
                let refreshed = await refreshPermissionSnapshot()
                if refreshed, !state.permissionRequests.contains(where: { $0.id == request.id }) {
                    state.permissionError = nil
                    notifyChange()
                    return
                }
                state.permissionError = "Permission request is no longer available"
            } else {
                state.permissionError = error.localizedDescription
            }
            notifyChange()
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
        let needsSessionFetch = requests.contains { request in
            request.sessionID != sessionID
                && !permissionSessions.contains { $0.id == request.sessionID }
        }
        if needsSessionFetch {
            do {
                permissionSessions = try await sessionRepository.listSessions(directory: worktree)
                state.permissionTopologyError = nil
            } catch {
                permissionSessions = []
                state.permissionTopologyError = "Unable to refresh permission session tree"
            }
        } else {
            state.permissionTopologyError = nil
        }
        state.permissionRequests = SessionChatViewModel.orderedPermissionRequests(
            rootSessionID: sessionID,
            sessions: permissionSessions,
            requests: requests
        )
        if let submitting = state.permissionSubmittingID,
           !state.permissionRequests.contains(where: { $0.id == submitting }) {
            state.permissionSubmittingID = nil
            state.permissionError = nil
        }
        notifyChange()
    }

    private func applyQuestionSnapshot(_ requests: [QuestionRequest]) async {
        let needsSessionFetch = requests.contains { request in
            request.sessionID != sessionID
                && !questionSessions.contains { $0.id == request.sessionID }
        }
        if needsSessionFetch {
            do {
                questionSessions = try await sessionRepository.listSessions(directory: worktree)
                state.questionTopologyError = nil
            } catch {
                questionSessions = []
                state.questionTopologyError = "Unable to refresh question session tree"
            }
        } else {
            state.questionTopologyError = nil
        }
        let previousVisibleID = state.questionRequests.first?.id
        state.questionRequests = dropOrphanQuestionRequests(
            orderedQuestionRequests(rootSessionID: sessionID, sessions: questionSessions, requests: requests),
            messages: loadedMessages()
        )
        syncQuestionDraftState()
        if let submitting = state.questionSubmittingID,
           !state.questionRequests.contains(where: { $0.id == submitting }) {
            state.questionSubmittingID = nil
            state.questionError = nil
        }
        let visibleID = state.questionRequests.first?.id
        if visibleID != previousVisibleID {
            OpenCodeLog.sessionChat.info("question visible=\(visibleID ?? "none") queue=\(self.state.questionRequests.count)")
        }
        notifyChange()
    }

    private func handleQuestionMutationFailure(requestID: String, error: Error, dismiss: Bool) async {
        if state.questionSubmittingID == requestID {
            state.questionSubmittingID = nil
        }
        if case let .http(statusCode, _) = error as? SessionError, statusCode == 404 {
            if await refreshQuestionSnapshot(), !state.questionRequests.contains(where: { $0.id == requestID }) {
                state.questionError = nil
                notifyChange()
                return
            }
            if state.visibleQuestionRequest?.id == requestID {
                state.questionError = "Question request is no longer available"
            }
        } else if state.visibleQuestionRequest?.id == requestID {
            state.questionError = error.localizedDescription.isEmpty
                ? (dismiss ? "Question dismiss failed" : "Question response failed")
                : error.localizedDescription
        }
        notifyChange()
    }

    private func syncQuestionDraftState() {
        guard let visible = state.questionRequests.first else {
            questionDraftRequestID = nil
            state.questionWizardIndex = 0
            state.questionDrafts.removeAll()
            return
        }
        if questionDraftRequestID != visible.id {
            questionDraftRequestID = visible.id
            state.questionWizardIndex = 0
            state.questionDrafts.removeAll()
        } else {
            state.questionWizardIndex = min(max(state.questionWizardIndex, 0), max(0, visible.questions.count - 1))
        }
    }

    private func notifyChange() {
        onChange?()
    }
}
