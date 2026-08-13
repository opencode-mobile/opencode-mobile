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
    func rejectQuestion(questionID: String, directory: String?) async throws -> Bool
    func rejectQuestion(questionID: String, directory: String?, sessionID: String?) async throws -> Bool
}

extension SessionRepository {
    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest] { [] }
    func listPendingQuestions(directory: String?) async throws -> [QuestionRequest] { [] }
    func replyToQuestion(questionID: String, answers: [[String]], directory: String?) async throws -> Bool { false }
    func replyToQuestion(questionID: String, answers: [[String]], directory: String?, sessionID: String?) async throws -> Bool {
        try await replyToQuestion(questionID: questionID, answers: answers, directory: directory)
    }
    func rejectQuestion(questionID: String, directory: String?) async throws -> Bool { false }
    func rejectQuestion(questionID: String, directory: String?, sessionID: String?) async throws -> Bool {
        try await rejectQuestion(questionID: questionID, directory: directory)
    }

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
    private let api: SessionsAPIClient
    private let adapter: SessionDomainAdapter
    private let sessionModelSelectionStore: (any SessionModelSelectionStore)?
    private let activeConnectionManager: ActiveConnectionManager?
    private let projectRepository: (any ProjectRepository)?

    init(
        api: SessionsAPIClient,
        adapter: SessionDomainAdapter,
        sessionModelSelectionStore: (any SessionModelSelectionStore)? = nil,
        activeConnectionManager: ActiveConnectionManager? = nil,
        projectRepository: (any ProjectRepository)? = nil
    ) {
        self.api = api
        self.adapter = adapter
        self.sessionModelSelectionStore = sessionModelSelectionStore
        self.activeConnectionManager = activeConnectionManager
        self.projectRepository = projectRepository
    }

    private func isV2() async -> Bool {
        if let protocolValue = activeConnectionManager?.serverProtocol {
            return protocolValue == .v2
        }
        guard let projectRepository else { return false }
        _ = try? await projectRepository.probeServer()
        return activeConnectionManager?.serverProtocol == .v2
    }

    func listSessions(directory: String?) async throws -> [Session] {
        try await run {
            if await isV2() {
                return try await api.listSessionsV2(directory: directory).map { adapter.session(from: $0) }
            }
            return try await api.listSessions(directory: directory).map { adapter.session(from: $0) }
        }
    }

    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest] {
        try await run {
            if await isV2() {
                return try await api.listPendingPermissionsV2(directory: directory).map { adapter.permissionRequest(from: $0) }
            }
            return try await api.listPendingPermissions(directory: directory).map { adapter.permissionRequest(from: $0) }
        }
    }

    func listPendingQuestions(directory: String?) async throws -> [QuestionRequest] {
        try await run {
            if await isV2() {
                return try await api.listPendingQuestionsV2(directory: directory).map { adapter.questionRequest(from: $0) }
            }
            return try await api.listPendingQuestions(directory: directory).map { adapter.questionRequest(from: $0) }
        }
    }

    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session {
        try await run {
            guard await isV2() else {
                return adapter.session(from: try await api.createSession(parentID: parentID, title: title, directory: directory))
            }
            // V2 create is location-scoped; title/parent are applied via follow-up when needed.
            var created = adapter.session(from: try await api.createSessionV2(directory: directory))
            if let title, !title.isEmpty {
                try await api.renameSessionV2(id: created.id, title: title)
                created.title = title
            }
            return created
        }
    }

    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] {
        try await run {
            if await isV2() {
                return adapter.sessionStatuses(fromActive: try await api.getActiveSessionsV2())
            }
            return try await api.getAllSessionStatuses(directory: directory).map { adapter.sessionStatus(sessionID: $0.key, raw: $0.value) }
        }
    }

    func getSession(id: String, directory: String?) async throws -> Session {
        try await run {
            if await isV2() {
                return adapter.session(from: try await api.getSessionV2(id: id))
            }
            return adapter.session(from: try await api.getSession(id: id, directory: directory))
        }
    }

    func updateSession(id: String, title: String?, directory: String?) async throws -> Session {
        try await run {
            guard await isV2() else {
                return adapter.session(from: try await api.updateSession(id: id, title: title, directory: directory))
            }
            guard let title, !title.isEmpty else {
                throw SessionError.transport("V2 rename requires a title")
            }
            try await api.renameSessionV2(id: id, title: title)
            var renamed = adapter.session(from: try await api.getSessionV2(id: id))
            renamed.title = title
            return renamed
        }
    }

    func archiveSession(id: String, directory: String?) async throws -> Session {
        try await run {
            guard await isV2() else {
                return adapter.session(from: try await api.archiveSession(id: id, directory: directory))
            }
            try await api.archiveSessionV2(id: id)
            var archived = adapter.session(from: try await api.getSessionV2(id: id))
            if archived.archivedAtEpochMs == nil {
                archived.archivedAtEpochMs = Int64(Date().timeIntervalSince1970 * 1000)
            }
            return archived
        }
    }

    func deleteSession(id: String, directory: String?) async throws -> Bool {
        let deleted = try await run {
            if await isV2() {
                return try await api.deleteSessionV2(id: id)
            }
            return try await api.deleteSession(id: id, directory: directory)
        }
        if deleted,
           let profile = activeConnectionManager?.currentProfile(),
           let sessionModelSelectionStore {
            await sessionModelSelectionStore.deleteSessionEntries(
                connectionIdentity: ModelSelectionConnectionIdentity(
                    serverBaseURL: profile.baseURL,
                    username: profile.username
                ),
                sessionID: id
            )
        }
        return deleted
    }

    func listChildSessions(id: String) async throws -> [Session] {
        try await run {
            if await isV2() {
                // No dedicated children route on published v2; filter the location list.
                return try await api.listSessionsV2()
                    .filter { $0.parentID == id }
                    .map { adapter.session(from: $0) }
            }
            return try await api.listChildSessions(id: id).map { adapter.session(from: $0) }
        }
    }

    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo] {
        try await run {
            if await isV2() {
                // No REST snapshot on v2. Throw so the live store keeps event-seeded
                // todos instead of replacing them with an empty list.
                throw SessionError.http(statusCode: 404, message: "Todos REST is unavailable on v2")
            }
            return adapter.todos(from: try await api.listSessionTodos(id: id, directory: directory))
        }
    }

    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool {
        try await run {
            try await api.initializeSessionAgents(
                id: id,
                body: InitSessionRequestDTO(messageID: messageID, providerID: providerID, modelID: modelID)
            )
        }
    }

    func forkSession(id: String, atMessageID: String?) async throws -> Session {
        try await run {
            if await isV2() {
                return adapter.session(from: try await api.forkSessionV2(id: id, messageID: atMessageID))
            }
            return adapter.session(from: try await api.forkSession(id: id, messageID: atMessageID))
        }
    }

    func abortSession(id: String, directory: String?) async throws -> Bool {
        try await run {
            if await isV2() {
                return try await api.interruptSessionV2(id: id)
            }
            return try await api.abortSession(id: id, directory: directory)
        }
    }

    func shareSession(id: String) async throws -> Session {
        try await run { adapter.session(from: try await api.shareSession(id: id)) }
    }

    func unshareSession(id: String) async throws -> Session {
        try await run { adapter.session(from: try await api.unshareSession(id: id)) }
    }

    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] {
        try await run {
            if await isV2() {
                throw SessionError.http(statusCode: 404, message: "session message diff is not available")
            }
            return try await api.getSessionDiff(id: id, messageID: messageID, directory: directory)
                .map(adapter.fileDiff)
        }
    }

    func getVcsInfo(directory: String?) async throws -> VcsInfo {
        try await run {
            if await isV2() {
                return adapter.vcsInfo(from: try await api.getVcsInfoV2(directory: directory))
            }
            return adapter.vcsInfo(from: try await api.getVcsInfo(directory: directory))
        }
    }

    func getVcsDiff(mode: VcsDiffMode, directory: String?, context: Int) async throws -> [FileDiff] {
        try await run {
            if await isV2() {
                return try await api.getVcsDiffV2(mode: mode.v2WireValue, directory: directory, context: context)
                    .map(adapter.fileDiff)
            }
            return try await api.getVcsDiff(mode: mode, directory: directory, context: context)
                .map(adapter.fileDiff)
        }
    }

    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool {
        try await run {
            if await isV2() {
                return try await api.compactSessionV2(id: id)
            }
            return try await api.summarizeSession(
                id: id,
                body: SummarizeSessionRequestDTO(providerID: providerID, modelID: modelID)
            )
        }
    }

    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool {
        try await run {
            if await isV2() {
                return try await api.stageRevertV2(id: id, messageID: messageID, partID: partID)
            }
            return try await api.revertSession(id: id, body: RevertSessionRequestDTO(messageID: messageID, partID: partID))
        }
    }

    func unrevertSession(id: String) async throws -> Bool {
        try await run {
            if await isV2() {
                return try await api.clearRevertV2(id: id)
            }
            return try await api.unrevertSession(id: id)
        }
    }

    func respondToPermission(
        sessionID: String,
        permissionID: String,
        decision: PermissionDecision,
        directory: String?
    ) async throws -> Bool {
        try await run {
            if await isV2() {
                return try await api.respondToPermissionV2(
                    sessionID: sessionID,
                    permissionID: permissionID,
                    body: V2PermissionReplyRequestDTO(reply: adapter.permissionResponse(decision))
                )
            }
            return try await api.respondToPermission(
                sessionID: sessionID,
                permissionID: permissionID,
                body: PermissionResponseRequestDTO(response: adapter.permissionResponse(decision)),
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
        try await run {
            if await isV2() {
                return try await api.replyToQuestionV2(
                    sessionID: sessionID ?? "",
                    questionID: questionID,
                    body: QuestionReplyRequestDTO(answers: answers)
                )
            }
            return try await api.replyToQuestion(
                questionID: questionID,
                body: QuestionReplyRequestDTO(answers: answers),
                directory: directory
            )
        }
    }

    func rejectQuestion(questionID: String, directory: String?) async throws -> Bool {
        try await rejectQuestion(questionID: questionID, directory: directory, sessionID: nil)
    }

    func rejectQuestion(questionID: String, directory: String?, sessionID: String?) async throws -> Bool {
        try await run {
            if await isV2() {
                return try await api.rejectQuestionV2(sessionID: sessionID ?? "", questionID: questionID)
            }
            return try await api.rejectQuestion(questionID: questionID, directory: directory)
        }
    }

    private func run<T>(_ work: () async throws -> T) async throws -> T {
        do {
            return try await work()
        } catch {
            throw adapter.mapError(error)
        }
    }
}
