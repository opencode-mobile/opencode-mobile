import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
@Suite(.serialized)
struct SessionMoveAPIClientTests {
    @Test func moveV2PostsOnlyTheDirectory() async throws {
        MoveURLProtocol.reset(statusCode: 204, body: Data())
        let client = makeClient()

        let ok = try await client.moveSessionV2(id: "ses_1", directory: "/src/repo-wt")

        #expect(ok)
        let request = try #require(MoveURLProtocol.lastRequest)
        #expect(request.httpMethod == "POST")
        #expect(request.url?.path.hasSuffix("/api/session/ses_1/move") == true)
        #expect(request.value(forHTTPHeaderField: "Content-Type") == "application/json")
        let body = try #require(MoveURLProtocol.lastBody)
        let json = try #require(try JSONSerialization.jsonObject(with: body) as? [String: Any])
        #expect(json["directory"] as? String == "/src/repo-wt")
        // additionalProperties: false — no delivery, nothing else.
        #expect(json.count == 1)
    }

    @Test func moveV2SurfacesTheServerMessage() async {
        MoveURLProtocol.reset(
            statusCode: 400,
            body: Data(#"{"_tag":"InvalidRequestError","message":"Destination unavailable"}"#.utf8)
        )
        let remote = V2SessionRemote(api: makeClient(), adapter: SessionDomainAdapter())

        do {
            try await remote.moveSession(id: "ses_1", directory: "/gone")
            Issue.record("expected the move to fail")
        } catch {
            let mapped = SessionDomainAdapter().mapError(error)
            guard case let .http(statusCode, _) = mapped else {
                Issue.record("expected an HTTP error, got \(mapped)")
                return
            }
            #expect(statusCode == 400)
            #expect(SessionChatMoveToWorktree.serverMessage(mapped) == "Destination unavailable")
        }
    }

    @Test func v1HasNoMoveEndpoint() async {
        MoveURLProtocol.reset(statusCode: 204, body: Data())
        let remote = V1SessionRemote(api: makeClient(), adapter: SessionDomainAdapter())

        await #expect(throws: SessionError.http(statusCode: 404, message: "Needs an opencode v2 server")) {
            try await remote.moveSession(id: "ses_1", directory: "/src/repo-wt")
        }
        #expect(MoveURLProtocol.lastRequest == nil)
    }

    private func makeClient() -> SessionsAPIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MoveURLProtocol.self]
        let connection = ServerConnection(baseURL: URL(string: "https://example.test")!, username: "opencode", password: "secret")
        return SessionsAPIClient(connectionProvider: { connection }, urlSession: URLSession(configuration: configuration))
    }
}

struct SessionMovedEventTests {
    private let adapter = EventDomainAdapter()

    @Test func v2SessionMovedCarriesTheNewDirectory() {
        let events = adapter.parseEvents(
            dataLine: #"{"id":"evt_1","type":"session.moved","location":{"directory":"/src/repo"},"data":{"sessionID":"ses_1","location":{"directory":"/src/repo-wt"},"projectID":"prj","subpath":""}}"#
        )
        let moved = events.compactMap(adapter.sessionMoved(from:))
        #expect(moved == [SessionMovedUpdate(sessionID: "ses_1", directory: "/src/repo-wt", projectID: "prj")])
        // Home refetches its lists on the projected update.
        #expect(events.contains { $0.type == "session.updated" && $0.triggersHomeRefresh })
    }

    @Test func otherSessionUpdatesAreNotMoves() {
        let v1Update = OpencodeEvent(
            type: "session.updated",
            sessionID: "ses_1",
            rawProperties: ["info": .dictionary(["id": .string("ses_1"), "directory": .string("/src/repo")])]
        )
        #expect(adapter.sessionMoved(from: v1Update) == nil)
        #expect(adapter.sessionMoved(from: OpencodeEvent(type: "session.idle", sessionID: "ses_1")) == nil)
        let noDirectory = OpencodeEvent(
            type: "session.updated",
            sessionID: "ses_1",
            rawProperties: ["location": .dictionary([:])]
        )
        #expect(adapter.sessionMoved(from: noDirectory) == nil)
    }
}

struct SessionChatMoveToWorktreeTests {
    private let resolution = HomeProjectResolution(id: "prj", canonical: "/src/repo", isRepository: true)

    private var repo: HomeProject {
        HomeProjectGrouping.group(
            addedPaths: ["/src/repo"],
            resolutions: ["/src/repo": resolution],
            inventories: ["/src/repo": ["/src/repo", "/src/repo-wt"]],
            branches: ["/src/repo": "main", "/src/repo-wt": "feature/x"]
        )[0]
    }

    private var single: HomeProject {
        HomeProject(
            key: "path:/notes",
            projectID: nil,
            displayName: "notes",
            memberPaths: ["/notes"],
            isRepository: false,
            worktrees: [HomeWorktree(directory: "/notes", branch: nil, isMain: true)]
        )
    }

    @Test func enabledOnV2WithWorktreesAndAnotherWorktree() {
        #expect(
            SessionChatMoveToWorktree.availability(
                supportsMove: true, worktreesEnabled: true, sessionCreated: true, project: repo
            ) == .available
        )
    }

    @Test func v1IsDisabledFirst() {
        let availability = SessionChatMoveToWorktree.availability(
            supportsMove: false, worktreesEnabled: false, sessionCreated: false, project: nil
        )
        #expect(!availability.enabled)
        #expect(availability.subtitle == "Needs an opencode v2 server")
    }

    @Test func worktreesOffIsDisabled() {
        let availability = SessionChatMoveToWorktree.availability(
            supportsMove: true, worktreesEnabled: false, sessionCreated: true, project: repo
        )
        #expect(!availability.enabled)
        #expect(availability.subtitle == "Turn on Worktrees in Settings")
    }

    @Test func uncreatedSessionIsDisabledWithoutASubtitle() {
        let availability = SessionChatMoveToWorktree.availability(
            supportsMove: true, worktreesEnabled: true, sessionCreated: false, project: repo
        )
        #expect(!availability.enabled)
        #expect(availability.subtitle == nil)
    }

    @Test func singleWorktreeProjectIsDisabled() {
        for project in [single, nil] as [HomeProject?] {
            let availability = SessionChatMoveToWorktree.availability(
                supportsMove: true, worktreesEnabled: true, sessionCreated: true, project: project
            )
            #expect(!availability.enabled)
            #expect(availability.subtitle == "This project has no other worktrees")
        }
    }

    @Test func optionsListTheProjectWorktreesWithTheCurrentOneChecked() {
        let options = SessionChatMoveToWorktree.options(project: repo, currentDirectory: "/src/repo-wt/")
        #expect(options.map(\.directory) == ["/src/repo", "/src/repo-wt"])
        #expect(options.map(\.title) == ["main", "feature/x"])
        #expect(options.map(\.subtitle) == ["main checkout", "repo-wt"])
        #expect(options.map(\.isCurrent) == [false, true])
    }

    @Test func projectIsFoundByAnyOfItsWorktrees() {
        #expect(SessionChatMoveToWorktree.project(containing: "/src/repo-wt", in: [single, repo])?.key == "prj")
        #expect(SessionChatMoveToWorktree.project(containing: "/elsewhere", in: [single, repo]) == nil)
    }

    @Test func serverMessageReadsTheErrorText() {
        let invalid = SessionError.http(statusCode: 400, message: #"{"_tag":"InvalidRequestError","message":"Not a directory"}"#)
        #expect(SessionChatMoveToWorktree.serverMessage(invalid) == "Not a directory")
        let plain = SessionError.http(statusCode: 404, message: "Session not found")
        #expect(SessionChatMoveToWorktree.serverMessage(plain) == "Session not found")
        #expect(SessionChatMoveToWorktree.serverMessage(SessionError.transport("Offline")) == "Offline")
    }

    @Test func copyMatchesTheContract() {
        #expect(SessionChatMoveToWorktree.menuTitle == "Move to worktree\u{2026}")
        #expect(SessionChatMoveToWorktree.sheetTitle == "Move to worktree")
        #expect(SessionChatMoveToWorktree.movesAfterTurn("feature/x") == "Moves to feature/x after this turn.")
        #expect(SessionChatMoveToWorktree.failure("Nope") == "Couldn't move the session: Nope")
    }
}

private final class MoveURLProtocol: URLProtocol {
    nonisolated(unsafe) static var lastRequest: URLRequest?
    nonisolated(unsafe) static var lastBody: Data?
    nonisolated(unsafe) static var statusCode = 204
    nonisolated(unsafe) static var responseBody = Data()

    static func reset(statusCode: Int, body: Data) {
        lastRequest = nil
        lastBody = nil
        self.statusCode = statusCode
        responseBody = body
    }

    override class func canInit(with _: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lastRequest = request
        Self.lastBody = request.httpBody ?? request.httpBodyStream.map(Self.read)
        client?.urlProtocol(
            self,
            didReceive: HTTPURLResponse(
                url: request.url!,
                statusCode: Self.statusCode,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!,
            cacheStoragePolicy: .notAllowed
        )
        client?.urlProtocol(self, didLoad: Self.responseBody)
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
