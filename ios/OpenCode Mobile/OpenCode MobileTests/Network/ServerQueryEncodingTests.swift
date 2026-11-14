import Foundation
import Testing
@testable import OpenCode_Mobile

/// The server reads a bare `+` in a query as a space, so every `+` in a query value goes out as
/// `%2B` (as Ktor sends it on Android), whichever route builds the query.
@MainActor @Suite(.serialized)
struct ServerQueryEncodingTests {
    private let connection = ServerConnection(baseURL: URL(string: "https://example.test")!, username: "opencode", password: "secret")

    @Test func authorizedRequestSendsPlusInDirectoryAsPercent2B() throws {
        let request = connection.authorizedRequest(
            path: "session",
            directory: "/Users/me/src/c++-tool",
            locationDirectory: "/Users/me/src/c++-tool"
        )

        let query = try #require(request.url?.query(percentEncoded: true))
        #expect(query == "directory=/Users/me/src/c%2B%2B-tool&location%5Bdirectory%5D=/Users/me/src/c%2B%2B-tool")
        #expect(queryValue(request.url, "directory") == "/Users/me/src/c++-tool")
        #expect(queryValue(request.url, "location[directory]") == "/Users/me/src/c++-tool")
    }

    @Test func itemsAppendedAfterAuthorizedRequestKeepPercent2B() throws {
        let request = connection.authorizedRequest(path: "api/shell/sh1/output", locationDirectory: "/src/c++")
            .appendingQueryItems([URLQueryItem(name: "cursor", value: "42"), URLQueryItem(name: "q", value: "a+b")])

        let query = try #require(request.url?.query(percentEncoded: true))
        #expect(query == "location%5Bdirectory%5D=/src/c%2B%2B&cursor=42&q=a%2Bb")
    }

    @Test func shellOutputKeepsPercent2BInLocationNextToCursor() async throws {
        QueryCaptureURLProtocol.reset(body: #"{"data":{"output":"","cursor":7,"size":7,"truncated":false}}"#)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [QueryCaptureURLProtocol.self]
        let connection = self.connection
        let client = ShellAPIClient(connectionProvider: { connection }, urlSession: URLSession(configuration: configuration))

        _ = try await client.output(id: "sh1", directory: "/Users/me/src/c++-tool", cursor: 7)

        let url = try #require(QueryCaptureURLProtocol.lastRequest?.url)
        #expect(url.path == "/api/shell/sh1/output")
        #expect(url.query(percentEncoded: true) == "location%5Bdirectory%5D=/Users/me/src/c%2B%2B-tool&cursor=7")
    }

    @Test func sessionDiffKeepsPercent2BInDirectoryAndMessageID() async throws {
        QueryCaptureURLProtocol.reset(body: "[]")
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [QueryCaptureURLProtocol.self]
        let connection = self.connection
        let client = SessionsAPIClient(connectionProvider: { connection }, urlSession: URLSession(configuration: configuration))

        _ = try await client.getSessionDiff(id: "ses1", messageID: "msg+1", directory: "/src/c++")

        let url = try #require(QueryCaptureURLProtocol.lastRequest?.url)
        #expect(url.query(percentEncoded: true) == "directory=/src/c%2B%2B&messageID=msg%2B1")
    }

    /// Values without a `+` come out exactly as Foundation's `queryItems` setter built them before.
    @Test(arguments: [
        "/repo",
        "/repo work/é ü",
        "/a&b=c/d?e#f%g",
        "C:\\Users\\me",
        "~/proj[1]",
    ])
    func valueWithoutPlusIsUnchanged(_ value: String) throws {
        let base = try #require(URL(string: "https://example.test/session"))
        var legacy = try #require(URLComponents(url: base, resolvingAgainstBaseURL: false))
        legacy.queryItems = [URLQueryItem(name: "directory", value: value), URLQueryItem(name: "cursor", value: "3")]

        let built = base.appendingDirectoryQuery(value).appendingQueryItems([URLQueryItem(name: "cursor", value: "3")])

        #expect(built.absoluteString == legacy.url?.absoluteString)
    }

    @Test func noItemsLeavesURLAlone() throws {
        let url = try #require(URL(string: "https://example.test/session?directory=/src/c%2B%2B"))
        #expect(url.appendingQueryItems([]) == url)
        #expect(url.appendingDirectoryQuery(nil) == url)
        #expect(url.appendingDirectoryQuery("") == url)
    }

    private func queryValue(_ url: URL?, _ name: String) -> String? {
        url.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == name }?.value }
    }
}

private final class QueryCaptureURLProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var _lastRequest: URLRequest?
    nonisolated(unsafe) private static var body = ""

    static var lastRequest: URLRequest? {
        lock.lock()
        defer { lock.unlock() }
        return _lastRequest
    }

    static func reset(body: String) {
        lock.lock()
        _lastRequest = nil
        self.body = body
        lock.unlock()
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock()
        Self._lastRequest = request
        let body = Self.body
        Self.lock.unlock()
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
