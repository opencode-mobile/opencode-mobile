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

    @Test func v1ListDirectoryUsesRootAsDirectoryAndAbsolutePath() async throws {
        FilesystemRoutingURLProtocol.reset(responses: [
            .json(#"""
            [
              {"name":"OpenChamber 2.3.3","path":"Volumes/OpenChamber 2.3.3/","absolute":"/Volumes/OpenChamber 2.3.3","type":"directory"},
              {"name":"notes.txt","path":"Volumes/notes.txt","absolute":"/Volumes/notes.txt","type":"file"}
            ]
            """#)
        ])
        let repository = makeFilesystemRepository(serverProtocol: .v1)

        let nodes = try await repository.listDirectory(path: "/Volumes").folders

        #expect(nodes.map(\.name) == ["OpenChamber 2.3.3"])
        #expect(nodes.map(\.absolutePath) == ["/Volumes/OpenChamber 2.3.3"])
        let url = try #require(FilesystemRoutingURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/file"))
        #expect(queryValue(url, "directory") == "/")
        #expect(queryValue(url, "path") == "/Volumes")
    }

    @Test func v1ListDirectoryUsesTheDriveRootOnWindows() async throws {
        FilesystemRoutingURLProtocol.reset(responses: [
            .json(#"""
            [{"name":"code","path":"Users\\me\\code","absolute":"C:\\Users\\me\\code","type":"directory"}]
            """#)
        ])
        let repository = makeFilesystemRepository(serverProtocol: .v1)

        let nodes = try await repository.listDirectory(path: #"C:\Users\me"#).folders

        #expect(nodes.map(\.absolutePath) == [#"C:\Users\me\code"#])
        let url = try #require(FilesystemRoutingURLProtocol.requests.first?.url)
        #expect(queryValue(url, "directory") == #"C:\"#)
        #expect(queryValue(url, "path") == #"C:\Users\me"#)
    }

    @Test func v2ListDirectoryResolvesEntriesAgainstTheResponseLocation() async throws {
        FilesystemRoutingURLProtocol.reset(responses: [
            .json(#"""
            {
              "location":{"directory":"/","project":{"id":"global","directory":"/","canonical":"/"}},
              "data":[
                {"path":"repo/src/","type":"directory"},
                {"path":"repo/docs","type":"directory"},
                {"path":"repo/.github/","type":"directory"},
                {"path":"repo/README.md","type":"file"}
              ]
            }
            """#)
        ])
        let repository = makeFilesystemRepository(serverProtocol: .v2)

        let nodes = try await repository.listDirectory(path: "/repo").folders

        // Hidden folders stay in the listing; the Add project sheet hides them until "." is typed.
        #expect(nodes.filter { !DirectoryListingPolicy.isHidden($0.name) }.map(\.name) == ["docs", "src"])
        #expect(Set(nodes.map(\.absolutePath)) == ["/repo/.github", "/repo/docs", "/repo/src"])
        #expect(nodes.map(\.isDirectory) == [true, true, true])
        let url = try #require(FilesystemRoutingURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/api/fs/list"))
        #expect(queryValue(url, "location[directory]") == "/")
        #expect(queryValue(url, "path") == "/repo")
    }

    @Test func v2ListDirectoryMatchesTheVerifiedVolumesResponse() async throws {
        FilesystemRoutingURLProtocol.reset(responses: [
            .json(#"""
            {"location":{"directory":"/"},"data":[{"path":"Volumes/OpenChamber 2.3.3/","type":"directory"}]}
            """#)
        ])
        let repository = makeFilesystemRepository(serverProtocol: .v2)

        let nodes = try await repository.listDirectory(path: "/Volumes").folders

        #expect(nodes.map(\.name) == ["OpenChamber 2.3.3"])
        #expect(nodes.map(\.absolutePath) == ["/Volumes/OpenChamber 2.3.3"])
    }

    @Test func v2ListDirectoryKeepsRootChildrenNamed() async throws {
        FilesystemRoutingURLProtocol.reset(responses: [
            .json(#"""
            {
              "location":{"directory":"/","project":{"id":"global","directory":"/","canonical":"/"}},
              "data":[
                {"path":"home/","type":"directory"},
                {"path":"etc/","type":"directory"}
              ]
            }
            """#)
        ])
        let repository = makeFilesystemRepository(serverProtocol: .v2)

        let nodes = try await repository.listDirectory(path: "/").folders

        #expect(nodes.map(\.name) == ["etc", "home"])
        #expect(nodes.map(\.absolutePath) == ["/etc", "/home"])
        let url = try #require(FilesystemRoutingURLProtocol.requests.first?.url)
        #expect(queryValue(url, "location[directory]") == "/")
        #expect(queryValue(url, "path") == "/")
    }

    @Test func v2ListDirectoryResolvesWindowsRelativeAndCrossDriveEntries() async throws {
        FilesystemRoutingURLProtocol.reset(responses: [
            .json(#"""
            {
              "location":{
                "directory":"C:\\Users\\me\\work",
                "project":{"id":"global","directory":"C:\\Users\\me\\work","canonical":"C:\\Users\\me\\work"}
              },
              "data":[
                {"path":"..\\code\\","type":"directory"},
                {"path":"D:\\archive\\","type":"directory"}
              ]
            }
            """#)
        ])
        let repository = makeFilesystemRepository(serverProtocol: .v2)

        let nodes = try await repository.listDirectory(path: #"C:\Users\me"#).folders

        #expect(nodes.map(\.name) == ["archive", "code"])
        #expect(nodes.map(\.absolutePath) == [#"D:\archive"#, #"C:\Users\me\code"#])
        let url = try #require(FilesystemRoutingURLProtocol.requests.first?.url)
        #expect(queryValue(url, "location[directory]") == #"C:\"#)
        #expect(queryValue(url, "path") == #"C:\Users\me"#)
    }

    @Test func v2ListDirectoryFallsBackToTheRootWithoutALocation() async throws {
        FilesystemRoutingURLProtocol.reset(responses: [
            .json(#"""
            {"data":[{"path":"Users\\me\\code\\","type":"directory"}]}
            """#)
        ])
        let repository = makeFilesystemRepository(serverProtocol: .v2)

        let nodes = try await repository.listDirectory(path: #"C:\Users\me"#).folders

        #expect(nodes.map(\.absolutePath) == [#"C:\Users\me\code"#])
    }

    @Test func v1ListDirectoryReportsAGitWorktreeFromItsGitFile() async throws {
        FilesystemRoutingURLProtocol.reset(responses: [
            .json(#"""
            [
              {"name":"src","path":"wt/src/","absolute":"/wt/src","type":"directory"},
              {"name":".git","path":"wt/.git","absolute":"/wt/.git","type":"file"}
            ]
            """#)
        ])
        let repository = makeFilesystemRepository(serverProtocol: .v1)

        let listing = try await repository.listDirectory(path: "/wt")

        #expect(listing.isGitRepository)
        #expect(listing.folders.map(\.name) == ["src"])
        #expect(listing.visibleFolderCount == 1)
    }

    @Test func v2ListDirectoryCountsVisibleFoldersAndFindsTheGitFolder() async throws {
        FilesystemRoutingURLProtocol.reset(responses: [
            .json(#"""
            {"location":{"directory":"/"},"data":[
              {"path":"repo/.git/","type":"directory"},
              {"path":"repo/.github/","type":"directory"},
              {"path":"repo/src/","type":"directory"},
              {"path":"repo/README.md","type":"file"}
            ]}
            """#)
        ])
        let repository = makeFilesystemRepository(serverProtocol: .v2)

        let listing = try await repository.listDirectory(path: "/repo")

        #expect(listing.isGitRepository)
        #expect(listing.visibleFolderCount == 1)
    }

    @Test func v2ListDirectoryMaps404ToNotFound() async throws {
        FilesystemRoutingURLProtocol.reset(responses: [
            FilesystemStubResponse(statusCode: 404, body: #"{"_tag":"FileNotFoundError","message":"Directory not found: /srv/x"}"#)
        ])
        let repository = makeFilesystemRepository(serverProtocol: .v2)

        await #expect(throws: FilesystemError.directoryNotFound) {
            try await repository.listDirectory(path: "/srv/x")
        }
        #expect(FilesystemRoutingURLProtocol.requests.count == 1)
    }

    @Test func v1ListDirectoryIsNotFoundWhenTheParentHasNoSuchFolder() async throws {
        FilesystemRoutingURLProtocol.reset(responses: [
            .serverError,
            .json(#"""
            [
              {"name":"code","path":"home/me/code/","absolute":"/home/me/code","type":"directory"},
              {"name":"notes","path":"home/me/notes","absolute":"/home/me/notes","type":"file"}
            ]
            """#)
        ])
        let repository = makeFilesystemRepository(serverProtocol: .v1)

        await #expect(throws: FilesystemError.directoryNotFound) {
            try await repository.listDirectory(path: "/home/me/notes")
        }
        let paths = FilesystemRoutingURLProtocol.requests.compactMap { $0.url.flatMap { queryValue($0, "path") } }
        #expect(paths == ["/home/me/notes", "/home/me"])
    }

    @Test func v1ListDirectoryWalksUpToTheFirstListableAncestor() async throws {
        FilesystemRoutingURLProtocol.reset(responses: [
            .serverError,
            .serverError,
            .json(#"[{"name":"opt","path":"opt/","absolute":"/opt","type":"directory"}]"#)
        ])
        let repository = makeFilesystemRepository(serverProtocol: .v1)

        await #expect(throws: FilesystemError.directoryNotFound) {
            try await repository.listDirectory(path: "/srv/apps")
        }
        let paths = FilesystemRoutingURLProtocol.requests.compactMap { $0.url.flatMap { queryValue($0, "path") } }
        #expect(paths == ["/srv/apps", "/srv", "/"])
    }

    @Test func v1ListDirectoryKeepsTheFailureWhenTheFolderExists() async throws {
        FilesystemRoutingURLProtocol.reset(responses: [
            .serverError,
            .json(#"[{"name":"private","path":"home/private/","absolute":"/home/private","type":"directory"}]"#)
        ])
        let repository = makeFilesystemRepository(serverProtocol: .v1)

        do {
            _ = try await repository.listDirectory(path: "/home/private")
            Issue.record("Expected a failure")
        } catch FilesystemError.directoryNotFound {
            Issue.record("An unreadable folder is not a missing one")
        } catch {
            guard case ProjectsAPIError.httpStatus(500, _) = error else {
                Issue.record("Expected the original 500, got \(error)")
                return
            }
        }
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

    /// What opencode v1 answers for a missing folder, a file, or an unreadable folder.
    static let serverError = FilesystemStubResponse(
        statusCode: 500,
        body: #"{"name":"UnknownError","data":{"message":"Unexpected server error."}}"#
    )
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
