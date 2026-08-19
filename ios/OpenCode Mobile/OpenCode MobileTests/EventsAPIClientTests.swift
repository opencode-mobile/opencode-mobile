import Foundation
import Testing
@testable import OpenCode_Mobile

struct EventsAPIClientTests {
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

    @Test func dataLineParser_flushesCompleteDataPayloadsWithoutBlankSeparators() {
        var parser = SSEDataLineParser()
        let connected = #"{"payload":{"type":"server.connected","properties":{}}}"#
        let heartbeat = #"{"payload":{"type":"server.heartbeat","properties":{}}}"#

        let first = parser.ingest("data: \(connected)")
        let second = parser.ingest("data: \(heartbeat)")

        #expect(first == [connected])
        #expect(second == [heartbeat])
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
        let client = makeClient(responseBody: body, serverProtocol: nil, resolvedProtocol: .v2)
        var lines: [String] = []
        try await client.streamGlobalEvents { line in
            lines.append(line)
        }
        #expect(lines.count == 1)
        #expect(MockSSEURLProtocol.lastPath?.hasSuffix("/global/event") == true)
    }

    private func makeClient(
        responseBody: String,
        serverProtocol: ServerProtocol? = nil,
        resolvedProtocol: ServerProtocol? = nil
    ) -> EventsAPIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockSSEURLProtocol.self]
        MockSSEURLProtocol.register(responseBody: Data(responseBody.utf8))
        let session = URLSession(configuration: configuration)
        let connection = ServerConnection(
            baseURL: URL(string: "http://127.0.0.1:4096/")!,
            password: "secret",
            serverProtocol: serverProtocol
        )
        let resolver: (@Sendable () async -> ServerProtocol)? = resolvedProtocol.map { value in
            { value }
        }
        return EventsAPIClient(
            connectionProvider: { connection },
            urlSession: session,
            protocolResolver: resolver
        )
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
    nonisolated(unsafe) static var lastPath: String?
    nonisolated(unsafe) static var recordedRequests: [MockSSERequest] = []

    static func register(responseBody: Data) {
        lock.lock()
        self.responseBody = responseBody
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
        Self.lastPath = request.url?.path
        if let path = request.url?.path {
            Self.recordedRequests.append(MockSSERequest(path: path, query: request.url?.query))
        }
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
