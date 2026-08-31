import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct TerminalProtocolRepositoryTests {
    @Test func v1ListUsesDirectoryQueryAndMapsSession() async throws {
        TerminalRoutingURLProtocol.reset(responses: [
            .json(ptyArrayJSON(id: "pty_1", status: "running"))
        ])
        let repository = makeRepository(serverProtocol: .v1)

        let sessions = try await repository.list(directory: "/repo")

        #expect(sessions.map(\.id) == ["pty_1"])
        #expect(sessions.first?.title == "term")
        #expect(sessions.first?.command == "/bin/zsh")
        #expect(sessions.first?.cwd == "/repo")
        #expect(sessions.first?.status == .running)
        let url = try #require(TerminalRoutingURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/pty"))
        #expect(queryValue(url, "directory") == "/repo")
    }

    @Test func v2ListUnwrapsEnvelopeAndUsesLocationDirectory() async throws {
        TerminalRoutingURLProtocol.reset(responses: [
            .json(#"""
            {"data":[\#(ptyObjectJSON(id: "pty_2", status: "exited"))],"cursor":{"previous":null,"next":null}}
            """#)
        ])
        let repository = makeRepository(serverProtocol: .v2)

        let sessions = try await repository.list(directory: "/repo")

        #expect(sessions.map(\.id) == ["pty_2"])
        #expect(sessions.first?.status == .exited)
        let url = try #require(TerminalRoutingURLProtocol.requests.first?.url)
        #expect(url.path.contains("/api/pty"))
        #expect(queryValue(url, "location[directory]") == "/repo")
    }

    @Test func v2ListAcceptsRawArray() async throws {
        TerminalRoutingURLProtocol.reset(responses: [
            .json(ptyArrayJSON(id: "pty_raw", status: "running"))
        ])
        let repository = makeRepository(serverProtocol: .v2)

        let sessions = try await repository.list(directory: "/repo")

        #expect(sessions.map(\.id) == ["pty_raw"])
        #expect(sessions.first?.status == .running)
    }

    @Test func v1CreatePostsCwdOnlyWithDirectoryQuery() async throws {
        TerminalRoutingURLProtocol.reset(responses: [
            .json(ptyObjectJSON(id: "pty_new", status: "running"))
        ])
        let repository = makeRepository(serverProtocol: .v1)

        let session = try await repository.create(directory: "/repo")

        #expect(session.id == "pty_new")
        let request = try #require(TerminalRoutingURLProtocol.requests.first)
        #expect(request.httpMethod == "POST")
        #expect(request.url?.path.hasSuffix("/pty") == true)
        #expect(queryValue(try #require(request.url), "directory") == "/repo")
        let body = try #require(jsonObject(TerminalRoutingURLProtocol.bodies.first ?? nil))
        #expect(body["cwd"] as? String == "/repo")
        #expect(body["command"] == nil)
        #expect(body["title"] == nil)
    }

    @Test func v2CreatePostsCwdWithLocationDirectory() async throws {
        TerminalRoutingURLProtocol.reset(responses: [
            .json(#"""
            {"data":\#(ptyObjectJSON(id: "pty_v2", status: "running"))}
            """#)
        ])
        let repository = makeRepository(serverProtocol: .v2)

        let session = try await repository.create(directory: "/work", cwd: "/work")

        #expect(session.id == "pty_v2")
        let request = try #require(TerminalRoutingURLProtocol.requests.first)
        #expect(request.httpMethod == "POST")
        #expect(request.url?.path.contains("/api/pty") == true)
        #expect(queryValue(try #require(request.url), "location[directory]") == "/work")
        let body = try #require(jsonObject(TerminalRoutingURLProtocol.bodies.first ?? nil))
        #expect(body["cwd"] as? String == "/work")
    }

    @Test func v1GetUsesDirectoryQuery() async throws {
        TerminalRoutingURLProtocol.reset(responses: [
            .json(ptyObjectJSON(id: "pty_1", status: "running"))
        ])
        let repository = makeRepository(serverProtocol: .v1)

        let session = try await repository.get(id: "pty_1", directory: "/repo")

        #expect(session.id == "pty_1")
        let url = try #require(TerminalRoutingURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/pty/pty_1"))
        #expect(queryValue(url, "directory") == "/repo")
    }

    @Test func v2GetUnwrapsDataEnvelope() async throws {
        TerminalRoutingURLProtocol.reset(responses: [
            .json(#"""
            {"data":\#(ptyObjectJSON(id: "pty_9", status: "exited"))}
            """#)
        ])
        let repository = makeRepository(serverProtocol: .v2)

        let session = try await repository.get(id: "pty_9", directory: "/repo")

        #expect(session.id == "pty_9")
        #expect(session.status == .exited)
        let url = try #require(TerminalRoutingURLProtocol.requests.first?.url)
        #expect(url.path.contains("/api/pty/pty_9"))
        #expect(queryValue(url, "location[directory]") == "/repo")
    }

    @Test func v1UpdateSizePutsRowsAndCols() async throws {
        TerminalRoutingURLProtocol.reset(responses: [
            .json(ptyObjectJSON(id: "pty_1", status: "running"))
        ])
        let repository = makeRepository(serverProtocol: .v1)

        _ = try await repository.updateSize(id: "pty_1", directory: "/repo", cols: 80, rows: 24)

        let request = try #require(TerminalRoutingURLProtocol.requests.first)
        #expect(request.httpMethod == "PUT")
        #expect(request.url?.path.hasSuffix("/pty/pty_1") == true)
        #expect(queryValue(try #require(request.url), "directory") == "/repo")
        let body = try #require(jsonObject(TerminalRoutingURLProtocol.bodies.first ?? nil))
        let size = try #require(body["size"] as? [String: Any])
        #expect(size["rows"] as? Int == 24)
        #expect(size["cols"] as? Int == 80)
    }

    @Test func v2UpdateSizePutsApiPathWithLocationDirectory() async throws {
        TerminalRoutingURLProtocol.reset(responses: [
            .json(#"""
            {"data":\#(ptyObjectJSON(id: "pty_1", status: "running"))}
            """#)
        ])
        let repository = makeRepository(serverProtocol: .v2)

        _ = try await repository.updateSize(id: "pty_1", directory: "/repo", cols: 120, rows: 40)

        let request = try #require(TerminalRoutingURLProtocol.requests.first)
        #expect(request.httpMethod == "PUT")
        #expect(request.url?.path.contains("/api/pty/pty_1") == true)
        #expect(queryValue(try #require(request.url), "location[directory]") == "/repo")
        let body = try #require(jsonObject(TerminalRoutingURLProtocol.bodies.first ?? nil))
        let size = try #require(body["size"] as? [String: Any])
        #expect(size["rows"] as? Int == 40)
        #expect(size["cols"] as? Int == 120)
    }

    @Test func ensureRunningReusesFirstRunningSession() async throws {
        TerminalRoutingURLProtocol.reset(responses: [
            .json(#"""
            [\#(ptyObjectJSON(id: "pty_dead", status: "exited")),\#(ptyObjectJSON(id: "pty_live", status: "running"))]
            """#)
        ])
        let repository = makeRepository(serverProtocol: .v1)

        let session = try await repository.ensureRunning(directory: "/repo")

        #expect(session.id == "pty_live")
        #expect(TerminalRoutingURLProtocol.requests.count == 1)
        #expect(TerminalRoutingURLProtocol.requests.first?.httpMethod == "GET")
    }

    @Test func v1RemoveDeletesWithDirectoryQuery() async throws {
        TerminalRoutingURLProtocol.reset(responses: [
            .json("true")
        ])
        let repository = makeRepository(serverProtocol: .v1)

        try await repository.remove(id: "pty_1", directory: "/repo")

        let request = try #require(TerminalRoutingURLProtocol.requests.first)
        #expect(request.httpMethod == "DELETE")
        #expect(request.url?.path.hasSuffix("/pty/pty_1") == true)
        #expect(queryValue(try #require(request.url), "directory") == "/repo")
    }

    @Test func v2RemoveDeletesApiPathWithLocationDirectory() async throws {
        TerminalRoutingURLProtocol.reset(responses: [
            .json("true")
        ])
        let repository = makeRepository(serverProtocol: .v2)

        try await repository.remove(id: "pty_9", directory: "/work")

        let request = try #require(TerminalRoutingURLProtocol.requests.first)
        #expect(request.httpMethod == "DELETE")
        #expect(request.url?.path.contains("/api/pty/pty_9") == true)
        #expect(queryValue(try #require(request.url), "location[directory]") == "/work")
    }

    @Test func listRunningFansOutPerDirectoryAndKeepsRunningOnly() async throws {
        TerminalRoutingURLProtocol.reset(responses: [
            .json("[]"),
            .json(#"""
            [\#(ptyObjectJSON(id: "pty_dead", status: "exited")),\#(ptyObjectJSON(id: "pty_live", status: "running"))]
            """#),
            .json(ptyArrayJSON(id: "pty_play", status: "running"))
        ])
        let repository = makeRepository(serverProtocol: .v1)

        let running = try await repository.listRunning(directories: ["/repo", "/play"])

        #expect(running.map(\.session.id) == ["pty_live", "pty_play"])
        #expect(running.map(\.directory) == ["/repo", "/play"])
        #expect(TerminalRoutingURLProtocol.requests.count == 3)
        #expect(TerminalRoutingURLProtocol.requests.allSatisfy { $0.httpMethod == "GET" })
        #expect(queryValue(try #require(TerminalRoutingURLProtocol.requests[0].url), "directory") == nil)
        #expect(queryValue(try #require(TerminalRoutingURLProtocol.requests[1].url), "directory") == "/repo")
        #expect(queryValue(try #require(TerminalRoutingURLProtocol.requests[2].url), "directory") == "/play")
    }

    @Test func listRunningUnscopedListReturnsRunningWithoutFanOut() async throws {
        TerminalRoutingURLProtocol.reset(responses: [
            .json(ptyArrayJSON(id: "pty_live", status: "running"))
        ])
        let repository = makeRepository(serverProtocol: .v1)

        let running = try await repository.listRunning(directories: ["/repo", "/play"])

        #expect(running.map(\.session.id) == ["pty_live"])
        #expect(running.map(\.directory) == ["/repo"])
        #expect(TerminalRoutingURLProtocol.requests.count == 1)
        #expect(queryValue(try #require(TerminalRoutingURLProtocol.requests[0].url), "directory") == nil)
    }

    @Test func listRunningSkipsFailedDirectoryAndKeepsOthers() async throws {
        TerminalRoutingURLProtocol.reset(responses: [
            .json("[]"),
            TerminalStubResponse(statusCode: 500, body: "no"),
            .json(ptyArrayJSON(id: "pty_play", status: "running"))
        ])
        let repository = makeRepository(serverProtocol: .v1)

        let running = try await repository.listRunning(directories: ["/bad", "/play"])

        #expect(running.map(\.session.id) == ["pty_play"])
        #expect(running.map(\.directory) == ["/play"])
    }

    @Test func listBlankStatusMapsToRunning() async throws {
        TerminalRoutingURLProtocol.reset(responses: [
            .json(#"""
            [{"id":"pty_1","title":"term","command":"/bin/zsh","cwd":"/repo"}]
            """#)
        ])
        let repository = makeRepository(serverProtocol: .v1)

        let sessions = try await repository.list(directory: "/repo")

        #expect(sessions.first?.status == .running)
    }

    @Test func ensureRunningCreatesWhenNoneRunning() async throws {
        TerminalRoutingURLProtocol.reset(responses: [
            .json(#"""
            [\#(ptyObjectJSON(id: "pty_dead", status: "exited"))]
            """#),
            .json(ptyObjectJSON(id: "pty_created", status: "running"))
        ])
        let repository = makeRepository(serverProtocol: .v1)

        let session = try await repository.ensureRunning(directory: "/repo")

        #expect(session.id == "pty_created")
        #expect(TerminalRoutingURLProtocol.requests.count == 2)
        #expect(TerminalRoutingURLProtocol.requests[0].httpMethod == "GET")
        #expect(TerminalRoutingURLProtocol.requests[1].httpMethod == "POST")
        let body = try #require(jsonObject(TerminalRoutingURLProtocol.bodies[1]))
        #expect(body["cwd"] as? String == "/repo")
        #expect(body["command"] == nil)
    }

    private func makeRepository(serverProtocol: ServerProtocol) -> DefaultTerminalRepository {
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
        configuration.protocolClasses = [TerminalRoutingURLProtocol.self]
        return DefaultTerminalRepository(
            api: PtyAPIClient(connectionProvider: { manager.connection }, urlSession: URLSession(configuration: configuration)),
            adapter: TerminalDomainAdapter(),
            activeConnectionManager: manager
        )
    }

    private func jsonObject(_ body: String?) -> [String: Any]? {
        guard let body, let data = body.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private func queryValue(_ url: URL, _ name: String) -> String? {
        URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?
            .first { $0.name == name }?
            .value
    }
}

private func ptyArrayJSON(id: String, status: String) -> String {
    "[\(ptyObjectJSON(id: id, status: status))]"
}

private func ptyObjectJSON(id: String, status: String) -> String {
    #"{"id":"\#(id)","title":"term","command":"/bin/zsh","args":[],"cwd":"/repo","status":"\#(status)","pid":12}"#
}

private struct TerminalStubResponse {
    let statusCode: Int
    let body: String

    static func json(_ body: String) -> TerminalStubResponse {
        TerminalStubResponse(statusCode: 200, body: body)
    }
}

private final class TerminalRoutingURLProtocol: URLProtocol, @unchecked Sendable {
    static var requests: [URLRequest] = []
    static var bodies: [String?] = []
    static var responses: [TerminalStubResponse] = []

    static func reset(responses: [TerminalStubResponse]) {
        requests = []
        bodies = []
        self.responses = responses
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.requests.append(request)
        Self.bodies.append(request.readBodyText())
        let stub = Self.responses.isEmpty ? TerminalStubResponse.json("{}") : Self.responses.removeFirst()
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

private extension URLRequest {
    func readBodyText() -> String? {
        if let httpBody {
            return String(data: httpBody, encoding: .utf8)
        }
        guard let stream = httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        let bufferSize = 4096
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }
        while stream.hasBytesAvailable {
            let read = stream.read(buffer, maxLength: bufferSize)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return String(data: data, encoding: .utf8)
    }
}
