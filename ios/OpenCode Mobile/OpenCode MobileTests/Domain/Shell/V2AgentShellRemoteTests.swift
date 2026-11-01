import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct V2AgentShellRemoteTests {
    @Test func listsTheSessionDirectoryAndFollowsEvents() async throws {
        ShellRoutingURLProtocol.reset(responses: [.json(AgentShellFixtures.listJSON)])
        let events = FakeForegroundEventStream()
        let remote = makeRemote(events: events)
        var updates = remote.shells(sessionID: "ses_test", directory: "/tmp/project", transcript: { AsyncStream { $0.finish() } })
            .makeAsyncIterator()

        let first = await updates.next()
        events.emit(try AgentShellFixtures.event(AgentShellFixtures.killedEvent))
        let stopped = await updates.next()
        events.emit(try AgentShellFixtures.event(AgentShellFixtures.deletedEvent))
        let removed = await updates.next()

        #expect(first?.map(\.id) == [AgentShellFixtures.runningID])
        #expect(stopped?.map(\.status) == [.stopped])
        #expect(removed == [])
        let request = try #require(ShellRoutingURLProtocol.requests.first)
        #expect(request.httpMethod == "GET")
        #expect(request.url?.path == "/api/shell")
        #expect(queryValue(request.url, "location[directory]") == "/tmp/project")
    }

    @Test func outputReadsTheTailThenPollsUntilTheShellIsGone() async throws {
        ShellRoutingURLProtocol.reset(responses: [
            .json(#"{"location":{"directory":"/tmp/project"},"data":{"output":"","cursor":35,"size":35,"truncated":false}}"#),
            .json(AgentShellFixtures.outputJSON),
            .json(#"{"location":{"directory":"/tmp/project"},"data":{"output":"line 6\n","cursor":42,"size":42,"truncated":false}}"#),
            TestShellStubResponse(statusCode: 404, body: AgentShellFixtures.notFoundJSON),
        ])
        let remote = makeRemote(events: FakeForegroundEventStream())
        let shell = AgentShellFixtures.shell(AgentShellFixtures.runningID)

        var outputs: [AgentShellOutput] = []
        for await output in remote.output(shell: shell, directory: "/tmp/project", transcript: { AsyncStream { $0.finish() } }) {
            outputs.append(output)
        }

        #expect(outputs.map(\.text) == ["line 1\nline 2\nline 3\nline 4\nline 5\n", "line 1\nline 2\nline 3\nline 4\nline 5\nline 6\n"])
        let requests = ShellRoutingURLProtocol.requests
        #expect(requests.count == 4)
        #expect(requests.map { queryValue($0.url, "cursor") } == ["999999999999", "0", "35", "42"])
        #expect(requests.allSatisfy { $0.url?.path == "/api/shell/\(AgentShellFixtures.runningID)/output" })
        #expect(requests.allSatisfy { queryValue($0.url, "location[directory]") == "/tmp/project" })
    }

    @Test func stopDeletesTheShellInItsDirectory() async throws {
        ShellRoutingURLProtocol.reset(responses: [TestShellStubResponse(statusCode: 204, body: "")])
        let remote = makeRemote(events: FakeForegroundEventStream())

        try await remote.stop(shell: AgentShellFixtures.shell(AgentShellFixtures.runningID), directory: "/tmp/project")

        let request = try #require(ShellRoutingURLProtocol.requests.first)
        #expect(request.httpMethod == "DELETE")
        #expect(request.url?.path == "/api/shell/\(AgentShellFixtures.runningID)")
        #expect(queryValue(request.url, "location[directory]") == "/tmp/project")
    }

    @Test func stopFailureThrows() async {
        ShellRoutingURLProtocol.reset(responses: [TestShellStubResponse(statusCode: 500, body: "{}")])
        let remote = makeRemote(events: FakeForegroundEventStream())

        await #expect(throws: ShellAPIError.self) {
            try await remote.stop(shell: AgentShellFixtures.shell(AgentShellFixtures.runningID), directory: "/tmp/project")
        }
    }

    @Test func v2ServerWithoutTheShellRouteHasNoShells() async {
        ShellRoutingURLProtocol.reset(responses: [])
        let manager = ActiveConnectionManager()
        manager.applyConnection(ServerConnection(
            baseURL: URL(string: "https://example.com")!,
            username: "opencode",
            password: "test-password",
            serverProtocol: .v2
        ))
        let repository = DefaultAgentShellRepository(
            api: makeAPI(manager: manager),
            eventStream: FakeForegroundEventStream(),
            activeConnectionManager: manager
        )

        var lists = repository.shells(sessionID: "ses_test", directory: "/tmp/project", transcript: { AsyncStream { $0.finish() } })
            .makeAsyncIterator()

        #expect(await lists.next() == [])
        #expect(ShellRoutingURLProtocol.requests.isEmpty)
    }

    @Test func coldStartPicksTheShellAPIOnceTheContractIsReadAndTheStreamConnects() async throws {
        let probe = ShellFeatureProbe()
        let manager = ActiveConnectionManager(connectionProbing: probe)
        manager.apply(
            ConnectionProfile(baseURL: "https://example.com/", username: "opencode", password: "test-password"),
            serverProtocol: .v2
        )
        _ = try? await manager.probeActive()
        #expect(!manager.capabilities.supportsAgentShells)
        let events = FakeForegroundEventStream()
        let shell = AgentShellFixtures.shell("sh_1")
        let repository = DefaultAgentShellRepository(
            v1: UnavailableAgentShellRemote(),
            v2: FixedShellsRemote(shells: [shell]),
            activeConnectionManager: manager,
            eventStream: events
        )
        var lists = repository.shells(sessionID: "ses_test", directory: "/tmp/project", transcript: { AsyncStream { $0.finish() } })
            .makeAsyncIterator()
        #expect(await lists.next() == [])

        probe.declaresShells = true
        _ = try? await manager.probeActive()
        events.emit(OpencodeEvent(type: "server.connected", sessionID: nil))

        #expect(await lists.next() == [shell])
    }

    private func makeRemote(events: FakeForegroundEventStream) -> V2AgentShellRemote {
        let manager = ActiveConnectionManager()
        manager.applyConnection(ServerConnection(
            baseURL: URL(string: "https://example.com")!,
            username: "opencode",
            password: "test-password",
            serverProtocol: .v2
        ))
        return V2AgentShellRemote(api: makeAPI(manager: manager), eventStream: events, pollInterval: .milliseconds(1))
    }

    private func makeAPI(manager: ActiveConnectionManager) -> ShellAPIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ShellRoutingURLProtocol.self]
        let connection = manager.connection
        return ShellAPIClient(connectionProvider: { connection }, urlSession: URLSession(configuration: configuration))
    }

    private func queryValue(_ url: URL?, _ name: String) -> String? {
        url.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == name }?.value }
    }
}

struct TestShellStubResponse {
    let statusCode: Int
    let body: String

    static func json(_ body: String) -> TestShellStubResponse {
        TestShellStubResponse(statusCode: 200, body: body)
    }
}

private final class ShellRoutingURLProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var _requests: [URLRequest] = []
    nonisolated(unsafe) private static var responses: [TestShellStubResponse] = []

    static var requests: [URLRequest] {
        lock.lock()
        defer { lock.unlock() }
        return _requests
    }

    static func reset(responses: [TestShellStubResponse]) {
        lock.lock()
        _requests = []
        self.responses = responses
        lock.unlock()
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lock.lock()
        Self._requests.append(request)
        let stub = Self.responses.isEmpty ? TestShellStubResponse(statusCode: 404, body: "{}") : Self.responses.removeFirst()
        Self.lock.unlock()
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

private final class ShellFeatureProbe: ConnectionProbing, @unchecked Sendable {
    private let lock = NSLock()
    private var _declaresShells = false

    var declaresShells: Bool {
        get { lock.withLock { _declaresShells } }
        set { lock.withLock { _declaresShells = newValue } }
    }

    func probe(connection _: ServerConnection) async throws -> ServerProtocol { .v2 }

    func probeV2Features(connection _: ServerConnection) async throws -> V2ServerFeatures {
        V2ServerFeatures(agentShells: declaresShells)
    }
}

/// Yields one fixed list and stays open.
private struct FixedShellsRemote: AgentShellRemote {
    let shells: [AgentShell]

    func shells(sessionID _: String, directory _: String, transcript _: @escaping AgentShellTranscript) -> AsyncStream<[AgentShell]> {
        let shells = shells
        return AsyncStream { $0.yield(shells) }
    }

    func output(shell _: AgentShell, directory _: String, transcript _: @escaping AgentShellTranscript) -> AsyncStream<AgentShellOutput> {
        AsyncStream { $0.finish() }
    }

    func stop(shell _: AgentShell, directory _: String) async throws {}
}
