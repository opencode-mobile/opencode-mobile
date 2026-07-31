import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct MessageDomainAdapterTests {
    private let adapter = MessageDomainAdapter()
    private let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        return decoder
    }()

    @Test func mapsAssistantErrorWhenPartsEmpty() throws {
        let data =
            """
            {
              "info": {
                "id": "msg-3",
                "role": "assistant",
                "error": {
                  "name": "APIError",
                  "data": {
                    "message": "Forbidden: unauthorized: not licensed to use Copilot",
                    "statusCode": 403
                  }
                }
              },
              "parts": []
            }
            """.data(using: .utf8)!
        let dto = try decoder.decode(MessageDTO.self, from: data)
        let message = adapter.message(from: dto)
        #expect(message.text == "Forbidden: unauthorized: not licensed to use Copilot")
    }

    @Test func sendRequest_includesModelAndNonDefaultVariant() throws {
        let body = adapter.sendRequest(
            options: PromptSendOptions(
                text: "Hello",
                model: PromptModelSelection(providerID: "anthropic", modelID: "claude-sonnet"),
                variant: "high"
            )
        )

        #expect(body.parts.map(\.text) == ["Hello"])
        #expect(body.model?.providerID == "anthropic")
        #expect(body.model?.modelID == "claude-sonnet")
        #expect(body.variant == "high")
    }

    @Test func sendRequest_omitsDefaultVariant() throws {
        let body = adapter.sendRequest(
            options: PromptSendOptions(
                text: "Hello",
                model: PromptModelSelection(providerID: "anthropic", modelID: "claude-sonnet"),
                variant: nil
            )
        )

        #expect(body.model?.providerID == "anthropic")
        #expect(body.variant == nil)
    }

    @Test func sendRequest_mapsModeToAgent() throws {
        let body = adapter.sendRequest(
            options: PromptSendOptions(
                text: "Hello",
                model: PromptModelSelection(providerID: "anthropic", modelID: "claude-sonnet"),
                mode: "plan"
            )
        )

        #expect(body.agent == "plan")
    }

    @Test func sendRequest_omitsModeWhenNil() throws {
        let body = adapter.sendRequest(
            options: PromptSendOptions(
                text: "Hello",
                model: PromptModelSelection(providerID: "anthropic", modelID: "claude-sonnet"),
                mode: nil
            )
        )

        #expect(body.agent == nil)
    }

    @Test func sendRequest_mapsTextAndAttachmentsToPromptParts() throws {
        let attachment = Attachment(
            id: "attachment-1",
            filename: "notes.txt",
            mime: "text/plain",
            kind: .text,
            dataURL: "data:text/plain;base64,SGVsbG8="
        )

        let body = adapter.sendRequest(
            options: PromptSendOptions(text: "Review this", attachments: [attachment])
        )

        #expect(body.parts.count == 2)
        #expect(body.parts[0].type == "text")
        #expect(body.parts[0].text == "Review this")
        #expect(body.parts[1].type == "file")
        #expect(body.parts[1].id == nil)
        #expect(body.parts[1].mime == "text/plain")
        #expect(body.parts[1].filename == "notes.txt")
        #expect(body.parts[1].url == "data:text/plain;base64,SGVsbG8=")
    }

    @Test func sendRequest_attachmentOnly_omitsEmptyTextPart() throws {
        let attachment = Attachment(
            id: "attachment-1",
            filename: "diagram.png",
            mime: "image/png",
            kind: .image,
            dataURL: "data:image/png;base64,iVBORw0KGgo="
        )

        let body = adapter.sendRequest(options: PromptSendOptions(text: "  ", attachments: [attachment]))

        #expect(body.parts.count == 1)
        #expect(body.parts[0].type == "file")
    }

    @Test func compactToolLabel_pending_returnsHourglass() {
        #expect(
            adapter.compactToolLabel(for: ToolPart(toolName: "webfetch", status: .pending)) == "\u{231B} webfetch"
        )
    }

    @Test func compactToolLabel_runningWithoutTitle_returnsHourglassWithName() {
        #expect(
            adapter.compactToolLabel(for: ToolPart(toolName: "webfetch", status: .running)) == "\u{23F3} webfetch"
        )
    }

    @Test func compactToolLabel_runningWithTitle_returnsHourglassWithTitle() {
        #expect(
            adapter.compactToolLabel(
                for: ToolPart(toolName: "webfetch", status: .running, title: "Searching...")
            ) == "\u{23F3} Searching..."
        )
    }

    @Test func compactToolLabel_completed_returnsCheckmark() {
        #expect(
            adapter.compactToolLabel(for: ToolPart(toolName: "webfetch", status: .completed)) == "\u{2713} webfetch"
        )
    }

    @Test func compactToolLabel_errorWithoutMessage_returnsCrossWithDefault() {
        #expect(
            adapter.compactToolLabel(for: ToolPart(toolName: "webfetch", status: .error)) == "\u{2715} webfetch: failed"
        )
    }

    @Test func compactToolLabel_errorWithMessage_returnsCrossWithMessage() {
        #expect(
            adapter.compactToolLabel(
                for: ToolPart(toolName: "webfetch", status: .error, error: "timeout")
            ) == "\u{2715} webfetch: timeout"
        )
    }

    @Test func toolStatus_missingOrUnrecognized_mapsToPending() {
        #expect(adapter.toolStatus(from: nil) == .pending)
        #expect(adapter.toolStatus(from: "unknown") == .pending)
        #expect(adapter.toolStatus(from: "") == .pending)
    }

    @Test func messagePart_textType_returnsTextPart() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-1","type":"text","text":"Hello"}"#.data(using: .utf8)!
        )
        let part = adapter.messagePart(from: dto)
        guard case .text(let t) = part else { Issue.record("Expected text part"); return }
        #expect(t.id == "prt-1")
        #expect(t.text == "Hello")
        #expect(t.isStreaming == false)
    }

    @Test func messagePart_reasoningType_returnsNil() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-1","type":"reasoning","text":"thinking..."}"#.data(using: .utf8)!
        )
        #expect(adapter.messagePart(from: dto) == nil)
    }

    @Test func messagePart_toolType_returnsStructuredToolPart() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-1","type":"tool","tool":"webfetch","callID":"call-1","state":{"status":"completed"}}"#.data(using: .utf8)!
        )
        let part = adapter.messagePart(from: dto)
        guard case .tool(let t) = part else { Issue.record("Expected tool part"); return }
        #expect(t.id == "prt-1")
        #expect(t.toolName == "webfetch")
        #expect(t.status == .completed)
        #expect(t.callID == "call-1")
        #expect(t.title == nil)
        #expect(t.error == nil)
        #expect(adapter.compactToolLabel(for: t) == "\u{2713} webfetch")
    }

    @Test func messagePart_missingToolState_mapsPending() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-1","type":"tool","tool":"glob"}"#.data(using: .utf8)!
        )
        guard case .tool(let t) = adapter.messagePart(from: dto) else {
            Issue.record("Expected tool part")
            return
        }
        #expect(t.status == .pending)
        #expect(adapter.compactToolLabel(for: t) == "\u{231B} glob")
    }

    @Test func messagePart_unknownStatus_mapsPending() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-1","type":"tool","tool":"webfetch","state":{"status":"unknown"}}"#.data(using: .utf8)!
        )
        guard case .tool(let t) = adapter.messagePart(from: dto) else {
            Issue.record("Expected tool part")
            return
        }
        #expect(t.status == .pending)
        #expect(adapter.compactToolLabel(for: t) == "\u{231B} webfetch")
    }

    @Test func messagePart_runningWithTitleAndInput() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-1","type":"tool","tool":"websearch","state":{"status":"running","title":"Searching...","input":{"query":"opencode"}}}"#.data(using: .utf8)!
        )
        guard case .tool(let t) = adapter.messagePart(from: dto) else {
            Issue.record("Expected tool part")
            return
        }
        #expect(t.status == .running)
        #expect(t.title == "Searching...")
        if case let .string(query)? = t.input?["query"] {
            #expect(query == "opencode")
        } else {
            Issue.record("Expected query input")
        }
        #expect(adapter.compactToolLabel(for: t) == "\u{23F3} Searching...")
    }

    @Test func messagePart_errorPreservesErrorText() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-1","type":"tool","tool":"webfetch","state":{"status":"error","error":"timeout"}}"#.data(using: .utf8)!
        )
        guard case .tool(let t) = adapter.messagePart(from: dto) else {
            Issue.record("Expected tool part")
            return
        }
        #expect(t.status == .error)
        #expect(t.error == "timeout")
        #expect(adapter.compactToolLabel(for: t) == "\u{2715} webfetch: timeout")
    }

    @Test func messagePart_completedEdit_preservesMetadataWhileCompactLabelStaysSimple() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: """
            {
              "id":"prt-1",
              "type":"tool",
              "tool":"edit",
              "callID":"call-edit-1",
              "state":{
                "status":"completed",
                "title":"src/Main.kt",
                "output":"edited",
                "metadata":{
                  "filediff":{"file":"src/Main.kt","additions":3,"deletions":1}
                }
              }
            }
            """.data(using: .utf8)!
        )
        guard case .tool(let t) = adapter.messagePart(from: dto) else {
            Issue.record("Expected tool part")
            return
        }
        #expect(t.toolName == "edit")
        #expect(t.status == .completed)
        #expect(t.title == "src/Main.kt")
        #expect(t.output == "edited")
        #expect(t.callID == "call-edit-1")
        if case let .dictionary(filediff)? = t.metadata?["filediff"],
           case let .string(file)? = filediff["file"],
           case let .int(additions)? = filediff["additions"],
           case let .int(deletions)? = filediff["deletions"]
        {
            #expect(file == "src/Main.kt")
            #expect(additions == 3)
            #expect(deletions == 1)
        } else {
            Issue.record("Expected filediff metadata")
        }
        #expect(adapter.compactToolLabel(for: t) == "\u{2713} edit")
    }

    @Test func messagePart_hiddenTypes_returnNil() throws {
        let hiddenTypes = ["reasoning", "step-start", "step-finish", "agent", "subtask", "compaction", "retry", "snapshot", "patch"]
        for type in hiddenTypes {
            let dto = try decoder.decode(
                MessagePartDTO.self,
                from: #"{"id":"prt-1","type":"\#(type)"}"#.data(using: .utf8)!
            )
            let part = adapter.messagePart(from: dto)
            #expect(part == nil, "\(type) should return nil")
        }
    }

    @Test func messagePart_textTypeMissingText_returnsNil() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-1","type":"text"}"#.data(using: .utf8)!
        )
        #expect(adapter.messagePart(from: dto) == nil)
    }

    @Test func messagePart_fileDataURL_returnsFilePart() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-file","type":"file","mime":"application/pdf","filename":"brief.pdf","url":"data:application/pdf;base64,JVBERi0="}"#.data(using: .utf8)!
        )

        guard case .file(let file) = adapter.messagePart(from: dto) else {
            Issue.record("Expected file part")
            return
        }
        #expect(file.id == "prt-file")
        #expect(file.filename == "brief.pdf")
        #expect(file.mime == "application/pdf")
        #expect(file.kind == .pdf)
        #expect(file.dataURL == "data:application/pdf;base64,JVBERi0=")
    }

    @Test func messagePart_fileURLThatIsNotData_returnsNil() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-file","type":"file","mime":"text/plain","filename":"secret.txt","url":"file:///repo/secret.txt"}"#.data(using: .utf8)!
        )

        #expect(adapter.messagePart(from: dto) == nil)
    }

    @Test func messagePart_toolTypeMissingTool_returnsNil() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-1","type":"tool"}"#.data(using: .utf8)!
        )
        #expect(adapter.messagePart(from: dto) == nil)
    }

    @Test func flattenedText_singleTextPart() {
        let parts: [MessagePart] = [.text(TextPart(text: "Hello", isStreaming: false))]
        #expect(adapter.flattenedText(from: parts) == "Hello")
    }

    @Test func flattenedText_multipleTextPartsJoinedByNewline() {
        let parts: [MessagePart] = [
            .text(TextPart(text: "First", isStreaming: false)),
            .text(TextPart(text: "Second", isStreaming: false)),
        ]
        #expect(adapter.flattenedText(from: parts) == "First\nSecond")
    }

    @Test func flattenedText_textAndToolParts_isTextOnly() {
        let parts: [MessagePart] = [
            .text(TextPart(text: "Look at this", isStreaming: false)),
            .tool(ToolPart(toolName: "webfetch", status: .completed)),
        ]
        #expect(adapter.flattenedText(from: parts) == "Look at this")
    }

    @Test func flattenedText_emptyParts_returnsEmptyString() {
        #expect(adapter.flattenedText(from: []) == "")
    }

    @Test func message_parsesPartsIntoFlattenedText() throws {
        let data =
            """
            {
              "info": { "id": "msg-1", "role": "assistant", "sessionID": "ses-1" },
              "parts": [
                { "type": "text", "id": "prt-1", "text": "Here is the answer." },
                { "type": "tool", "id": "prt-2", "tool": "bash", "state": { "status": "completed" } }
              ]
            }
            """.data(using: .utf8)!
        let dto = try decoder.decode(MessageDTO.self, from: data)
        let message = adapter.message(from: dto)
        #expect(message.text == "Here is the answer.")
        #expect(message.parts.count == 2)
        guard case .tool(let tool) = message.parts[1] else {
            Issue.record("Expected tool part")
            return
        }
        #expect(tool.status == .completed)
        #expect(tool.toolName == "bash")
    }

    @Test func messageCountsFilePartSourceTextForContextEstimate() throws {
        let dto = try decodeMessage(
            """
            {
              "info": {"id": "msg-file", "role": "user"},
              "parts": [
                {
                  "id": "prt-file",
                  "sessionID": "ses-1",
                  "messageID": "msg-file",
                  "type": "file",
                  "mime": "text/plain",
                  "url": "file:///repo/file.txt",
                  "source": {
                    "type": "file",
                    "path": "/repo/file.txt",
                    "text": {"value": "file source text", "start": 0, "end": 16}
                  }
                }
              ]
            }
            """
        )

        let message = adapter.message(from: dto)

        #expect(message.contextEstimate.userCharacters == "file source text".count)
    }

    @Test func messageCountsAgentPartSourceTextForContextEstimate() throws {
        let dto = try decodeMessage(
            """
            {
              "info": {"id": "msg-agent", "role": "user"},
              "parts": [
                {
                  "id": "prt-agent",
                  "sessionID": "ses-1",
                  "messageID": "msg-agent",
                  "type": "agent",
                  "name": "review",
                  "source": {"value": "agent prompt text", "start": 0, "end": 17}
                }
              ]
            }
            """
        )

        let message = adapter.message(from: dto)

        #expect(message.contextEstimate.userCharacters == "agent prompt text".count)
    }

    private func decodeMessage(_ json: String) throws -> MessageDTO {
        try decoder.decode(MessageDTO.self, from: Data(json.utf8))
    }
}
