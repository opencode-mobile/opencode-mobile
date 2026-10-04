import Foundation

final class T3SessionRepository: SessionRepository, @unchecked Sendable {
    private let client: T3EnvironmentClient

    init(client: T3EnvironmentClient) {
        self.client = client
    }

    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest] {
        let snapshot = try await client.fetchShell(force: true)
        return snapshot.activeThreads.compactMap { thread in
            if let directory, !directory.isEmpty {
                let projectRoot = snapshot.projects.first { $0.id == thread.projectID }?.workspaceRoot
                if thread.worktreePath != directory && projectRoot != directory { return nil }
            }
            return T3ShellMapper.permissionRequest(from: thread)
        }
    }

    func getVcsInfo(directory _: String?) async throws -> VcsInfo {
        VcsInfo(currentBranch: nil, defaultBranch: nil)
    }
    func listPendingQuestions(directory _: String?) async throws -> [QuestionRequest] { [] }

    func listSessions(directory _: String?) async throws -> [Session] {
        T3ShellMapper.sessions(from: try await client.fetchShell(force: true))
    }

    func createSession(parentID _: String?, title: String?, directory _: String?) async throws -> Session {
        try await client.launchThread(title: title)
    }

    func getAllSessionStatuses(directory _: String?) async throws -> [SessionStatus] {
        T3ShellMapper.statuses(from: try await client.fetchShell(force: true))
    }

    func getSession(id: String, directory _: String?) async throws -> Session {
        if let cached = client.cachedThread(id: id) {
            return T3ShellMapper.session(from: cached)
        }
        let snapshot = try await client.fetchShell(force: true)
        if let thread = snapshot.thread(id: id) {
            return T3ShellMapper.session(from: thread)
        }
        let detail = try await client.fetchThread(id: id)
        if let thread = detail.thread {
            return T3ShellMapper.session(from: thread)
        }
        throw SessionError.http(statusCode: 404, message: "Thread not found")
    }

    func updateSession(id: String, title: String?, directory: String?) async throws -> Session {
        if let title, !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            try await client.dispatchCommand(
                T3RpcCodec.metadataUpdateCommand(
                    commandID: UUID().uuidString,
                    threadID: id,
                    title: title.trimmingCharacters(in: .whitespacesAndNewlines)
                )
            )
        }
        return try await getSession(id: id, directory: directory)
    }

    func archiveSession(id: String, directory: String?) async throws -> Session {
        try await client.dispatchCommand(
            T3RpcCodec.archiveCommand(commandID: UUID().uuidString, threadID: id)
        )
        var session = try await getSession(id: id, directory: directory)
        if session.archivedAtEpochMs == nil {
            session.archivedAtEpochMs = Int64(Date().timeIntervalSince1970 * 1_000)
        }
        return session
    }

    func deleteSession(id: String, directory _: String?) async throws -> Bool {
        try await client.dispatchCommand(
            T3RpcCodec.deleteCommand(commandID: UUID().uuidString, threadID: id)
        )
        return true
    }

    func listChildSessions(id _: String) async throws -> [Session] { [] }
    func listSessionTodos(id _: String, directory _: String?) async throws -> [SessionTodo] { [] }
    func initializeSessionAgents(id _: String, messageID _: String, providerID _: String, modelID _: String) async throws -> Bool {
        false
    }

    func forkSession(id _: String, atMessageID _: String?) async throws -> Session {
        throw SessionError.http(statusCode: 404, message: "Fork is not available on T3 threads")
    }

    func abortSession(id: String, directory _: String?) async throws -> Bool {
        let thread: T3Thread?
        if let cached = client.cachedThread(id: id) {
            thread = cached
        } else {
            thread = try await client.fetchShell(force: true).thread(id: id)
        }
        guard let runID = thread?.activeRunID, !runID.isEmpty else { return false }
        try await client.dispatchCommand(
            T3RpcCodec.interruptCommand(commandID: UUID().uuidString, threadID: id, runID: runID)
        )
        return true
    }

    func shareSession(id: String) async throws -> Session {
        try await getSession(id: id, directory: nil)
    }

    func unshareSession(id: String) async throws -> Session {
        try await getSession(id: id, directory: nil)
    }

    func getSessionDiff(id _: String, messageID _: String?, directory _: String?) async throws -> [FileDiff] { [] }
    func summarizeSession(id _: String, providerID _: String, modelID _: String) async throws -> Bool { false }
    func revertSession(id _: String, messageID _: String, partID _: String?) async throws -> Bool { false }
    func unrevertSession(id _: String) async throws -> Bool { false }

    func respondToPermission(
        sessionID: String,
        permissionID: String,
        decision: PermissionDecision,
        directory _: String?
    ) async throws -> Bool {
        let thread: T3Thread?
        if let cached = client.cachedThread(id: sessionID) {
            thread = cached
        } else {
            thread = try await client.fetchShell(force: true).thread(id: sessionID)
        }
        guard let thread,
              thread.pendingRequestID == permissionID,
              thread.pendingRequestKind != "user_input"
        else { return false }
        try await client.dispatchCommand(
            T3RpcCodec.runtimeRequestRespondCommand(
                commandID: UUID().uuidString,
                threadID: sessionID,
                requestID: permissionID,
                decision: decision
            )
        )
        _ = try? await client.fetchShell(force: true)
        return true
    }

    func settleSession(id: String, directory _: String?) async throws -> Bool {
        try await client.dispatchCommand(
            T3RpcCodec.settleCommand(commandID: UUID().uuidString, threadID: id)
        )
        return true
    }
}
