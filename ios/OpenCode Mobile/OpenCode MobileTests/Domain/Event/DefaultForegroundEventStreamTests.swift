import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct DefaultForegroundEventStreamTests {
    private static let connectedFrame =
        #"data: {"payload":{"type":"server.connected","properties":{}}}"# + "\n\n"
    private static let heartbeatFrame =
        #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n"
    private static let messageFrame =
        #"data: {"payload":{"type":"message.updated","properties":{"sessionID":"ses-1"}}}"# + "\n\n"

    @Test func serverConnectedFirstFrameMarksStreamConnected() async {
        let harness = makeStream(responses: [Self.connectedFrame])
        var states: [EventStreamConnectionState] = []
        let stateTask = Task { @MainActor in
            for await state in harness.stream.connectionStateUpdates() {
                states.append(state)
            }
        }

        await Task.yield()
        harness.stream.start()
        let becameConnected = await waitUntil(timeoutMs: 2_000) { states.contains(.connected) }
        await harness.stream.stop()
        stateTask.cancel()

        #expect(becameConnected)
    }

    @Test func heartbeatFirstFrameDoesNotMarkLive() async {
        let harness = makeStream(
            responses: [Self.heartbeatFrame],
            firstFrameTimeout: .milliseconds(80)
        )
        var states: [EventStreamConnectionState] = []
        let stateTask = Task { @MainActor in
            for await state in harness.stream.connectionStateUpdates() {
                states.append(state)
            }
        }
        harness.stream.start()
        try? await Task.sleep(for: .milliseconds(200))
        await harness.stream.stop()
        stateTask.cancel()
        #expect(!states.contains(.connected))
    }

    @Test func messageEventsPublishAfterServerConnected() async {
        let harness = makeStream(responses: [Self.connectedFrame + Self.messageFrame])
        var states: [EventStreamConnectionState] = []
        var events: [OpencodeEvent] = []
        let stateTask = Task { @MainActor in
            for await state in harness.stream.connectionStateUpdates() {
                states.append(state)
            }
        }
        let eventTask = Task { @MainActor in
            for await event in harness.stream.events() {
                events.append(event)
            }
        }

        await Task.yield()
        harness.stream.start()
        let receivedMessage = await waitUntil(timeoutMs: 2_000) {
            states.contains(.connected) && events.contains { $0.type == "message.updated" && $0.sessionID == "ses-1" }
        }
        await harness.stream.stop()
        stateTask.cancel()
        eventTask.cancel()

        #expect(receivedMessage)
    }

    @Test func start_opensLeasesWithoutWaitingForDirectories() async {
        let hold = HoldingDirectories(initial: ["/repo"], next: ["/repo"])
        let harness = makeStream(
            responses: [Self.connectedFrame],
            instanceDirectories: { await hold.next() }
        )
        var states: [EventStreamConnectionState] = []
        let stateTask = Task { @MainActor in
            for await state in harness.stream.connectionStateUpdates() {
                states.append(state)
            }
        }
        await Task.yield()
        harness.stream.start()
        let becameConnected = await waitUntil(timeoutMs: 2_000) { states.contains(.connected) }
        await harness.stream.stop()
        stateTask.cancel()
        await hold.release()
        #expect(becameConnected)
    }

    @Test func http401StopsRetriesAndSurfacesAuthFailed() async {
        let harness = makeStream(responses: [""], statusCode: 401)
        var states: [EventStreamConnectionState] = []
        let stateTask = Task { @MainActor in
            for await state in harness.stream.connectionStateUpdates() {
                states.append(state)
            }
        }
        harness.stream.start()
        let failed = await waitUntil(timeoutMs: 2_000) { states.contains(.authFailed) }
        try? await Task.sleep(for: .milliseconds(250))
        let requestCount = MockForegroundSSEURLProtocol.requestCount(streamID: harness.streamID)
        await harness.stream.stop()
        stateTask.cancel()
        #expect(failed)
        #expect(requestCount == 1)
    }

    @Test func http404FastRetriesWhileProtocolUnconfirmed() async {
        let harness = makeStream(
            responses: [""],
            statusCode: 404,
            cachedProtocol: { nil }
        )
        harness.stream.start()
        let retried = await waitUntil(timeoutMs: 2_500) {
            MockForegroundSSEURLProtocol.requestCount(streamID: harness.streamID) >= 2
        }
        await harness.stream.stop()
        #expect(retried)
    }

    @Test func firstFrameTimeoutFailsAttemptWithoutGoingLive() async {
        let harness = makeStream(
            responses: [],
            holdOpen: true,
            firstFrameTimeout: .milliseconds(60)
        )
        var states: [EventStreamConnectionState] = []
        let stateTask = Task { @MainActor in
            for await state in harness.stream.connectionStateUpdates() {
                states.append(state)
            }
        }
        harness.stream.start()
        try? await Task.sleep(for: .milliseconds(180))
        await harness.stream.stop()
        stateTask.cancel()
        #expect(!states.contains(.connected))
        #expect(MockForegroundSSEURLProtocol.requestCount(streamID: harness.streamID) >= 1)
    }

    @Test func onReconnectWakeCancelsBackoffSleep() async {
        let harness = makeStream(responses: [""], statusCode: 500)
        harness.stream.start()
        let first = await waitUntil(timeoutMs: 2_000) {
            MockForegroundSSEURLProtocol.requestCount(streamID: harness.streamID) >= 1
        }
        #expect(first)
        harness.stream.onReconnectWake()
        let second = await waitUntil(timeoutMs: 400) {
            MockForegroundSSEURLProtocol.requestCount(streamID: harness.streamID) >= 2
        }
        await harness.stream.stop()
        #expect(second)
    }

    @Test func coldStartWaitsForProbeBeforeOpeningV2Lease() async {
        let gate = ProbeGate()
        let harness = makeStream(
            responses: [Self.connectedFrame],
            protocolHint: { .v1 },
            cachedProtocol: { nil },
            protocolProbe: {
                await gate.wait()
                return .v2
            }
        )
        harness.stream.start()
        let entered = await waitUntil(timeoutMs: 2_000) { gate.hasEntered }
        #expect(entered)
        #expect(MockForegroundSSEURLProtocol.requestCount(streamID: harness.streamID) == 0)
        gate.release()
        let openedV2 = await waitUntil(timeoutMs: 2_000) {
            MockForegroundSSEURLProtocol.paths(streamID: harness.streamID).contains { $0.hasSuffix("/api/event") }
        }
        await harness.stream.stop()
        #expect(openedV2)
        #expect(
            MockForegroundSSEURLProtocol.paths(streamID: harness.streamID)
                .contains { $0.contains("/global/event") } == false
        )
    }

    @Test func cachedProtocolOpensMatchingSsePathWithoutWaiting() async {
        let probeCalls = LockedBox(0)
        let harness = makeStream(
            responses: [Self.connectedFrame],
            protocolHint: { .v2 },
            cachedProtocol: { .v2 },
            protocolProbe: {
                probeCalls.value += 1
                try? await Task.sleep(for: .milliseconds(400))
                return .v2
            }
        )
        harness.stream.start()
        let opened = await waitUntil(timeoutMs: 2_000) {
            MockForegroundSSEURLProtocol.paths(streamID: harness.streamID).contains { $0.hasSuffix("/api/event") }
        }
        await harness.stream.stop()
        #expect(opened)
        #expect(
            MockForegroundSSEURLProtocol.paths(streamID: harness.streamID)
                .contains { $0.contains("/global/event") } == false
        )
    }

    @Test func passwordChangeAfterAuthFailedRestartsToLive() async {
        let fingerprint = LockedBox("old-fingerprint")
        let harness = makeStream(
            responses: [""],
            statusCode: 401,
            credentialFingerprint: { fingerprint.value }
        )
        var states: [EventStreamConnectionState] = []
        let stateTask = Task { @MainActor in
            for await state in harness.stream.connectionStateUpdates() {
                states.append(state)
            }
        }
        harness.stream.start()
        let failed = await waitUntil(timeoutMs: 2_000) { states.contains(.authFailed) }
        #expect(failed)
        await harness.stream.stop()
        fingerprint.value = "new-fingerprint"
        MockForegroundSSEURLProtocol.register(
            streamID: harness.streamID,
            responses: [Data(Self.connectedFrame.utf8)],
            statusCode: 200,
            holdOpen: false
        )
        harness.stream.start()
        let live = await waitUntil(timeoutMs: 2_000) { states.contains(.connecting) && states.contains(.connected) }
        await harness.stream.stop()
        stateTask.cancel()
        #expect(live)
        #expect(states.last == .idle || states.contains(.connected))
    }

    private struct StreamHarness {
        let stream: DefaultForegroundEventStream
        let streamID: String
    }

    private func makeStream(
        responses: [String],
        statusCode: Int = 200,
        holdOpen: Bool = false,
        firstFrameTimeout: Duration = .seconds(3),
        deviceNetworkPath: any DeviceNetworkPath = ReadyDeviceNetworkPath(),
        instanceDirectories: @escaping @Sendable () async -> [String] = { [] },
        configurationRevisions: (@Sendable () -> AsyncStream<Void>)? = nil,
        protocolHint: (@Sendable () -> ServerProtocol)? = nil,
        cachedProtocol: (@Sendable () -> ServerProtocol?)? = nil,
        protocolProbe: (@Sendable () async -> ServerProtocol)? = nil,
        profileID: (@Sendable () async -> String)? = nil,
        connectionIdentity: (@Sendable () async -> String)? = nil,
        credentialFingerprint: (@Sendable () async -> String)? = nil
    ) -> StreamHarness {
        let streamID = UUID().uuidString
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockForegroundSSEURLProtocol.self]
        MockForegroundSSEURLProtocol.register(
            streamID: streamID,
            responses: responses.map { Data($0.utf8) },
            statusCode: statusCode,
            holdOpen: holdOpen
        )
        let session = URLSession(configuration: configuration)
        let connection = ServerConnection(
            baseURL: URL(string: "http://127.0.0.1:4096/\(streamID)/")!,
            password: "secret"
        )
        let client = EventsAPIClient(connectionProvider: { connection }, urlSession: session)
        let stream = DefaultForegroundEventStream(
            api: client,
            adapter: EventDomainAdapter(),
            deviceNetworkPath: deviceNetworkPath,
            firstFrameTimeout: firstFrameTimeout,
            instanceDirectories: instanceDirectories,
            configurationRevisions: configurationRevisions ?? { AsyncStream { $0.finish() } },
            profileID: profileID ?? { "" },
            connectionIdentity: connectionIdentity,
            credentialFingerprint: credentialFingerprint,
            protocolHint: protocolHint,
            cachedProtocol: cachedProtocol,
            protocolProbe: protocolProbe
        )
        return StreamHarness(stream: stream, streamID: streamID)
    }

    private func waitUntil(
        timeoutMs: Int = 1_000,
        predicate: @MainActor () -> Bool
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
        while Date() < deadline {
            if predicate() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return predicate()
    }
}

private final class ProbeGate: @unchecked Sendable {
    private let lock = NSLock()
    private var entered = false
    private var released = false
    private var continuation: CheckedContinuation<Void, Never>?

    var hasEntered: Bool {
        lock.lock()
        defer { lock.unlock() }
        return entered
    }

    func wait() async {
        await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                lock.lock()
                entered = true
                if released || Task.isCancelled {
                    lock.unlock()
                    continuation.resume()
                    return
                }
                self.continuation = continuation
                lock.unlock()
            }
        } onCancel: {
            lock.lock()
            let continuation = self.continuation
            self.continuation = nil
            lock.unlock()
            continuation?.resume()
        }
    }

    func release() {
        lock.lock()
        released = true
        let continuation = self.continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume()
    }
}

private final class LockedBox<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var _value: Value

    var value: Value {
        get {
            lock.lock()
            defer { lock.unlock() }
            return _value
        }
        set {
            lock.lock()
            _value = newValue
            lock.unlock()
        }
    }

    init(_ value: Value) {
        _value = value
    }
}

private final class HoldingDirectories: @unchecked Sendable {
    private let lock = NSLock()
    private let initial: [String]
    private let next: [String]
    private var resolves = 0
    private var holdContinuation: CheckedContinuation<Void, Never>?

    init(initial: [String], next: [String]) {
        self.initial = initial
        self.next = next
    }

    func next() async -> [String] {
        lock.lock()
        resolves += 1
        let count = resolves
        lock.unlock()
        if count == 1 {
            await withTaskCancellationHandler {
                await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                    lock.lock()
                    if Task.isCancelled {
                        lock.unlock()
                        continuation.resume()
                        return
                    }
                    holdContinuation = continuation
                    lock.unlock()
                }
            } onCancel: {
                lock.lock()
                let continuation = holdContinuation
                holdContinuation = nil
                lock.unlock()
                continuation?.resume()
            }
            return initial
        }
        return next
    }

    func release() async {
        lock.lock()
        let continuation = holdContinuation
        holdContinuation = nil
        lock.unlock()
        continuation?.resume()
    }
}

private final class MockForegroundSSEURLProtocol: URLProtocol {
    private struct StreamState {
        var responses: [Data]
        var statusCode: Int
        var holdOpen: Bool
        var paths: [String] = []
        var liveClients: [MockForegroundSSEURLProtocol] = []
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var streams: [String: StreamState] = [:]

    static func register(streamID: String, responses: [Data], statusCode: Int, holdOpen: Bool) {
        lock.lock()
        streams[streamID] = StreamState(responses: responses, statusCode: statusCode, holdOpen: holdOpen)
        lock.unlock()
    }

    static func requestCount(streamID: String) -> Int {
        lock.lock()
        defer { lock.unlock() }
        return streams[streamID]?.paths.count ?? 0
    }

    static func paths(streamID: String) -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return streams[streamID]?.paths ?? []
    }

    private static func streamID(from request: URLRequest) -> String? {
        guard let url = request.url, url.path.contains("event") else { return nil }
        return url.pathComponents.filter { $0 != "/" }.first
    }

    override class func canInit(with request: URLRequest) -> Bool {
        streamID(from: request) != nil
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let streamID = Self.streamID(from: request) else { return }
        Self.lock.lock()
        var state = Self.streams[streamID] ?? StreamState(responses: [], statusCode: 200, holdOpen: false)
        let body = state.responses.isEmpty ? Data() : state.responses.removeFirst()
        let statusCode = state.statusCode
        let holdOpen = state.holdOpen
        state.paths.append(request.url?.path ?? "")
        if holdOpen {
            state.liveClients.append(self)
        }
        Self.streams[streamID] = state
        Self.lock.unlock()

        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: statusCode,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "text/event-stream"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if !body.isEmpty {
            client?.urlProtocol(self, didLoad: body)
        }
        if !holdOpen {
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() {
        client?.urlProtocol(self, didFailWithError: URLError(.cancelled))
    }
}
