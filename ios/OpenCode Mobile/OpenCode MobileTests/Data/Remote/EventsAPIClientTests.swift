import Foundation
import Testing
@testable import OpenCode_Mobile

struct EventsAPIClientTests {
    @Test func streamingSessionKey_usesFingerprintNotRawPassword() {
        let first = ServerConnection(
            baseURL: URL(string: "http://127.0.0.1:4096/")!,
            username: "opencode",
            password: "super-secret"
        )
        let second = ServerConnection(
            baseURL: URL(string: "http://127.0.0.1:4096/")!,
            username: "opencode",
            password: "other-secret"
        )
        let firstKey = EventsAPIClient.streamingSessionKey(for: first)
        let secondKey = EventsAPIClient.streamingSessionKey(for: second)
        #expect(!firstKey.contains("super-secret"))
        #expect(!secondKey.contains("other-secret"))
        #expect(firstKey != secondKey)
        #expect(firstKey == EventsAPIClient.streamingSessionKey(for: first))
    }

    @Test func streamGlobalEvents_parsesStandardSSEDataLines() async throws {
        let body = "data: {\"payload\":{\"type\":\"server.connected\",\"properties\":{}}}\n\n"
        let client = makeClient(responseBody: body)
        var lines: [String] = []
        try await client.streamGlobalEvents { line in
            lines.append(line)
        }
        #expect(lines.count == 1)
        #expect(lines[0].contains("server.connected"))
    }

    @Test func streamGlobalEvents_parsesRawJSONLines() async throws {
        let body = "{\"payload\":{\"type\":\"server.connected\",\"properties\":{}}}\n"
        let client = makeClient(responseBody: body)
        var lines: [String] = []
        try await client.streamGlobalEvents { line in
            lines.append(line)
        }
        #expect(lines.count == 1)
        #expect(lines[0].contains("server.connected"))
    }

    @Test func dataLineParser_framesOnBlankLineAndJoinsMultilineData() {
        var parser = SSEDataLineParser()
        let connected = #"{"payload":{"type":"server.connected","properties":{}}}"#

        #expect(parser.ingest("data: \(connected)") == [])
        #expect(parser.ingest("") == [connected])

        #expect(parser.ingest("data: {\"a\":") == [])
        #expect(parser.ingest("data: 1}") == [])
        #expect(parser.ingest("") == ["{\"a\":\n1}"])
    }

    @Test func dataLineParser_doesNotFlushCompleteJSONWithoutBlankSeparator() {
        var parser = SSEDataLineParser()
        let connected = #"{"payload":{"type":"server.connected","properties":{}}}"#
        let heartbeat = #"{"payload":{"type":"server.heartbeat","properties":{}}}"#

        #expect(parser.ingest("data: \(connected)") == [])
        #expect(parser.ingest("data: \(heartbeat)") == [])
        #expect(parser.ingest("") == ["\(connected)\n\(heartbeat)"])
    }

    @Test func streamGlobalEvents_commentLinesCountAsActivityWithoutPublishingEvents() async throws {
        final class Counter: @unchecked Sendable {
            var value = 0
        }
        let body = ": keep-alive\ndata: {\"payload\":{\"type\":\"server.connected\",\"properties\":{}}}\n\n"
        let client = makeClient(responseBody: body)
        let activity = Counter()
        var lines: [String] = []
        try await client.streamGlobalEvents(
            onActivity: { activity.value += 1 },
            onDataLine: { line in lines.append(line) }
        )
        #expect(activity.value >= 1)
        #expect(lines.count == 1)
        #expect(lines[0].contains("server.connected"))
    }

    @Test func streamGlobalEvents_usesV2PathWhenProtocolIsV2() async throws {
        let body = "data: {\"type\":\"server.connected\",\"data\":{}}\n\n"
        let client = makeClient(responseBody: body, serverProtocol: .v2)
        var lines: [String] = []
        try await client.streamGlobalEvents { line in
            lines.append(line)
        }
        #expect(lines.count == 1)
        #expect(client.eventSubscribePath == "api/event")
        #expect(MockSSEURLProtocol.lastPath?.hasSuffix("/api/event") == true)
    }

    @Test func streamSubscriptions_v1OpensGlobalAndInstanceEvent() async throws {
        let body = "data: {\"payload\":{\"type\":\"server.heartbeat\",\"properties\":{}}}\n\n"
        let client = makeClient(responseBody: body, serverProtocol: .v1)
        MockSSEURLProtocol.resetPaths()
        try await client.streamSubscriptions(directories: ["/tmp/project"]) { _ in }
        let requests = MockSSEURLProtocol.recordedRequests
        #expect(requests.contains { $0.path.hasSuffix("/global/event") })
        #expect(requests.contains { request in
            request.path.hasSuffix("/event")
                && !request.path.contains("/global/")
                && directoryQueryValue(request.query) == "/tmp/project"
        })
        #expect(requests.contains { $0.path.hasSuffix("/api/event") } == false)
    }

    @Test func streamSubscriptions_v2OpensOnlyApiEvent() async throws {
        let body = "data: {\"type\":\"server.connected\",\"data\":{}}\n\n"
        let client = makeClient(responseBody: body, serverProtocol: .v2)
        MockSSEURLProtocol.resetPaths()
        try await client.streamSubscriptions(directories: ["/tmp/project"]) { _ in }
        let requests = MockSSEURLProtocol.recordedRequests
        #expect(!requests.isEmpty)
        #expect(requests.allSatisfy { $0.path.hasSuffix("/api/event") })
        #expect(requests.allSatisfy { directoryQueryValue($0.query) == nil })
    }

    @Test func streamGlobalEvents_cacheMissOpensV1HintPath() async throws {
        let body = "data: {\"type\":\"server.connected\",\"data\":{}}\n\n"
        let client = makeClient(responseBody: body, serverProtocol: nil)
        var lines: [String] = []
        _ = try await client.streamGlobalEvents { line in
            lines.append(line)
        }
        #expect(lines.count == 1)
        #expect(MockSSEURLProtocol.lastPath?.hasSuffix("/global/event") == true)
    }

    @Test func streamEvents_returnsHttpOutcomeForUnauthorized() async throws {
        let client = makeClient(responseBody: "", statusCode: 401)
        let outcome = try await client.streamEvents(path: "global/event", directory: nil) { _ in }
        guard case .http(401) = outcome else {
            Issue.record("Expected HTTP 401 outcome, got \(String(describing: outcome))")
            return
        }
    }

    @Test func streamEvents_connectBudgetFailsHungHandshakeAsTransport() async throws {
        let before = Set(DiagnosticLogBuffer.shared.snapshot().entries.map(\.id))
        let budgetHits = BudgetHitCounter()
        let client = makeClient(
            responseBody: "",
            hang: true,
            onConnectBudgetExceeded: { budgetHits.increment() }
        )
        let started = ContinuousClock.now
        let outcome = try await client.streamEvents(
            path: "api/event",
            directory: nil,
            connectBudget: .milliseconds(80)
        ) { _ in }
        let elapsed = ContinuousClock.now - started
        guard case let .transport(error?) = outcome else {
            Issue.record("Expected transport budget failure, got \(String(describing: outcome))")
            return
        }
        #expect(error is SseConnectBudgetExceeded)
        #expect(!(error is SseHandshakeError))
        #expect(elapsed < .seconds(2))
        #expect(budgetHits.value == 1)

        let fresh = DiagnosticLogBuffer.shared.snapshot().entries.filter { !before.contains($0.id) }
        #expect(fresh.contains {
            $0.message == "SSE connect budget exceeded path=api/event budgetMs=80"
        })
        #expect(!fresh.contains { $0.message.contains("API failure") && $0.message.contains("/api/event") })

        MockSSEURLProtocol.register(
            responseBody: Data("data: {\"payload\":{\"type\":\"server.connected\",\"properties\":{}}}\n\n".utf8),
            statusCode: 200
        )
        var lines: [String] = []
        let followUp = try await client.streamEvents(path: "api/event", directory: nil) { line in
            lines.append(line)
        }
        guard case .ended = followUp else {
            Issue.record("Injected session should survive budget cancellation, got \(String(describing: followUp))")
            return
        }
        #expect(lines.count == 1)
        #expect(budgetHits.value == 1)
    }

    @Test func retireStreamingSessions_rejectsLaterOpenOfOlderToken() {
        let connection = ServerConnection(
            baseURL: URL(string: "http://127.0.0.1:4096/")!,
            password: "secret"
        )
        let client = EventsAPIClient(connectionProvider: { connection })
        #expect(client.openStreamingSession(token: 2) != nil)
        client.retireStreamingSessions(olderThan: 2)
        #expect(client.ownedStreamingDialCount == 1)

        client.retireStreamingSessions(olderThan: 3)
        #expect(client.ownedStreamingDialCount == 0)

        #expect(client.openStreamingSession(token: 2) == nil)
        #expect(client.ownedStreamingDialCount == 0)

        #expect(client.openStreamingSession(token: 3) != nil)
        #expect(client.ownedStreamingDialCount == 1)
    }

    @Test(arguments: ["global/event", "api/event"])
    func streamEvents_retiredTokenDialEndsAsTransport(path: String) async throws {
        let client = makeOwnedSessionClient()
        client.retireStreamingSessions(olderThan: 2)

        let outcome = try await client.streamEvents(path: path, directory: nil, sessionToken: 1) { _ in }

        expectCancelledTransport(outcome)
        #expect(client.ownedStreamingDialCount == 0)
        #expect(HangingSSEURLProtocol.startCount(path: path, marker: nil) == 0)
    }

    /// #745: a retire between opening the session and creating its task used to
    /// invalidate the session first, and `bytes(for:)` then raised NSGenericException.
    @Test(arguments: ["global/event", "api/event"])
    func streamEvents_retiredBetweenOpenAndDialEndsAsTransport(path: String) async throws {
        let box = ClientBox()
        let marker = UUID().uuidString
        let client = makeOwnedSessionClient(beforeDial: {
            box.client?.retireStreamingSessions(olderThan: 2)
        })
        box.client = client
        client.installFreshStreamingSession(token: 1)

        let started = ContinuousClock.now
        let outcome = try await client.streamEvents(
            path: path,
            directory: marker,
            connectBudget: .seconds(10),
            sessionToken: 1
        ) { _ in }

        expectCancelledTransport(outcome)
        #expect(ContinuousClock.now - started < .seconds(2))
        #expect(client.ownedStreamingDialCount == 0)
        #expect(HangingSSEURLProtocol.startCount(path: path, marker: marker) == 0)
    }

    @Test(arguments: ["global/event", "api/event"])
    func streamEvents_tokenRotationWhileDialingEndsAsTransport(path: String) async throws {
        let marker = UUID().uuidString
        let client = makeOwnedSessionClient()
        client.installFreshStreamingSession(token: 1)

        let started = ContinuousClock.now
        let dial = Task {
            try await client.streamEvents(
                path: path,
                directory: marker,
                connectBudget: .seconds(10),
                sessionToken: 1
            ) { _ in }
        }
        while HangingSSEURLProtocol.startCount(path: path, marker: marker) == 0 {
            try await Task.sleep(for: .milliseconds(10))
            if ContinuousClock.now - started > .seconds(5) {
                Issue.record("Dial never reached the server")
                dial.cancel()
                return
            }
        }
        client.installFreshStreamingSession(token: 2)

        let outcome = try await dial.value
        expectCancelledTransport(outcome)
        #expect(ContinuousClock.now - started < .seconds(5))
        #expect(client.ownedStreamingDialCount == 0)
    }

    private func expectCancelledTransport(
        _ outcome: SseAttemptOutcome,
        sourceLocation: SourceLocation = #_sourceLocation
    ) {
        guard case let .transport(error?) = outcome else {
            Issue.record("Expected cancelled transport, got \(String(describing: outcome))", sourceLocation: sourceLocation)
            return
        }
        #expect((error as? URLError)?.code == .cancelled, sourceLocation: sourceLocation)
    }

    private func makeOwnedSessionClient(beforeDial: @escaping @Sendable () -> Void = {}) -> EventsAPIClient {
        let connection = ServerConnection(
            baseURL: URL(string: "http://127.0.0.1:4096/")!,
            password: "secret"
        )
        return EventsAPIClient(
            connectionProvider: { connection },
            streamingSessionFactory: {
                let configuration = URLSessionConfiguration.ephemeral
                configuration.protocolClasses = [HangingSSEURLProtocol.self]
                return URLSession(configuration: configuration)
            },
            beforeDial: beforeDial
        )
    }

    private func makeClient(
        responseBody: String,
        serverProtocol: ServerProtocol? = nil,
        statusCode: Int = 200,
        hang: Bool = false,
        onConnectBudgetExceeded: @escaping @Sendable () -> Void = {}
    ) -> EventsAPIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockSSEURLProtocol.self]
        MockSSEURLProtocol.register(
            responseBody: Data(responseBody.utf8),
            statusCode: statusCode,
            hang: hang
        )
        let session = URLSession(configuration: configuration)
        let connection = ServerConnection(
            baseURL: URL(string: "http://127.0.0.1:4096/")!,
            password: "secret",
            serverProtocol: serverProtocol
        )
        return EventsAPIClient(
            connectionProvider: { connection },
            urlSession: session,
            onConnectBudgetExceeded: onConnectBudgetExceeded
        )
    }
}

private final class ClientBox: @unchecked Sendable {
    var client: EventsAPIClient?
}

/// Never answers, so a dial stays in its handshake until cancelled. Requests are
/// keyed by path and `directory` marker so parallel tests do not see each other.
private final class HangingSSEURLProtocol: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var starts: [String: Int] = [:]

    static func startCount(path: String, marker: String?) -> Int {
        lock.lock()
        defer { lock.unlock() }
        return starts[key(path: path, marker: marker)] ?? 0
    }

    private static func key(path: String, marker: String?) -> String {
        "\(path)|\(marker ?? "")"
    }

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        let path = String((request.url?.path ?? "").drop { $0 == "/" })
        let marker = directoryQueryValue(request.url?.query)
        Self.lock.lock()
        Self.starts[Self.key(path: path, marker: marker), default: 0] += 1
        Self.lock.unlock()
    }

    override func stopLoading() {
        client?.urlProtocol(self, didFailWithError: URLError(.cancelled))
    }
}

private final class BudgetHitCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var hits = 0

    func increment() {
        lock.lock()
        hits += 1
        lock.unlock()
    }

    var value: Int {
        lock.lock()
        defer { lock.unlock() }
        return hits
    }
}

private func directoryQueryValue(_ query: String?) -> String? {
    guard let query else { return nil }
    return URLComponents(string: "http://dummy?\(query)")?
        .queryItems?
        .first { $0.name == "directory" }?
        .value
}

private struct MockSSERequest: Equatable {
    let path: String
    let query: String?
}

private final class MockSSEURLProtocol: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var responseBody = Data()
    nonisolated(unsafe) private static var statusCode = 200
    nonisolated(unsafe) private static var hang = false
    nonisolated(unsafe) static var lastPath: String?
    nonisolated(unsafe) static var recordedRequests: [MockSSERequest] = []

    static func register(responseBody: Data, statusCode: Int = 200, hang: Bool = false) {
        lock.lock()
        self.responseBody = responseBody
        self.statusCode = statusCode
        self.hang = hang
        self.lastPath = nil
        self.recordedRequests = []
        lock.unlock()
    }

    static func resetPaths() {
        lock.lock()
        lastPath = nil
        recordedRequests = []
        lock.unlock()
    }

    override class func canInit(with request: URLRequest) -> Bool {
        let path = request.url?.path ?? ""
        return path.hasSuffix("/global/event") || path.hasSuffix("/api/event") || path.hasSuffix("/event")
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        Self.lock.lock()
        let body = Self.responseBody
        let statusCode = Self.statusCode
        let hang = Self.hang
        Self.lastPath = request.url?.path
        if let path = request.url?.path {
            Self.recordedRequests.append(MockSSERequest(path: path, query: request.url?.query))
        }
        Self.lock.unlock()

        if hang {
            return
        }
        finished = true
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: statusCode,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "text/event-stream"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {
        guard !finished else { return }
        finished = true
        client?.urlProtocol(self, didFailWithError: URLError(.cancelled))
    }

    private var finished = false
}
