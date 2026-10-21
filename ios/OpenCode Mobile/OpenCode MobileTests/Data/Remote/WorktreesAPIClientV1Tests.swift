import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
@Suite(.serialized)
struct WorktreesAPIClientV1Tests {
    @Test func listsExperimentalWorktreeForTheAddedDirectory() async throws {
        V1WorktreeURLProtocol.reset(status: 200, body: #"["/abs/wt-a","/abs/wt-b"]"#)

        let result = await makeClient().listV1(directory: "/abs/repo")

        guard case let .ok(body) = result else {
            Issue.record("expected ok, got \(result)")
            return
        }
        #expect(V1WorktreeListParser.parse(body) == ["/abs/wt-a", "/abs/wt-b"])
        let url = try #require(V1WorktreeURLProtocol.lastRequest?.url)
        #expect(url.path.hasSuffix("/experimental/worktree"))
        #expect(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "directory" }?.value == "/abs/repo")
    }

    @Test func notFoundMeansAnOldServer() async {
        V1WorktreeURLProtocol.reset(status: 404, body: "Not Found")

        let result = await makeClient().listV1(directory: "/abs/repo")

        guard case .notFound = result else {
            Issue.record("expected notFound, got \(result)")
            return
        }
    }

    @Test func repositoryReadsTheListAndStopsAfterNotFound() async {
        V1WorktreeURLProtocol.reset(status: 200, body: #"["/abs/wt-a"]"#)
        let repository = DefaultWorktreeRepository(remote: makeClient(), protocolProvider: { .v1 })
        repository.onConnection(key: "server")

        #expect(await repository.load(addedPath: "/abs/repo", projectID: "prj") == .loaded(directories: ["/abs/wt-a"], listsSessions: true))

        V1WorktreeURLProtocol.reset(status: 404, body: "")
        #expect(await repository.load(addedPath: "/abs/repo", projectID: "prj") == .stopped)
        V1WorktreeURLProtocol.reset(status: 200, body: #"["/abs/wt-a"]"#)
        #expect(await repository.load(addedPath: "/abs/repo", projectID: "prj") == .stopped)
        #expect(V1WorktreeURLProtocol.requestCount == 0)
    }

    @Test func emptyListIsLoaded() async {
        V1WorktreeURLProtocol.reset(status: 200, body: "[]")
        let repository = DefaultWorktreeRepository(remote: makeClient(), protocolProvider: { .v1 })
        repository.onConnection(key: "empty")

        #expect(await repository.load(addedPath: "/abs/repo", projectID: nil) == .loaded(directories: [], listsSessions: true))
    }

    private func makeClient() -> WorktreesAPIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [V1WorktreeURLProtocol.self]
        let connection = ServerConnection(baseURL: URL(string: "https://example.test")!, username: "opencode", password: "secret")
        return WorktreesAPIClient(connectionProvider: { connection }, urlSession: URLSession(configuration: configuration))
    }
}

private final class V1WorktreeURLProtocol: URLProtocol {
    nonisolated(unsafe) static var lastRequest: URLRequest?
    nonisolated(unsafe) static var requestCount = 0
    nonisolated(unsafe) static var status = 200
    nonisolated(unsafe) static var body = ""

    static func reset(status: Int, body: String) {
        lastRequest = nil
        requestCount = 0
        self.status = status
        self.body = body
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lastRequest = request
        Self.requestCount += 1
        client?.urlProtocol(
            self,
            didReceive: HTTPURLResponse(url: request.url!, statusCode: Self.status, httpVersion: nil, headerFields: nil)!,
            cacheStoragePolicy: .notAllowed
        )
        client?.urlProtocol(self, didLoad: Data(Self.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
