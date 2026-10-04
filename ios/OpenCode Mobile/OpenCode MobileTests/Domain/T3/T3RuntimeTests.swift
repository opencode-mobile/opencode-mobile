import Foundation
import Testing
@testable import OpenCode_Mobile

/// Socket lifecycle for the T3 engine: reconnect after close, timeouts,
/// keepalive, subscription re-establishment, profile-switch reset, and
/// connection-state publication. Everything runs against in-memory fakes.
struct T3RuntimeTests {
    private static let urlA = URL(string: "http://t3-a.test/")!
    private static let urlB = URL(string: "http://t3-b.test/")!

    // MARK: - Socket reuse and reconnect

    @Test func rpc_reconnectsWithFreshTicketAfterServerClose() async throws {
        let harness = T3ClientHarness()
        let first = Task { try await harness.client.dispatchCommand(["type": "noop"]) }
        let socket1 = try await harness.connector.connection(at: 0)
        socket1.open()
        let request = try await socket1.firstRequest(tag: "orchestration.dispatchCommand")
        socket1.receive(Frames.exitSuccess(requestID: request.id, value: NSNull()))
        try await first.value
        #expect(harness.signals.connectionState == .connected)

        socket1.serverClose()
        #expect(await waitUntil { harness.signals.connectionState == .disconnected })

        let second = Task { try await harness.client.dispatchCommand(["type": "noop"]) }
        let socket2 = try await harness.connector.connection(at: 1)
        #expect(socket2 !== socket1)
        socket2.open()
        let request2 = try await socket2.firstRequest(tag: "orchestration.dispatchCommand")
        socket2.receive(Frames.exitSuccess(requestID: request2.id, value: NSNull()))
        try await second.value
        #expect(harness.http.ticketCount == 2)
        #expect(harness.signals.connectionState == .connected)
    }

    @Test func pendingRequest_failsInsteadOfHangingWhenSocketCloses() async throws {
        let harness = T3ClientHarness()
        let call = Task { try await harness.client.dispatchCommand(["type": "noop"]) }
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        _ = try await socket.firstRequest(tag: "orchestration.dispatchCommand")
        socket.serverClose()
        await #expect(throws: T3SocketError.connectionLost(detail: "T3 websocket closed.")) { try await call.value }
    }

    @Test func subscribe_onDeadSessionThrowsImmediately() async throws {
        let session = T3RpcSession(connector: FakeSocketConnector())
        var thrown: Error?
        do {
            for try await _ in session.subscribe(tag: "orchestration.subscribeThread", payload: [:]) {}
        } catch {
            thrown = error
        }
        #expect(thrown as? T3SocketError == .connectionLost(detail: "T3 websocket is closed."))
        #expect(thrown?.localizedDescription == "Lost connection to T3")
    }

    // MARK: - Timeouts and keepalive

    @Test func request_timesOutAndInterruptsServer() async throws {
        let harness = T3ClientHarness(requestTimeout: .milliseconds(80))
        let call = Task { try await harness.client.dispatchCommand(["type": "noop"]) }
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        let request = try await socket.firstRequest(tag: "orchestration.dispatchCommand")
        await #expect(throws: T3ClientError.rpc("T3 request orchestration.dispatchCommand timed out.")) {
            try await call.value
        }
        #expect(await waitUntil { socket.frames(tag: "Interrupt").contains { $0["requestId"] as? String == request.id } })
        // The socket itself stays alive; only the request failed.
        #expect(socket.isClosed == false)
    }

    @Test func connect_timesOutWhenSocketNeverOpens() async throws {
        let harness = T3ClientHarness(connectTimeout: .milliseconds(80))
        await #expect(throws: T3ClientError.transport("Timed out connecting to the T3 websocket.")) {
            try await harness.client.dispatchCommand(["type": "noop"])
        }
        let socket = try await harness.connector.connection(at: 0)
        #expect(socket.isClosed)
        #expect(harness.signals.connectionState == .disconnected)
    }

    @Test func keepalive_sendsPingAndAnswersServerPing() async throws {
        let harness = T3ClientHarness(keepaliveInterval: .milliseconds(30))
        let call = Task { try await harness.client.dispatchCommand(["type": "noop"]) }
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        let request = try await socket.firstRequest(tag: "orchestration.dispatchCommand")
        socket.receive(Frames.exitSuccess(requestID: request.id, value: NSNull()))
        try await call.value
        #expect(await waitUntil { !socket.frames(tag: "Ping").isEmpty })
        socket.receive(#"{"_tag":"Ping"}"#)
        #expect(await waitUntil { !socket.frames(tag: "Pong").isEmpty })
    }

    // MARK: - Thread subscription

    @MainActor
    @Test func subscription_reestablishesAfterReconnectFromLastKnownSequence() async throws {
        let harness = T3ClientHarness()
        harness.http.threadDetail = T3ThreadDetail(
            sequence: 5,
            thread: Fixtures.thread(id: "t1"),
            messages: [T3ConversationMessage(id: "m1", role: "user", text: "Hello", streaming: false)]
        )
        let repository = T3MessageRepository(
            client: harness.client,
            resubscribeDelay: .milliseconds(10)
        )
        let collector = UpdateCollector()
        let observe = Task {
            for await update in repository.observeMessages(sessionID: "t1", directory: nil) {
                collector.append(update)
            }
        }

        let socket1 = try await harness.connector.connection(at: 0)
        socket1.open()
        let subscribe1 = try await socket1.firstRequest(tag: "orchestration.subscribeThread")
        #expect(subscribe1.payload["threadId"] as? String == "t1")
        #expect(subscribe1.payload["afterSequence"] as? Int == 5)
        #expect(repository.subscribedSessionIDs == ["t1"])

        socket1.receive(Frames.chunk(requestID: subscribe1.id, values: [
            Fixtures.messageEvent(sequence: 7, id: "m2", text: "Reply"),
        ]))
        #expect(await waitUntil { collector.latestMessages()?.map(\.id) == ["m1", "m2"] })
        #expect(await waitUntil { !socket1.frames(tag: "Ack").isEmpty })
        #expect(repository.lastKnownSequence(sessionID: "t1") == 7)

        socket1.serverClose()
        #expect(await waitUntil { collector.failureCount > 0 })

        let socket2 = try await harness.connector.connection(at: 1)
        socket2.open()
        let subscribe2 = try await socket2.firstRequest(tag: "orchestration.subscribeThread")
        #expect(subscribe2.payload["afterSequence"] as? Int == 7)
        #expect(harness.http.ticketCount == 2)

        observe.cancel()
        #expect(await waitOnMain { repository.subscribedSessionIDs.isEmpty })
        #expect(await waitUntil {
            socket2.frames(tag: "Interrupt").contains { $0["requestId"] as? String == subscribe2.id }
        })
    }

    @MainActor
    @Test func subscription_rpcFailureReachesObserversAsLoadFailed() async throws {
        let harness = T3ClientHarness()
        harness.http.threadDetail = T3ThreadDetail(sequence: 1, thread: Fixtures.thread(id: "t1"), messages: [])
        let repository = T3MessageRepository(client: harness.client, resubscribeDelay: .seconds(30))
        let collector = UpdateCollector()
        let observe = Task {
            for await update in repository.observeMessages(sessionID: "t1", directory: nil) {
                collector.append(update)
            }
        }
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        let subscribe = try await socket.firstRequest(tag: "orchestration.subscribeThread")
        socket.receive(Frames.exitFailure(requestID: subscribe.id, message: "thread gone"))
        #expect(await waitUntil { collector.failureDetails.contains("thread gone") })
        #expect(collector.failureMessages == ["T3 rejected the request"])
        observe.cancel()
    }

    // MARK: - Protocol re-check on fresh sockets

    @Test func rpc_validatesProtocolOncePerIdentityAcrossReconnects() async throws {
        let harness = T3ClientHarness()
        let first = Task { try await harness.client.dispatchCommand(["type": "noop"]) }
        let socket1 = try await harness.connector.connection(at: 0)
        socket1.open()
        let request = try await socket1.firstRequest(tag: "orchestration.dispatchCommand")
        socket1.receive(Frames.exitSuccess(requestID: request.id, value: NSNull()))
        try await first.value
        #expect(harness.http.descriptorCount == 1)

        socket1.serverClose()
        #expect(await waitUntil { harness.signals.connectionState == .disconnected })
        let second = Task { try await harness.client.dispatchCommand(["type": "noop"]) }
        let socket2 = try await harness.connector.connection(at: 1)
        socket2.open()
        let request2 = try await socket2.firstRequest(tag: "orchestration.dispatchCommand")
        socket2.receive(Frames.exitSuccess(requestID: request2.id, value: NSNull()))
        try await second.value
        #expect(harness.http.ticketCount == 2)
        #expect(harness.http.descriptorCount == 1, "a passed environment is not re-fetched this session")
    }

    @Test func rpc_descriptorOutageDoesNotBlockConnecting() async throws {
        let harness = T3ClientHarness()
        harness.http.descriptorResult = .failure(T3ClientError.http(statusCode: 503, message: "down"))
        let call = Task { try await harness.client.dispatchCommand(["type": "noop"]) }
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        let request = try await socket.firstRequest(tag: "orchestration.dispatchCommand")
        socket.receive(Frames.exitSuccess(requestID: request.id, value: NSNull()))
        try await call.value
        #expect(harness.http.descriptorCount == 1)
    }

    @MainActor
    @Test func rpc_protocolMismatchStopsRetryingAndSurfacesError() async throws {
        let harness = T3ClientHarness(shellResubscribeDelay: .milliseconds(10))
        harness.http.descriptorResult = .failure(T3ClientError.unsupportedProtocol(1))
        harness.http.threadDetail = T3ThreadDetail(sequence: 1, thread: Fixtures.thread(id: "t1"), messages: [])
        let repository = T3MessageRepository(client: harness.client, resubscribeDelay: .milliseconds(10))
        let collector = UpdateCollector()
        let observe = Task {
            for await update in repository.observeMessages(sessionID: "t1", directory: nil) {
                collector.append(update)
            }
        }
        await harness.signals.emit(.start)

        // The message stream and the connection state both show the mismatch.
        #expect(await waitUntil { collector.failureMessages.contains(T3ClientError.unsupportedProtocol(1).errorDescription ?? "") })
        #expect(await waitUntil { harness.signals.connectionState == .authFailed })
        // Nothing retries: no socket ever opened and the descriptor was fetched once.
        try await Task.sleep(for: .milliseconds(300))
        #expect(harness.connector.count == 0)
        #expect(harness.http.ticketCount == 0)
        #expect(harness.http.descriptorCount == 1)
        #expect(harness.client.isShellLiveRunning == false)
        #expect(await waitOnMain { repository.subscribedSessionIDs.isEmpty })
        await #expect(throws: T3ClientError.unsupportedProtocol(1)) {
            try await harness.client.dispatchCommand(["type": "noop"])
        }
        #expect(harness.http.descriptorCount == 1)

        // A stop (profile switch / background) forgets the verdict so an upgraded or re-paired environment is checked again.
        await harness.signals.emit(.stop)
        harness.http.descriptorResult = .success(FakeT3Http.v2Descriptor)
        await harness.signals.emit(.start)
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        #expect(await waitUntil { harness.signals.connectionState == .connected })
        #expect(harness.http.descriptorCount == 2)
        observe.cancel()
    }

    // MARK: - Error text

    @Test func errorText_isShortAndKeepsCauseForDiagnostics() {
        #expect(T3ErrorText.userMessage(T3ClientError.accessRevoked(statusCode: 401, body: "{\"trace\":\"x\"}")) == T3ClientError.accessRevokedMessage)
        #expect(T3ClientError.accessRevokedMessage.contains("Re-pair this environment"))
        #expect(T3ErrorText.detail(T3ClientError.accessRevoked(statusCode: 401, body: "{\"trace\":\"x\"}")) == "{\"trace\":\"x\"}")
        #expect(T3ErrorText.userMessage(T3SocketError.connectionLost(detail: "NSPOSIXErrorDomain 57")) == "Lost connection to T3")
        #expect(T3ErrorText.detail(T3SocketError.connectionLost(detail: "NSPOSIXErrorDomain 57")) == "NSPOSIXErrorDomain 57")
        #expect(T3ErrorText.userMessage(T3SocketError.rejected(cause: "ThreadNotFound")) == "T3 rejected the request")
        #expect(T3SocketError.rejected(cause: "ThreadNotFound").localizedDescription == "T3 rejected the request")
        #expect(T3ErrorText.detail(T3SocketError.rejected(cause: "ThreadNotFound")) == "ThreadNotFound")
        #expect(T3ErrorText.userMessage(T3ClientError.unsupportedProtocol(1)) == T3ClientError.unsupportedProtocol(1).errorDescription)
        #expect(T3ErrorText.userMessage(T3ClientError.http(statusCode: 500, message: "<html>stack</html>")) == "T3 request failed (500).")
        struct Opaque: Error {}
        #expect(T3ErrorText.userMessage(Opaque()) == "T3 request failed")
    }

    // MARK: - Profile switch and reset

    @Test func profileSwitch_closesOldSocketAndDropsShellCache() async throws {
        let harness = T3ClientHarness()
        harness.http.shell = Fixtures.shell(threadID: "t-a")
        _ = try await harness.client.fetchShell(force: true)
        #expect(harness.client.cachedThread(id: "t-a") != nil)

        let socket1 = try await harness.connector.connection(at: 0)
        socket1.open()
        #expect(await waitUntil { harness.signals.connectionState == .connected })

        harness.switchProfile(to: Self.urlB)
        #expect(harness.client.cachedThread(id: "t-a") == nil)

        let call = Task { try await harness.client.dispatchCommand(["type": "noop"]) }
        let socket2 = try await harness.connector.connection(at: 1)
        #expect(socket1.isClosed)
        #expect(harness.http.ticketBaseURLs.last == Self.urlB)
        socket2.open()
        let request = try await socket2.firstRequest(tag: "orchestration.dispatchCommand")
        socket2.receive(Frames.exitSuccess(requestID: request.id, value: NSNull()))
        try await call.value
    }

    @Test func lifecycleStop_resetsSocketAndCache() async throws {
        let harness = T3ClientHarness()
        harness.http.shell = Fixtures.shell(threadID: "t-a")
        _ = try await harness.client.fetchShell(force: true)
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        #expect(await waitUntil { harness.signals.connectionState == .connected })

        await harness.signals.emit(.stop)
        #expect(socket.isClosed)
        #expect(harness.client.cachedThread(id: "t-a") == nil)
        #expect(harness.signals.connectionState == .disconnected)

        await harness.signals.emit(.start)
        let socket2 = try await harness.connector.connection(at: 1)
        socket2.open()
        #expect(await waitUntil { harness.signals.connectionState == .connected })
    }

    @Test func connectionSessionCoordinator_resetsEngineRuntimeOnProfileSwitch() async throws {
        let resets = Counter()
        let store = SwitchingProfileStore(profiles: [
            Fixtures.profile(id: "p1", baseURL: Self.urlA.absoluteString),
            Fixtures.profile(id: "p2", baseURL: Self.urlB.absoluteString),
        ])
        let manager = ActiveConnectionManager(profileStore: store)
        let coordinator = ConnectionSessionCoordinator(
            activeConnectionManager: manager,
            connectionProfileStore: store,
            resetEngineRuntime: { resets.increment() }
        )
        await coordinator.switchProfile(id: "p2")
        #expect(resets.value == 1)
        #expect(manager.currentProfile()?.id == "p2")
    }

    // MARK: - Connection indicator

    @Test func foregroundEventStream_followsT3SocketState() async throws {
        let signals = T3LiveSignals()
        let lifecycle = LifecycleRecorder()
        let stream = DefaultForegroundEventStream(
            api: EventsAPIClient(
                connectionProvider: { ServerConnection(baseURL: Self.urlA, password: "secret") },
                urlSession: URLSession(configuration: .ephemeral)
            ),
            adapter: EventDomainAdapter(),
            engineProvider: { .t3 },
            t3ConnectionStates: { signals.connectionStateUpdates() },
            t3Lifecycle: { lifecycle.record($0) }
        )
        stream.start()
        #expect(await waitUntil { lifecycle.events == [.start] })
        #expect(await waitUntil { stream.connectionState == .disconnected })

        signals.publish(.connecting)
        #expect(await waitUntil { stream.connectionState == .connecting })
        signals.publish(.connected)
        #expect(await waitUntil { stream.connectionState == .connected })
        #expect(await waitUntil { stream.snapshot.liveTargets == ["t3"] })
        signals.publish(.disconnected)
        #expect(await waitUntil { stream.connectionState == .disconnected })
        #expect(await waitUntil { stream.snapshot.liveTargets.isEmpty })

        await stream.stop()
        #expect(lifecycle.events == [.start, .stop])
        #expect(stream.connectionState == .idle)
        signals.publish(.connected)
        try await Task.sleep(for: .milliseconds(50))
        #expect(stream.connectionState == .idle)
    }

    // MARK: - launchThread

    @Test func launchThread_usesRequestedProjectAndItsDefaultModel() async throws {
        let harness = T3ClientHarness()
        harness.http.shell = T3ShellSnapshot(
            sequence: 1,
            threads: [],
            projects: [
                T3Project(id: "p1", title: "One", workspaceRoot: "/one", defaultModelSelection: T3ModelSelection(instanceID: "i1", model: "m1")),
                T3Project(id: "p2", title: "Two", workspaceRoot: "/two", defaultModelSelection: T3ModelSelection(instanceID: "i2", model: "m2")),
            ]
        )
        let call = Task { try await harness.client.launchThread(title: "Hi", projectID: "p2") }
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        let responder = T3ConfigResponder.start(socket, config: T3ConfigFixtures.config(providers: [
            T3ConfigFixtures.provider("i1", models: [T3ConfigFixtures.model("m1")]),
            T3ConfigFixtures.provider("i2", models: [T3ConfigFixtures.model("m2")]),
        ]))
        defer { responder.cancel() }
        let request = try await socket.firstRequest(tag: "orchestration.launchThread")
        #expect(request.payload["projectId"] as? String == "p2")
        let selection = request.payload["modelSelection"] as? [String: Any]
        #expect(selection?["model"] as? String == "m2")
        socket.receive(Frames.exitSuccess(requestID: request.id, value: [
            "thread": ["id": "t9", "title": "Hi", "projectId": "p2", "status": "idle"],
        ]))
        let session = try await call.value
        #expect(session.id == "t9")
        #expect(session.projectID == "p2")
    }

    @Test func launchThread_fallsBackToFirstProjectForUnknownProjectID() async throws {
        let harness = T3ClientHarness()
        harness.http.shell = T3ShellSnapshot(
            sequence: 1,
            threads: [],
            projects: [
                T3Project(id: "p1", title: "One", workspaceRoot: "/one", defaultModelSelection: T3ModelSelection(instanceID: "i1", model: "m1")),
            ]
        )
        let call = Task { try await harness.client.launchThread(title: "Hi", projectID: "missing") }
        let socket = try await harness.connector.connection(at: 0)
        socket.open()
        let responder = T3ConfigResponder.start(socket, config: T3ConfigFixtures.config(providers: [
            T3ConfigFixtures.provider("i1", models: [T3ConfigFixtures.model("m1")]),
        ]))
        defer { responder.cancel() }
        let request = try await socket.firstRequest(tag: "orchestration.launchThread")
        #expect(request.payload["projectId"] as? String == "p1")
        socket.receive(Frames.exitSuccess(requestID: request.id, value: [
            "thread": ["id": "t1", "title": "Hi", "projectId": "p1", "status": "idle"],
        ]))
        _ = try await call.value
    }
}

// MARK: - T3ClientHarness

private final class ProfileBox: @unchecked Sendable {
    private let lock = NSLock()
    private var baseURL = URL(string: "http://t3-a.test/")!

    func connection() -> ServerConnection {
        lock.lock()
        defer { lock.unlock() }
        return ServerConnection(baseURL: baseURL, password: "bearer-token")
    }

    func set(_ url: URL) {
        lock.lock()
        baseURL = url
        lock.unlock()
    }
}

final class T3ClientHarness: @unchecked Sendable {
    let http: FakeT3Http
    let connector: FakeSocketConnector
    let signals: T3LiveSignals
    let client: T3EnvironmentClient
    private let profile: ProfileBox

    init(
        connectTimeout: Duration = .seconds(5),
        requestTimeout: Duration = .seconds(5),
        keepaliveInterval: Duration = .seconds(60),
        shellRefreshDebounce: Duration = T3EnvironmentClient.defaultShellRefreshDebounce,
        shellResubscribeDelay: Duration = .milliseconds(10)
    ) {
        let http = FakeT3Http()
        let connector = FakeSocketConnector()
        let signals = T3LiveSignals()
        let profile = ProfileBox()
        self.profile = profile
        self.http = http
        self.connector = connector
        self.signals = signals
        client = T3EnvironmentClient(
            connectionProvider: { profile.connection() },
            http: http,
            makeSession: { onStateChange in
                T3RpcSession(
                    connector: connector,
                    connectTimeout: connectTimeout,
                    requestTimeout: requestTimeout,
                    keepaliveInterval: keepaliveInterval,
                    onStateChange: onStateChange
                )
            },
            signals: signals,
            shellRefreshDebounce: shellRefreshDebounce,
            shellResubscribeDelay: shellResubscribeDelay
        )
    }

    func switchProfile(to url: URL) {
        profile.set(url)
    }
}

final class FakeT3Http: T3HttpTransport, @unchecked Sendable {
    static let v2Descriptor = T3EnvironmentDescriptor(environmentID: "env-1", label: "Desk", orchestrationProtocolVersion: 2)

    private let lock = NSLock()
    private var _ticketCount = 0
    private var _shellFetchCount = 0
    private var _descriptorCount = 0
    private var _descriptorResult: Result<T3EnvironmentDescriptor, Error> = .success(FakeT3Http.v2Descriptor)
    private var _ticketBaseURLs: [URL] = []
    private var _shell = T3ShellSnapshot(sequence: 0, threads: [], projects: [])
    private var _threadDetail = T3ThreadDetail(sequence: 0, thread: nil, messages: [])

    var descriptorCount: Int { lock.lock(); defer { lock.unlock() }; return _descriptorCount }
    var descriptorResult: Result<T3EnvironmentDescriptor, Error> {
        get { lock.lock(); defer { lock.unlock() }; return _descriptorResult }
        set { lock.lock(); _descriptorResult = newValue; lock.unlock() }
    }

    var ticketCount: Int { lock.lock(); defer { lock.unlock() }; return _ticketCount }
    var shellFetchCount: Int { lock.lock(); defer { lock.unlock() }; return _shellFetchCount }
    var ticketBaseURLs: [URL] { lock.lock(); defer { lock.unlock() }; return _ticketBaseURLs }
    var shell: T3ShellSnapshot {
        get { lock.lock(); defer { lock.unlock() }; return _shell }
        set { lock.lock(); _shell = newValue; lock.unlock() }
    }

    var threadDetail: T3ThreadDetail {
        get { lock.lock(); defer { lock.unlock() }; return _threadDetail }
        set { lock.lock(); _threadDetail = newValue; lock.unlock() }
    }

    func fetchDescriptor(baseURL _: URL) async throws -> T3EnvironmentDescriptor {
        lock.lock()
        _descriptorCount += 1
        let result = _descriptorResult
        lock.unlock()
        return try result.get()
    }

    func fetchShell(baseURL _: URL, bearer _: String) async throws -> T3ShellSnapshot {
        lock.lock()
        _shellFetchCount += 1
        lock.unlock()
        return shell
    }

    func fetchThread(baseURL _: URL, bearer _: String, id: String) async throws -> T3ThreadDetail {
        var detail = threadDetail
        detail.thread = detail.thread ?? Fixtures.thread(id: id)
        return detail
    }

    func issueWebsocketTicket(baseURL: URL, bearer _: String) async throws -> String {
        lock.lock()
        _ticketCount += 1
        _ticketBaseURLs.append(baseURL)
        let count = _ticketCount
        lock.unlock()
        return "ticket-\(count)"
    }

    func fetchBytes(url _: URL, maxBytes _: Int) async -> Data? { nil }
}

final class FakeSocketConnector: T3SocketConnector, @unchecked Sendable {
    private let lock = NSLock()
    private var connections: [FakeSocketConnection] = []

    func open(url: URL, events: T3SocketEvents) -> any T3SocketConnection {
        let connection = FakeSocketConnection(url: url, events: events)
        lock.lock()
        connections.append(connection)
        lock.unlock()
        return connection
    }

    func connection(at index: Int) async throws -> FakeSocketConnection {
        let found = await waitUntil { self.count > index }
        guard found else {
            struct NoConnection: Error {}
            throw NoConnection()
        }
        lock.lock()
        defer { lock.unlock() }
        return connections[index]
    }

    var count: Int {
        lock.lock()
        defer { lock.unlock() }
        return connections.count
    }
}

final class FakeSocketConnection: T3SocketConnection, @unchecked Sendable {
    struct Request {
        let id: String
        let tag: String
        let payload: [String: Any]
    }

    let url: URL
    private let events: T3SocketEvents
    private let lock = NSLock()
    private var sent: [[String: Any]] = []
    private var _closed = false

    init(url: URL, events: T3SocketEvents) {
        self.url = url
        self.events = events
    }

    var isClosed: Bool { lock.lock(); defer { lock.unlock() }; return _closed }

    func send(_ text: String, completion: @escaping @Sendable (Error?) -> Void) {
        lock.lock()
        if _closed {
            lock.unlock()
            completion(T3ClientError.rpc("closed"))
            return
        }
        if let object = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any] {
            sent.append(object)
        }
        lock.unlock()
        completion(nil)
    }

    func close() {
        lock.lock()
        _closed = true
        lock.unlock()
    }

    func open() { events.onOpen() }
    func receive(_ text: String) { events.onText(text) }

    func serverClose() {
        close()
        events.onClose(nil)
    }

    func frames(tag: String) -> [[String: Any]] {
        lock.lock()
        defer { lock.unlock() }
        return sent.filter { $0["_tag"] as? String == tag }
    }

    func firstRequest(tag: String) async throws -> Request {
        let found = await waitUntil { self.request(tag: tag) != nil }
        guard found, let request = request(tag: tag) else {
            struct NoRequest: Error { let tag: String }
            throw NoRequest(tag: tag)
        }
        return request
    }

    private func request(tag: String) -> Request? {
        frames(tag: "Request")
            .first { $0["tag"] as? String == tag }
            .map {
                Request(
                    id: $0["id"] as? String ?? "",
                    tag: tag,
                    payload: $0["payload"] as? [String: Any] ?? [:]
                )
            }
    }
}

enum Frames {
    static func exitSuccess(requestID: String, value: Any) -> String {
        encode(["_tag": "Exit", "requestId": requestID, "exit": ["_tag": "Success", "value": value]])
    }

    static func exitFailure(requestID: String, message: String) -> String {
        encode(["_tag": "Exit", "requestId": requestID, "exit": ["_tag": "Failure", "cause": message]])
    }

    static func chunk(requestID: String, values: [Any]) -> String {
        encode(["_tag": "Chunk", "requestId": requestID, "values": values])
    }

    private static func encode(_ object: [String: Any]) -> String {
        String(decoding: try! JSONSerialization.data(withJSONObject: object), as: UTF8.self)
    }
}

enum Fixtures {
    static func thread(id: String) -> T3Thread {
        T3Thread(
            id: id,
            title: "Thread \(id)",
            projectID: "p1",
            status: "idle",
            modelSelection: T3ModelSelection(instanceID: "i1", model: "m1")
        )
    }

    static func shell(threadID: String) -> T3ShellSnapshot {
        T3ShellSnapshot(
            sequence: 1,
            threads: [thread(id: threadID)],
            projects: [T3Project(id: "p1", title: "One", workspaceRoot: "/one", defaultModelSelection: nil)]
        )
    }

    static func messageEvent(sequence: Int, id: String, text: String) -> [String: Any] {
        [
            "kind": "event",
            "sequence": sequence,
            "event": [
                "type": "message.updated",
                "payload": ["id": id, "role": "assistant", "text": text, "streaming": false],
            ],
        ]
    }

    static func profile(id: String, baseURL: String) -> ConnectionProfile {
        var profile = ConnectionProfile(baseURL: baseURL, username: "opencode", password: "secret")
        profile.id = id
        profile.engine = .t3
        return profile
    }
}

private final class UpdateCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var updates: [MessageListUpdate] = []

    func append(_ update: MessageListUpdate) {
        lock.lock()
        updates.append(update)
        lock.unlock()
    }

    func latestMessages() -> [Message]? {
        lock.lock()
        defer { lock.unlock() }
        for update in updates.reversed() {
            if case let .messages(messages, _) = update { return messages }
        }
        return nil
    }

    var failureCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return updates.filter { if case .loadFailed = $0 { true } else { false } }.count
    }

    var failureMessages: [String] {
        lock.lock()
        defer { lock.unlock() }
        return updates.compactMap {
            if case let .loadFailed(error) = $0 { T3ErrorText.userMessage(error) } else { nil }
        }
    }

    var failureDetails: [String] {
        lock.lock()
        defer { lock.unlock() }
        return updates.compactMap {
            if case let .loadFailed(error) = $0 { T3ErrorText.detail(error) } else { nil }
        }
    }
}

private final class LifecycleRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var _events: [T3LiveSignals.Lifecycle] = []

    var events: [T3LiveSignals.Lifecycle] { lock.lock(); defer { lock.unlock() }; return _events }

    func record(_ event: T3LiveSignals.Lifecycle) {
        lock.lock()
        _events.append(event)
        lock.unlock()
    }
}

private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var _value = 0
    var value: Int { lock.lock(); defer { lock.unlock() }; return _value }
    func increment() { lock.lock(); _value += 1; lock.unlock() }
}

private final class SwitchingProfileStore: ConnectionProfileStore, @unchecked Sendable {
    private let lock = NSLock()
    private var profiles: [ConnectionProfile]
    private var activeID: String?

    init(profiles: [ConnectionProfile]) {
        self.profiles = profiles
        activeID = profiles.first?.id
    }

    func activeProfile() async -> ConnectionProfile? {
        lock.lock()
        defer { lock.unlock() }
        return profiles.first { $0.id == activeID }
    }

    func listProfiles() async -> [ConnectionProfile] { lock.lock(); defer { lock.unlock() }; return profiles }

    func saveProfile(_ profile: ConnectionProfile) async throws {
        lock.lock()
        if let index = profiles.firstIndex(where: { $0.id == profile.id }) {
            profiles[index] = profile
        } else {
            profiles.append(profile)
        }
        lock.unlock()
    }

    func applyProfile(id: String) async {
        lock.lock()
        activeID = id
        lock.unlock()
    }

    func removeProfile(id: String) async {
        lock.lock()
        profiles.removeAll { $0.id == id }
        lock.unlock()
    }

    func addedWorktrees(serverBaseURL _: String) async -> [String] { [] }
    func addWorktree(serverBaseURL _: String, worktree _: String) async {}
    func removeWorktree(serverBaseURL _: String, worktree _: String) async {}
    func setAddedWorktrees(serverBaseURL _: String, worktrees _: [String]) async {}
    func clearAll() async {}
}

@MainActor
private func waitOnMain(
    timeoutMs: Int = 5_000,
    _ predicate: @MainActor () -> Bool
) async -> Bool {
    let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
    while Date() < deadline {
        if predicate() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return predicate()
}

private func waitUntil(
    timeoutMs: Int = 5_000,
    _ predicate: @escaping @Sendable () -> Bool
) async -> Bool {
    let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
    while Date() < deadline {
        if predicate() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return predicate()
}

// MARK: - Inbound image cap

/// `T3HttpClient.fetchBytes` against a stubbed URL loader: the body is streamed
/// and abandoned at the cap instead of being buffered whole first.
@Suite(.serialized)
struct T3HttpClientFetchBytesTests {
    private func client() -> T3HttpClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ChunkedBodyURLProtocol.self]
        return T3HttpClient(urlSession: URLSession(configuration: configuration))
    }

    @Test func fetchBytes_returnsBodyUnderCap() async throws {
        ChunkedBodyURLProtocol.reset(chunkSize: 1_024, chunkCount: 4, declaredLength: 4_096)
        let data = await client().fetchBytes(url: URL(string: "https://assets.test/under")!, maxBytes: 8_192)
        #expect(data?.count == 4_096)
        #expect(ChunkedBodyURLProtocol.deliveredChunks == 4)
    }

    @Test func fetchBytes_refusesDeclaredLengthOverCapBeforeReadingBody() async throws {
        ChunkedBodyURLProtocol.reset(chunkSize: 1_024, chunkCount: 64, declaredLength: 65_536)
        let data = await client().fetchBytes(url: URL(string: "https://assets.test/declared")!, maxBytes: 8_192)
        #expect(data == nil)
        #expect(await waitUntil { ChunkedBodyURLProtocol.stopped })
        #expect(ChunkedBodyURLProtocol.deliveredChunks < 64, "the download was cancelled, not drained")
    }

    @Test func fetchBytes_abortsStreamedBodyAtCap() async throws {
        ChunkedBodyURLProtocol.reset(chunkSize: 1_024, chunkCount: 64, declaredLength: nil)
        let data = await client().fetchBytes(url: URL(string: "https://assets.test/streamed")!, maxBytes: 8_192)
        #expect(data == nil)
        #expect(await waitUntil { ChunkedBodyURLProtocol.stopped })
        #expect(ChunkedBodyURLProtocol.deliveredChunks < 64, "the download was cancelled at the cap, not drained")
    }
}

/// Serves a body of `chunkCount` chunks, one every few milliseconds, so a
/// cancellation can land mid-body. Static state is reset per test; the suite
/// above is serialized.
final class ChunkedBodyURLProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    private static var chunkSize = 1_024
    private static var chunkCount = 1
    private static var declaredLength: Int?
    private static var _delivered = 0
    private static var _stopped = false

    static func reset(chunkSize: Int, chunkCount: Int, declaredLength: Int?) {
        lock.lock()
        self.chunkSize = chunkSize
        self.chunkCount = chunkCount
        self.declaredLength = declaredLength
        _delivered = 0
        _stopped = false
        lock.unlock()
    }

    static var deliveredChunks: Int { lock.lock(); defer { lock.unlock() }; return _delivered }
    static var stopped: Bool { lock.lock(); defer { lock.unlock() }; return _stopped }

    private var cancelled = false
    private let stateLock = NSLock()

    override class func canInit(with _: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lock.lock()
        let size = Self.chunkSize
        let count = Self.chunkCount
        let declared = Self.declaredLength
        Self.lock.unlock()
        var headers = ["Content-Type": "image/png"]
        if let declared { headers["Content-Length"] = String(declared) }
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        let chunk = Data(repeating: 0x42, count: size)
        DispatchQueue.global().async { [weak self] in
            for _ in 0 ..< count {
                guard let self else { return }
                self.stateLock.lock()
                let cancelled = self.cancelled
                self.stateLock.unlock()
                if cancelled { return }
                self.client?.urlProtocol(self, didLoad: chunk)
                Self.lock.lock()
                Self._delivered += 1
                Self.lock.unlock()
                Thread.sleep(forTimeInterval: 0.005)
            }
            guard let self else { return }
            self.client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() {
        stateLock.lock()
        cancelled = true
        stateLock.unlock()
        Self.lock.lock()
        Self._stopped = true
        Self.lock.unlock()
    }
}
