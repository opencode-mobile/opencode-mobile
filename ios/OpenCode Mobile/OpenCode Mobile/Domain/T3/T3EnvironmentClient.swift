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
    private let shellRefreshDebounce: Duration
    private let shellResubscribeDelay: Duration
    private var shellFetchTask: Task<T3ShellSnapshot, Error>?
    private var shellFetchIdentity: String?
    private var lastShellFetchStart: ContinuousClock.Instant?
    private var lastShellFetchIdentity: String?
    /// True between lifecycle `start` and `stop`: the shell subscription is kept alive.
    private var shellLiveWanted = false
    private var shellTask: Task<Void, Never>?

    static let defaultShellRefreshDebounce: Duration = .milliseconds(400)
    static let defaultShellResubscribeDelay: Duration = .seconds(1)
    static let maxShellResubscribeDelay: Duration = .seconds(16)

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
        signals: T3LiveSignals = .shared,
        shellRefreshDebounce: Duration = T3EnvironmentClient.defaultShellRefreshDebounce,
        shellResubscribeDelay: Duration = T3EnvironmentClient.defaultShellResubscribeDelay
    ) {
        self.connectionProvider = connectionProvider
        self.http = http
        self.makeSession = makeSession
        self.signals = signals
        self.shellRefreshDebounce = shellRefreshDebounce
        self.shellResubscribeDelay = shellResubscribeDelay
        lifecycleRegistration = signals.onLifecycle { [weak self] lifecycle in
            guard let self else { return }
            switch lifecycle {
            case .stop:
                await self.reset()
            case .start:
                self.startShellLive()
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

    /// REST shell. `force` bypasses the cache but is coalesced: concurrent
    /// callers share one in-flight request, and a forced fetch that starts
    /// within `shellRefreshDebounce` of the previous one waits for the window
    /// to elapse so bursts of thread activity collapse into a single request.
    /// The result never regresses the cache below a newer live sequence.
    func fetchShell(force: Bool = false) async throws -> T3ShellSnapshot {
        let credentials = try credentials()
        let identity = identity(of: credentials)
        if !force, let cached = locked({ cachedShellIdentity == identity ? cachedShell : nil }) {
            return cached
        }
        let http = self.http
        let debounce = shellRefreshDebounce
        let task: Task<T3ShellSnapshot, Error> = locked {
            if let shellFetchTask, shellFetchIdentity == identity {
                return shellFetchTask
            }
            let now = ContinuousClock.now
            var wait: Duration = .zero
            if force, lastShellFetchIdentity == identity, let last = lastShellFetchStart {
                let elapsed = now - last
                if elapsed < debounce { wait = debounce - elapsed }
            }
            let created = Task<T3ShellSnapshot, Error> { [weak self] in
                if wait > .zero {
                    try await Task.sleep(for: wait)
                }
                guard let self else { throw CancellationError() }
                self.locked {
                    self.lastShellFetchStart = ContinuousClock.now
                    self.lastShellFetchIdentity = identity
                }
                let snapshot = try await http.fetchShell(baseURL: credentials.baseURL, bearer: credentials.bearer)
                return self.applyRestShell(snapshot, identity: identity)
            }
            shellFetchTask = created
            shellFetchIdentity = identity
            return created
        }
        defer {
            locked {
                if shellFetchTask == task {
                    shellFetchTask = nil
                    shellFetchIdentity = nil
                }
            }
        }
        let snapshot = try await task.value
        warmConnection()
        return snapshot
    }

    /// Stores a REST snapshot unless the live stream already moved past it,
    /// and publishes status / approval changes (no lifecycle: the caller is
    /// reloading the list itself).
    private func applyRestShell(_ snapshot: T3ShellSnapshot, identity: String) -> T3ShellSnapshot {
        let (previous, applied) = locked { () -> (T3ShellSnapshot?, T3ShellSnapshot) in
            let previous = cachedShellIdentity == identity ? cachedShell : nil
            if let previous, snapshot.sequence > 0, snapshot.sequence < previous.sequence {
                return (previous, previous)
            }
            cachedShell = snapshot
            cachedShellIdentity = identity
            return (previous, snapshot)
        }
        if applied != previous {
            publishShellEvents(T3ShellLive.events(previous: previous, next: applied, includeLifecycle: false))
        }
        return applied
    }

    private func publishShellEvents(_ events: [OpencodeEvent]) {
        for event in events {
            signals.publishEvent(event)
        }
    }

    // MARK: - Shell subscription

    /// Keeps `orchestration.subscribeShell` running until `reset()`. Items
    /// update the cached shell and publish the resulting synthetic events; a
    /// dead socket re-subscribes from the last applied sequence through the
    /// client's normal reconnect.
    func startShellLive() {
        locked { shellLiveWanted = true }
        ensureShellSubscription()
    }

    var isShellLiveRunning: Bool {
        locked { shellTask != nil }
    }

    private func ensureShellSubscription() {
        guard (try? credentials()) != nil else { return }
        locked {
            guard shellLiveWanted, shellTask == nil else { return }
            shellTask = Task { [weak self] in
                await self?.runShellSubscription()
            }
        }
    }

    private func runShellSubscription() async {
        var attempt = 0
        while !Task.isCancelled {
            var failure: Error?
            do {
                let credentials = try credentials()
                let identity = identity(of: credentials)
                let session = try await rpc()
                let afterSequence = locked { cachedShellIdentity == identity ? cachedShell?.sequence : nil }
                let payload = T3RpcCodec.subscribeShellPayload(afterSequence: afterSequence)
                for try await item in session.subscribe(tag: T3RpcCodec.subscribeShellTag, payload: payload) {
                    if Task.isCancelled { return }
                    attempt = 0
                    applyShellStreamItem(item, identity: identity)
                }
                failure = T3ClientError.rpc("T3 shell subscription ended.")
            } catch is CancellationError {
                return
            } catch {
                failure = error
            }
            if Task.isCancelled { return }
            if let failure {
                DiagnosticLog.warning(.sse, "T3 shell subscription lost: \(failure)")
                // The stream refused or died; a REST pass keeps home converging
                // while the socket comes back.
                Task { [weak self] in
                    _ = try? await self?.fetchShell(force: true)
                }
            }
            let delay = min(
                Self.maxShellResubscribeDelay,
                shellResubscribeDelay * (1 << min(attempt, 4))
            )
            attempt += 1
            try? await Task.sleep(for: delay)
        }
    }

    private func applyShellStreamItem(_ item: Any, identity: String) {
        let outcome = locked { () -> (T3ShellSnapshot?, T3ShellSnapshot)? in
            let current = cachedShellIdentity == identity ? cachedShell : nil
            switch T3ShellLive.apply(item, to: current) {
            case .ignored, .synchronized:
                return nil
            case let .replaced(next, _):
                if let current, next.sequence > 0, next.sequence < current.sequence { return nil }
                cachedShell = next
                cachedShellIdentity = identity
                return (current, next)
            }
        }
        guard let (previous, next) = outcome else { return }
        publishShellEvents(T3ShellLive.events(previous: previous, next: next, includeLifecycle: true))
    }

    func replaceShell(_ snapshot: T3ShellSnapshot) {
        guard let credentials = try? credentials() else { return }
        let identity = identity(of: credentials)
        locked {
            cachedShell = snapshot
            cachedShellIdentity = identity
        }
    }

    /// Cached shell for the active connection, if any (no network).
    var cachedShellSnapshot: T3ShellSnapshot? {
        guard let credentials = try? credentials() else { return nil }
        let identity = identity(of: credentials)
        return locked { cachedShellIdentity == identity ? cachedShell : nil }
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
        let (session, connect, shell, fetch) = locked {
            () -> (T3RpcSession?, Task<T3RpcSession, Error>?, Task<Void, Never>?, Task<T3ShellSnapshot, Error>?) in
            cachedShell = nil
            cachedShellIdentity = nil
            shellLiveWanted = false
            let shell = shellTask
            shellTask = nil
            let fetch = shellFetchTask
            shellFetchTask = nil
            shellFetchIdentity = nil
            lastShellFetchStart = nil
            lastShellFetchIdentity = nil
            let session = rpcSession
            rpcSession = nil
            rpcIdentity = nil
            rpcGeneration += 1
            connectIdentity = nil
            let connect = connectTask
            connectTask = nil
            return (session, connect, shell, fetch)
        }
        shell?.cancel()
        fetch?.cancel()
        connect?.cancel()
        await session?.close()
        signals.publish(.disconnected)
    }

    private func rpc() async throws -> T3RpcSession {
        let credentials = try credentials()
        let identity = identity(of: credentials)
        let http = self.http
        let baseURL = credentials.baseURL
        let bearer = credentials.bearer
        let makeSession = self.makeSession
        // Created and registered under one lock so two concurrent callers can
        // never open two sockets; the new connect settles the previous one
        // (stale socket or cancelled connect) before opening its own.
        let task: Task<T3RpcSession, Error> = locked {
            if let session = rpcSession, rpcIdentity == identity, !session.isClosed {
                return Task { session }
            }
            if let connectTask, connectIdentity == identity {
                return connectTask
            }
            // Dead socket or a different environment: never reuse it.
            let stale = rpcSession
            rpcSession = nil
            rpcIdentity = nil
            let previous = connectTask
            previous?.cancel()
            rpcGeneration += 1
            let generation = rpcGeneration
            let publish: @Sendable (T3SocketState) -> Void = { [weak self] state in
                self?.publishSocketState(state, generation: generation)
            }
            let created = Task<T3RpcSession, Error> { @concurrent [weak self] in
                if let previous {
                    _ = try? await previous.value
                }
                if let stale {
                    await stale.close()
                }
                try Task.checkCancellation()
                let ticket = try await http.issueWebsocketTicket(baseURL: baseURL, bearer: bearer)
                guard let url = T3RpcCodec.websocketURL(httpBase: baseURL, ticket: ticket) else {
                    throw T3ClientError.decoding("Could not build the T3 websocket URL.")
                }
                let session = makeSession(publish)
                do {
                    try await session.connect(url: url)
                    try Task.checkCancellation()
                } catch {
                    await session.close()
                    throw error
                }
                let keep = self?.locked { () -> Bool in
                    guard let self, self.rpcGeneration == generation else { return false }
                    self.rpcSession = session
                    self.rpcIdentity = identity
                    self.connectTask = nil
                    self.connectIdentity = nil
                    return true
                } ?? false
                if !keep {
                    await session.close()
                    throw T3ClientError.rpc("T3 connection was reset while connecting.")
                }
                return session
            }
            connectIdentity = identity
            connectTask = created
            return created
        }
        do {
            return try await task.value
        } catch {
            locked {
                if connectTask == task {
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
