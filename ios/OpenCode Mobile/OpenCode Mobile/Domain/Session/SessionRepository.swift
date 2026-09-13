import Foundation

protocol SessionRepository: Sendable {
    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest]
    func listPendingQuestions(directory: String?) async throws -> [QuestionRequest]
    func listSessions(directory: String?) async throws -> [Session]
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus]
    func getSession(id: String, directory: String?) async throws -> Session
    func updateSession(id: String, title: String?, directory: String?) async throws -> Session
    func archiveSession(id: String, directory: String?) async throws -> Session
    func deleteSession(id: String, directory: String?) async throws -> Bool
    func listChildSessions(id: String) async throws -> [Session]
    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo]
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool
    func forkSession(id: String, atMessageID: String?) async throws -> Session
    func abortSession(id: String, directory: String?) async throws -> Bool
    func shareSession(id: String) async throws -> Session
    func unshareSession(id: String) async throws -> Session
    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff]
    func getVcsInfo(directory: String?) async throws -> VcsInfo
    func getVcsDiff(mode: VcsDiffMode, directory: String?, context: Int) async throws -> [FileDiff]
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool
    func unrevertSession(id: String) async throws -> Bool
    func respondToPermission(sessionID: String, permissionID: String, decision: PermissionDecision, directory: String?) async throws -> Bool
    func replyToQuestion(questionID: String, answers: [[String]], directory: String?) async throws -> Bool
    func replyToQuestion(questionID: String, answers: [[String]], directory: String?, sessionID: String?) async throws -> Bool
    func replyToQuestion(questionID: String, answers: [[String]], directory: String?, sessionID: String?, questions: [QuestionInfo]) async throws -> Bool
    func rejectQuestion(questionID: String, directory: String?) async throws -> Bool
    func rejectQuestion(questionID: String, directory: String?, sessionID: String?) async throws -> Bool
    func cancelPendingPrompt(sessionID: String, inboxID: String) async throws -> Bool
    func steerPendingPrompt(sessionID: String, inboxID: String) async throws -> Bool
    func queuePendingPrompt(sessionID: String, inboxID: String) async throws -> Bool
}

extension SessionRepository {
    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest] { [] }
    func listPendingQuestions(directory: String?) async throws -> [QuestionRequest] { [] }
    func replyToQuestion(questionID: String, answers: [[String]], directory: String?) async throws -> Bool { false }
    func replyToQuestion(questionID: String, answers: [[String]], directory: String?, sessionID: String?) async throws -> Bool {
        try await replyToQuestion(questionID: questionID, answers: answers, directory: directory)
    }
    func replyToQuestion(questionID: String, answers: [[String]], directory: String?, sessionID: String?, questions: [QuestionInfo]) async throws -> Bool {
        try await replyToQuestion(questionID: questionID, answers: answers, directory: directory, sessionID: sessionID)
    }
    func rejectQuestion(questionID: String, directory: String?) async throws -> Bool { false }
    func rejectQuestion(questionID: String, directory: String?, sessionID: String?) async throws -> Bool {
        try await rejectQuestion(questionID: questionID, directory: directory)
    }

    func cancelPendingPrompt(sessionID _: String, inboxID _: String) async throws -> Bool { false }
    func steerPendingPrompt(sessionID _: String, inboxID _: String) async throws -> Bool { false }
    func queuePendingPrompt(sessionID _: String, inboxID _: String) async throws -> Bool { false }

    func getSession(id: String) async throws -> Session {
        try await getSession(id: id, directory: nil)
    }

    func updateSession(id: String, title: String?) async throws -> Session {
        try await updateSession(id: id, title: title, directory: nil)
    }

    func respondToPermission(sessionID: String, permissionID: String, decision: PermissionDecision) async throws -> Bool {
        try await respondToPermission(sessionID: sessionID, permissionID: permissionID, decision: decision, directory: nil)
    }

    func replyToQuestion(questionID: String, answers: [[String]]) async throws -> Bool {
        try await replyToQuestion(questionID: questionID, answers: answers, directory: nil)
    }

    func rejectQuestion(questionID: String) async throws -> Bool {
        try await rejectQuestion(questionID: questionID, directory: nil)
    }

    func getSessionDiff(id: String, messageID: String?) async throws -> [FileDiff] {
        try await getSessionDiff(id: id, messageID: messageID, directory: nil)
    }

    func getVcsInfo(directory: String?) async throws -> VcsInfo {
        throw SessionError.http(statusCode: 404, message: "VCS endpoints are unavailable")
    }

    func getVcsDiff(mode: VcsDiffMode, directory: String?, context: Int = 12) async throws -> [FileDiff] {
        throw SessionError.http(statusCode: 404, message: "VCS endpoints are unavailable")
    }
}

struct DefaultSessionRepository: SessionRepository, Sendable {
    private let remote: any SessionRemote
    private let adapter: SessionDomainAdapter
    private let sessionModelSelectionStore: (any SessionModelSelectionStore)?
    private let composerDraftStore: (any ComposerDraftStore)?
    private let activeConnectionManager: ActiveConnectionManager?

    init(
        api: SessionsAPIClient,
        adapter: SessionDomainAdapter,
        sessionModelSelectionStore: (any SessionModelSelectionStore)? = nil,
        composerDraftStore: (any ComposerDraftStore)? = nil,
        activeConnectionManager: ActiveConnectionManager? = nil
    ) {
        self.remote = ProtocolSelectedSessionRemote(
            v1: V1SessionRemote(api: api, adapter: adapter),
            v2: V2SessionRemote(api: api, adapter: adapter),
            activeConnectionManager: activeConnectionManager
        )
        self.adapter = adapter
        self.sessionModelSelectionStore = sessionModelSelectionStore
        self.composerDraftStore = composerDraftStore
        self.activeConnectionManager = activeConnectionManager
    }

    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest] {
        try await run { try await remote.listPendingPermissions(directory: directory) }
    }

    func listPendingQuestions(directory: String?) async throws -> [QuestionRequest] {
        try await run { try await remote.listPendingQuestions(directory: directory) }
    }

    func listSessions(directory: String?) async throws -> [Session] {
        try await run { try await remote.listSessions(directory: directory) }
    }

    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session {
        try await run { try await remote.createSession(parentID: parentID, title: title, directory: directory) }
    }

    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] {
        try await run { try await remote.getAllSessionStatuses(directory: directory) }
    }

    func getSession(id: String, directory: String?) async throws -> Session {
        try await run { try await remote.getSession(id: id, directory: directory) }
    }

    func updateSession(id: String, title: String?, directory: String?) async throws -> Session {
        try await run { try await remote.updateSession(id: id, title: title, directory: directory) }
    }

    func archiveSession(id: String, directory: String?) async throws -> Session {
        try await run { try await remote.archiveSession(id: id, directory: directory) }
    }

    func deleteSession(id: String, directory: String?) async throws -> Bool {
        let deleted = try await run { try await remote.deleteSession(id: id, directory: directory) }
        if deleted,
           let profile = activeConnectionManager?.currentProfile() {
            let identity = ModelSelectionConnectionIdentity(
                serverBaseURL: profile.baseURL,
                username: profile.username
            )
            if let sessionModelSelectionStore {
                await sessionModelSelectionStore.deleteSessionEntries(
                    connectionIdentity: identity,
                    sessionID: id
                )
            }
            if let composerDraftStore {
                composerDraftStore.deleteSessionEntries(
                    connectionIdentity: identity,
                    sessionID: id
                )
            }
        }
        return deleted
    }

    func listChildSessions(id: String) async throws -> [Session] {
        try await run { try await remote.listChildSessions(id: id) }
    }

    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo] {
        try await run { try await remote.listSessionTodos(id: id, directory: directory) }
    }

    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool {
        try await run {
            try await remote.initializeSessionAgents(id: id, messageID: messageID, providerID: providerID, modelID: modelID)
        }
    }

    func forkSession(id: String, atMessageID: String?) async throws -> Session {
        try await run { try await remote.forkSession(id: id, atMessageID: atMessageID) }
    }

    func abortSession(id: String, directory: String?) async throws -> Bool {
        try await run { try await remote.abortSession(id: id, directory: directory) }
    }

    func shareSession(id: String) async throws -> Session {
        try await run { try await remote.shareSession(id: id) }
    }

    func unshareSession(id: String) async throws -> Session {
        try await run { try await remote.unshareSession(id: id) }
    }

    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] {
        try await run { try await remote.getSessionDiff(id: id, messageID: messageID, directory: directory) }
    }

    func getVcsInfo(directory: String?) async throws -> VcsInfo {
        try await run { try await remote.getVcsInfo(directory: directory) }
    }

    func getVcsDiff(mode: VcsDiffMode, directory: String?, context: Int) async throws -> [FileDiff] {
        try await run { try await remote.getVcsDiff(mode: mode, directory: directory, context: context) }
    }

    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool {
        try await run { try await remote.summarizeSession(id: id, providerID: providerID, modelID: modelID) }
    }

    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool {
        try await run { try await remote.revertSession(id: id, messageID: messageID, partID: partID) }
    }

    func unrevertSession(id: String) async throws -> Bool {
        try await run { try await remote.unrevertSession(id: id) }
    }

    func respondToPermission(
        sessionID: String,
        permissionID: String,
        decision: PermissionDecision,
        directory: String?
    ) async throws -> Bool {
        try await run {
            try await remote.respondToPermission(
                sessionID: sessionID,
                permissionID: permissionID,
                decision: decision,
                directory: directory
            )
        }
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
        try await run {
            try await remote.replyToQuestion(
                questionID: questionID,
                answers: answers,
                directory: directory,
                sessionID: sessionID,
                questions: questions
            )
        }
    }

    func rejectQuestion(questionID: String, directory: String?) async throws -> Bool {
        try await rejectQuestion(questionID: questionID, directory: directory, sessionID: nil)
    }

    func rejectQuestion(questionID: String, directory: String?, sessionID: String?) async throws -> Bool {
        try await run {
            try await remote.rejectQuestion(questionID: questionID, directory: directory, sessionID: sessionID)
        }
    }

    func cancelPendingPrompt(sessionID: String, inboxID: String) async throws -> Bool {
        try await run { try await remote.cancelPendingPrompt(sessionID: sessionID, inboxID: inboxID) }
    }

    func steerPendingPrompt(sessionID: String, inboxID: String) async throws -> Bool {
        try await run { try await remote.steerPendingPrompt(sessionID: sessionID, inboxID: inboxID) }
    }

    func queuePendingPrompt(sessionID: String, inboxID: String) async throws -> Bool {
        try await run { try await remote.queuePendingPrompt(sessionID: sessionID, inboxID: inboxID) }
    }

    private func run<T>(_ work: () async throws -> T) async throws -> T {
        do {
            return try await work()
        } catch {
            throw adapter.mapError(error)
        }
    }
}
