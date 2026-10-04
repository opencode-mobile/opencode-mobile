import Foundation

final class T3SessionRepository: SessionRepository, @unchecked Sendable {
    private let client: any T3EngineClient

    init(client: any T3EngineClient) {
        self.client = client
    }

    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest] {
        let snapshot = try await client.fetchShell(force: true)
        return snapshot.activeThreads
            .filter { T3ProjectSelection.matches($0, directory: directory, in: snapshot) }
            .compactMap(T3ShellMapper.permissionRequest(from:))
    }

    func getVcsInfo(directory _: String?) async throws -> VcsInfo {
        VcsInfo(currentBranch: nil, defaultBranch: nil)
    }
    func listPendingQuestions(directory _: String?) async throws -> [QuestionRequest] { [] }

    func listSessions(directory: String?) async throws -> [Session] {
        let snapshot = try await client.fetchShell(force: true)
        return snapshot.activeThreads
            .filter { T3ProjectSelection.matches($0, directory: directory, in: snapshot) }
            .map { thread in
                T3ShellMapper.session(
                    from: thread,
                    directory: Self.fallbackDirectory(directory, thread: thread, snapshot: snapshot)
                )
            }
    }

    func createSession(parentID _: String?, title: String?, directory: String?) async throws -> Session {
        let snapshot = try await client.fetchShell(force: true)
        guard let project = T3ProjectSelection.project(for: directory, in: snapshot) else {
            throw SessionError.http(statusCode: 400, message: "This T3 environment has no projects")
        }
        let session = try await client.launchThread(title: title, projectID: project.id)
        guard session.directory == nil else { return session }
        return Self.withDirectory(session, Self.nonEmpty(directory) ?? project.workspaceRoot)
    }

    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] {
        let snapshot = try await client.fetchShell(force: false)
        return snapshot.activeThreads
            .filter { T3ProjectSelection.matches($0, directory: directory, in: snapshot) }
            .map { thread in
                SessionStatus(sessionID: thread.id, raw: ["type": thread.isBusy ? "busy" : "idle"])
            }
    }

    func getSession(id: String, directory: String?) async throws -> Session {
        let thread = try await resolveThread(id: id, refresh: false)
        return T3ShellMapper.session(from: thread, directory: await resolvedDirectory(directory, thread: thread))
    }

    func updateSession(id: String, title: String?, directory: String?) async throws -> Session {
        let trimmed = title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !trimmed.isEmpty else {
            return try await getSession(id: id, directory: directory)
        }
        try await client.dispatchCommand(
            T3RpcCodec.metadataUpdateCommand(commandID: UUID().uuidString, threadID: id, title: trimmed)
        )
        let thread = try await resolveThread(id: id, refresh: true)
        var session = T3ShellMapper.session(from: thread, directory: await resolvedDirectory(directory, thread: thread))
        session.title = trimmed
        return session
    }

    func archiveSession(id: String, directory: String?) async throws -> Session {
        try await client.dispatchCommand(
            T3RpcCodec.archiveCommand(commandID: UUID().uuidString, threadID: id)
        )
        let thread = try await resolveThread(id: id, refresh: true)
        var session = T3ShellMapper.session(from: thread, directory: await resolvedDirectory(directory, thread: thread))
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
        throw Self.unsupported("Agents")
    }

    func forkSession(id _: String, atMessageID _: String?) async throws -> Session {
        throw Self.unsupported("Fork")
    }

    /// Interrupts the thread's active run. A thread with nothing running is
    /// already in the requested state, so that is a success like Android.
    func abortSession(id: String, directory _: String?) async throws -> Bool {
        let thread = try await resolveThread(id: id, refresh: true)
        guard let runID = thread.activeRunID, !runID.isEmpty else { return true }
        try await client.dispatchCommand(
            T3RpcCodec.interruptCommand(commandID: UUID().uuidString, threadID: id, runID: runID)
        )
        return true
    }

    func shareSession(id _: String) async throws -> Session {
        throw Self.unsupported("Share")
    }

    func unshareSession(id _: String) async throws -> Session {
        throw Self.unsupported("Share")
    }

    func getSessionDiff(id _: String, messageID _: String?, directory _: String?) async throws -> [FileDiff] { [] }

    func summarizeSession(id _: String, providerID _: String, modelID _: String) async throws -> Bool {
        throw Self.unsupported("Compact")
    }

    func revertSession(id _: String, messageID _: String, partID _: String?) async throws -> Bool {
        throw Self.unsupported("Undo")
    }

    func unrevertSession(id _: String) async throws -> Bool {
        throw Self.unsupported("Undo")
    }

    /// Answers the thread's pending runtime request. The shell is refreshed
    /// first so a request id from a stale cache cannot silently drop the reply.
    func respondToPermission(
        sessionID: String,
        permissionID: String,
        decision: PermissionDecision,
        directory _: String?
    ) async throws -> Bool {
        let thread = try await resolveThread(id: sessionID, refresh: true)
        guard thread.pendingRequestID == permissionID, thread.pendingRequestKind != "user_input" else {
            return false
        }
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

    // MARK: - Helpers

    /// Finds the thread in the cache (unless `refresh`), then the shell, then
    /// the thread detail endpoint. Throws 404 when none of them know it.
    private func resolveThread(id: String, refresh: Bool) async throws -> T3Thread {
        if !refresh, let cached = client.cachedThread(id: id) {
            return cached
        }
        if let thread = try await client.fetchShell(force: true).thread(id: id) {
            return thread
        }
        if let thread = try await client.fetchThread(id: id).thread {
            return thread
        }
        throw SessionError.http(statusCode: 404, message: "Thread not found")
    }

    /// The caller's directory, else the owning project's workspace root.
    private func resolvedDirectory(_ directory: String?, thread: T3Thread) async -> String? {
        if let directory = Self.nonEmpty(directory) { return directory }
        guard let snapshot = try? await client.fetchShell(force: false) else { return nil }
        return T3ShellMapper.projectRoot(for: thread, in: snapshot)
    }

    private static func fallbackDirectory(_ directory: String?, thread: T3Thread, snapshot: T3ShellSnapshot) -> String? {
        nonEmpty(directory) ?? T3ShellMapper.projectRoot(for: thread, in: snapshot)
    }

    private static func nonEmpty(_ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return value
    }

    private static func withDirectory(_ session: Session, _ directory: String?) -> Session {
        var copy = Session(
            id: session.id,
            title: session.title,
            slug: session.slug,
            projectID: session.projectID,
            directory: directory,
            parentID: session.parentID,
            updatedAtEpochMs: session.updatedAtEpochMs
        )
        copy.cost = session.cost
        copy.tokens = session.tokens
        copy.createdAtEpochMs = session.createdAtEpochMs
        copy.archivedAtEpochMs = session.archivedAtEpochMs
        copy.revert = session.revert
        copy.providerInstanceID = session.providerInstanceID
        copy.runtimeMode = session.runtimeMode
        copy.modelName = session.modelName
        copy.activeRunID = session.activeRunID
        return copy
    }

    private static func unsupported(_ label: String) -> SessionError {
        .http(statusCode: 400, message: "\(label) is not available on T3")
    }
}
