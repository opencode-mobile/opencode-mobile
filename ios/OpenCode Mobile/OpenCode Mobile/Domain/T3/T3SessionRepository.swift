import Foundation

final class T3SessionRepository: SessionRepository, @unchecked Sendable {
    private let client: any T3EngineClient

    init(client: any T3EngineClient) {
        self.client = client
    }

    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest] {
        let snapshot = try await client.fetchShell(force: true)
        let waiting = snapshot.activeThreads
            .filter { T3ProjectSelection.matches($0, directory: directory, in: snapshot) }
            .filter { T3ShellMapper.approvalID(for: $0) != nil }
        var requests: [PermissionRequest] = []
        for thread in waiting {
            if let request = T3ShellMapper.permissionRequest(from: thread, turnItems: await turnItems(threadID: thread.id)) {
                requests.append(request)
            }
        }
        return requests
    }

    func getVcsInfo(directory _: String?) async throws -> VcsInfo {
        VcsInfo(currentBranch: nil, defaultBranch: nil)
    }

    func listPendingQuestions(directory: String?) async throws -> [QuestionRequest] {
        let snapshot = try await client.fetchShell(force: true)
        let waiting = snapshot.activeThreads
            .filter { T3ProjectSelection.matches($0, directory: directory, in: snapshot) }
            .filter { T3ShellMapper.questionID(for: $0) != nil }
        var requests: [QuestionRequest] = []
        for thread in waiting {
            if let request = T3ShellMapper.questionRequest(from: thread, turnItems: await turnItems(threadID: thread.id)) {
                requests.append(request)
            }
        }
        return requests
    }

    func replyToQuestion(questionID: String, answers: [[String]], directory: String?) async throws -> Bool {
        try await replyToQuestion(questionID: questionID, answers: answers, directory: directory, sessionID: nil, questions: [])
    }

    func replyToQuestion(questionID: String, answers: [[String]], directory: String?, sessionID: String?) async throws -> Bool {
        try await replyToQuestion(questionID: questionID, answers: answers, directory: directory, sessionID: sessionID, questions: [])
    }

    /// Answers the thread's pending `user_input` request. Question ids come from
    /// the caller's `questions` when they carry field keys, else from the thread.
    func replyToQuestion(
        questionID: String,
        answers: [[String]],
        directory _: String?,
        sessionID: String?,
        questions: [QuestionInfo]
    ) async throws -> Bool {
        guard let thread = try await questionThread(sessionID: sessionID, requestID: questionID) else { return false }
        let known: [QuestionInfo]
        if !questions.isEmpty, questions.allSatisfy({ !($0.fieldKey ?? "").isEmpty }) {
            known = questions
        } else if let request = T3ShellMapper.questionRequest(from: thread, turnItems: await turnItems(threadID: thread.id)) {
            known = request.questions
        } else {
            return false
        }
        let encoded = T3QuestionCodec.encodeAnswers(questions: known, answers: answers)
        guard !encoded.isEmpty else { return false }
        try await client.dispatchCommand(
            T3QuestionCodec.userInputRespondCommand(
                commandID: UUID().uuidString,
                threadID: thread.id,
                requestID: questionID,
                answers: encoded
            )
        )
        _ = try? await client.fetchShell(force: true)
        return true
    }

    func rejectQuestion(questionID: String, directory: String?) async throws -> Bool {
        try await rejectQuestion(questionID: questionID, directory: directory, sessionID: nil)
    }

    func rejectQuestion(questionID: String, directory _: String?, sessionID: String?) async throws -> Bool {
        guard let thread = try await questionThread(sessionID: sessionID, requestID: questionID) else { return false }
        try await client.dispatchCommand(
            T3QuestionCodec.userInputDismissCommand(commandID: UUID().uuidString, threadID: thread.id, requestID: questionID)
        )
        _ = try? await client.fetchShell(force: true)
        return true
    }

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

    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session {
        try await createSession(parentID: parentID, title: title, directory: directory, options: .defaults)
    }

    /// Launches a thread in the project owning `directory`. An explicit
    /// harness + model goes to the launch as-is; otherwise the client resolves
    /// one from the harness catalogue.
    func createSession(
        parentID _: String?,
        title: String?,
        directory: String?,
        options: SessionCreateOptions
    ) async throws -> Session {
        let snapshot = try await client.fetchShell(force: true)
        guard let project = T3ProjectSelection.project(for: directory, in: snapshot) else {
            throw SessionError.http(statusCode: 400, message: "This T3 environment has no projects")
        }
        let selection: T3ModelSelection? = {
            let instance = options.providerInstanceID?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let model = options.modelID?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !instance.isEmpty, !model.isEmpty else { return nil }
            return T3ModelSelection(instanceID: instance, model: model)
        }()
        let runtimeMode = options.runtimeMode.flatMap { T3RuntimeModeLabel.allModes.contains($0) ? $0 : nil }
        let session = try await client.launchThread(
            title: title,
            projectID: project.id,
            selection: selection,
            runtimeMode: runtimeMode
        )
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

    func listArchivedSessions(directory: String?) async throws -> [Session] {
        let snapshot = try await client.fetchShell(force: true)
        return snapshot.archivedThreads
            .filter { T3ProjectSelection.matches($0, directory: directory, in: snapshot) }
            .map { thread in
                T3ShellMapper.session(
                    from: thread,
                    directory: Self.fallbackDirectory(directory, thread: thread, snapshot: snapshot)
                )
            }
    }

    func unarchiveSession(id: String, directory: String?) async throws -> Session {
        try await client.dispatchCommand(
            T3SessionCommands.unarchiveCommand(commandID: UUID().uuidString, threadID: id)
        )
        let thread = try await resolveThread(id: id, refresh: true)
        var session = T3ShellMapper.session(from: thread, directory: await resolvedDirectory(directory, thread: thread))
        session.archivedAtEpochMs = nil
        return session
    }

    func unsettleSession(id: String, directory _: String?) async throws -> Bool {
        try await client.dispatchCommand(
            T3SessionCommands.unsettleCommand(commandID: UUID().uuidString, threadID: id)
        )
        _ = try? await client.fetchShell(force: true)
        return true
    }

    func setRuntimeMode(id: String, runtimeMode: String, directory: String?) async throws -> Session {
        guard T3RuntimeModeLabel.allModes.contains(runtimeMode) else {
            throw SessionError.http(statusCode: 400, message: "Unknown runtime mode: \(runtimeMode)")
        }
        try await client.dispatchCommand(
            T3SessionCommands.runtimeModeSetCommand(commandID: UUID().uuidString, threadID: id, runtimeMode: runtimeMode)
        )
        let thread = try await resolveThread(id: id, refresh: true)
        var session = T3ShellMapper.session(from: thread, directory: await resolvedDirectory(directory, thread: thread))
        session.runtimeMode = runtimeMode
        return session
    }

    func setModelSelection(id: String, providerInstanceID: String, modelID: String, directory: String?) async throws -> Session {
        let instance = providerInstanceID.trimmingCharacters(in: .whitespacesAndNewlines)
        let model = modelID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !instance.isEmpty, !model.isEmpty else {
            throw SessionError.http(statusCode: 400, message: "A provider instance and model are required")
        }
        try await client.dispatchCommand(
            T3SessionCommands.modelSelectionSetCommand(
                commandID: UUID().uuidString,
                threadID: id,
                selection: T3ModelSelection(instanceID: instance, model: model)
            )
        )
        let thread = try await resolveThread(id: id, refresh: true)
        var session = T3ShellMapper.session(from: thread, directory: await resolvedDirectory(directory, thread: thread))
        session.providerInstanceID = instance
        session.modelName = model
        return session
    }

    // MARK: - Helpers

    /// The thread waiting on `requestID`; found by id, else by its pending question.
    private func questionThread(sessionID: String?, requestID: String) async throws -> T3Thread? {
        let snapshot = try await client.fetchShell(force: true)
        let thread: T3Thread?
        if let sessionID = Self.nonEmpty(sessionID) {
            thread = snapshot.thread(id: sessionID)
        } else {
            thread = snapshot.threads.first { T3ShellMapper.questionID(for: $0) == requestID }
        }
        guard let thread, T3ShellMapper.questionID(for: thread) == requestID else { return nil }
        return thread
    }

    /// The thread projection's turn items; a failed fetch degrades to an empty list.
    private func turnItems(threadID: String) async -> [T3TurnItem] {
        (try? await client.fetchThread(id: threadID))?.turnItems ?? []
    }

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
        copy.settledAtEpochMs = session.settledAtEpochMs
        return copy
    }

    private static func unsupported(_ label: String) -> SessionError {
        .http(statusCode: 400, message: "\(label) is not available on T3")
    }
}
