import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct SessionsAPIClientDiffTests {
    @Test func scopesLastTurnRequestByMessageAndDirectory() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SessionDiffURLProtocol.self]
        let session = URLSession(configuration: configuration)
        let connection = ServerConnection(baseURL: URL(string: "https://example.test")!, username: "opencode", password: "secret")
        let client = SessionsAPIClient(connectionProvider: { connection }, urlSession: session)

        _ = try await client.getSessionDiff(id: "ses/1", messageID: "msg + 1", directory: "/repo work")

        let request = try #require(SessionDiffURLProtocol.lastRequest)
        let url = try #require(request.url)
        let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
        #expect(components.path.hasSuffix("/session/ses/1/diff"))
        #expect(components.queryItems?.first { $0.name == "messageID" }?.value == "msg + 1")
        #expect(components.queryItems?.first { $0.name == "directory" }?.value == "/repo work")
        #expect(request.value(forHTTPHeaderField: "Authorization")?.hasPrefix("Basic ") == true)
    }
}

private final class SessionDiffURLProtocol: URLProtocol {
    static var lastRequest: URLRequest?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lastRequest = request
        let body = #"[{"file":"App.swift","patch":"@@ -1 +1 @@\n-old\n+new","additions":1,"deletions":1}]"#
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
