import Foundation

protocol SessionRepository: Sendable {
    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest]
    func listSessions(directory: String?) async throws -> [Session]
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus]
    func getSession(id: String, directory: String?) async throws -> Session
    func updateSession(id: String, title: String?) async throws -> Session
    func deleteSession(id: String) async throws -> Bool
    func listChildSessions(id: String) async throws -> [Session]
    func listSessionTodos(id: String) async throws -> [SessionTodo]
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
}

extension SessionRepository {
    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest] { [] }

    func getSession(id: String) async throws -> Session {
        try await getSession(id: id, directory: nil)
    }

    func respondToPermission(sessionID: String, permissionID: String, decision: PermissionDecision) async throws -> Bool {
        try await respondToPermission(sessionID: sessionID, permissionID: permissionID, decision: decision, directory: nil)
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

    init(api: SessionsAPIClient, adapter: SessionDomainAdapter) {
        self.api = api
        self.adapter = adapter
    }

    func listSessions(directory: String?) async throws -> [Session] {
        try await run { try await api.listSessions(directory: directory).map(adapter.session) }
    }

    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest] {
        try await run { try await api.listPendingPermissions(directory: directory).map { adapter.permissionRequest(from: $0) } }
    }

    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session {
        try await run {
            adapter.session(from: try await api.createSession(parentID: parentID, title: title, directory: directory))
        }
    }

    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] {
        try await run {
            try await api.getAllSessionStatuses(directory: directory).map { adapter.sessionStatus(sessionID: $0.key, raw: $0.value) }
        }
    }

    func getSession(id: String, directory: String?) async throws -> Session {
        try await run { adapter.session(from: try await api.getSession(id: id, directory: directory)) }
    }

    func updateSession(id: String, title: String?) async throws -> Session {
        try await run { adapter.session(from: try await api.updateSession(id: id, title: title)) }
    }

    func deleteSession(id: String) async throws -> Bool {
        try await run { try await api.deleteSession(id: id) }
    }

    func listChildSessions(id: String) async throws -> [Session] {
        try await run { try await api.listChildSessions(id: id).map(adapter.session) }
    }

    func listSessionTodos(id: String) async throws -> [SessionTodo] {
        try await run { try await api.listSessionTodos(id: id).map(adapter.todo) }
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
        try await run { adapter.session(from: try await api.forkSession(id: id, messageID: atMessageID)) }
    }

    func abortSession(id: String, directory: String?) async throws -> Bool {
        try await run { try await api.abortSession(id: id, directory: directory) }
    }

    func shareSession(id: String) async throws -> Session {
        try await run { adapter.session(from: try await api.shareSession(id: id)) }
    }

    func unshareSession(id: String) async throws -> Session {
        try await run { adapter.session(from: try await api.unshareSession(id: id)) }
    }

    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] {
        try await run {
            try await api.getSessionDiff(id: id, messageID: messageID, directory: directory)
                .map(adapter.fileDiff)
        }
    }

    func getVcsInfo(directory: String?) async throws -> VcsInfo {
        try await run { adapter.vcsInfo(from: try await api.getVcsInfo(directory: directory)) }
    }

    func getVcsDiff(mode: VcsDiffMode, directory: String?, context: Int) async throws -> [FileDiff] {
        try await run {
            try await api.getVcsDiff(mode: mode, directory: directory, context: context)
                .map(adapter.fileDiff)
        }
    }

    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool {
        try await run {
            try await api.summarizeSession(
                id: id,
                body: SummarizeSessionRequestDTO(providerID: providerID, modelID: modelID)
            )
        }
    }

    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool {
        try await run {
            try await api.revertSession(id: id, body: RevertSessionRequestDTO(messageID: messageID, partID: partID))
        }
    }

    func unrevertSession(id: String) async throws -> Bool {
        try await run { try await api.unrevertSession(id: id) }
    }

    func respondToPermission(
        sessionID: String,
        permissionID: String,
        decision: PermissionDecision,
        directory: String?
    ) async throws -> Bool {
        try await run {
            try await api.respondToPermission(
                sessionID: sessionID,
                permissionID: permissionID,
                body: PermissionResponseRequestDTO(response: adapter.permissionResponse(decision)),
                directory: directory
            )
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
