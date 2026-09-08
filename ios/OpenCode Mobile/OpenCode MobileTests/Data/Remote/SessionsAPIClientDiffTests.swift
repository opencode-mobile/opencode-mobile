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

    @Test func scopesVcsMetadataAndDiffRequests() async throws {
        SessionDiffURLProtocol.reset(responses: [
            #"{"branch":"feature/review","default_branch":"main"}"#,
            #"[{"file":"App.swift","patch":"@@ -1 +1 @@\n-old\n+new","additions":1,"deletions":1,"status":"modified"}]"#,
        ])
        let client = makeClient()

        let info = try await client.getVcsInfo(directory: "/repo work")
        let files = try await client.getVcsDiff(mode: .workingTree, directory: "/repo work", context: 12)

        #expect(info.branch == "feature/review")
        #expect(info.defaultBranch == "main")
        #expect(files.first?.file == "App.swift")
        #expect(SessionDiffURLProtocol.requests.count == 2)
        let infoURL = try #require(SessionDiffURLProtocol.requests[0].url)
        let diffURL = try #require(SessionDiffURLProtocol.requests[1].url)
        #expect(infoURL.path.hasSuffix("/vcs"))
        #expect(URLComponents(url: infoURL, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "directory" }?.value == "/repo work")
        let diffQuery = URLComponents(url: diffURL, resolvingAgainstBaseURL: false)?.queryItems
        #expect(diffURL.path.hasSuffix("/vcs/diff"))
        #expect(diffQuery?.first { $0.name == "mode" }?.value == "git")
        #expect(diffQuery?.first { $0.name == "context" }?.value == "12")
        #expect(diffQuery?.first { $0.name == "directory" }?.value == "/repo work")
    }

    @Test func mapsMainBranchSourceToBranchMode() async throws {
        SessionDiffURLProtocol.reset(responses: ["[]"])
        let client = makeClient()

        _ = try await client.getVcsDiff(mode: .mainBranch, directory: "/repo", context: 12)

        let requestURL = try #require(SessionDiffURLProtocol.requests.first?.url)
        #expect(URLComponents(url: requestURL, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "mode" }?.value == "branch")
    }

    private func makeClient() -> SessionsAPIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SessionDiffURLProtocol.self]
        let connection = ServerConnection(baseURL: URL(string: "https://example.test")!, username: "opencode", password: "secret")
        return SessionsAPIClient(connectionProvider: { connection }, urlSession: URLSession(configuration: configuration))
    }
}

private final class SessionDiffURLProtocol: URLProtocol {
    static var lastRequest: URLRequest?
    static var requests: [URLRequest] = []
    static var responses: [String] = []

    static func reset(responses: [String]) {
        lastRequest = nil
        requests = []
        self.responses = responses
    }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lastRequest = request
        Self.requests.append(request)
        let body = Self.responses.isEmpty
            ? #"[{"file":"App.swift","patch":"@@ -1 +1 @@\n-old\n+new","additions":1,"deletions":1}]"#
            : Self.responses.removeFirst()
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
