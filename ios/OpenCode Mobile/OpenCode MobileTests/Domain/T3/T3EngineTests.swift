import Foundation
import Testing
@testable import OpenCode_Mobile

struct T3EngineTests {
    @Test func tokenExchangeBody_containsGrantAndTokenFields() {
        let body = T3Auth.tokenExchangeBody(pairingToken: "pair token")
        let pairs = formPairs(body)
        #expect(pairs["grant_type"] == T3Auth.grantType)
        #expect(pairs["subject_token"] == "pair token")
        #expect(pairs["subject_token_type"] == T3Auth.subjectTokenType)
        #expect(pairs["requested_token_type"] == T3Auth.requestedTokenType)
        #expect(pairs["client_label"] == T3Auth.clientLabel)
        #expect(pairs["client_device_type"] == T3Auth.clientDeviceType)
    }

    @Test func normalizeBaseURL_defaultsToHttpsWithoutScheme() {
        #expect(T3HttpClient.withDefaultScheme("t3.example:3000") == "https://t3.example:3000")
        #expect(T3HttpClient.withDefaultScheme("  t3.example:3000  ") == "https://t3.example:3000")
        #expect(T3HttpClient.withDefaultScheme("http://10.0.0.2:3000/") == "http://10.0.0.2:3000/")
        #expect(T3HttpClient.withDefaultScheme("") == "")
        #expect(T3HttpClient.normalizeBaseURL("t3.example:3000").absoluteString == "https://t3.example:3000/")
        #expect(T3HttpClient.normalizeBaseURL("http://10.0.0.2:3000").absoluteString == "http://10.0.0.2:3000/")
    }

    @Test func clientError_mapsUnauthorizedToAccessRevokedWithoutServerBody() {
        let revoked = T3ClientError.fromStatus(401, body: "{\"error\":\"invalid_token\"}")
        #expect(revoked == .accessRevoked(statusCode: 401, body: "{\"error\":\"invalid_token\"}"))
        #expect(revoked.errorDescription == T3ClientError.accessRevokedMessage)
        #expect(revoked.diagnosticBody == "{\"error\":\"invalid_token\"}")
        #expect(T3ClientError.fromStatus(403, body: nil) == .accessRevoked(statusCode: 403, body: nil))
        let server = T3ClientError.fromStatus(500, body: "<html>stack trace</html>")
        #expect(server == .http(statusCode: 500, message: "<html>stack trace</html>"))
        #expect(server.errorDescription == "T3 request failed (500).")
        #expect(server.diagnosticBody == "<html>stack trace</html>")
    }

    @Test func pairingLink_parsesDesktopAndHostedForms() {
        let desktop = T3PairingLink.parse("http://192.168.0.160:3773/pair#token=pair-abc")
        #expect(desktop == T3PairingLink(baseURL: "http://192.168.0.160:3773/", pairingToken: "pair-abc", label: ""))
        let query = T3PairingLink.parse("https://desk.tail1234.ts.net/pair?token=pair%20xyz")
        #expect(query?.baseURL == "https://desk.tail1234.ts.net/")
        #expect(query?.pairingToken == "pair xyz")
        let hosted = T3PairingLink.parse(
            "https://app.t3.codes/pair?host=https%3A%2F%2Fdesk.tail1234.ts.net%3A3773&label=Desk#token=pair-hosted"
        )
        #expect(hosted == T3PairingLink(baseURL: "https://desk.tail1234.ts.net:3773/", pairingToken: "pair-hosted", label: "Desk"))
    }

    @Test func pairingLink_rejectsNonPairingPayloads() {
        #expect(T3PairingLink.parse("{\"urls\":[\"http://192.168.0.160:4096\"],\"username\":\"opencode\"}") == nil)
        #expect(T3PairingLink.parse("http://192.168.0.160:3773/") == nil)
        #expect(T3PairingLink.parse("http://192.168.0.160:3773/pair") == nil)
        #expect(T3PairingLink.parse("ftp://192.168.0.160/pair#token=x") == nil)
        #expect(T3PairingLink.parse("") == nil)
    }

    @Test func shellJSON_returnsOnlyActiveSessions() throws {
        let json = """
        {
          "snapshotSequence": 4,
          "threads": [
            {
              "id": "t-active",
              "title": "Active thread",
              "projectId": "p1",
              "status": "idle",
              "providerInstanceId": "inst-1",
              "runtimeMode": "approval-required",
              "modelSelection": { "instanceId": "inst-1", "model": "gpt-5" },
              "updatedAt": "2026-01-02T00:00:00Z"
            },
            {
              "id": "t-settled",
              "title": "Settled thread",
              "projectId": "p1",
              "status": "idle",
              "settledAt": "2026-01-01T00:00:00Z"
            }
          ],
          "archivedThreads": [
            {
              "id": "t-archived",
              "title": "Archived thread",
              "projectId": "p1",
              "status": "idle",
              "archivedAt": "2026-01-01T00:00:00Z"
            }
          ]
        }
        """.data(using: .utf8)!
        let snapshot = try T3ShellMapper.snapshot(from: json)
        let sessions = T3ShellMapper.sessions(from: snapshot)
        #expect(sessions.map(\.id) == ["t-active"])
        #expect(sessions.first?.title == "Active thread")
        #expect(sessions.first?.providerInstanceID == "inst-1")
        #expect(sessions.first?.runtimeMode == "approval-required")
    }

    @Test func threadMessages_mapRoleAndText() throws {
        let json = """
        {
          "snapshotSequence": 8,
          "projection": {
            "messages": [
              { "id": "m1", "role": "user", "text": "Hello", "streaming": false, "createdAt": "2026-01-01T00:00:00Z" },
              { "id": "m2", "role": "assistant", "text": "Hi there", "streaming": true },
              { "id": "m3", "role": "system", "text": "Paired" }
            ]
          }
        }
        """.data(using: .utf8)!
        let messages = T3MessageMapper.messages(from: try JSONSerialization.jsonObject(with: json))
        #expect(messages.count == 3)
        #expect(messages[0].id == "m1")
        #expect(messages[0].role == .user)
        #expect(messages[0].text == "Hello")
        #expect(messages[1].id == "m2")
        #expect(messages[1].role == .assistant)
        #expect(messages[1].text == "Hi there")
        #expect(messages[2].role == .system)
        #expect(messages[2].text == "Paired")
    }

    @Test func t3AssistantText_preparesBoldAndInlineCode() async {
        let message = T3MessageMapper.domainMessage(
            from: T3ConversationMessage(
                id: "m-protocol",
                role: "assistant",
                text: "Server **0.0.40** at `http://example:3773`.",
                streaming: false,
                createdAtEpochMs: nil
            )
        )
        let display = await MessageContentPreparer.shared.prepareMessage(message)
        let inlines = display.renderedMarkdown?.blocks.flatMap { block -> [NativeMarkdownInline] in
            guard case .paragraph(let inlines) = block else { return [] }
            return inlines
        } ?? []
        #expect(inlines.contains { inline in
            if case .strong = inline { return true }
            return false
        })
        #expect(inlines.contains { inline in
            if case .code(let code) = inline { return code == "http://example:3773" }
            return false
        })
    }

    @Test func attachmentJSON_becomesImagePdfAndFileChips() throws {
        let json = """
        {
          "projection": {
            "messages": [
              {
                "id": "m-user",
                "role": "user",
                "text": "Connected.",
                "attachments": [
                  {"type": "image", "id": "att-image", "name": "image.png", "mimeType": "image/png", "sizeBytes": 229320},
                  {"type": "file", "id": "att-pdf", "name": "notes.pdf", "mimeType": "application/pdf", "sizeBytes": 1200},
                  {"type": "clip", "id": "att-unknown", "name": "blob.bin", "mimeType": "application/octet-stream", "sizeBytes": 4}
                ]
              }
            ]
          }
        }
        """.data(using: .utf8)!
        let detail = try T3MessageMapper.detail(from: json)
        #expect(detail.messages[0].attachments[0].sizeBytes == 229320)
        let message = T3MessageMapper.messages(from: try JSONSerialization.jsonObject(with: json))[0]
        #expect(message.text == "Connected.")
        guard case .file(let image) = message.parts[1] else {
            Issue.record("missing image part")
            return
        }
        #expect(image.id == "att-image")
        #expect(image.filename == "image.png")
        #expect(image.kind == .image)
        #expect(image.dataURL.isEmpty)
        #expect(image.previewData == nil)
        guard case .file(let pdf) = message.parts[2] else {
            Issue.record("missing pdf part")
            return
        }
        #expect(pdf.kind == .pdf)
        guard case .file(let other) = message.parts[3] else {
            Issue.record("missing file part")
            return
        }
        #expect(other.kind == .text)
        #expect(other.filename == "blob.bin")
    }

    @Test func contextImageMarkdown_isOmittedBecauseTheFilePartCarriesTheImage() {
        let message = T3MessageMapper.domainMessage(
            from: T3ConversationMessage(
                id: "m-user",
                role: "user",
                text: "Connected.\n![image.png](t3-context://attachment/att-image)",
                streaming: false,
                createdAtEpochMs: nil,
                attachments: [
                    T3Attachment(id: "att-image", type: "image", name: "image.png", mimeType: "image/png", sizeBytes: 12),
                ]
            )
        )
        #expect(message.text == "Connected.")
        #expect(T3MessageMapper.withoutContextImages("See ![diagram](https://example.com/a.png)") == "See ![diagram](https://example.com/a.png)")
    }

    @Test func imagePreviewBytes_attachToTheMatchingFilePart() {
        let message = T3MessageMapper.domainMessage(
            from: T3ConversationMessage(
                id: "m-user",
                role: "user",
                text: "Connected.",
                streaming: false,
                createdAtEpochMs: nil,
                attachments: [
                    T3Attachment(id: "att-image", type: "image", name: "image.png", mimeType: "image/png", sizeBytes: 229320),
                ]
            )
        )
        let preview = Data([0x01, 0x02])
        let shown = T3MessageMapper.applyingImagePreviews([message], previews: ["att-image": preview])
        guard case .file(let file) = shown[0].parts[1] else {
            Issue.record("missing file part")
            return
        }
        #expect(file.previewData == preview)
        #expect(file.kind == .image)
        #expect(file.mime == "image/png")
    }

    @Test func assetCreateUrl_targetsAttachmentAndJoinsRelativePath() {
        let payload = T3Assets.createURLPayload(id: "att-image", fileName: "image.png", mimeType: "image/png")
        let resource = payload["resource"] as? [String: Any]
        #expect(resource?["_tag"] as? String == "attachment")
        #expect(resource?["attachmentId"] as? String == "att-image")
        #expect(resource?["fileName"] as? String == "image.png")
        #expect(resource?["mimeType"] as? String == "image/png")
        #expect(resource?["disposition"] as? String == "inline")
        let value: [String: Any] = ["relativeUrl": "/api/assets/tok/image.png", "expiresAt": 1]
        let relative = T3Assets.relativeURL(from: value)
        #expect(relative == "/api/assets/tok/image.png")
        let url = T3Assets.resolve(baseURL: URL(string: "http://127.0.0.1:3773/")!, relativePath: relative ?? "")
        #expect(url?.absoluteString == "http://127.0.0.1:3773/api/assets/tok/image.png")
        #expect(T3Assets.relativeURL(from: ["relativeUrl": "https://evil.example/x"]) == nil)
        #expect(T3Assets.relativeURL(from: ["relativeUrl": "//evil.example/x"]) == nil)
    }

    @Test func snapshotStreamItem_keepsImageAttachments() {
        let item: [String: Any] = [
            "kind": "snapshot",
            "snapshotSequence": 3,
            "projection": [
                "messages": [
                    [
                        "id": "m-user",
                        "role": "user",
                        "text": "Connected.",
                        "attachments": [
                            ["type": "image", "id": "att-image", "name": "image.png", "mimeType": "image/png", "sizeBytes": 12],
                        ],
                    ],
                ],
            ],
        ]
        let messages = T3MessageMapper.messagesFromSnapshotItem(item)
        #expect(messages?.count == 1)
        #expect(messages?.first?.parts.count == 2)
        #expect(T3MessageMapper.messagesFromSnapshotItem(["kind": "event"]) == nil)
    }

    @Test func pendingRuntimeRequest_becomesPermissionAndSkipsUserInput() throws {
        let json = """
        {
          "threads": [
            {
              "id": "thr-ask",
              "title": "Ask",
              "projectId": "p1",
              "status": "idle",
              "pendingRuntimeRequest": {
                "id": "rr-1",
                "kind": "file-change",
                "createdAt": "2026-10-03T12:00:00Z"
              }
            },
            {
              "id": "thr-question",
              "title": "Question",
              "projectId": "p1",
              "status": "idle",
              "pendingRuntimeRequest": {
                "id": "rr-2",
                "kind": "user_input",
                "createdAt": "2026-10-03T12:00:00Z"
              }
            }
          ]
        }
        """.data(using: .utf8)!
        let snapshot = try T3ShellMapper.snapshot(from: json)
        let permissions = snapshot.threads.compactMap(T3ShellMapper.permissionRequest(from:))
        #expect(permissions.map(\.id) == ["rr-1"])
        #expect(permissions.first?.sessionID == "thr-ask")
        #expect(permissions.first?.permission == "file_change")
        #expect(permissions.first?.always == ["this session"])

        let command = T3RpcCodec.runtimeRequestRespondCommand(
            commandID: "cmd-1",
            threadID: "thr-ask",
            requestID: "rr-1",
            decision: .allowOnce
        )
        #expect(command["type"] as? String == "runtime-request.respond")
        #expect(command["decision"] as? String == "accept")
        #expect(T3RpcCodec.approvalDecision(.alwaysAllow) == "acceptForSession")
        #expect(T3RpcCodec.approvalDecision(.deny) == "decline")
    }

    @Test func rpcEncoder_writesDispatchRequestAndParsesExitSuccess() throws {
        let command = T3RpcCodec.messageDispatchCommand(
            commandID: "cmd-1",
            threadID: "thread-1",
            messageID: "msg-1",
            text: "Ship it",
            selection: T3ModelSelection(instanceID: "inst-1", model: "gpt-5"),
            isRunning: false
        )
        let data = try T3RpcCodec.encodeRequest(
            id: "req-1",
            tag: "orchestration.dispatchCommand",
            payload: command
        )
        let object = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        #expect(object["_tag"] as? String == "Request")
        #expect(object["id"] as? String == "req-1")
        #expect(object["tag"] as? String == "orchestration.dispatchCommand")
        let payload = try #require(object["payload"] as? [String: Any])
        #expect(payload["type"] as? String == "message.dispatch")
        #expect(payload["createdBy"] as? String == "user")
        #expect(payload["creationSource"] as? String == "mobile")
        #expect(payload["threadId"] as? String == "thread-1")
        #expect(payload["text"] as? String == "Ship it")
        let mode = try #require(payload["dispatchMode"] as? [String: Any])
        #expect(mode["type"] as? String == "start_immediately")

        let exit = try T3RpcCodec.parseFrame(
            Data(#"{"_tag":"Exit","requestId":"req-1","exit":{"_tag":"Success","value":true}}"#.utf8)
        )
        guard case let .exitSuccess(requestID, _) = exit else {
            Issue.record("expected Exit success")
            return
        }
        #expect(requestID == "req-1")
    }

    @Test func engineSessionRepository_delegatesToT3OnlyWhenProfileEngineIsT3() async throws {
        let opencode = ProbeSessionRepository(tag: "opencode")
        let t3 = ProbeSessionRepository(tag: "t3")
        let manager = ActiveConnectionManager()
        manager.apply(
            ConnectionProfile(
                baseURL: "http://127.0.0.1:4096/",
                username: "opencode",
                password: "",
                engine: .opencode
            )
        )
        let repository = EngineSessionRepository(
            opencode: opencode,
            t3: t3,
            activeConnectionManager: manager
        )

        let openSessions = try await repository.listSessions(directory: nil)
        #expect(openSessions.map(\.id) == ["opencode"])
        #expect(opencode.listCalls == 1)
        #expect(t3.listCalls == 0)

        manager.apply(
            ConnectionProfile(
                baseURL: "http://127.0.0.1:3000/",
                username: T3Auth.username,
                password: "bearer",
                engine: .t3
            )
        )
        let t3Sessions = try await repository.listSessions(directory: nil)
        #expect(t3Sessions.map(\.id) == ["t3"])
        #expect(opencode.listCalls == 1)
        #expect(t3.listCalls == 1)
    }

    @Test func visibleTurnItems_placeReasoningAndToolsBetweenMessages() throws {
        let messages = try orderedTurnMessages()
        #expect(messages.map(\.id) == ["m-user", "activity:r1", "m-assistant", "activity:d1"])
        #expect(messages.map(\.text) == ["Look", "", "Done", ""])
        #expect(messages.filter { $0.id == "m-assistant" }.count == 1)
        #expect(messages[0].role == .user)
        #expect(messages[0].parts.count == 2)
        guard case .file(let image) = messages[0].parts[1] else {
            Issue.record("missing image part")
            return
        }
        #expect(image.id == "att-image")
        #expect(image.kind == .image)
        guard case .reasoning(let reasoning) = messages[1].parts.first else {
            Issue.record("missing reasoning")
            return
        }
        #expect(messages[1].parts.count == 1)
        #expect(reasoning.id == "r1")
        #expect(reasoning.text == "Need the file")
        #expect(reasoning.isStreaming == false)
        #expect(messages[3].parts.count == 3)
        let read = try tool(messages[3].parts[0])
        let bash = try tool(messages[3].parts[1])
        let subagent = try tool(messages[3].parts[2])
        #expect(read.toolName == "read")
        #expect(read.input?["filePath"]?.stringValue == "ios/App.swift")
        #expect(bash.toolName == "bash")
        #expect(bash.input?["command"]?.stringValue == "ls")
        #expect(subagent.toolName == "subagent")
        #expect(messages.contains { message in
            message.parts.contains { part in
                if case .reasoning(let reasoning) = part { return reasoning.text == "DECOY" }
                return false
            }
        } == false)
    }

    @Test func reasoningTurnItem_withTextIsNotStreaming() throws {
        let messages = try orderedTurnMessages()
        guard case .reasoning(let reasoning) = messages[1].parts[0] else {
            Issue.record("missing reasoning")
            return
        }
        #expect(reasoning.id == "r1")
        #expect(reasoning.isStreaming == false)
        #expect(reasoning.timeStartEpochMs != nil)
        #expect(reasoning.timeEndEpochMs != nil)
    }

    @Test func subagentTurnItem_keepsChildThreadId() throws {
        let messages = try orderedTurnMessages()
        let subagent = try tool(messages[3].parts[2])
        #expect(subagent.toolName == "subagent")
        #expect(subagent.input?["agent"]?.stringValue == "Explore")
        #expect(subagent.input?["description"]?.stringValue == "Find callers")
        #expect(subagent.metadata?["sessionId"]?.stringValue == "child-1")
    }

    @Test func dynamicToolNames_mapReadFileAndCommandExecution() throws {
        let messages = try orderedTurnMessages()
        let read = try tool(messages[3].parts[0])
        let bash = try tool(messages[3].parts[1])
        #expect(read.id == "d1")
        #expect(read.toolName == "read")
        #expect(read.input?["filePath"]?.stringValue == "ios/App.swift")
        #expect(read.status == .completed)
        #expect(bash.id == "c1")
        #expect(bash.toolName == "bash")
        #expect(bash.input?["command"]?.stringValue == "ls")
        #expect(bash.metadata?["exit"] == .int(0))
        #expect(bash.output == "App.swift")
    }

    @Test func turnItemUpdated_insertsReasoningBetweenMessages() throws {
        var transcript = try conversationTranscript()
        let event = try JSONSerialization.jsonObject(with: Data("""
        {
          "kind": "event",
          "event": {
            "type": "turn-item.updated",
            "payload": {
              "type": "reasoning",
              "id": "r-live",
              "ordinal": 2,
              "status": "completed",
              "text": "Thinking",
              "streaming": true,
              "startedAt": "2026-01-01T00:00:01Z"
            }
          }
        }
        """.utf8))
        transcript = try #require(T3MessageMapper.applyingLiveEvent(event, to: transcript))
        let messages = T3MessageMapper.domainMessages(from: transcript)
        #expect(messages.map(\.id) == ["m-user", "activity:r-live", "m-assistant"])
        guard case .reasoning(let reasoning) = messages[1].parts[0] else {
            Issue.record("missing reasoning")
            return
        }
        #expect(reasoning.id == "r-live")
        #expect(reasoning.isStreaming == false)
        #expect(reasoning.text == "Thinking")
    }

    @Test func messageUpdated_keepsReasoningAndChangesAssistantText() throws {
        var transcript = try conversationTranscript(reasoning: true)
        let wrapped = try JSONSerialization.jsonObject(with: Data("""
        {
          "kind": "event",
          "event": {
            "type": "message.updated",
            "payload": { "id": "m-assistant", "role": "assistant", "text": "Updated", "streaming": false }
          }
        }
        """.utf8))
        transcript = try #require(T3MessageMapper.applyingLiveEvent(wrapped, to: transcript))
        var messages = T3MessageMapper.domainMessages(from: transcript)
        #expect(messages.map(\.id) == ["m-user", "activity:r1", "m-assistant"])
        #expect(messages[2].text == "Updated")
        guard case .reasoning(let reasoning) = messages[1].parts[0] else {
            Issue.record("missing reasoning")
            return
        }
        #expect(reasoning.text == "Because")

        let direct: [String: Any] = [
            "type": "message.updated",
            "payload": [
                "id": "m-assistant",
                "role": "assistant",
                "text": "Updated again",
                "streaming": false,
            ],
        ]
        transcript = try #require(T3MessageMapper.applyingLiveEvent(direct, to: transcript))
        messages = T3MessageMapper.domainMessages(from: transcript)
        #expect(messages.map(\.id) == ["m-user", "activity:r1", "m-assistant"])
        #expect(messages[2].text == "Updated again")
        #expect(messages[1].parts.count == 1)
    }

    @Test func systemNotice_producesNoPart() throws {
        let json = Data("""
        {
          "projection": {
            "messages": [
              { "id": "m-user", "role": "user", "text": "Hello" },
              { "id": "m-assistant", "role": "assistant", "text": "Hi" }
            ],
            "visibleTurnItems": [
              { "item": { "type": "user_message", "id": "u1", "ordinal": 1, "messageId": "m-user", "text": "Hello", "attachments": [] } },
              { "item": { "type": "dynamic_tool", "id": "d1", "ordinal": 2, "toolName": "read_file", "status": "completed", "input": { "path": "A.swift" } } },
              { "item": { "type": "system_notice", "id": "n1", "ordinal": 3, "status": "completed", "message": "ignored" } },
              { "broken": true },
              { "item": { "type": "command_execution", "id": "c1", "ordinal": 4, "status": "completed", "input": "ls", "title": "List" } },
              { "item": { "type": "assistant_message", "id": "a1", "ordinal": 5, "messageId": "m-assistant", "text": "Hi", "streaming": false } }
            ]
          }
        }
        """.utf8)
        let messages = T3MessageMapper.messages(from: try JSONSerialization.jsonObject(with: json))
        #expect(messages.map(\.id) == ["m-user", "activity:d1", "m-assistant"])
        #expect(messages[1].parts.count == 2)
        #expect(try tool(messages[1].parts[0]).toolName == "read")
        #expect(try tool(messages[1].parts[1]).toolName == "bash")
        #expect(messages[1].parts.contains { part in
            switch part {
            case .tool(let tool): return tool.id == "n1"
            case .reasoning(let reasoning): return reasoning.id == "n1"
            default: return false
            }
        } == false)
    }

    @Test func snapshotWithoutTurnItems_returnsConversationMessages() throws {
        let previous = try conversationTranscript(reasoning: true)
        let item: [String: Any] = [
            "kind": "snapshot",
            "projection": [
                "messages": [
                    ["id": "m1", "role": "user", "text": "Hello"],
                    ["id": "m2", "role": "assistant", "text": "Hi"],
                ],
            ],
        ]
        let messages = T3MessageMapper.messagesFromSnapshotItem(item)
        #expect(messages?.map(\.id) == ["m1", "m2"])
        #expect(messages?.map(\.text) == ["Hello", "Hi"])
        let replaced = try #require(T3MessageMapper.transcript(fromSnapshotItem: item))
        #expect(previous.turnItems.isEmpty == false)
        #expect(replaced.turnItems.isEmpty)
        #expect(replaced.messages.map(\.id) == ["m1", "m2"])
        #expect(T3MessageMapper.domainMessages(from: replaced).map(\.id) == ["m1", "m2"])
        let emptyItems: [String: Any] = [
            "kind": "snapshot",
            "projection": [
                "messages": [
                    ["id": "m1", "role": "user", "text": "Hello"],
                ],
                "visibleTurnItems": [Any](),
                "turnItems": [Any](),
            ],
        ]
        #expect(T3MessageMapper.messagesFromSnapshotItem(emptyItems)?.map(\.id) == ["m1"])
    }

    @Test func turnItems_areUsedWhenVisibleTurnItemsAreEmpty() throws {
        let json = Data("""
        {
          "projection": {
            "messages": [
              { "id": "m-user", "role": "user", "text": "Hello" },
              { "id": "m-assistant", "role": "assistant", "text": "Hi" }
            ],
            "visibleTurnItems": [],
            "turnItems": [
              { "type": "user_message", "id": "u1", "ordinal": 1, "messageId": "m-user", "text": "Hello", "attachments": [] },
              { "type": "reasoning", "id": "r1", "ordinal": 2, "text": "Because", "streaming": false },
              { "type": "assistant_message", "id": "a1", "ordinal": 3, "messageId": "m-assistant", "text": "Hi", "streaming": false }
            ]
          }
        }
        """.utf8)
        let messages = T3MessageMapper.messages(from: try JSONSerialization.jsonObject(with: json))
        #expect(messages.map(\.id) == ["m-user", "activity:r1", "m-assistant"])
        guard case .reasoning(let reasoning) = messages[1].parts[0] else {
            Issue.record("missing reasoning")
            return
        }
        #expect(reasoning.text == "Because")
    }

    private func orderedTurnMessages() throws -> [Message] {
        let json = Data("""
        {
          "projection": {
            "messages": [
              {
                "id": "m-user",
                "role": "user",
                "text": "Look ![image.png](t3-context://attachment/att-image)",
                "attachments": [
                  {"type": "image", "id": "att-image", "name": "image.png", "mimeType": "image/png", "sizeBytes": 12}
                ]
              },
              { "id": "m-assistant", "role": "assistant", "text": "Done", "streaming": false }
            ],
            "turnItems": [
              { "type": "reasoning", "id": "decoy", "ordinal": 0, "text": "DECOY", "streaming": false }
            ],
            "visibleTurnItems": [
              {
                "position": 0,
                "visibility": "local",
                "sourceThreadId": "t1",
                "sourceItemId": "u1",
                "item": {
                  "type": "user_message",
                  "id": "u1",
                  "ordinal": 50,
                  "messageId": "m-user",
                  "text": "WRONG",
                  "attachments": []
                }
              },
              {
                "position": 1,
                "visibility": "local",
                "sourceThreadId": "t1",
                "sourceItemId": "r1",
                "item": {
                  "type": "reasoning",
                  "id": "r1",
                  "ordinal": 40,
                  "status": "completed",
                  "text": "Need the file",
                  "streaming": true,
                  "startedAt": "2026-01-01T00:00:01Z",
                  "completedAt": "2026-01-01T00:00:02Z"
                }
              },
              "skip-me",
              {
                "position": 2,
                "visibility": "local",
                "sourceThreadId": "t1",
                "sourceItemId": "a1",
                "item": {
                  "type": "assistant_message",
                  "id": "a1",
                  "ordinal": 30,
                  "messageId": "m-assistant",
                  "text": "STALE",
                  "streaming": false
                }
              },
              {
                "position": 3,
                "visibility": "local",
                "sourceThreadId": "t1",
                "sourceItemId": "d1",
                "item": {
                  "type": "dynamic_tool",
                  "id": "d1",
                  "ordinal": 20,
                  "toolName": "read_file",
                  "status": "completed",
                  "title": "Read",
                  "input": { "path": "ios/App.swift" },
                  "output": "contents"
                }
              },
              {
                "position": 4,
                "visibility": "local",
                "sourceThreadId": "t1",
                "sourceItemId": "c1",
                "item": {
                  "type": "command_execution",
                  "id": "c1",
                  "ordinal": 10,
                  "status": "completed",
                  "title": "List",
                  "input": "ls",
                  "output": "App.swift",
                  "exitCode": 0
                }
              },
              {
                "position": 5,
                "visibility": "local",
                "sourceThreadId": "t1",
                "sourceItemId": "s1",
                "item": {
                  "type": "subagent",
                  "id": "s1",
                  "ordinal": 1,
                  "status": "completed",
                  "title": "Explore",
                  "prompt": "Find callers",
                  "childThreadId": "child-1"
                }
              }
            ]
          }
        }
        """.utf8)
        return T3MessageMapper.messages(from: try JSONSerialization.jsonObject(with: json))
    }

    private func conversationTranscript(reasoning: Bool = false) throws -> T3Transcript {
        let reasoningItem = reasoning
            ? #",{"type":"reasoning","id":"r1","ordinal":2,"text":"Because","streaming":true,"status":"completed"}"#
            : ""
        let json = Data("""
        {
          "projection": {
            "messages": [
              { "id": "m-user", "role": "user", "text": "Hello" },
              { "id": "m-assistant", "role": "assistant", "text": "Hi" }
            ],
            "visibleTurnItems": [
              { "item": { "type": "user_message", "id": "u1", "ordinal": 1, "messageId": "m-user", "text": "Hello", "attachments": [] } }\(reasoningItem),
              { "item": { "type": "assistant_message", "id": "a1", "ordinal": 3, "messageId": "m-assistant", "text": "Hi", "streaming": false } }
            ]
          }
        }
        """.utf8)
        let detail = try T3MessageMapper.detail(from: json)
        return T3Transcript(messages: detail.messages, turnItems: detail.turnItems)
    }

    private func tool(_ part: MessagePart) throws -> ToolPart {
        guard case .tool(let tool) = part else {
            Issue.record("expected tool part")
            struct ExpectedTool: Error {}
            throw ExpectedTool()
        }
        return tool
    }

    private func formPairs(_ body: String) -> [String: String] {
        Dictionary(uniqueKeysWithValues: body.split(separator: "&").compactMap { part -> (String, String)? in
            let bits = part.split(separator: "=", maxSplits: 1)
            guard bits.count == 2 else { return nil }
            let key = String(bits[0]).removingPercentEncoding ?? String(bits[0])
            let value = String(bits[1]).removingPercentEncoding ?? String(bits[1])
            return (key, value)
        })
    }
}

private final class ProbeSessionRepository: SessionRepository, @unchecked Sendable {
    let tag: String
    private(set) var listCalls = 0

    init(tag: String) {
        self.tag = tag
    }

    func listSessions(directory _: String?) async throws -> [Session] {
        listCalls += 1
        return [
            Session(
                id: tag,
                title: tag,
                slug: nil,
                projectID: nil,
                directory: nil,
                parentID: nil,
                updatedAtEpochMs: 1
            ),
        ]
    }

    func createSession(parentID _: String?, title _: String?, directory _: String?) async throws -> Session {
        try await getSession(id: tag, directory: nil)
    }

    func getAllSessionStatuses(directory _: String?) async throws -> [SessionStatus] { [] }

    func getSession(id: String, directory: String?) async throws -> Session {
        Session(
            id: id,
            title: tag,
            slug: nil,
            projectID: nil,
            directory: directory,
            parentID: nil,
            updatedAtEpochMs: 1
        )
    }

    func updateSession(id: String, title _: String?, directory: String?) async throws -> Session {
        try await getSession(id: id, directory: directory)
    }

    func archiveSession(id: String, directory: String?) async throws -> Session {
        try await getSession(id: id, directory: directory)
    }

    func deleteSession(id _: String, directory _: String?) async throws -> Bool { true }
    func listChildSessions(id _: String) async throws -> [Session] { [] }
    func listSessionTodos(id _: String, directory _: String?) async throws -> [SessionTodo] { [] }
    func initializeSessionAgents(
        id _: String,
        messageID _: String,
        providerID _: String,
        modelID _: String
    ) async throws -> Bool { false }

    func forkSession(id: String, atMessageID _: String?) async throws -> Session {
        try await getSession(id: id, directory: nil)
    }

    func abortSession(id _: String, directory _: String?) async throws -> Bool { false }
    func shareSession(id: String) async throws -> Session { try await getSession(id: id, directory: nil) }
    func unshareSession(id: String) async throws -> Session { try await getSession(id: id, directory: nil) }
    func getSessionDiff(id _: String, messageID _: String?, directory _: String?) async throws -> [FileDiff] { [] }
    func summarizeSession(id _: String, providerID _: String, modelID _: String) async throws -> Bool { false }
    func revertSession(id _: String, messageID _: String, partID _: String?) async throws -> Bool { false }
    func unrevertSession(id _: String) async throws -> Bool { false }
    func respondToPermission(
        sessionID _: String,
        permissionID _: String,
        decision _: PermissionDecision,
        directory _: String?
    ) async throws -> Bool { false }
}
