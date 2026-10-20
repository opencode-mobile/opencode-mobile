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
        #expect(queryValue(url, "limit") == nil)
        #expect(queryValue(url, "before") == nil)
    }

    @Test func remoteDefaultPageRequestDoesNotDumpFullHistory() async {
        let remote = UnpagedMessageRemote()
        await #expect(throws: MessageHistoryPagingError.pageRequestNotImplemented) {
            _ = try await remote.listMessagePage(
                sessionID: "ses_1",
                directory: "/repo",
                query: .newest(limit: 20)
            )
        }
    }

    @Test func v1TailUsesLimitWithoutBefore() async throws {
        MessageProtocolURLProtocol.reset(responses: [
            MessageStubResponse(
                statusCode: 200,
                body: #"[{"info":{"id":"msg_20","role":"user"},"parts":[{"type":"text","text":"Tail"}]}]"#,
                headers: ["X-Next-Cursor": "cursor-19"]
            )
        ])
        let router = makeRouter(serverProtocol: .v1)

        let page = try await router.listMessagePage(
            sessionID: "ses_1",
            directory: "/repo",
            query: .newest(limit: 20)
        )

        #expect(page.messages.map(\.id) == ["msg_20"])
        #expect(page.olderCursor == "cursor-19")
        #expect(page.hasOlder)
        let url = try #require(MessageProtocolURLProtocol.requests.first?.url)
        #expect(queryValue(url, "limit") == "20")
        #expect(queryValue(url, "before") == nil)
    }

    @Test func v1OlderUsesLimitAndBeforeFromNextCursor() async throws {
        MessageProtocolURLProtocol.reset(responses: [
            MessageStubResponse(
                statusCode: 200,
                body: #"[{"info":{"id":"msg_1","role":"user"},"parts":[{"type":"text","text":"Old"}]}]"#,
                headers: [
                    "Link": "</session/ses_1/message?limit=200&before=prev>; rel=\"next\""
                ]
            )
        ])
        let router = makeRouter(serverProtocol: .v1)

        let page = try await router.listMessagePage(
            sessionID: "ses_1",
            directory: "/repo",
            query: .older(cursor: "cursor-19", limit: 200)
        )

        #expect(page.messages.map(\.id) == ["msg_1"])
        #expect(page.olderCursor == "prev")
        #expect(page.hasOlder)
        let url = try #require(MessageProtocolURLProtocol.requests.first?.url)
        #expect(queryValue(url, "limit") == "200")
        #expect(queryValue(url, "before") == "cursor-19")
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
            """#),
            .json(#"{"data":[]}"#)
        ])
        let router = makeRouter(serverProtocol: .v2)

        let messages = try await router.listMessages(sessionID: "ses_2", directory: "/repo")

        #expect(messages.map(\.id) == ["msg_user", "msg_asst"])
        #expect(messages[0].text == "Hello")
        #expect(messages[1].text == "Hi")
        let url = try #require(MessageProtocolURLProtocol.requests.first?.url)
        #expect(url.path.contains("/api/session/ses_2/message"))
        #expect(queryValue(url, "directory") == nil)
        #expect(queryValue(url, "order") == "asc")
    }

    @Test func v2ListMessagesFollowsCursorPages() async throws {
        MessageProtocolURLProtocol.reset(responses: [
            .json(#"""
            {"data":[{"id":"msg_a","type":"user","text":"A","time":{"created":1}}],"cursor":{"next":"page-2"}}
            """#),
            .json(#"""
            {"data":[{"id":"msg_b","type":"user","text":"B","time":{"created":2}}],"cursor":{"next":null}}
            """#),
            .json(#"{"data":[]}"#)
        ])
        let router = makeRouter(serverProtocol: .v2)

        let messages = try await router.listMessages(sessionID: "ses_2", directory: "/repo")

        #expect(messages.map(\.id) == ["msg_a", "msg_b"])
        #expect(MessageProtocolURLProtocol.requests.count == 3)
        let firstURL = try #require(MessageProtocolURLProtocol.requests[0].url)
        let secondURL = try #require(MessageProtocolURLProtocol.requests[1].url)
        let inboxURL = try #require(MessageProtocolURLProtocol.requests[2].url)
        #expect(queryValue(firstURL, "order") == "asc")
        #expect(queryValue(firstURL, "cursor") == nil)
        #expect(queryValue(secondURL, "cursor") == "page-2")
        #expect(queryValue(secondURL, "order") == nil)
        #expect(inboxURL.path.hasSuffix("/inbox"))
    }

    @Test func v2ListMessagesMergesInboxQueueDelivery() async throws {
        MessageProtocolURLProtocol.reset(responses: [
            .json(#"""
            {
              "data": [
                {"id":"msg_a","type":"user","text":"A","time":{"created":1}},
                {"id":"msg_b","type":"user","text":"B","time":{"created":2}}
              ],
              "cursor": {"next": null}
            }
            """#),
            .json(#"""
            {
              "data": [
                {
                  "id": "msg_b",
                  "type": "user",
                  "payload": {"text": "B"},
                  "delivery": "queue",
                  "timeCreated": 2
                }
              ]
            }
            """#)
        ])
        let router = makeRouter(serverProtocol: .v2)

        let messages = try await router.listMessages(sessionID: "ses_2", directory: "/repo")

        let msgB = try #require(messages.first { $0.id == "msg_b" })
        #expect(msgB.isWaitingForPickup)
        #expect(msgB.followUpDelivery == .queue)
        #expect(MessageProtocolURLProtocol.requests.last?.url?.path.hasSuffix("/inbox") == true)
    }

    @Test func v2ListMessagesKeepsMessagesWhenInboxFails() async throws {
        MessageProtocolURLProtocol.reset(responses: [
            .json(#"""
            {"data":[{"id":"msg_a","type":"user","text":"A","time":{"created":1}}],"cursor":{"next":null}}
            """#),
            MessageStubResponse(statusCode: 500, body: "boom")
        ])
        let router = makeRouter(serverProtocol: .v2)

        let messages = try await router.listMessages(sessionID: "ses_2", directory: "/repo")

        #expect(messages.map(\.id) == ["msg_a"])
        #expect(messages[0].isWaitingForPickup == false)
        #expect(messages[0].followUpDelivery == nil)
        #expect(MessageProtocolURLProtocol.requests.count == 2)
        #expect(MessageProtocolURLProtocol.requests.last?.url?.path.hasSuffix("/inbox") == true)
    }

    @Test func v2TailUsesLimitAndDescThenReturnsChronological() async throws {
        MessageProtocolURLProtocol.reset(responses: [
            .json(#"""
            {
              "data": [
                {"id":"msg_new","type":"user","text":"New","time":{"created":2}},
                {"id":"msg_old","type":"user","text":"Old","time":{"created":1}}
              ],
              "cursor": {"next":"older-cursor"}
            }
            """#),
            .json(#"{"data":[]}"#)
        ])
        let router = makeRouter(serverProtocol: .v2)

        let page = try await router.listMessagePage(
            sessionID: "ses_2",
            directory: "/repo",
            query: .newest(limit: 20)
        )

        #expect(page.messages.map(\.id) == ["msg_old", "msg_new"])
        #expect(page.olderCursor == "older-cursor")
        #expect(page.hasOlder)
        let url = try #require(MessageProtocolURLProtocol.requests.first?.url)
        #expect(queryValue(url, "limit") == "20")
        #expect(queryValue(url, "order") == "desc")
        #expect(queryValue(url, "cursor") == nil)
    }

    @Test func v2OlderSendsCursorWithoutOrder() async throws {
        MessageProtocolURLProtocol.reset(responses: [
            .json(#"""
            {
              "data": [
                {"id":"msg_b","type":"user","text":"B","time":{"created":2}},
                {"id":"msg_a","type":"user","text":"A","time":{"created":1}}
              ],
              "cursor": {"next":null}
            }
            """#)
        ])
        let router = makeRouter(serverProtocol: .v2)

        let page = try await router.listMessagePage(
            sessionID: "ses_2",
            directory: "/repo",
            query: .older(cursor: "older-cursor", limit: 200)
        )

        #expect(page.messages.map(\.id) == ["msg_a", "msg_b"])
        #expect(page.hasOlder == false)
        let url = try #require(MessageProtocolURLProtocol.requests.first?.url)
        #expect(queryValue(url, "cursor") == "older-cursor")
        #expect(queryValue(url, "order") == nil)
        #expect(queryValue(url, "limit") == "200")
    }

    @Test func v2DescPageReversesOnceIgnoringTimestamps() async throws {
        MessageProtocolURLProtocol.reset(responses: [
            .json(#"""
            {
              "data": [
                {"id":"msg_new","type":"user","text":"New","time":{"created":1}},
                {"id":"msg_old","type":"user","text":"Old","time":{"created":100}}
              ],
              "cursor": {"next":"older-cursor"}
            }
            """#),
            .json(#"{"data":[]}"#)
        ])
        let router = makeRouter(serverProtocol: .v2)

        let page = try await router.listMessagePage(
            sessionID: "ses_2",
            directory: "/repo",
            query: .newest(limit: 20)
        )

        #expect(page.messages.map(\.id) == ["msg_old", "msg_new"])
    }

    @Test func v2CursorDescPageReversesOnceIgnoringTimestamps() async throws {
        MessageProtocolURLProtocol.reset(responses: [
            .json(#"""
            {
              "data": [
                {"id":"msg_b","type":"user","text":"B","time":{"created":1}},
                {"id":"msg_a","type":"user","text":"A","time":{"created":100}}
              ],
              "cursor": {"next":null}
            }
            """#)
        ])
        let router = makeRouter(serverProtocol: .v2)

        let page = try await router.listMessagePage(
            sessionID: "ses_2",
            directory: "/repo",
            query: .older(cursor: "older-cursor", limit: 200)
        )

        #expect(page.messages.map(\.id) == ["msg_a", "msg_b"])
    }

    @Test func unknownProtocolProbesBeforeRoutingToV2() async throws {
        MessageProtocolURLProtocol.reset(responses: [
            .json(#"""
            {"data":[{"id":"msg_v2","type":"user","text":"V2","time":{"created":1}}],"cursor":{"next":null}}
            """#),
            .json(#"{"data":[]}"#)
        ])
        let manager = makeManager(serverProtocol: nil, probing: StubV2Probe())
        let router = ProtocolSelectedMessageRemote(
            api: MessagesAPIClient(connectionProvider: { manager.connection }, urlSession: makeURLSession()),
            adapter: MessageDomainAdapter(),
            activeConnectionManager: manager
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

    @Test func v1RunShellPostsAgentCommandAndModelToSessionShell() async throws {
        MessageProtocolURLProtocol.reset(responses: [.json(#"{"id":"msg_a","role":"assistant"}"#)])
        let router = makeRouter(serverProtocol: .v1)

        try await router.runShellCommand(
            sessionID: "ses_1",
            options: ShellCommandOptions(
                command: "git status",
                agent: "build",
                model: PromptModelSelection(providerID: "openai", modelID: "gpt-5")
            ),
            directory: "/repo"
        )

        let request = try #require(MessageProtocolURLProtocol.requests.first)
        #expect(MessageProtocolURLProtocol.requests.count == 1)
        #expect(request.httpMethod == "POST")
        #expect(request.url?.path.hasSuffix("/session/ses_1/shell") == true)
        #expect(queryValue(try #require(request.url), "directory") == "/repo")
        let body = try #require(MessageProtocolURLProtocol.bodies.first ?? nil)
        let json = try #require(try JSONSerialization.jsonObject(with: Data(body.utf8)) as? NSDictionary)
        #expect(json == [
            "agent": "build",
            "command": "git status",
            "model": ["providerID": "openai", "modelID": "gpt-5"],
        ] as NSDictionary)
    }

    @Test func v1RunShellWithoutModelOmitsIt() async throws {
        MessageProtocolURLProtocol.reset(responses: [.json("{}")])
        let router = makeRouter(serverProtocol: .v1)

        try await router.runShellCommand(
            sessionID: "ses_1",
            options: ShellCommandOptions(command: "ls", agent: "plan", model: nil),
            directory: nil
        )

        let body = try #require(MessageProtocolURLProtocol.bodies.first ?? nil)
        let json = try #require(try JSONSerialization.jsonObject(with: Data(body.utf8)) as? NSDictionary)
        #expect(json == ["agent": "plan", "command": "ls"] as NSDictionary)
    }

    @Test func v2RunShellPostsOnlyTheCommandWithoutSwitchingAgentOrModel() async throws {
        MessageProtocolURLProtocol.reset(responses: [MessageStubResponse(statusCode: 204, body: "")])
        let router = makeRouter(serverProtocol: .v2)

        try await router.runShellCommand(
            sessionID: "ses_2",
            options: ShellCommandOptions(
                command: "echo one\necho two",
                agent: "build",
                model: PromptModelSelection(providerID: "openai", modelID: "gpt-5")
            ),
            directory: "/repo"
        )

        let request = try #require(MessageProtocolURLProtocol.requests.first)
        #expect(MessageProtocolURLProtocol.requests.count == 1)
        #expect(request.httpMethod == "POST")
        #expect(request.url?.path.hasSuffix("/api/session/ses_2/shell") == true)
        #expect(queryValue(try #require(request.url), "directory") == nil)
        let body = try #require(MessageProtocolURLProtocol.bodies.first ?? nil)
        let json = try #require(try JSONSerialization.jsonObject(with: Data(body.utf8)) as? NSDictionary)
        #expect(json == ["command": "echo one\necho two"] as NSDictionary)
    }

    @Test func v1SendPrompt_mentionOnlyIncludesFileSource() async throws {
        MessageProtocolURLProtocol.reset(responses: [
            MessageStubResponse(statusCode: 204, body: "")
        ])
        let router = makeRouter(serverProtocol: .v1)

        try await router.sendPromptAsync(
            sessionID: "ses_1",
            options: PromptSendOptions(
                text: "see @src/App.kt",
                fileMentions: [FileMention(id: "f1", path: "src/App.kt", start: 4, end: 15)],
                worktree: "/repo"
            ),
            directory: "/repo"
        )

        let body = try #require(MessageProtocolURLProtocol.bodies.first ?? nil)
        #expect(body.contains(#""url":"file:///repo/src/App.kt""#))
        #expect(body.contains(#""path":"src/App.kt""#))
        #expect(body.contains(#""value":"@src/App.kt""#))
        #expect(!body.contains("data:"))
    }

    @Test func v2SendPrompt_mentionOnlyIncludesFileUri() async throws {
        MessageProtocolURLProtocol.reset(responses: [
            MessageStubResponse(statusCode: 200, body: #"{"data":{"id":"msg_1"}}"#)
        ])
        let router = makeRouter(serverProtocol: .v2)

        try await router.sendPromptAsync(
            sessionID: "ses_2",
            options: PromptSendOptions(
                text: "see @src/App.kt",
                fileMentions: [FileMention(id: "f1", path: "src/App.kt", start: 4, end: 15)],
                worktree: "/repo"
            ),
            directory: "/repo"
        )

        let body = try #require(MessageProtocolURLProtocol.bodies.first ?? nil)
        #expect(body.contains(#""uri":"file:///repo/src/App.kt""#))
        #expect(body.contains(#""mention""#))
        #expect(body.contains(#""text":"@src/App.kt""#))
        #expect(!body.contains("data:"))
    }

    private func makeManager(serverProtocol: ServerProtocol?, probing: (any ConnectionProbing)? = nil) -> ActiveConnectionManager {
        let manager = ActiveConnectionManager(connectionProbing: probing)
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

    private func makeRouter(serverProtocol: ServerProtocol) -> ProtocolSelectedMessageRemote {
        let manager = makeManager(serverProtocol: serverProtocol)
        return ProtocolSelectedMessageRemote(
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
    let headers: [String: String]

    init(statusCode: Int, body: String, headers: [String: String] = [:]) {
        self.statusCode = statusCode
        self.body = body
        self.headers = headers
    }

    static func json(_ body: String) -> MessageStubResponse {
        MessageStubResponse(statusCode: 200, body: body)
    }
}

private final class MessageProtocolURLProtocol: URLProtocol {
    static var requests: [URLRequest] = []
    static var bodies: [String?] = []
    static var responses: [MessageStubResponse] = []

    static func reset(responses: [MessageStubResponse]) {
        requests = []
        bodies = []
        self.responses = responses
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.requests.append(request)
        Self.bodies.append(request.readBodyText())
        let stub = Self.responses.isEmpty ? MessageStubResponse.json("{}") : Self.responses.removeFirst()
        var headers = ["Content-Type": "application/json"]
        stub.headers.forEach { headers[$0.key] = $0.value }
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: stub.statusCode,
            httpVersion: nil,
            headerFields: headers
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

private struct StubV2Probe: ConnectionProbing {
    func probe(connection: ServerConnection) async throws -> ServerProtocol { .v2 }
}

private struct UnpagedMessageRemote: MessageRemote {
    func listMessages(sessionID: String, directory: String?) async throws -> [Message] {
        [Message(id: "full", role: .user, text: "dump", model: nil, mode: nil, sentAtEpochMs: nil)]
    }

    func sendPromptAsync(sessionID: String, options: PromptSendOptions, directory: String?) async throws {}

    func runShellCommand(sessionID: String, options: ShellCommandOptions, directory: String?) async throws {}
}
