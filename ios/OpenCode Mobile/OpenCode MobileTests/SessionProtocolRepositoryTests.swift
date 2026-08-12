import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct SessionProtocolRepositoryTests {
    @Test func v1ListSessionsUsesFlatArrayAndDirectoryQuery() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [
            .json(#"[{"id":"ses_1","title":"One","projectID":"proj","directory":"/repo","time":{"created":1,"updated":2}}]"#)
        ])
        let repository = makeSessionRepository(serverProtocol: .v1)

        let sessions = try await repository.listSessions(directory: "/repo")

        #expect(sessions.map(\.id) == ["ses_1"])
        let url = try #require(ProtocolRoutingURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/session"))
        #expect(queryValue(url, "directory") == "/repo")
    }

    @Test func v2ListSessionsUnwrapsEnvelopeAndKeepsDirectoryQuery() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [
            .json(#"""
            {
              "data": [{
                "id": "ses_2",
                "title": "Two",
                "projectID": "proj",
                "cost": 0,
                "tokens": {"input": 1, "output": 2, "reasoning": 0, "cache": {"read": 0, "write": 0}},
                "time": {"created": 1, "updated": 2},
                "location": {"directory": "/repo"}
              }],
              "cursor": {"previous": null, "next": null}
            }
            """#)
        ])
        let repository = makeSessionRepository(serverProtocol: .v2)

        let sessions = try await repository.listSessions(directory: "/repo")

        let session = try #require(sessions.first)
        #expect(sessions.count == 1)
        #expect(session.id == "ses_2")
        #expect(session.directory == "/repo")
        let url = try #require(ProtocolRoutingURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/api/session"))
        #expect(queryValue(url, "directory") == "/repo")
    }

    @Test func v2ActiveStatusesMapRunningToBusy() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [
            .json(#"{"data":{"ses_busy":{"type":"running"}},"watermarks":{}}"#)
        ])
        let repository = makeSessionRepository(serverProtocol: .v2)

        let statuses = try await repository.getAllSessionStatuses(directory: "/repo")

        let status = try #require(statuses.first)
        #expect(statuses.count == 1)
        #expect(status.sessionID == "ses_busy")
        #expect(status.raw["type"] == "busy")
        let url = try #require(ProtocolRoutingURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/api/session/active"))
    }

    @Test func v2RenamePostsRenameThenReloadsSession() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [
            .noContent,
            .json(sessionEnvelope(id: "ses_1", title: "Renamed"))
        ])
        let repository = makeSessionRepository(serverProtocol: .v2)

        let session = try await repository.updateSession(id: "ses_1", title: "Renamed", directory: "/repo")

        #expect(session.title == "Renamed")
        #expect(ProtocolRoutingURLProtocol.requests.count == 2)
        #expect(ProtocolRoutingURLProtocol.requests[0].httpMethod == "POST")
        #expect(ProtocolRoutingURLProtocol.requests[0].url?.path.hasSuffix("/api/session/ses_1/rename") == true)
        #expect(ProtocolRoutingURLProtocol.bodies[0]?.contains(#""title":"Renamed""#) == true)
        #expect(ProtocolRoutingURLProtocol.requests[1].url?.path.hasSuffix("/api/session/ses_1") == true)
    }

    @Test func v2ForkPostsMessageIdEnvelope() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [
            .json(sessionEnvelope(id: "ses_fork", title: "Fork", parentID: "ses_1"))
        ])
        let repository = makeSessionRepository(serverProtocol: .v2)

        let forked = try await repository.forkSession(id: "ses_1", atMessageID: "msg_1")

        #expect(forked.id == "ses_fork")
        #expect(forked.parentID == "ses_1")
        let request = try #require(ProtocolRoutingURLProtocol.requests.first)
        #expect(request.httpMethod == "POST")
        #expect(request.url?.path.hasSuffix("/api/session/ses_1/fork") == true)
        #expect(ProtocolRoutingURLProtocol.bodies.first??.contains(#""messageID":"msg_1""#) == true)
    }

    @Test func v2CreateSessionPostsLocationThenOptionalRename() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [
            .json(sessionEnvelope(id: "ses_new", title: "New session")),
            .noContent
        ])
        let repository = makeSessionRepository(serverProtocol: .v2)

        let created = try await repository.createSession(parentID: nil, title: "Named", directory: "/repo")

        #expect(created.id == "ses_new")
        #expect(created.title == "Named")
        #expect(ProtocolRoutingURLProtocol.requests.count == 2)
        #expect(ProtocolRoutingURLProtocol.requests[0].url?.path.hasSuffix("/api/session") == true)
        let createBody = try #require(ProtocolRoutingURLProtocol.bodies[0])
        let location = try #require(jsonObject(createBody)?["location"] as? [String: Any])
        #expect(location["directory"] as? String == "/repo")
        #expect(ProtocolRoutingURLProtocol.requests[1].url?.path.hasSuffix("/api/session/ses_new/rename") == true)
    }

    @Test func v2DeleteSessionUsesApiPath() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [.noContent])
        let repository = makeSessionRepository(serverProtocol: .v2)

        let deleted = try await repository.deleteSession(id: "ses_1", directory: "/repo")

        #expect(deleted)
        let request = try #require(ProtocolRoutingURLProtocol.requests.first)
        #expect(request.httpMethod == "DELETE")
        #expect(request.url?.path.hasSuffix("/api/session/ses_1") == true)
    }

    @Test func v2ArchiveSessionPostsArchiveThenReloadsSession() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [
            .noContent,
            .json(sessionEnvelope(id: "ses_1", title: "One", archived: 42))
        ])
        let repository = makeSessionRepository(serverProtocol: .v2)

        let archived = try await repository.archiveSession(id: "ses_1", directory: "/repo")

        #expect(archived.archivedAtEpochMs == 42)
        #expect(ProtocolRoutingURLProtocol.requests[0].httpMethod == "POST")
        #expect(ProtocolRoutingURLProtocol.requests[0].url?.path.hasSuffix("/api/session/ses_1/archive") == true)
        #expect(ProtocolRoutingURLProtocol.requests[1].url?.path.hasSuffix("/api/session/ses_1") == true)
    }

    @Test func v2ProjectCurrentUsesLocationDirectoryQuery() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [.json(#"{"id":"proj_1","directory":"/repo"}"#)])
        let manager = makeManager(serverProtocol: .v2)
        let repository = DefaultProjectRepository(
            api: ProjectsAPIClient(fixedConnection: try #require(manager.connection), urlSession: makeURLSession()),
            adapter: ProjectDomainAdapter(),
            activeConnectionManager: manager
        )

        let project = try await repository.getCurrentProject(directory: "/repo")

        #expect(project.id == "proj_1")
        #expect(project.worktree == "/repo")
        let url = try #require(ProtocolRoutingURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/api/project/current"))
        #expect(queryValue(url, "location[directory]") == "/repo")
        #expect(queryValue(url, "directory") == nil)
    }

    @Test func v1ProjectCurrentKeepsDirectoryQuery() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [.json(#"{"id":"proj_1","worktree":"/repo"}"#)])
        let manager = makeManager(serverProtocol: .v1)
        let repository = DefaultProjectRepository(
            api: ProjectsAPIClient(fixedConnection: try #require(manager.connection), urlSession: makeURLSession()),
            adapter: ProjectDomainAdapter(),
            activeConnectionManager: manager
        )

        let project = try await repository.getCurrentProject(directory: "/repo")

        #expect(project.worktree == "/repo")
        let url = try #require(ProtocolRoutingURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/project/current"))
        #expect(queryValue(url, "directory") == "/repo")
        #expect(queryValue(url, "location[directory]") == nil)
    }

    @Test func v2ListSessionsFollowsCursorPages() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [
            .json(#"""
            {"data":[{"id":"ses_a","title":"A","projectID":"p","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"directory":"/repo"}}],"cursor":{"previous":null,"next":"page-2"}}
            """#),
            .json(#"""
            {"data":[{"id":"ses_b","title":"B","projectID":"p","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":3,"updated":4},"location":{"directory":"/repo"}}],"cursor":{"previous":"page-2","next":null}}
            """#)
        ])
        let repository = makeSessionRepository(serverProtocol: .v2)

        let sessions = try await repository.listSessions(directory: "/repo")

        #expect(sessions.map(\.id) == ["ses_a", "ses_b"])
        #expect(ProtocolRoutingURLProtocol.requests.count == 2)
        #expect(queryValue(try #require(ProtocolRoutingURLProtocol.requests[0].url), "cursor") == nil)
        #expect(queryValue(try #require(ProtocolRoutingURLProtocol.requests[1].url), "cursor") == "page-2")
    }

    @Test func unknownProtocolProbesBeforeRoutingToV2() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [
            .json(#"""
            {"data":[{"id":"ses_2","title":"Two","projectID":"p","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},"time":{"created":1,"updated":2},"location":{"directory":"/repo"}}],"cursor":{"next":null}}
            """#)
        ])
        let manager = makeManager(serverProtocol: nil)
        let repository = DefaultSessionRepository(
            api: SessionsAPIClient(connectionProvider: { manager.connection }, urlSession: makeURLSession()),
            adapter: SessionDomainAdapter(),
            activeConnectionManager: manager,
            projectRepository: ProbeToV2ProjectRepository(manager: manager)
        )

        let sessions = try await repository.listSessions(directory: "/repo")

        #expect(sessions.map(\.id) == ["ses_2"])
        #expect(manager.serverProtocol == .v2)
        let url = try #require(ProtocolRoutingURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/api/session"))
    }

    @Test func v2ListPermissionsUnwrapsEnvelopeAndMapsAction() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [
            .json(#"""
            {"data":[{
              "id":"per_1",
              "sessionID":"ses_1",
              "action":"edit",
              "resources":["src/a.kt"],
              "save":["src/*"],
              "source":{"type":"tool","messageID":"msg_1","callID":"call_1"}
            }]}
            """#)
        ])
        let repository = makeSessionRepository(serverProtocol: .v2)

        let permissions = try await repository.listPendingPermissions(directory: "/repo")

        let permission = try #require(permissions.first)
        #expect(permission.id == "per_1")
        #expect(permission.permission == "edit")
        #expect(permission.patterns == ["src/a.kt"])
        #expect(permission.toolMessageID == "msg_1")
        let url = try #require(ProtocolRoutingURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/api/permission/request"))
        #expect(queryValue(url, "location[directory]") == "/repo")
    }

    @Test func v2RespondToPermissionPostsReplyBody() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [.noContent])
        let repository = makeSessionRepository(serverProtocol: .v2)

        let ok = try await repository.respondToPermission(
            sessionID: "ses_1",
            permissionID: "per_1",
            decision: .allowOnce,
            directory: "/repo"
        )

        #expect(ok)
        let request = try #require(ProtocolRoutingURLProtocol.requests.first)
        #expect(request.httpMethod == "POST")
        #expect(request.url?.path.hasSuffix("/api/session/ses_1/permission/per_1/reply") == true)
        #expect(ProtocolRoutingURLProtocol.bodies.first??.contains(#""reply":"once""#) == true)
    }

    @Test func v2ReplyToQuestionUsesSessionScopedPath() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [.noContent])
        let repository = makeSessionRepository(serverProtocol: .v2)

        let ok = try await repository.replyToQuestion(
            questionID: "que_1",
            answers: [["Yes"]],
            directory: "/repo",
            sessionID: "ses_1"
        )

        #expect(ok)
        let request = try #require(ProtocolRoutingURLProtocol.requests.first)
        #expect(request.url?.path.hasSuffix("/api/session/ses_1/question/que_1/reply") == true)
    }

    @Test func v2AbortSessionPostsInterrupt() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [.noContent])
        let repository = makeSessionRepository(serverProtocol: .v2)

        let ok = try await repository.abortSession(id: "ses_1", directory: "/repo")

        #expect(ok)
        let request = try #require(ProtocolRoutingURLProtocol.requests.first)
        #expect(request.httpMethod == "POST")
        #expect(request.url?.path.hasSuffix("/api/session/ses_1/interrupt") == true)
    }

    @Test func v2SummarizeSessionPostsCompactWithSteerDelivery() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [.noContent])
        let repository = makeSessionRepository(serverProtocol: .v2)

        let ok = try await repository.summarizeSession(id: "ses_1", providerID: "openai", modelID: "gpt-4.1")

        #expect(ok)
        let request = try #require(ProtocolRoutingURLProtocol.requests.first)
        #expect(request.httpMethod == "POST")
        #expect(request.url?.path.hasSuffix("/api/session/ses_1/compact") == true)
        let body = try #require(ProtocolRoutingURLProtocol.bodies.first.flatMap { $0 })
        #expect(body.contains("\"delivery\":\"steer\""))
    }

    @Test func v2RevertSessionStagesWithMessageAndFiles() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [.noContent])
        let repository = makeSessionRepository(serverProtocol: .v2)

        let ok = try await repository.revertSession(id: "ses_1", messageID: "msg_9", partID: "part_2")

        #expect(ok)
        let request = try #require(ProtocolRoutingURLProtocol.requests.first)
        #expect(request.url?.path.hasSuffix("/api/session/ses_1/revert/stage") == true)
        let body = try #require(ProtocolRoutingURLProtocol.bodies.first.flatMap { $0 })
        #expect(body.contains("\"messageID\":\"msg_9\""))
        #expect(body.contains("\"partID\":\"part_2\""))
        #expect(body.contains("\"files\":true"))
    }

    @Test func v2UnrevertSessionClearsStagedRevert() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [.noContent])
        let repository = makeSessionRepository(serverProtocol: .v2)

        let ok = try await repository.unrevertSession(id: "ses_1")

        #expect(ok)
        let request = try #require(ProtocolRoutingURLProtocol.requests.first)
        #expect(request.httpMethod == "POST")
        #expect(request.url?.path.hasSuffix("/api/session/ses_1/revert/clear") == true)
    }

    @Test func v2ListSessionTodosSkipsRestAndReturnsEmpty() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [.json("[]")])
        let repository = makeSessionRepository(serverProtocol: .v2)

        let todos = try await repository.listSessionTodos(id: "ses_1", directory: "/repo")

        #expect(todos.isEmpty)
        #expect(ProtocolRoutingURLProtocol.requests.isEmpty)
    }

    @Test func v2GetVcsInfoUnwrapsBranchEnvelope() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [
            .json(#"""
            {
              "location":{"directory":"/repo","project":{"id":"p","directory":"/repo","canonical":"/repo"}},
              "data":{"branch":{"current":"feature/review","default":"main"}}
            }
            """#)
        ])
        let repository = makeSessionRepository(serverProtocol: .v2)

        let info = try await repository.getVcsInfo(directory: "/repo")

        #expect(info.currentBranch == "feature/review")
        #expect(info.defaultBranch == "main")
        let url = try #require(ProtocolRoutingURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/api/vcs"))
        #expect(queryValue(url, "location[directory]") == "/repo")
    }

    @Test func v2GetVcsDiffMapsGitModeToWorking() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [
            .json(#"""
            {
              "location":{"directory":"/repo","project":{"id":"p","directory":"/repo","canonical":"/repo"}},
              "data":[{"file":"App.kt","patch":"@@","additions":1,"deletions":0,"status":"modified"}]
            }
            """#)
        ])
        let repository = makeSessionRepository(serverProtocol: .v2)

        let files = try await repository.getVcsDiff(mode: .workingTree, directory: "/repo", context: 12)

        #expect(files.map(\.path) == ["App.kt"])
        let url = try #require(ProtocolRoutingURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/api/vcs/diff"))
        #expect(queryValue(url, "mode") == "working")
        #expect(queryValue(url, "context") == "12")
        #expect(queryValue(url, "location[directory]") == "/repo")
    }

    @Test func v2ListCommandsUnwrapsLocationEnvelope() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [
            .json(#"{"data":[{"name":"init","template":"do init","description":"Setup"}]}"#)
        ])
        let manager = makeManager(serverProtocol: .v2)
        let repository = DefaultCommandRepository(
            api: CommandsAPIClient(connectionProvider: { manager.connection }, urlSession: makeURLSession()),
            adapter: CommandDomainAdapter(),
            activeConnectionManager: manager
        )

        let commands = try await repository.listCommands(directory: "/repo")

        #expect(commands.map(\.name) == ["init"])
        #expect(commands.first?.description == "Setup")
        let url = try #require(ProtocolRoutingURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/api/command"))
        #expect(queryValue(url, "location[directory]") == "/repo")
    }

    @Test func v2ListAgentsMapsIdAndFiltersPrimary() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [
            .json(#"""
            {"data":[
              {"id":"build","mode":"primary","hidden":false,"description":"Primary"},
              {"id":"explore","mode":"subagent","hidden":false,"description":"Explorer"}
            ]}
            """#)
        ])
        let manager = makeManager(serverProtocol: .v2)
        let repository = DefaultAgentRepository(
            api: AgentsAPIClient(connectionProvider: { manager.connection }, urlSession: makeURLSession()),
            adapter: AgentDomainAdapter(),
            activeConnectionManager: manager
        )

        let agents = try await repository.listMentionableAgents(directory: "/repo")

        #expect(agents.map(\.name) == ["explore"])
        #expect(agents.first?.description == "Explorer")
        let url = try #require(ProtocolRoutingURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/api/agent"))
        #expect(queryValue(url, "location[directory]") == "/repo")
    }

    @Test func v2ListModelOptionsJoinsProviderAndModelLists() async throws {
        ProtocolRoutingURLProtocol.reset(responses: [
            .json(#"{"data":[{"id":"anthropic","name":"Anthropic","disabled":false}]}"#),
            .json(#"""
            {"data":[{
              "id":"claude-sonnet",
              "providerID":"anthropic",
              "name":"Claude Sonnet",
              "enabled":true,
              "status":"active",
              "variants":[{"id":"high"},{"id":"low"}],
              "capabilities":{"tools":true,"input":["text","image"]},
              "limit":{"context":200000,"output":8192}
            }]}
            """#)
        ])
        let manager = makeManager(serverProtocol: .v2)
        let repository = DefaultModelRepository(
            api: ModelsAPIClient(connectionProvider: { manager.connection }, urlSession: makeURLSession()),
            adapter: ModelDomainAdapter(),
            activeConnectionManager: manager
        )

        let catalog = try await repository.listModelOptions(directory: "/repo")

        let option = try #require(catalog.options.first)
        #expect(catalog.options.count == 1)
        #expect(option.providerID == "anthropic")
        #expect(option.providerName == "Anthropic")
        #expect(option.modelID == "claude-sonnet")
        #expect(option.variants == ["high", "low"])
        #expect(option.supportsImageInput)
        #expect(!option.supportsPdfInput)
        #expect(catalog.serverDefault == nil)
        #expect(ProtocolRoutingURLProtocol.requests.count == 2)
        #expect(ProtocolRoutingURLProtocol.requests[0].url?.path.hasSuffix("/api/provider") == true)
        #expect(ProtocolRoutingURLProtocol.requests[1].url?.path.hasSuffix("/api/model") == true)
        #expect(queryValue(try #require(ProtocolRoutingURLProtocol.requests[0].url), "location[directory]") == "/repo")
    }

    // MARK: - Helpers

    private func makeManager(serverProtocol: ServerProtocol?) -> ActiveConnectionManager {
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
        configuration.protocolClasses = [ProtocolRoutingURLProtocol.self]
        return URLSession(configuration: configuration)
    }

    private func makeSessionRepository(serverProtocol: ServerProtocol) -> DefaultSessionRepository {
        let manager = makeManager(serverProtocol: serverProtocol)
        let urlSession = makeURLSession()
        return DefaultSessionRepository(
            api: SessionsAPIClient(connectionProvider: { manager.connection }, urlSession: urlSession),
            adapter: SessionDomainAdapter(),
            activeConnectionManager: manager
        )
    }

    private func jsonObject(_ body: String) -> [String: Any]? {
        try? JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any]
    }

    private func queryValue(_ url: URL, _ name: String) -> String? {
        URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?
            .first { $0.name == name }?
            .value
    }

    private func sessionEnvelope(
        id: String,
        title: String,
        parentID: String? = nil,
        archived: Int64? = nil
    ) -> String {
        let parentField = parentID.map { ",\"parentID\":\"\($0)\"" } ?? ""
        let archivedField = archived.map { ",\"archived\":\($0)" } ?? ""
        return "{\"data\":{"
            + "\"id\":\"\(id)\",\"title\":\"\(title)\",\"projectID\":\"p\",\"cost\":0,"
            + "\"tokens\":{\"input\":0,\"output\":0,\"reasoning\":0,\"cache\":{\"read\":0,\"write\":0}},"
            + "\"time\":{\"created\":1,\"updated\":2\(archivedField)},"
            + "\"location\":{\"directory\":\"/repo\"}\(parentField)}}"
    }
}

private struct StubResponse {
    let statusCode: Int
    let body: String

    static func json(_ body: String) -> StubResponse {
        StubResponse(statusCode: 200, body: body)
    }

    static var noContent: StubResponse {
        StubResponse(statusCode: 204, body: "")
    }
}

private final class ProtocolRoutingURLProtocol: URLProtocol {
    static var requests: [URLRequest] = []
    static var bodies: [String?] = []
    static var responses: [StubResponse] = []

    static func reset(responses: [StubResponse]) {
        requests = []
        bodies = []
        self.responses = responses
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.requests.append(request)
        Self.bodies.append(request.readBodyText())
        let stub = Self.responses.isEmpty ? StubResponse.json("{}") : Self.responses.removeFirst()
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

private struct ProbeToV2ProjectRepository: ProjectRepository {
    let manager: ActiveConnectionManager

    func probeServer() async throws {
        manager.updateProtocol(.v2)
    }

    func listProjects() async throws -> [Project] { [] }

    func getCurrentProject(directory: String?) async throws -> Project {
        Project(id: "unused", worktree: directory ?? "", displayName: "unused")
    }
}
