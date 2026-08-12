import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct MessageProtocolRepositoryTests {
    @Test func v1ListMessagesUsesFlatWithPartsArray() async throws {
        MessageProtocolURLProtocol.reset(responses: [
            .json(#"[{"info":{"id":"msg_1","role":"user"},"parts":[{"type":"text","text":"Hi"}]}]"#)
        ])
        let router = makeRouter(serverProtocol: .v1)

        let messages = try await router.listMessages(sessionID: "ses_1", directory: "/repo")

        #expect(messages.map(\.id) == ["msg_1"])
        #expect(messages.first?.text == "Hi")
        let url = try #require(MessageProtocolURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/session/ses_1/message"))
        #expect(queryValue(url, "directory") == "/repo")
    }

    @Test func v2ListMessagesUnwrapsEnvelopeAndProjectsDomainMessages() async throws {
        MessageProtocolURLProtocol.reset(responses: [
            .json(#"""
            {
              "data": [
                {"id":"msg_user","type":"user","text":"Hello","time":{"created":1}},
                {
                  "id":"msg_asst",
                  "type":"assistant",
                  "agent":"build",
                  "model":{"id":"gpt-5","providerID":"openai"},
                  "content":[{"type":"text","id":"t1","text":"Hi"}],
                  "time":{"created":2}
                }
              ],
              "cursor": {"previous": null, "next": null}
            }
            """#)
        ])
        let router = makeRouter(serverProtocol: .v2)

        let messages = try await router.listMessages(sessionID: "ses_2", directory: "/repo")

        #expect(messages.map(\.id) == ["msg_user", "msg_asst"])
        #expect(messages[0].text == "Hello")
        #expect(messages[1].text == "Hi")
        let url = try #require(MessageProtocolURLProtocol.requests.first?.url)
        #expect(url.path.contains("/api/session/ses_2/message"))
        #expect(queryValue(url, "directory") == "/repo")
        #expect(queryValue(url, "order") == "asc")
    }

    @Test func v2ListMessagesFollowsCursorPages() async throws {
        MessageProtocolURLProtocol.reset(responses: [
            .json(#"""
            {"data":[{"id":"msg_a","type":"user","text":"A","time":{"created":1}}],"cursor":{"next":"page-2"}}
            """#),
            .json(#"""
            {"data":[{"id":"msg_b","type":"user","text":"B","time":{"created":2}}],"cursor":{"next":null}}
            """#)
        ])
        let router = makeRouter(serverProtocol: .v2)

        let messages = try await router.listMessages(sessionID: "ses_2", directory: "/repo")

        #expect(messages.map(\.id) == ["msg_a", "msg_b"])
        #expect(MessageProtocolURLProtocol.requests.count == 2)
        #expect(queryValue(try #require(MessageProtocolURLProtocol.requests[1].url), "cursor") == "page-2")
    }

    @Test func unknownProtocolProbesBeforeRoutingToV2() async throws {
        MessageProtocolURLProtocol.reset(responses: [
            .json(#"""
            {"data":[{"id":"msg_v2","type":"user","text":"V2","time":{"created":1}}],"cursor":{"next":null}}
            """#)
        ])
        let manager = makeManager(serverProtocol: nil)
        let router = MessageProtocolRouter(
            api: MessagesAPIClient(connectionProvider: { manager.connection }, urlSession: makeURLSession()),
            adapter: MessageDomainAdapter(),
            activeConnectionManager: manager,
            projectRepository: MessageProbeToV2ProjectRepository(manager: manager)
        )

        let messages = try await router.listMessages(sessionID: "ses_2", directory: "/repo")

        #expect(messages.map(\.id) == ["msg_v2"])
        let url = try #require(MessageProtocolURLProtocol.requests.first?.url)
        #expect(url.path.contains("/api/session/ses_2/message"))
    }

    @Test func v1SendPromptUsesPromptAsyncPartsBody() async throws {
        MessageProtocolURLProtocol.reset(responses: [
            MessageStubResponse(statusCode: 204, body: "")
        ])
        let router = makeRouter(serverProtocol: .v1)

        try await router.sendPromptAsync(
            sessionID: "ses_1",
            options: PromptSendOptions(text: "Hello"),
            directory: "/repo"
        )

        let url = try #require(MessageProtocolURLProtocol.requests.first?.url)
        #expect(url.path.hasSuffix("/session/ses_1/prompt_async"))
    }

    @Test func v2SendPromptSwitchesAgentModelThenPostsPrompt() async throws {
        MessageProtocolURLProtocol.reset(responses: [
            MessageStubResponse(statusCode: 204, body: ""),
            MessageStubResponse(statusCode: 204, body: ""),
            MessageStubResponse(statusCode: 200, body: #"{"data":{"id":"msg_1","sessionID":"ses_2","prompt":{"text":"Hello"},"delivery":"steer","admittedSeq":1,"timeCreated":1}}"#)
        ])
        let router = makeRouter(serverProtocol: .v2)
        let attachment = Attachment(
            id: "a1",
            filename: "note.txt",
            mime: "text/plain",
            kind: .text,
            dataURL: "data:text/plain;base64,SGk="
        )

        try await router.sendPromptAsync(
            sessionID: "ses_2",
            options: PromptSendOptions(
                text: "Hello",
                attachments: [attachment],
                model: PromptModelSelection(providerID: "openai", modelID: "gpt-5"),
                variant: "high",
                mode: "build"
            ),
            directory: "/repo"
        )

        #expect(MessageProtocolURLProtocol.requests.count == 3)
        #expect(MessageProtocolURLProtocol.requests[0].url?.path.hasSuffix("/api/session/ses_2/agent") == true)
        #expect(MessageProtocolURLProtocol.requests[1].url?.path.hasSuffix("/api/session/ses_2/model") == true)
        #expect(MessageProtocolURLProtocol.requests[2].url?.path.hasSuffix("/api/session/ses_2/prompt") == true)
    }

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
        configuration.protocolClasses = [MessageProtocolURLProtocol.self]
        return URLSession(configuration: configuration)
    }

    private func makeRouter(serverProtocol: ServerProtocol) -> MessageProtocolRouter {
        let manager = makeManager(serverProtocol: serverProtocol)
        return MessageProtocolRouter(
            api: MessagesAPIClient(connectionProvider: { manager.connection }, urlSession: makeURLSession()),
            adapter: MessageDomainAdapter(),
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

private struct MessageStubResponse {
    let statusCode: Int
    let body: String

    static func json(_ body: String) -> MessageStubResponse {
        MessageStubResponse(statusCode: 200, body: body)
    }
}

private final class MessageProtocolURLProtocol: URLProtocol {
    static var requests: [URLRequest] = []
    static var responses: [MessageStubResponse] = []

    static func reset(responses: [MessageStubResponse]) {
        requests = []
        self.responses = responses
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.requests.append(request)
        let stub = Self.responses.isEmpty ? MessageStubResponse.json("{}") : Self.responses.removeFirst()
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

private struct MessageProbeToV2ProjectRepository: ProjectRepository {
    let manager: ActiveConnectionManager

    func probeServer() async throws {
        manager.updateProtocol(.v2)
    }

    func listProjects() async throws -> [Project] { [] }

    func getCurrentProject(directory: String?) async throws -> Project {
        Project(id: "unused", worktree: directory ?? "", displayName: "unused")
    }
}
