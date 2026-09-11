import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct DefaultForegroundEventStreamTests {
    @Test func successfulHTTPResponseMarksStreamConnectedWithoutServerConnectedEvent() async {
        let stream = makeStream(
            responses: [
                #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n",
            ]
        )
        var states: [EventStreamConnectionState] = []
        let stateTask = Task { @MainActor in
            for await state in stream.connectionStateUpdates() {
                states.append(state)
            }
        }

        await Task.yield()
        stream.start()
        try? await Task.sleep(for: .milliseconds(50))
        let becameConnected = await waitUntil(timeoutMs: 2_000) { states.contains(.connected) }
        stream.stop()
        stateTask.cancel()

        #expect(becameConnected)
    }

    @Test func messageEventsPublishWhileStreamIsConnected() async {
        let stream = makeStream(
            responses: [
                #"data: {"payload":{"type":"message.updated","properties":{"sessionID":"ses-1"}}}"# + "\n\n",
            ]
        )
        var states: [EventStreamConnectionState] = []
        var events: [OpencodeEvent] = []
        let stateTask = Task { @MainActor in
            for await state in stream.connectionStateUpdates() {
                states.append(state)
            }
        }
        let eventTask = Task { @MainActor in
            for await event in stream.events() {
                events.append(event)
            }
        }

        await Task.yield()
        stream.start()
        try? await Task.sleep(for: .milliseconds(50))
        let receivedMessage = await waitUntil(timeoutMs: 2_000) {
            states.contains(.connected) && events.contains { $0.type == "message.updated" && $0.sessionID == "ses-1" }
        }
        stream.stop()
        stateTask.cancel()
        eventTask.cancel()

        #expect(receivedMessage)
    }

    @Test func start_opensLeasesWithoutWaitingForDirectories() async {
        let hold = HoldingDirectories(initial: ["/repo"], next: ["/repo"])
        let stream = makeStream(
            responses: [
                #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n",
            ],
            instanceDirectories: { await hold.next() }
        )
        var states: [EventStreamConnectionState] = []
        let stateTask = Task { @MainActor in
            for await state in stream.connectionStateUpdates() {
                states.append(state)
            }
        }
        await Task.yield()
        stream.start()
        let becameConnected = await waitUntil(timeoutMs: 2_000) { states.contains(.connected) }
        stream.stop()
        stateTask.cancel()
        await hold.release()
        #expect(becameConnected)
    }

    private func makeStream(
        responses: [String],
        deviceNetworkPath: any DeviceNetworkPath = ReadyDeviceNetworkPath(),
        instanceDirectories: @escaping @Sendable () async -> [String] = { [] },
        configurationRevisions: (@Sendable () -> AsyncStream<Void>)? = nil,
        protocolHint: (@Sendable () -> ServerProtocol)? = nil,
        profileID: (@Sendable () async -> String)? = nil,
        connectionIdentity: (@Sendable () async -> String)? = nil
    ) -> DefaultForegroundEventStream {
        let streamID = UUID().uuidString
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockForegroundSSEURLProtocol.self]
        MockForegroundSSEURLProtocol.register(
            streamID: streamID,
            responses: responses.map { Data($0.utf8) }
        )
        let session = URLSession(configuration: configuration)
        let connection = ServerConnection(
            baseURL: URL(string: "http://127.0.0.1:4096/\(streamID)/")!,
            password: "secret"
        )
        let client = EventsAPIClient(connectionProvider: { connection }, urlSession: session)
        return DefaultForegroundEventStream(
            api: client,
            adapter: EventDomainAdapter(),
            deviceNetworkPath: deviceNetworkPath,
            instanceDirectories: instanceDirectories,
            configurationRevisions: configurationRevisions ?? { AsyncStream { $0.finish() } },
            profileID: profileID ?? { "" },
            connectionIdentity: connectionIdentity,
            protocolHint: protocolHint
        )
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

private final class HoldingDirectories: @unchecked Sendable {
    private let lock = NSLock()
    private let initial: [String]
    private let next: [String]
    private var resolves = 0
    private var holdContinuation: CheckedContinuation<Void, Never>?

    var resolveCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return resolves
    }

    var isHolding: Bool {
        lock.lock()
        defer { lock.unlock() }
        return holdContinuation != nil
    }

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
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                lock.lock()
                holdContinuation = continuation
                lock.unlock()
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
    private static let lock = NSLock()
    nonisolated(unsafe) private static var responseQueues: [String: [Data]] = [:]

    static func register(streamID: String, responses: [Data]) {
        lock.lock()
        responseQueues[streamID] = responses
        lock.unlock()
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
        var queue = Self.responseQueues[streamID] ?? []
        let body = queue.isEmpty ? Data() : queue.removeFirst()
        Self.responseQueues[streamID] = queue
        Self.lock.unlock()

        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "text/event-stream"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if !body.isEmpty {
            client?.urlProtocol(self, didLoad: body)
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
