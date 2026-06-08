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
        let becameConnected = await waitUntil { states.contains(.connected) }
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
        let receivedMessage = await waitUntil {
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
        let reconnected = await waitUntil(timeoutMs: 2_500) {
            events.filter { $0.type == "stream.reconnected" }.count == 1
        }
        try? await Task.sleep(for: .milliseconds(100))
        stream.stop()
        eventTask.cancel()

        #expect(reconnected)
        #expect(events.filter { $0.type == "stream.reconnected" }.count == 1)
    }

    private func makeStream(responses: [String]) -> DefaultForegroundEventStream {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockForegroundSSEURLProtocol.self]
        MockForegroundSSEURLProtocol.register(responses: responses.map { Data($0.utf8) })
        let session = URLSession(configuration: configuration)
        let connection = ServerConnection(baseURL: URL(string: "http://127.0.0.1:4096/")!, password: "secret")
        let client = EventsAPIClient(connectionProvider: { connection }, urlSession: session)
        return DefaultForegroundEventStream(api: client, adapter: EventDomainAdapter())
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
    nonisolated(unsafe) private static var responses: [Data] = []

    static func register(responses: [Data]) {
        lock.lock()
        self.responses = responses
        lock.unlock()
    }

    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.path.hasSuffix("/global/event") == true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        Self.lock.lock()
        let body = Self.responses.isEmpty ? Data() : Self.responses.removeFirst()
        Self.lock.unlock()

        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "text/event-stream"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
