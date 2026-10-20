import Foundation

protocol SessionRemote: SessionRepository {}

struct ProtocolSelectedSessionRemote: SessionRemote, Sendable {
    private let v1: V1SessionRemote
    private let v2: V2SessionRemote
    private let activeConnectionManager: ActiveConnectionManager?

    init(
        v1: V1SessionRemote,
        v2: V2SessionRemote,
        activeConnectionManager: ActiveConnectionManager?
    ) {
        self.v1 = v1
        self.v2 = v2
        self.activeConnectionManager = activeConnectionManager
    }

    private func selected() async -> any SessionRemote {
        if await activeConnectionManager?.currentProtocolOrProbe() == .v2 {
            return v2
        }
        return v1
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
        try await selected().initializeSessionAgents(id: id, messageID: messageID, providerID: providerID, modelID: modelID)
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

    func commitRevert(id: String) async throws -> Bool {
        try await selected().commitRevert(id: id)
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
        try await replyToQuestion(questionID: questionID, answers: answers, directory: directory, sessionID: nil)
    }

    func replyToQuestion(
        questionID: String,
        answers: [[String]],
        directory: String?,
        sessionID: String?
    ) async throws -> Bool {
        try await replyToQuestion(questionID: questionID, answers: answers, directory: directory, sessionID: sessionID, questions: [])
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
        try await rejectQuestion(questionID: questionID, directory: directory, sessionID: nil)
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

    func markSessionViewed(sessionId: String, idleAtEpochMs: Int64) async throws {
        try await selected().markSessionViewed(sessionId: sessionId, idleAtEpochMs: idleAtEpochMs)
    }
}
