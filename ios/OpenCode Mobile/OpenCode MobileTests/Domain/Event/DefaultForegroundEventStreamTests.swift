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

    @Test func reconnectPublishesSyntheticEventOnlyOnceForCurrentStream() async {
        let connected = #"data: {"payload":{"type":"server.connected","properties":{}}}"# + "\n\n"
        let stream = makeStream(responses: [connected, connected])
        var events: [OpencodeEvent] = []
        let eventTask = Task { @MainActor in
            for await event in stream.events() {
                events.append(event)
            }
        }

        await Task.yield()
        stream.start()
        try? await Task.sleep(for: .milliseconds(1_200))
        let reconnected = await waitUntil(timeoutMs: 3_500) {
            events.filter { $0.type == "stream.reconnected" }.count == 1
        }
        try? await Task.sleep(for: .milliseconds(100))
        stream.stop()
        eventTask.cancel()

        #expect(reconnected)
        #expect(events.filter { $0.type == "stream.reconnected" }.count == 1)
    }

    @Test func startIsIdempotentWhileStreamIsRunning() async {
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
        let connected = await waitUntil(timeoutMs: 2_000) { states.contains(.connected) }
        #expect(connected)
        let countAfterFirst = states.count
        stream.start()
        try? await Task.sleep(for: .milliseconds(80))
        let countAfterSecondStart = states.count
        stream.stop()
        stateTask.cancel()

        // Second start must not tear down (no new Idle/Connecting while live).
        #expect(countAfterSecondStart == countAfterFirst)
    }

    @Test func successfulResumeDoesNotPublishDisconnectedWhileRetrying() async {
        // Stream body ends after the first event; the loop retries while still
        // occupied. Chrome must stay connecting/reconnecting — not offline.
        let stream = makeStream(
            responses: [
                #"data: {"payload":{"type":"server.connected","properties":{}}}"# + "\n\n",
                #"data: {"payload":{"type":"server.connected","properties":{}}}"# + "\n\n",
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
        let reconnected = await waitUntil(timeoutMs: 3_500) {
            states.filter { $0 == .connected }.count >= 2
        }
        stream.stop()
        stateTask.cancel()

        #expect(reconnected)
        #expect(!states.contains(.disconnected))
    }

    @Test func stopThenStartPublishesStreamReconnectedForColdResume() async {
        let connected = #"data: {"payload":{"type":"server.connected","properties":{}}}"# + "\n\n"
        let stream = makeStream(responses: [connected, connected])
        var events: [OpencodeEvent] = []
        let eventTask = Task { @MainActor in
            for await event in stream.events() {
                events.append(event)
            }
        }

        await Task.yield()
        stream.start()
        let firstLive = await waitUntil(timeoutMs: 2_000) {
            events.contains { $0.type == "server.connected" }
        }
        #expect(firstLive)
        stream.stop()
        try? await Task.sleep(for: .milliseconds(50))
        let beforeResume = events.filter { $0.type == "stream.reconnected" }.count
        stream.start()
        let coldResume = await waitUntil(timeoutMs: 3_500) {
            events.filter { $0.type == "stream.reconnected" }.count == beforeResume + 1
        }
        stream.stop()
        eventTask.cancel()

        #expect(coldResume)
    }

    @Test func stopThenStartDoesNotLeaveCancelledTaskIdleOverConnecting() async {
        let stream = makeStream(
            responses: [
                #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n",
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
        let firstLive = await waitUntil(timeoutMs: 2_000) { states.contains(.connected) }
        #expect(firstLive)
        stream.stop()
        let stopped = await waitUntil(timeoutMs: 1_000) { states.last == .idle }
        #expect(stopped)
        let indexAfterStop = states.count
        stream.start()
        let secondLive = await waitUntil(timeoutMs: 2_000) {
            states[indexAfterStop...].contains(.connected)
        }
        #expect(secondLive)
        // After the second start, Idle from the cancelled first task must not
        // appear after Connecting for the new generation (before Connected).
        let slice = Array(states[indexAfterStop...])
        if let connectingIdx = slice.firstIndex(of: .connecting),
           let connectedIdx = slice.firstIndex(of: .connected),
           connectingIdx < connectedIdx
        {
            #expect(!slice[connectingIdx..<connectedIdx].contains(.idle))
        }
        stream.stop()
        stateTask.cancel()
    }

    @Test func unavailablePath_doesNotOpenSseUntilReady() async {
        let path = FakeDeviceNetworkPath(status: .unavailable)
        var states: [EventStreamConnectionState] = []
        let stream = makeStream(
            responses: [
                #"data: {"payload":{"type":"server.heartbeat","properties":{}}}"# + "\n\n",
            ],
            deviceNetworkPath: path
        )
        let stateTask = Task { @MainActor in
            for await state in stream.connectionStateUpdates() {
                states.append(state)
            }
        }
        await Task.yield()
        stream.start()
        try? await Task.sleep(for: .milliseconds(80))
        let stayedIdle = !states.contains(.connecting) && !states.contains(.connected)
        path.set(.ready)
        let becameConnected = await waitUntil(timeoutMs: 2_000) { states.contains(.connected) }
        stream.stop()
        stateTask.cancel()
        #expect(stayedIdle)
        #expect(becameConnected)
    }

    private func makeStream(
        responses: [String],
        deviceNetworkPath: any DeviceNetworkPath = ReadyDeviceNetworkPath()
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
            deviceNetworkPath: deviceNetworkPath
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

private final class MockForegroundSSEURLProtocol: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var responseQueues: [String: [Data]] = [:]

    static func register(streamID: String, responses: [Data]) {
        lock.lock()
        responseQueues[streamID] = responses
        lock.unlock()
    }

    private static func streamID(from request: URLRequest) -> String? {
        guard let url = request.url else { return nil }
        let components = url.pathComponents.filter { $0 != "/" }
        guard components.count >= 3 else { return nil }
        guard components[components.count - 2] == "global", components.last == "event" else { return nil }
        return components[components.count - 3]
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
