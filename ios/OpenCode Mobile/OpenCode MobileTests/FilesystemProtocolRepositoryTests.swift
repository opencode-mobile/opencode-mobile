import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct FilesystemProtocolRepositoryTests {
    @Test func v1GetPathsUsesUnprefixedPath() async throws {
        FilesystemRoutingURLProtocol.reset(responses: [
            .json(#"{"home":"/home","worktree":"/repo","directory":"/repo"}"#)
        ])
        let repository = makeFilesystemRepository(serverProtocol: .v1)

        let paths = try await repository.getPaths()

        #expect(paths.home == "/home")
        #expect(paths.directory == "/repo")
        let url = try #require(FilesystemRoutingURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/path"))
    }

    @Test func v2GetPathsMapsLocationDirectory() async throws {
        FilesystemRoutingURLProtocol.reset(responses: [
            .json(#"""
            {
              "directory":"/repo",
              "project":{"id":"p","directory":"/repo","canonical":"/repo"}
            }
            """#)
        ])
        let repository = makeFilesystemRepository(serverProtocol: .v2)

        let paths = try await repository.getPaths()

        #expect(paths.home == "/repo")
        #expect(paths.directory == "/repo")
        #expect(paths.worktree == "/repo")
        let url = try #require(FilesystemRoutingURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/api/location"))
    }

    @Test func v2ListDirectoryJoinsRelativeEntries() async throws {
        FilesystemRoutingURLProtocol.reset(responses: [
            .json(#"""
            {
              "location":{"directory":"/repo","project":{"id":"p","directory":"/repo","canonical":"/repo"}},
              "data":[
                {"path":"src","type":"directory"},
                {"path":"README.md","type":"file"}
              ]
            }
            """#)
        ])
        let repository = makeFilesystemRepository(serverProtocol: .v2)

        let nodes = try await repository.listDirectory(path: "/repo")

        #expect(nodes.map(\.name) == ["src"])
        #expect(nodes.first?.absolutePath == "/repo/src")
        #expect(nodes.first?.isDirectory == true)
        let url = try #require(FilesystemRoutingURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/api/fs/list"))
        #expect(queryValue(url, "location[directory]") == "/repo")
        #expect(queryValue(url, "path") == ".")
    }

    private func makeFilesystemRepository(serverProtocol: ServerProtocol) -> DefaultFilesystemRepository {
        let manager = makeManager(serverProtocol: serverProtocol)
        return DefaultFilesystemRepository(
            api: FilesystemAPIClient(connectionProvider: { manager.connection }, urlSession: makeURLSession()),
            adapter: FilesystemDomainAdapter(),
            activeConnectionManager: manager
        )
    }

    private func makeManager(serverProtocol: ServerProtocol) -> ActiveConnectionManager {
        let manager = ActiveConnectionManager()
        manager.applyConnection(
            ServerConnection(
                baseURL: URL(string: "https://example.test")!,
                username: "opencode",
                password: "secret",
                serverProtocol: serverProtocol
            )
        )
        return manager
    }

    private func makeURLSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [FilesystemRoutingURLProtocol.self]
        return URLSession(configuration: configuration)
    }

    private func queryValue(_ url: URL, _ name: String) -> String? {
        URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?
            .first { $0.name == name }?
            .value
    }
}

private struct FilesystemStubResponse {
    let statusCode: Int
    let body: String

    static func json(_ body: String) -> FilesystemStubResponse {
        FilesystemStubResponse(statusCode: 200, body: body)
    }
}

private final class FilesystemRoutingURLProtocol: URLProtocol {
    static var requests: [URLRequest] = []
    static var responses: [FilesystemStubResponse] = []

    static func reset(responses: [FilesystemStubResponse]) {
        requests = []
        self.responses = responses
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.requests.append(request)
        let stub = Self.responses.isEmpty ? FilesystemStubResponse.json("{}") : Self.responses.removeFirst()
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
