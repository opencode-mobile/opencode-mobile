import Foundation

protocol SessionRepository: Sendable {
    func listSessions(directory: String?) async throws -> [Session]
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session
    func getAllSessionStatuses() async throws -> [SessionStatus]
    func getSession(id: String) async throws -> Session
    func updateSession(id: String, title: String?) async throws -> Session
    func deleteSession(id: String) async throws -> Bool
    func listChildSessions(id: String) async throws -> [Session]
    func listSessionTodos(id: String) async throws -> [SessionTodo]
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool
    func forkSession(id: String, atMessageID: String?) async throws -> Session
    func abortSession(id: String) async throws -> Bool
    func shareSession(id: String) async throws -> Session
    func unshareSession(id: String) async throws -> Session
    func getSessionDiff(id: String, messageID: String?) async throws -> [FileDiff]
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool
    func unrevertSession(id: String) async throws -> Bool
    func respondToPermission(sessionID: String, permissionID: String, decision: PermissionDecision) async throws -> Bool
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

    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session {
        try await run {
            adapter.session(from: try await api.createSession(parentID: parentID, title: title, directory: directory))
        }
    }

    func getAllSessionStatuses() async throws -> [SessionStatus] {
        try await run {
            try await api.getAllSessionStatuses().map { adapter.sessionStatus(sessionID: $0.key, raw: $0.value) }
        }
    }

    func getSession(id: String) async throws -> Session {
        try await run { adapter.session(from: try await api.getSession(id: id)) }
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

    func abortSession(id: String) async throws -> Bool {
        try await run { try await api.abortSession(id: id) }
    }

    func shareSession(id: String) async throws -> Session {
        try await run { adapter.session(from: try await api.shareSession(id: id)) }
    }

    func unshareSession(id: String) async throws -> Session {
        try await run { adapter.session(from: try await api.unshareSession(id: id)) }
    }

    func getSessionDiff(id: String, messageID: String?) async throws -> [FileDiff] {
        try await run { try await api.getSessionDiff(id: id, messageID: messageID).map(adapter.fileDiff) }
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

    func respondToPermission(sessionID: String, permissionID: String, decision: PermissionDecision) async throws -> Bool {
        try await run {
            try await api.respondToPermission(
                sessionID: sessionID,
                permissionID: permissionID,
                body: PermissionResponseRequestDTO(response: adapter.permissionResponse(decision))
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
