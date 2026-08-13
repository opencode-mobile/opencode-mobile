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

    @Test func streamGlobalEvents_probesProtocolOnCacheMiss() async throws {
        let body = "data: {\"type\":\"server.connected\",\"data\":{}}\n\n"
        let client = makeClient(responseBody: body, serverProtocol: nil, resolvedProtocol: .v2)
        var lines: [String] = []
        try await client.streamGlobalEvents { line in
            lines.append(line)
        }
        #expect(lines.count == 1)
        #expect(MockSSEURLProtocol.lastPath?.hasSuffix("/api/event") == true)
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

private final class MockSSEURLProtocol: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var responseBody = Data()
    nonisolated(unsafe) static var lastPath: String?

    static func register(responseBody: Data) {
        lock.lock()
        self.responseBody = responseBody
        self.lastPath = nil
        lock.unlock()
    }

    override class func canInit(with request: URLRequest) -> Bool {
        let path = request.url?.path ?? ""
        return path.hasSuffix("/global/event") || path.hasSuffix("/api/event")
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        Self.lock.lock()
        let body = Self.responseBody
        Self.lastPath = request.url?.path
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
