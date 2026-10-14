import Foundation

struct V1SessionRemote: SessionRemote, Sendable {
    private let api: SessionsAPIClient
    private let adapter: SessionDomainAdapter

    init(api: SessionsAPIClient, adapter: SessionDomainAdapter) {
        self.api = api
        self.adapter = adapter
    }

    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest] {
        try await api.listPendingPermissions(directory: directory).map { adapter.permissionRequest(from: $0) }
    }

    func listPendingQuestions(directory: String?) async throws -> [QuestionRequest] {
        try await api.listPendingQuestions(directory: directory).map { adapter.questionRequest(from: $0) }
    }

    func listSessions(directory: String?) async throws -> [Session] {
        try await api.listSessions(directory: directory).map { adapter.session(from: $0) }
    }

    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session {
        adapter.session(from: try await api.createSession(parentID: parentID, title: title, directory: directory))
    }

    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] {
        try await api.getAllSessionStatuses(directory: directory).map { adapter.sessionStatus(sessionID: $0.key, raw: $0.value) }
    }

    func getSession(id: String, directory: String?) async throws -> Session {
        adapter.session(from: try await api.getSession(id: id, directory: directory))
    }

    func updateSession(id: String, title: String?, directory: String?) async throws -> Session {
        adapter.session(from: try await api.updateSession(id: id, title: title, directory: directory))
    }

    func archiveSession(id: String, directory: String?) async throws -> Session {
        adapter.session(from: try await api.archiveSession(id: id, directory: directory))
    }

    func deleteSession(id: String, directory: String?) async throws -> Bool {
        try await api.deleteSession(id: id, directory: directory)
    }

    func listChildSessions(id: String) async throws -> [Session] {
        try await api.listChildSessions(id: id).map { adapter.session(from: $0) }
    }

    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo] {
        adapter.todos(from: try await api.listSessionTodos(id: id, directory: directory))
    }

    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool {
        try await api.initializeSessionAgents(
            id: id,
            body: InitSessionRequestDTO(messageID: messageID, providerID: providerID, modelID: modelID)
        )
    }

    func forkSession(id: String, atMessageID: String?) async throws -> Session {
        adapter.session(from: try await api.forkSession(id: id, messageID: atMessageID))
    }

    func abortSession(id: String, directory: String?) async throws -> Bool {
        try await api.abortSession(id: id, directory: directory)
    }

    func shareSession(id: String) async throws -> Session {
        adapter.session(from: try await api.shareSession(id: id))
    }

    func unshareSession(id: String) async throws -> Session {
        adapter.session(from: try await api.unshareSession(id: id))
    }

    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] {
        try await api.getSessionDiff(id: id, messageID: messageID, directory: directory).map(adapter.fileDiff)
    }

    func getVcsInfo(directory: String?) async throws -> VcsInfo {
        adapter.vcsInfo(from: try await api.getVcsInfo(directory: directory))
    }

    func getVcsStatus(directory: String?) async throws -> [VcsFileStatus] {
        try await api.getVcsStatus(directory: directory).map(adapter.vcsFileStatus)
    }

    func getVcsDiff(mode: VcsDiffMode, directory: String?, context: Int) async throws -> [FileDiff] {
        try await api.getVcsDiff(mode: mode, directory: directory, context: context).map(adapter.fileDiff)
    }

    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool {
        try await api.summarizeSession(
            id: id,
            body: SummarizeSessionRequestDTO(providerID: providerID, modelID: modelID)
        )
    }

    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool {
        try await api.revertSession(id: id, body: RevertSessionRequestDTO(messageID: messageID, partID: partID))
    }

    func unrevertSession(id: String) async throws -> Bool {
        try await api.unrevertSession(id: id)
    }

    func respondToPermission(
        sessionID: String,
        permissionID: String,
        decision: PermissionDecision,
        directory: String?
    ) async throws -> Bool {
        try await api.respondToPermission(
            sessionID: sessionID,
            permissionID: permissionID,
            body: PermissionResponseRequestDTO(reply: adapter.permissionResponse(decision)),
            directory: directory
        )
    }

    func replyToQuestion(questionID: String, answers: [[String]], directory: String?) async throws -> Bool {
        try await replyToQuestion(questionID: questionID, answers: answers, directory: directory, sessionID: nil)
    }

    func replyToQuestion(
        questionID: String,
        answers: [[String]],
        directory: String?,
        sessionID _: String?
    ) async throws -> Bool {
        try await api.replyToQuestion(
            questionID: questionID,
            body: QuestionReplyRequestDTO(answers: answers),
            directory: directory
        )
    }

    func rejectQuestion(questionID: String, directory: String?) async throws -> Bool {
        try await rejectQuestion(questionID: questionID, directory: directory, sessionID: nil)
    }

    func rejectQuestion(questionID: String, directory: String?, sessionID _: String?) async throws -> Bool {
        try await api.rejectQuestion(questionID: questionID, directory: directory)
    }

    func cancelPendingPrompt(sessionID _: String, inboxID _: String) async throws -> Bool { false }

    func steerPendingPrompt(sessionID _: String, inboxID _: String) async throws -> Bool { false }

    func queuePendingPrompt(sessionID _: String, inboxID _: String) async throws -> Bool { false }

    /// Classic servers have no viewed watermark; never touches the network.
    func markSessionViewed(sessionId _: String, idleAtEpochMs _: Int64) async throws {}
}
