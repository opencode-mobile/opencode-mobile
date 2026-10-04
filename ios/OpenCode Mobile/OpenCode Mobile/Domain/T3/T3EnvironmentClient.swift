import Foundation

/// Owns the T3 REST shell cache and the single websocket RPC session for the
/// active Connection Profile.
///
/// Everything cached here is keyed by the connection identity (base URL +
/// bearer) so a profile switch can never serve another environment's shell or
/// socket. A dead socket is never reused: `rpc()` reconnects with a fresh
/// ticket, and real socket state is published through `T3LiveSignals` for the
/// connection indicator.
nonisolated final class T3EnvironmentClient: @unchecked Sendable {
    typealias SessionFactory = @Sendable (_ onStateChange: @escaping @Sendable (T3SocketState) -> Void) -> T3RpcSession

    private let connectionProvider: @Sendable () -> ServerConnection?
    private let http: any T3HttpTransport
    private let makeSession: SessionFactory
    private let signals: T3LiveSignals
    private let lock = NSLock()
    private var cachedShell: T3ShellSnapshot?
    private var cachedShellIdentity: String?
    private var rpcSession: T3RpcSession?
    private var rpcIdentity: String?
    private var rpcGeneration: UInt64 = 0
    private var connectTask: Task<T3RpcSession, Error>?
    private var connectIdentity: String?
    private var lifecycleRegistration: UUID?

    convenience init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession = OpenCodeURLSessions.rest
    ) {
        self.init(
            connectionProvider: connectionProvider,
            http: T3HttpClient(urlSession: urlSession)
        )
    }

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        http: any T3HttpTransport,
        makeSession: @escaping SessionFactory = { onStateChange in T3RpcSession(onStateChange: onStateChange) },
        signals: T3LiveSignals = .shared
    ) {
        self.connectionProvider = connectionProvider
        self.http = http
        self.makeSession = makeSession
        self.signals = signals
        lifecycleRegistration = signals.onLifecycle { [weak self] lifecycle in
            guard let self else { return }
            switch lifecycle {
            case .stop:
                await self.reset()
            case .start:
                self.warmConnection()
            }
        }
    }

    deinit {
        if let lifecycleRegistration {
            signals.removeLifecycleHandler(lifecycleRegistration)
        }
    }

    func credentials() throws -> (baseURL: URL, bearer: String) {
        guard let connection = connectionProvider() else {
            throw T3ClientError.notConnected
        }
        guard let bearer = connection.password, !bearer.isEmpty else {
            throw T3ClientError.notConnected
        }
        return (connection.baseURL, bearer)
    }

    private func identity(of credentials: (baseURL: URL, bearer: String)) -> String {
        "\(credentials.baseURL.absoluteString)|\(credentials.bearer)"
    }

    /// Current socket state as the connection indicator should show it.
    var connectionState: EventStreamConnectionState {
        signals.connectionState
    }

    func fetchShell(force: Bool = false) async throws -> T3ShellSnapshot {
        let credentials = try credentials()
        let identity = identity(of: credentials)
        if !force, let cached = locked({ cachedShellIdentity == identity ? cachedShell : nil }) {
            return cached
        }
        let snapshot = try await http.fetchShell(baseURL: credentials.baseURL, bearer: credentials.bearer)
        locked {
            cachedShell = snapshot
            cachedShellIdentity = identity
        }
        warmConnection()
        return snapshot
    }

    func replaceShell(_ snapshot: T3ShellSnapshot) {
        guard let credentials = try? credentials() else { return }
        let identity = identity(of: credentials)
        locked {
            cachedShell = snapshot
            cachedShellIdentity = identity
        }
    }

    func cachedThread(id: String) -> T3Thread? {
        guard let credentials = try? credentials() else { return nil }
        let identity = identity(of: credentials)
        return locked { cachedShellIdentity == identity ? cachedShell?.thread(id: id) : nil }
    }

    func fetchThread(id: String) async throws -> T3ThreadDetail {
        let credentials = try credentials()
        let identity = identity(of: credentials)
        let detail = try await http.fetchThread(baseURL: credentials.baseURL, bearer: credentials.bearer, id: id)
        if let thread = detail.thread {
            locked {
                if cachedShellIdentity == identity, var shell = cachedShell {
                    if let index = shell.threads.firstIndex(where: { $0.id == thread.id }) {
                        shell.threads[index] = thread
                    } else {
                        shell.threads.append(thread)
                    }
                    cachedShell = shell
                }
            }
        }
        return detail
    }

    func fetchImageBytes(id: String, fileName: String, mimeType: String) async -> Data? {
        guard mimeType.lowercased().hasPrefix("image/") else { return nil }
        do {
            let value = try await rpc().request(
                tag: T3Assets.createURLTag,
                payload: T3Assets.createURLPayload(id: id, fileName: fileName, mimeType: mimeType)
            )
            guard let relative = T3Assets.relativeURL(from: value) else { return nil }
            let credentials = try credentials()
            guard let url = T3Assets.resolve(baseURL: credentials.baseURL, relativePath: relative) else { return nil }
            return await http.fetchBytes(url: url, maxBytes: T3Assets.maxImageBytes)
        } catch {
            return nil
        }
    }

    func dispatchCommand(_ command: [String: Any]) async throws {
        _ = try await rpc().request(tag: "orchestration.dispatchCommand", payload: command)
    }

    /// One RPC round trip on the environment socket for callers outside this
    /// file (attachment uploads). Connects on demand like every other request.
    func rpcRequest(tag: String, payload: [String: Any]) async throws -> Any? {
        try await rpc().request(tag: tag, payload: payload)
    }

    /// Launches a thread in `projectID` when it names a project of this
    /// environment; otherwise in the environment's first project.
    func launchThread(title: String?, projectID: String? = nil) async throws -> Session {
        let shell = try await fetchShell(force: false)
        let requested = projectID.flatMap { id in shell.projects.first { $0.id == id } }
        guard let project = requested ?? shell.projects.first else {
            throw T3ClientError.decoding("This T3 environment has no projects yet.")
        }
        let selection =
            project.defaultModelSelection
            ?? shell.threads.compactMap(\.modelSelection).first
        guard let selection else {
            throw T3ClientError.decoding("This T3 environment has no model selection yet.")
        }
        let resolvedTitle = {
            let trimmed = title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return trimmed.isEmpty ? "New thread" : trimmed
        }()
        let payload = T3RpcCodec.launchThreadPayload(
            commandID: UUID().uuidString,
            projectID: project.id,
            title: resolvedTitle,
            selection: selection
        )
        let value = try await rpc().request(tag: "orchestration.launchThread", payload: payload)
        if let object = T3JSON.object(value) {
            let projection = object["projection"] ?? object
            if let thread = T3ShellMapper.thread(from: T3JSON.object(projection)?["thread"] as Any)
                ?? T3ShellMapper.thread(from: object["thread"] as Any)
            {
                return T3ShellMapper.session(from: thread)
            }
            if let threadID = T3JSON.string(object, "threadId") {
                let snapshot = try await fetchShell(force: true)
                if let thread = snapshot.thread(id: threadID) {
                    return T3ShellMapper.session(from: thread)
                }
                return Session(
                    id: threadID,
                    title: resolvedTitle,
                    slug: nil,
                    projectID: project.id,
                    directory: project.workspaceRoot,
                    parentID: nil,
                    updatedAtEpochMs: Int64(Date().timeIntervalSince1970 * 1_000),
                    providerInstanceID: selection.instanceID,
                    runtimeMode: "approval-required",
                    modelName: selection.model
                )
            }
        }
        throw T3ClientError.decoding("T3 did not return the new thread.")
    }

    /// Live thread items after `afterSequence`. Throws when the socket cannot be
    /// opened, dies mid-stream, or the server reports a failure, so callers can
    /// surface the error and re-establish the subscription.
    func subscribeThread(id: String, afterSequence: Int) -> AsyncThrowingStream<Any, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let session = try await rpc()
                    let payload: [String: Any] = [
                        "threadId": id,
                        "afterSequence": afterSequence,
                        "requestCompletionMarker": true,
                    ]
                    for try await value in session.subscribe(tag: "orchestration.subscribeThread", payload: payload) {
                        try Task.checkCancellation()
                        continuation.yield(value)
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Opens the socket in the background if it is not already open. Mirrors
    /// Android's `refreshShell -> ensureConnected` so the indicator reflects a
    /// real socket after a foreground refresh without blocking the caller.
    func warmConnection() {
        guard (try? credentials()) != nil else { return }
        Task { [weak self] in
            _ = try? await self?.rpc()
        }
    }

    /// Drops the socket and every identity-bound cache. Called on profile switch
    /// and when the app leaves the foreground; the next call reconnects lazily.
    func reset() async {
        let (session, connect) = locked { () -> (T3RpcSession?, Task<T3RpcSession, Error>?) in
            cachedShell = nil
            cachedShellIdentity = nil
            let session = rpcSession
            rpcSession = nil
            rpcIdentity = nil
            rpcGeneration += 1
            connectIdentity = nil
            let connect = connectTask
            connectTask = nil
            return (session, connect)
        }
        connect?.cancel()
        await session?.close()
        signals.publish(.disconnected)
    }

    private func rpc() async throws -> T3RpcSession {
        let credentials = try credentials()
        let identity = identity(of: credentials)
        let (existing, stale) = locked { () -> (T3RpcSession?, T3RpcSession?) in
            guard let session = rpcSession else { return (nil, nil) }
            if rpcIdentity == identity, !session.isClosed {
                return (session, nil)
            }
            // Dead socket or a different environment: never reuse it.
            rpcSession = nil
            rpcIdentity = nil
            rpcGeneration += 1
            return (nil, session)
        }
        if let existing { return existing }
        if let stale {
            await stale.close()
        }
        let http = self.http
        let baseURL = credentials.baseURL
        let bearer = credentials.bearer
        let makeSession = self.makeSession
        let generation = locked { () -> UInt64 in
            rpcGeneration += 1
            return rpcGeneration
        }
        let publish: @Sendable (T3SocketState) -> Void = { [weak self] state in
            self?.publishSocketState(state, generation: generation)
        }
        let created = Task { @concurrent in
            try Task.checkCancellation()
            let ticket = try await http.issueWebsocketTicket(baseURL: baseURL, bearer: bearer)
            guard let url = T3RpcCodec.websocketURL(httpBase: baseURL, ticket: ticket) else {
                throw T3ClientError.decoding("Could not build the T3 websocket URL.")
            }
            let session = makeSession(publish)
            do {
                try await session.connect(url: url)
                try Task.checkCancellation()
                return session
            } catch {
                await session.close()
                throw error
            }
        }
        let taskToAwait: Task<T3RpcSession, Error> = locked {
            if let session = rpcSession, rpcIdentity == identity, !session.isClosed {
                created.cancel()
                return Task { session }
            }
            if let connectTask, connectIdentity == identity {
                created.cancel()
                return connectTask
            }
            connectTask?.cancel()
            connectIdentity = identity
            connectTask = created
            return created
        }
        if taskToAwait != created {
            // A concurrent caller owns the connect; just share its outcome.
            return try await taskToAwait.value
        }
        do {
            let session = try await created.value
            let keep = locked { () -> Bool in
                guard connectIdentity == identity, connectTask == created else { return false }
                rpcSession = session
                rpcIdentity = identity
                connectTask = nil
                connectIdentity = nil
                return true
            }
            if !keep {
                await session.close()
                throw T3ClientError.rpc("T3 connection was reset while connecting.")
            }
            return session
        } catch {
            locked {
                if connectTask == created {
                    connectTask = nil
                    connectIdentity = nil
                }
            }
            throw error
        }
    }

    private func publishSocketState(_ state: T3SocketState, generation: UInt64) {
        // An old socket closing after a newer one opened must not flip the
        // indicator back to disconnected.
        let current = locked { rpcGeneration == generation }
        guard current else { return }
        switch state {
        case .connecting:
            signals.publish(.connecting)
        case .connected:
            signals.publish(.connected)
        case .disconnected:
            signals.publish(.disconnected)
        }
    }

    @discardableResult
    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}
