import Foundation

struct EngineSessionRepository: SessionRepository, Sendable {
    private let opencode: any SessionRepository
    private let t3: any SessionRepository
    private let activeConnectionManager: ActiveConnectionManager

    init(
        opencode: any SessionRepository,
        t3: any SessionRepository,
        activeConnectionManager: ActiveConnectionManager
    ) {
        self.opencode = opencode
        self.t3 = t3
        self.activeConnectionManager = activeConnectionManager
    }

    private func selected() -> any SessionRepository {
        activeConnectionManager.currentProfile()?.engine == .t3 ? t3 : opencode
    }

    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest] {
        try await selected().listPendingPermissions(directory: directory)
    }

    func listPendingQuestions(directory: String?) async throws -> [QuestionRequest] {
        try await selected().listPendingQuestions(directory: directory)
    }

    func listSessions(directory: String?) async throws -> [Session] {
        try await selected().listSessions(directory: directory)
    }

    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session {
        try await selected().createSession(parentID: parentID, title: title, directory: directory)
    }

    func createSession(
        parentID: String?,
        title: String?,
        directory: String?,
        options: SessionCreateOptions
    ) async throws -> Session {
        try await selected().createSession(parentID: parentID, title: title, directory: directory, options: options)
    }

    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] {
        try await selected().getAllSessionStatuses(directory: directory)
    }

    func getSession(id: String, directory: String?) async throws -> Session {
        try await selected().getSession(id: id, directory: directory)
    }

    func updateSession(id: String, title: String?, directory: String?) async throws -> Session {
        try await selected().updateSession(id: id, title: title, directory: directory)
    }

    func archiveSession(id: String, directory: String?) async throws -> Session {
        try await selected().archiveSession(id: id, directory: directory)
    }

    func deleteSession(id: String, directory: String?) async throws -> Bool {
        try await selected().deleteSession(id: id, directory: directory)
    }

    func listChildSessions(id: String) async throws -> [Session] {
        try await selected().listChildSessions(id: id)
    }

    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo] {
        try await selected().listSessionTodos(id: id, directory: directory)
    }

    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool {
        try await selected().initializeSessionAgents(
            id: id,
            messageID: messageID,
            providerID: providerID,
            modelID: modelID
        )
    }

    func forkSession(id: String, atMessageID: String?) async throws -> Session {
        try await selected().forkSession(id: id, atMessageID: atMessageID)
    }

    func abortSession(id: String, directory: String?) async throws -> Bool {
        try await selected().abortSession(id: id, directory: directory)
    }

    func shareSession(id: String) async throws -> Session {
        try await selected().shareSession(id: id)
    }

    func unshareSession(id: String) async throws -> Session {
        try await selected().unshareSession(id: id)
    }

    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] {
        try await selected().getSessionDiff(id: id, messageID: messageID, directory: directory)
    }

    func getVcsInfo(directory: String?) async throws -> VcsInfo {
        try await selected().getVcsInfo(directory: directory)
    }

    func getVcsStatus(directory: String?) async throws -> [VcsFileStatus] {
        try await selected().getVcsStatus(directory: directory)
    }

    func getVcsDiff(mode: VcsDiffMode, directory: String?, context: Int) async throws -> [FileDiff] {
        try await selected().getVcsDiff(mode: mode, directory: directory, context: context)
    }

    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool {
        try await selected().summarizeSession(id: id, providerID: providerID, modelID: modelID)
    }

    func compactSession(id: String, providerID: String, modelID: String) async throws -> CompactionStart {
        try await selected().compactSession(id: id, providerID: providerID, modelID: modelID)
    }

    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool {
        try await selected().revertSession(id: id, messageID: messageID, partID: partID)
    }

    func unrevertSession(id: String) async throws -> Bool {
        try await selected().unrevertSession(id: id)
    }

    func respondToPermission(
        sessionID: String,
        permissionID: String,
        decision: PermissionDecision,
        directory: String?
    ) async throws -> Bool {
        try await selected().respondToPermission(
            sessionID: sessionID,
            permissionID: permissionID,
            decision: decision,
            directory: directory
        )
    }

    func replyToQuestion(questionID: String, answers: [[String]], directory: String?) async throws -> Bool {
        try await selected().replyToQuestion(questionID: questionID, answers: answers, directory: directory)
    }

    func replyToQuestion(
        questionID: String,
        answers: [[String]],
        directory: String?,
        sessionID: String?
    ) async throws -> Bool {
        try await selected().replyToQuestion(
            questionID: questionID,
            answers: answers,
            directory: directory,
            sessionID: sessionID
        )
    }

    func replyToQuestion(
        questionID: String,
        answers: [[String]],
        directory: String?,
        sessionID: String?,
        questions: [QuestionInfo]
    ) async throws -> Bool {
        try await selected().replyToQuestion(
            questionID: questionID,
            answers: answers,
            directory: directory,
            sessionID: sessionID,
            questions: questions
        )
    }

    func rejectQuestion(questionID: String, directory: String?) async throws -> Bool {
        try await selected().rejectQuestion(questionID: questionID, directory: directory)
    }

    func rejectQuestion(questionID: String, directory: String?, sessionID: String?) async throws -> Bool {
        try await selected().rejectQuestion(questionID: questionID, directory: directory, sessionID: sessionID)
    }

    func cancelPendingPrompt(sessionID: String, inboxID: String) async throws -> Bool {
        try await selected().cancelPendingPrompt(sessionID: sessionID, inboxID: inboxID)
    }

    func steerPendingPrompt(sessionID: String, inboxID: String) async throws -> Bool {
        try await selected().steerPendingPrompt(sessionID: sessionID, inboxID: inboxID)
    }

    func queuePendingPrompt(sessionID: String, inboxID: String) async throws -> Bool {
        try await selected().queuePendingPrompt(sessionID: sessionID, inboxID: inboxID)
    }

    func settleSession(id: String, directory: String?) async throws -> Bool {
        try await selected().settleSession(id: id, directory: directory)
    }

    func listArchivedSessions(directory: String?) async throws -> [Session] {
        try await selected().listArchivedSessions(directory: directory)
    }

    func unarchiveSession(id: String, directory: String?) async throws -> Session {
        try await selected().unarchiveSession(id: id, directory: directory)
    }

    func unsettleSession(id: String, directory: String?) async throws -> Bool {
        try await selected().unsettleSession(id: id, directory: directory)
    }

    func setRuntimeMode(id: String, runtimeMode: String, directory: String?) async throws -> Session {
        try await selected().setRuntimeMode(id: id, runtimeMode: runtimeMode, directory: directory)
    }

    func setModelSelection(id: String, providerInstanceID: String, modelID: String, directory: String?) async throws -> Session {
        try await selected().setModelSelection(
            id: id,
            providerInstanceID: providerInstanceID,
            modelID: modelID,
            directory: directory
        )
    }
}
