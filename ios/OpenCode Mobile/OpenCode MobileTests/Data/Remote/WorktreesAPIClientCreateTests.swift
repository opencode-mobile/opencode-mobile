import Foundation
import Testing
@testable import OpenCode_Mobile

/// #752: create requests and the v2 branch list, v1 and v2.
@MainActor
@Suite(.serialized)
struct WorktreesAPIClientCreateTests {
    // MARK: v2

    @Test func createV2PostsProjectSourceNameAndBranch() async throws {
        CreateURLProtocol.reset(status: 200, body: #"{"directory":"/data/worktree/abc123/brave-otter"}"#)

        let directory = try await makeClient().createV2(
            projectID: "prj", from: "/src/repo", name: "brave-otter", branch: "origin/main"
        )

        #expect(directory == "/data/worktree/abc123/brave-otter")
        let request = try #require(CreateURLProtocol.lastRequest)
        #expect(request.httpMethod == "POST")
        #expect(request.url?.path.hasSuffix("/api/worktree") == true)
        #expect(request.url?.query == nil)
        #expect(request.value(forHTTPHeaderField: "Content-Type") == "application/json")
        let json = try bodyJSON()
        #expect(json["projectID"] as? String == "prj")
        #expect(json["from"] as? String == "/src/repo")
        #expect(json["name"] as? String == "brave-otter")
        #expect(json["branch"] as? String == "origin/main")
        #expect(json.count == 4)
    }

    @Test func createV2OmitsTheBranchKeyForTheDefaultBase() async throws {
        CreateURLProtocol.reset(status: 200, body: #"{"directory":"/data/worktree/abc123/calm-river"}"#)

        _ = try await makeClient().createV2(projectID: "prj", from: "/src/repo", name: "calm-river", branch: nil)

        let json = try bodyJSON()
        #expect(json["branch"] == nil)
        #expect(Set(json.keys) == ["projectID", "from", "name"])
    }

    @Test func createV2ErrorKeepsTheStatusAndBody() async {
        let body = #"{"_tag":"WorktreeError","name":"WorktreeError","data":{"message":"fatal: invalid reference: nope","forceRequired":false}}"#
        CreateURLProtocol.reset(status: 400, body: body)

        await #expect(throws: WorktreeRemoteError.http(statusCode: 400, body: body)) {
            _ = try await makeClient().createV2(projectID: "prj", from: "/src/repo", name: "x", branch: "nope")
        }
    }

    @Test func branchesV2ReadsTheLocatedList() async throws {
        CreateURLProtocol.reset(
            status: 200,
            body: #"{"location":{"directory":"/src/repo","workspaceID":null},"data":["main","feature/x","origin/main"]}"#
        )

        let branches = try await makeClient().branchesV2(directory: "/src/repo")

        #expect(branches == ["main", "feature/x", "origin/main"])
        let url = try #require(CreateURLProtocol.lastRequest?.url)
        #expect(url.path.hasSuffix("/api/vcs/branch"))
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        #expect(query.first { $0.name == "location[directory]" }?.value == "/src/repo")
    }

    @Test func branchesV2AcceptsABareList() async throws {
        CreateURLProtocol.reset(status: 200, body: #"["main"]"#)

        #expect(try await makeClient().branchesV2(directory: "/src/repo") == ["main"])
    }

    // MARK: v1

    @Test func createV1PostsTheNameForTheSourceDirectory() async throws {
        CreateURLProtocol.reset(
            status: 200,
            body: #"{"name":"brave-otter-tidy-tiger","branch":"opencode/brave-otter-tidy-tiger","directory":"/data/wt/brave-otter-tidy-tiger"}"#
        )

        let created = try await makeClient().createV1(directory: "/src/repo", name: "brave-otter")

        #expect(created == CreatedWorktree(
            directory: "/data/wt/brave-otter-tidy-tiger",
            name: "brave-otter-tidy-tiger",
            branch: "opencode/brave-otter-tidy-tiger"
        ))
        let request = try #require(CreateURLProtocol.lastRequest)
        #expect(request.httpMethod == "POST")
        let url = try #require(request.url)
        #expect(url.path.hasSuffix("/experimental/worktree"))
        #expect(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "directory" }?.value == "/src/repo")
        let json = try bodyJSON()
        #expect(json["name"] as? String == "brave-otter")
        #expect(json.count == 1)
    }

    // MARK: Repository

    @Test func repositoryV2ReturnsTheFolderAsNameAndNoBranch() async throws {
        CreateURLProtocol.reset(status: 200, body: #"{"directory":"/data/worktree/abc123/probe-one-2/"}"#)
        let repository = DefaultWorktreeRepository(remote: makeClient(), protocolProvider: { .v2 })

        let created = try await repository.create(
            CreateWorktreeRequest(projectID: "prj", sourceDirectory: "/src/repo", name: " probe-one ", baseRef: nil)
        )

        #expect(created == CreatedWorktree(directory: "/data/worktree/abc123/probe-one-2", name: "probe-one-2", branch: nil))
        #expect(try bodyJSON()["name"] as? String == "probe-one")
    }

    @Test func repositoryV2WithoutAProjectThrowsBeforeAnyRequest() async {
        CreateURLProtocol.reset(status: 200, body: #"{"directory":"/x"}"#)
        let repository = DefaultWorktreeRepository(remote: makeClient(), protocolProvider: { .v2 })

        for projectID in [nil, "", "global"] as [String?] {
            await #expect(throws: WorktreeCreateError(message: "Needs a git repository with a commit")) {
                _ = try await repository.create(
                    CreateWorktreeRequest(projectID: projectID, sourceDirectory: "/src/repo", name: "x", baseRef: nil)
                )
            }
        }
        #expect(CreateURLProtocol.requestCount == 0)
    }

    @Test func repositoryV1IgnoresProjectAndBase() async throws {
        CreateURLProtocol.reset(status: 200, body: #"{"name":"probe-v1","branch":"opencode/probe-v1","directory":"/data/wt/probe-v1"}"#)
        let repository = DefaultWorktreeRepository(remote: makeClient(), protocolProvider: { .v1 })

        let created = try await repository.create(
            CreateWorktreeRequest(projectID: nil, sourceDirectory: "/src/repo", name: "probe-v1", baseRef: "feature/x")
        )

        #expect(created == CreatedWorktree(directory: "/data/wt/probe-v1", name: "probe-v1", branch: "opencode/probe-v1"))
        #expect(CreateURLProtocol.lastRequest?.url?.path.hasSuffix("/experimental/worktree") == true)
        #expect(Set(try bodyJSON().keys) == ["name"])
    }

    @Test func repositoryV1NotFoundMeansTheServerCannotCreate() async {
        CreateURLProtocol.reset(status: 404, body: "Not Found")
        let repository = DefaultWorktreeRepository(remote: makeClient(), protocolProvider: { .v1 })
        repository.onConnection(key: "server")
        #expect(repository.canCreateWorktrees)

        await #expect(throws: WorktreeCreateError(message: "This server can't create worktrees")) {
            _ = try await repository.create(
                CreateWorktreeRequest(projectID: nil, sourceDirectory: "/src/repo", name: "x", baseRef: nil)
            )
        }
        #expect(!repository.canCreateWorktrees)
        // A new connection asks again.
        repository.onConnection(key: "other")
        #expect(repository.canCreateWorktrees)
    }

    @Test func repositoryErrorsUseTheServerWords() async {
        let cases: [(Int, String, String)] = [
            (400, #"{"_tag":"WorktreeError","name":"WorktreeError","data":{"message":"fatal: invalid reference: nope"}}"#,
             "fatal: invalid reference: nope"),
            (404, #"{"_tag":"ProjectNotFoundError","projectID":"prj","message":"Project not found: prj"}"#,
             "Project not found: prj"),
            (500, "  upstream exploded  ", "upstream exploded"),
            (502, "", "Request failed (HTTP 502)"),
        ]
        let repository = DefaultWorktreeRepository(remote: makeClient(), protocolProvider: { .v2 })
        for (status, body, message) in cases {
            CreateURLProtocol.reset(status: status, body: body)
            await #expect(throws: WorktreeCreateError(message: message)) {
                _ = try await repository.create(
                    CreateWorktreeRequest(projectID: "prj", sourceDirectory: "/src/repo", name: "x", baseRef: nil)
                )
            }
        }
    }

    @Test func repositoryV1ErrorUsesDataMessage() async {
        CreateURLProtocol.reset(
            status: 400,
            body: #"{"name":"WorktreeNotGitError","data":{"message":"Worktrees are only supported for git projects"}}"#
        )
        let repository = DefaultWorktreeRepository(remote: makeClient(), protocolProvider: { .v1 })

        await #expect(throws: WorktreeCreateError(message: "Worktrees are only supported for git projects")) {
            _ = try await repository.create(
                CreateWorktreeRequest(projectID: nil, sourceDirectory: "/notes", name: "x", baseRef: nil)
            )
        }
        #expect(repository.canCreateWorktrees)
    }

    @Test func repositoryBaseBranchesAreV2Only() async throws {
        CreateURLProtocol.reset(status: 200, body: #"{"location":{"directory":"/src/repo"},"data":["main"]}"#)
        let v2 = DefaultWorktreeRepository(remote: makeClient(), protocolProvider: { .v2 })
        #expect(try await v2.baseBranches(directory: "/src/repo") == ["main"])

        CreateURLProtocol.reset(status: 200, body: #"["main"]"#)
        let v1 = DefaultWorktreeRepository(remote: makeClient(), protocolProvider: { .v1 })
        #expect(try await v1.baseBranches(directory: "/src/repo") == [])
        #expect(CreateURLProtocol.requestCount == 0)
    }

    // MARK: Helpers

    private func bodyJSON() throws -> [String: Any] {
        let body = try #require(CreateURLProtocol.lastBody)
        return try #require(try JSONSerialization.jsonObject(with: body) as? [String: Any])
    }

    private func makeClient() -> WorktreesAPIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [CreateURLProtocol.self]
        let connection = ServerConnection(baseURL: URL(string: "https://example.test")!, username: "opencode", password: "secret")
        return WorktreesAPIClient(connectionProvider: { connection }, urlSession: URLSession(configuration: configuration))
    }
}

private final class CreateURLProtocol: URLProtocol {
    nonisolated(unsafe) static var lastRequest: URLRequest?
    nonisolated(unsafe) static var lastBody: Data?
    nonisolated(unsafe) static var requestCount = 0
    nonisolated(unsafe) static var status = 200
    nonisolated(unsafe) static var body = ""

    static func reset(status: Int, body: String) {
        lastRequest = nil
        lastBody = nil
        requestCount = 0
        self.status = status
        self.body = body
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lastRequest = request
        Self.lastBody = request.httpBody ?? request.httpBodyStream.map(Self.read)
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

    private static func read(_ stream: InputStream) -> Data {
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1024)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }
}
