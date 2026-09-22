import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct McpProtocolRepositoryTests {
    @Test func v1ListServersParsesStatusMapAndSortsByName() async throws {
        McpStubURLProtocol.reset(responses: [
            .json("""
            {
              "playwright": {"status":"failed","error":"spawn npx ENOENT"},
              "context7": {"status":"connected"},
              "github": {"status":"needs_auth"},
              "notion": {"status":"needs_client_registration"},
              "filesystem": {"status":"disabled"}
            }
            """)
        ])
        let repository = makeRepository(serverProtocol: .v1)

        let servers = try await repository.listServers(directory: "/repo")

        #expect(servers.map(\.name) == ["context7", "filesystem", "github", "notion", "playwright"])
        #expect(servers.map(\.status) == [.connected, .disabled, .needsSignIn, .needsSignIn, .failed])
        #expect(servers.last?.errorMessage == "spawn npx ENOENT")
        #expect(servers.first?.errorMessage == nil)
        let request = try #require(McpStubURLProtocol.requests.first)
        #expect(request.url?.path == "/mcp")
        #expect(queryValue(request.url!, "directory") == "/repo")
    }

    @Test func v2ListServersUnwrapsEnvelopeAndMapsPending() async throws {
        McpStubURLProtocol.reset(responses: [
            .json("""
            {"location":{"directory":"/repo"},"data":[
              {"name":"linear","status":{"status":"pending"}},
              {"name":"github","status":{"status":"needs_auth","error":"OAuth token expired"}},
              {"name":"context7","status":{"status":"connected"}}
            ]}
            """)
        ])
        let repository = makeRepository(serverProtocol: .v2)

        let servers = try await repository.listServers(directory: "/repo")

        #expect(servers.map(\.name) == ["context7", "github", "linear"])
        #expect(servers.last?.status == .connecting)
        #expect(servers[1].errorMessage == "OAuth token expired")
        let request = try #require(McpStubURLProtocol.requests.first)
        #expect(request.url?.path == "/api/mcp")
        #expect(queryValue(request.url!, "location[directory]") == "/repo")
    }

    @Test func listServersHttpFailureMapsToMcpError() async {
        McpStubURLProtocol.reset(responses: [McpStubResponse(statusCode: 500, body: "nope")])
        let repository = makeRepository(serverProtocol: .v1)

        do {
            _ = try await repository.listServers(directory: "/repo")
            Issue.record("expected failure")
        } catch let error as McpError {
            #expect(error == .http(statusCode: 500, message: "nope"))
        } catch {
            Issue.record("unexpected error \(error)")
        }
    }

    @Test func summaryRanksFailedAboveNeedsSignIn() {
        let summary = McpServerCatalog.summary([
            McpServer(name: "a", status: .connected),
            McpServer(name: "b", status: .needsSignIn),
            McpServer(name: "c", status: .failed, errorMessage: "boom"),
            McpServer(name: "d", status: .disabled)
        ])
        #expect(summary.worst == .failed)
        #expect(summary.attention == 2)
        #expect(summary.connected == 1)
        #expect(summary.headline == "1 of 4 connected")
        #expect(McpServerCatalog.summary([]) == .empty)
    }

    // MARK: - Helpers

    private func makeRepository(serverProtocol: ServerProtocol) -> DefaultMcpRepository {
        let manager = ActiveConnectionManager(connectionProbing: nil)
        manager.applyConnection(
            ServerConnection(
                baseURL: URL(string: "https://example.test")!,
                username: "opencode",
                password: "secret",
                serverProtocol: serverProtocol
            )
        )
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [McpStubURLProtocol.self]
        return DefaultMcpRepository(
            api: McpAPIClient(connectionProvider: { manager.connection }, urlSession: URLSession(configuration: configuration)),
            adapter: McpDomainAdapter(),
            activeConnectionManager: manager
        )
    }

    private func queryValue(_ url: URL, _ name: String) -> String? {
        URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?
            .first { $0.name == name }?
            .value
    }
}

private struct McpStubResponse {
    let statusCode: Int
    let body: String

    static func json(_ body: String) -> McpStubResponse {
        McpStubResponse(statusCode: 200, body: body)
    }
}

private final class McpStubURLProtocol: URLProtocol {
    nonisolated(unsafe) static var requests: [URLRequest] = []
    nonisolated(unsafe) static var responses: [McpStubResponse] = []

    static func reset(responses: [McpStubResponse]) {
        requests = []
        self.responses = responses
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.requests.append(request)
        let stub = Self.responses.isEmpty ? McpStubResponse.json("{}") : Self.responses.removeFirst()
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: stub.statusCode,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if !stub.body.isEmpty {
            client?.urlProtocol(self, didLoad: Data(stub.body.utf8))
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
