import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct ProjectFilesRepositoryTests {
    // MARK: v1

    @Test func v1ListUsesFileEndpointWithWorktreeDirectoryAndDotRoot() async throws {
        ProjectFilesURLProtocol.reset(responses: [
            .json(#"""
            [
              {"name":"src","path":"src","absolute":"/repo/src","type":"directory","ignored":false},
              {"name":"build","path":"build","absolute":"/repo/build","type":"directory","ignored":true},
              {"name":".env","path":".env","absolute":"/repo/.env","type":"file","ignored":false}
            ]
            """#),
        ])
        let repository = makeRepository(.v1)

        let entries = try await repository.listEntries(worktree: "/repo", relativeDirectory: "")

        #expect(entries.map(\.relativePath) == ["src", "build", ".env"])
        #expect(entries.map(\.isDirectory) == [true, true, false])
        #expect(entries.map(\.isIgnored) == [false, true, false])
        let url = try #require(ProjectFilesURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/file"))
        #expect(queryValue(url, "path") == ".")
        #expect(queryValue(url, "directory") == "/repo")
    }

    @Test func v1ListNestedKeepsDirectoryAndVariesPath() async throws {
        ProjectFilesURLProtocol.reset(responses: [
            .json(#"[{"name":"App.swift","path":"src/App.swift","absolute":"/repo/src/App.swift","type":"file"}]"#),
        ])
        let repository = makeRepository(.v1)

        let entries = try await repository.listEntries(worktree: "/repo", relativeDirectory: "src")

        #expect(entries == [ProjectEntry(name: "App.swift", relativePath: "src/App.swift", isDirectory: false)])
        let url = try #require(ProjectFilesURLProtocol.requests.first?.url)
        #expect(queryValue(url, "path") == "src")
        #expect(queryValue(url, "directory") == "/repo")
    }

    @Test func v1FindSendsFilesOnlyAndLimit() async throws {
        ProjectFilesURLProtocol.reset(responses: [.json(#"["src/App.swift","README.md"]"#)])
        let repository = makeRepository(.v1)

        let entries = try await repository.findFiles(worktree: "/repo", query: "app", limit: 50)

        #expect(entries.map(\.name) == ["App.swift", "README.md"])
        #expect(entries.map(\.parentPath) == ["src", ""])
        let url = try #require(ProjectFilesURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/find/file"))
        #expect(queryValue(url, "query") == "app")
        #expect(queryValue(url, "dirs") == "false")
        #expect(queryValue(url, "limit") == "50")
        #expect(queryValue(url, "directory") == "/repo")
    }

    @Test func v1ReadTextUsesContentEndpoint() async throws {
        ProjectFilesURLProtocol.reset(responses: [.json(#"{"type":"text","content":"let x = 1"}"#)])
        let repository = makeRepository(.v1)

        let content = try await repository.readFile(worktree: "/repo", relativePath: "src/a.swift")

        #expect(content == .text("let x = 1", isTruncated: false, totalBytes: 9))
        let url = try #require(ProjectFilesURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/file/content"))
        #expect(queryValue(url, "path") == "src/a.swift")
        #expect(queryValue(url, "directory") == "/repo")
    }

    @Test func v1ReadEmptyTextIsEmpty() async throws {
        ProjectFilesURLProtocol.reset(responses: [.json(#"{"type":"text","content":""}"#)])
        let content = try await makeRepository(.v1).readFile(worktree: "/repo", relativePath: "missing.txt")
        #expect(content == .empty)
    }

    @Test func v1ReadBase64ImageDecodes() async throws {
        let png = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        ProjectFilesURLProtocol.reset(responses: [
            .json(#"{"type":"binary","content":"\#(png.base64EncodedString())","encoding":"base64","mimeType":"image/png"}"#),
        ])
        let content = try await makeRepository(.v1).readFile(worktree: "/repo", relativePath: "logo.png")
        #expect(content == .image(png, mimeType: "image/png"))
    }

    @Test func v1ReadBase64BinaryKeepsMimeType() async throws {
        let bytes = Data([0, 1, 2, 3])
        ProjectFilesURLProtocol.reset(responses: [
            .json(#"{"type":"binary","content":"\#(bytes.base64EncodedString())","encoding":"base64","mimeType":"application/zip"}"#),
        ])
        let content = try await makeRepository(.v1).readFile(worktree: "/repo", relativePath: "a.zip")
        #expect(content == .binary(mimeType: "application/zip", byteCount: 4))
    }

    @Test func v1ReadLargeTextIsTruncatedClientSide() async throws {
        let line = String(repeating: "a", count: 99) + "\n"
        let text = String(repeating: line, count: 11_000) // 1.1 MB
        let body = try JSONSerialization.data(withJSONObject: ["type": "text", "content": text])
        ProjectFilesURLProtocol.reset(responses: [ProjectFilesStubResponse(statusCode: 200, headers: [:], body: body)])

        let content = try await makeRepository(.v1).readFile(worktree: "/repo", relativePath: "big.txt")

        guard case let .text(kept, isTruncated, total) = content else {
            Issue.record("expected text, got \(content)")
            return
        }
        #expect(isTruncated)
        #expect(total == text.utf8.count)
        #expect(kept.utf8.count <= ProjectFileLimits.maxTextBytes)
        #expect(kept.hasSuffix("a"))
        #expect(kept.split(separator: "\n").allSatisfy { $0.count == 99 })
    }

    @Test func v1ServerErrorThrowsDomainError() async throws {
        ProjectFilesURLProtocol.reset(responses: [ProjectFilesStubResponse(statusCode: 500, headers: [:], body: Data("boom".utf8))])
        await #expect(throws: ProjectFilesError.server(statusCode: 500)) {
            try await makeRepository(.v1).readFile(worktree: "/repo", relativePath: "../outside")
        }
    }

    // MARK: v2

    @Test func v2ListRootOmitsPathAndDerivesNames() async throws {
        ProjectFilesURLProtocol.reset(responses: [
            .json(#"""
            {"location":{"directory":"/repo"},"data":[
              {"path":"src/","type":"directory"},
              {"path":"README.md","type":"file"}
            ]}
            """#),
        ])
        let entries = try await makeRepository(.v2).listEntries(worktree: "/repo", relativeDirectory: "")

        #expect(entries == [
            ProjectEntry(name: "src", relativePath: "src", isDirectory: true),
            ProjectEntry(name: "README.md", relativePath: "README.md", isDirectory: false),
        ])
        let url = try #require(ProjectFilesURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/api/fs/list"))
        #expect(queryValue(url, "path") == nil)
        #expect(queryValue(url, "location[directory]") == "/repo")
    }

    @Test func v2ListNestedSendsPathAndStripsTrailingSlash() async throws {
        ProjectFilesURLProtocol.reset(responses: [
            .json(#"{"location":{"directory":"/repo"},"data":[{"path":"src/ui/","type":"directory"},{"path":"src/a.ts","type":"file"}]}"#),
        ])
        let entries = try await makeRepository(.v2).listEntries(worktree: "/repo", relativeDirectory: "src")

        #expect(entries.map(\.name) == ["ui", "a.ts"])
        #expect(entries.map(\.relativePath) == ["src/ui", "src/a.ts"])
        #expect(entries.map(\.isDirectory) == [true, false])
        #expect(entries.allSatisfy { !$0.isIgnored })
        let url = try #require(ProjectFilesURLProtocol.requests.first?.url)
        #expect(queryValue(url, "path") == "src")
    }

    @Test func v2FindSendsTypeFileAndLimit() async throws {
        ProjectFilesURLProtocol.reset(responses: [
            .json(#"{"location":{"directory":"/repo"},"data":[{"path":"src/a.ts","type":"file"},{"path":"src/","type":"directory"}]}"#),
        ])
        let entries = try await makeRepository(.v2).findFiles(worktree: "/repo", query: "a", limit: 50)

        #expect(entries == [ProjectEntry(name: "a.ts", relativePath: "src/a.ts", isDirectory: false)])
        let url = try #require(ProjectFilesURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/api/fs/find"))
        #expect(queryValue(url, "query") == "a")
        #expect(queryValue(url, "type") == "file")
        #expect(queryValue(url, "limit") == "50")
        #expect(queryValue(url, "location[directory]") == "/repo")
    }

    @Test func v2ReadPercentEncodesEachSegmentAndSendsRange() async throws {
        ProjectFilesURLProtocol.reset(responses: [.bytes(Data("hello\n".utf8), contentType: "text/plain; charset=utf-8")])

        let content = try await makeRepository(.v2).readFile(worktree: "/repo", relativePath: "my dir/a#1 ?.txt")

        #expect(content == .text("hello\n", isTruncated: false, totalBytes: 6))
        let request = try #require(ProjectFilesURLProtocol.requests.first)
        let url = try #require(request.url)
        let encodedPath = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedPath)
        #expect(encodedPath.hasSuffix("/api/fs/read/my%20dir/a%231%20%3F.txt"))
        #expect(queryValue(url, "location[directory]") == "/repo")
        #expect(request.value(forHTTPHeaderField: "Range") == "bytes=0-1048575")
    }

    @Test func v2Read206UsesContentRangeTotalForTruncation() async throws {
        let line = String(repeating: "b", count: 63) + "\n"
        let body = Data(String(repeating: line, count: ProjectFileLimits.maxTextBytes / 64).utf8)
        ProjectFilesURLProtocol.reset(responses: [
            ProjectFilesStubResponse(
                statusCode: 206,
                headers: ["Content-Type": "text/plain", "Content-Range": "bytes 0-1048575/3000000"],
                body: body
            ),
        ])

        let content = try await makeRepository(.v2).readFile(worktree: "/repo", relativePath: "big.log")

        guard case let .text(text, isTruncated, total) = content else {
            Issue.record("expected text, got \(content)")
            return
        }
        #expect(isTruncated)
        #expect(total == 3_000_000)
        #expect(!text.hasSuffix("\n"))
        #expect(text.split(separator: "\n").count == ProjectFileLimits.maxTextBytes / 64)
    }

    @Test func v2Read200FullBodyStopsAfterLimitAndTruncates() async throws {
        let line = String(repeating: "c", count: 63) + "\n"
        let body = Data(String(repeating: line, count: ProjectFileLimits.maxTextBytes / 64 + 100).utf8)
        ProjectFilesURLProtocol.reset(responses: [.bytes(body, contentType: "text/plain")])

        let content = try await makeRepository(.v2).readFile(worktree: "/repo", relativePath: "big.log")

        guard case let .text(text, isTruncated, total) = content else {
            Issue.record("expected text, got \(content)")
            return
        }
        #expect(isTruncated)
        #expect(total == nil)
        #expect(text.utf8.count < ProjectFileLimits.maxTextBytes)
        #expect(text.split(separator: "\n").allSatisfy { $0.count == 63 })
    }

    @Test func v2ReadNulByteIsBinary() async throws {
        ProjectFilesURLProtocol.reset(responses: [.bytes(Data([0x41, 0x00, 0x42]), contentType: "application/octet-stream")])
        let content = try await makeRepository(.v2).readFile(worktree: "/repo", relativePath: "a.bin")
        #expect(content == .binary(mimeType: "application/octet-stream", byteCount: 3))
    }

    @Test func v2ReadInvalidUTF8IsBinary() async throws {
        ProjectFilesURLProtocol.reset(responses: [.bytes(Data([0x41, 0xFF, 0xFE, 0x42]), contentType: nil)])
        let content = try await makeRepository(.v2).readFile(worktree: "/repo", relativePath: "a.dat")
        #expect(content == .binary(mimeType: nil, byteCount: 4))
    }

    @Test func v2ReadEmptyIsEmpty() async throws {
        ProjectFilesURLProtocol.reset(responses: [.bytes(Data(), contentType: "text/plain")])
        let content = try await makeRepository(.v2).readFile(worktree: "/repo", relativePath: "empty.txt")
        #expect(content == .empty)
    }

    @Test func v2ReadImageSkipsRangeAndReturnsImage() async throws {
        let png = Data([0x89, 0x50, 0x4E, 0x47])
        ProjectFilesURLProtocol.reset(responses: [.bytes(png, contentType: "image/png")])

        let content = try await makeRepository(.v2).readFile(worktree: "/repo", relativePath: "assets/logo.png")

        #expect(content == .image(png, mimeType: "image/png"))
        #expect(ProjectFilesURLProtocol.requests.first?.value(forHTTPHeaderField: "Range") == nil)
    }

    @Test func v2ReadImageOverCapIsBinary() async throws {
        let big = Data(count: ProjectFileLimits.maxImageBytes + 10)
        ProjectFilesURLProtocol.reset(responses: [.bytes(big, contentType: "image/jpeg")])
        let content = try await makeRepository(.v2).readFile(worktree: "/repo", relativePath: "photo.jpg")
        #expect(content == .binary(mimeType: "image/jpeg", byteCount: nil))
    }

    @Test func v2NotFoundThrowsNotFound() async throws {
        ProjectFilesURLProtocol.reset(responses: [ProjectFilesStubResponse(statusCode: 404, headers: [:], body: Data())])
        await #expect(throws: ProjectFilesError.notFound) {
            try await makeRepository(.v2).readFile(worktree: "/repo", relativePath: "gone.txt")
        }
    }

    // MARK: Helpers

    private func makeRepository(_ serverProtocol: ServerProtocol) -> DefaultProjectFilesRepository {
        let manager = ActiveConnectionManager()
        manager.applyConnection(
            ServerConnection(
                baseURL: URL(string: "https://example.test")!,
                username: "opencode",
                password: "secret",
                serverProtocol: serverProtocol
            )
        )
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ProjectFilesURLProtocol.self]
        let session = URLSession(configuration: configuration)
        return DefaultProjectFilesRepository(
            api: FilesystemAPIClient(connectionProvider: { manager.connection }, urlSession: session),
            adapter: FilesystemDomainAdapter(),
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

private struct ProjectFilesStubResponse {
    let statusCode: Int
    let headers: [String: String]
    let body: Data

    static func json(_ body: String) -> ProjectFilesStubResponse {
        ProjectFilesStubResponse(statusCode: 200, headers: ["Content-Type": "application/json"], body: Data(body.utf8))
    }

    static func bytes(_ body: Data, contentType: String?) -> ProjectFilesStubResponse {
        ProjectFilesStubResponse(
            statusCode: 200,
            headers: contentType.map { ["Content-Type": $0] } ?? [:],
            body: body
        )
    }
}

private final class ProjectFilesURLProtocol: URLProtocol {
    nonisolated(unsafe) static var requests: [URLRequest] = []
    nonisolated(unsafe) static var responses: [ProjectFilesStubResponse] = []
    private static let lock = NSLock()

    static func reset(responses: [ProjectFilesStubResponse]) {
        lock.lock()
        defer { lock.unlock() }
        requests = []
        self.responses = responses
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lock.lock()
        Self.requests.append(request)
        let stub = Self.responses.isEmpty ? ProjectFilesStubResponse.json("[]") : Self.responses.removeFirst()
        Self.lock.unlock()
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: stub.statusCode,
            httpVersion: "HTTP/1.1",
            headerFields: stub.headers
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if !stub.body.isEmpty {
            client?.urlProtocol(self, didLoad: stub.body)
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
