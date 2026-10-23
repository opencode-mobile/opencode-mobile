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
        #expect(message.role == .assistant)
        #expect(message.text == "")
        #expect(message.failure?.type == "APIError")
        #expect(message.failure?.line() == "HTTP 403 · Forbidden: unauthorized: not licensed to use Copilot")
        #expect(message.failure?.providerBody == nil)
    }

    @Test func sendRequests_carryClientMessageID() {
        let options = PromptSendOptions(text: "Hello", messageID: "msg_client")

        #expect(adapter.sendRequest(options: options).messageID == "msg_client")
        #expect(adapter.v2PromptRequest(options: options).id == "msg_client")
        #expect(adapter.v2PromptRequest(options: PromptSendOptions(text: "Hello")).id == nil)
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

    @Test func v2PromptRequest_mapsTextFilesAndAgents() {
        let attachment = Attachment(
            id: "attachment-1",
            filename: "notes.txt",
            mime: "text/plain",
            kind: .text,
            dataURL: "data:text/plain;base64,SGk="
        )
        let body = adapter.v2PromptRequest(
            options: PromptSendOptions(
                text: "@explore please",
                attachments: [attachment],
                mode: "build",
                agentMentions: [AgentMention(id: "m1", name: "explore", start: 0, end: 8)]
            )
        )
        #expect(body.text == "@explore please")
        #expect(body.delivery == "steer")
        #expect(body.files?.first?.uri == "data:text/plain;base64,SGk=")
        #expect(body.files?.first?.name == "notes.txt")
        #expect(body.files?.first?.mime == "text/plain")
        #expect(body.agents?.first?.name == "explore")
        #expect(body.agents?.first?.mention?.text == "@explore")
        #expect(body.skills == nil)
    }

    @Test func sendRequest_includesFileReferencePartsWithoutAttachmentBytes() {
        let attachment = Attachment(
            id: "attachment-1",
            filename: "notes.txt",
            mime: "text/plain",
            kind: .text,
            dataURL: "data:text/plain;base64,SGk="
        )
        let body = adapter.sendRequest(
            options: PromptSendOptions(
                text: "see @src/App.kt",
                attachments: [attachment],
                fileMentions: [FileMention(id: "f1", path: "src/App.kt", start: 4, end: 15)],
                worktree: "/repo"
            )
        )
        #expect(body.parts.map(\.type) == ["text", "file", "file"])
        let reference = body.parts[1]
        #expect(reference.url == "file:///repo/src/App.kt")
        #expect(reference.filename == "App.kt")
        #expect(reference.mime == "text/plain")
        #expect(reference.fileSource?.path == "src/App.kt")
        #expect(reference.fileSource?.value == "@src/App.kt")
        #expect(reference.fileSource?.start == 4)
        #expect(reference.fileSource?.end == 15)
        #expect(reference.source == nil)
        #expect(body.parts[2].url == "data:text/plain;base64,SGk=")
        #expect(body.parts[2].fileSource == nil)
    }

    @Test func v2PromptRequest_appendsFileMentionsAfterAttachments() {
        let attachment = Attachment(
            id: "attachment-1",
            filename: "notes.txt",
            mime: "text/plain",
            kind: .text,
            dataURL: "data:text/plain;base64,SGk="
        )
        let body = adapter.v2PromptRequest(
            options: PromptSendOptions(
                text: "see @src/App.kt",
                attachments: [attachment],
                fileMentions: [FileMention(id: "f1", path: "src/App.kt", start: 4, end: 15)],
                worktree: "/repo"
            )
        )
        #expect(body.files?.first?.uri == "data:text/plain;base64,SGk=")
        #expect(body.fileMentions?.count == 1)
        #expect(body.fileMentions?.first?.uri == "file:///repo/src/App.kt")
        #expect(body.fileMentions?.first?.name == "App.kt")
        #expect(body.fileMentions?.first?.mention.text == "@src/App.kt")
        #expect(body.fileMentions?.first?.mention.start == 4)
        #expect(body.fileMentions?.first?.mention.end == 15)
    }

    @Test func v2PromptRequest_mapsSkillIds() {
        let body = adapter.v2PromptRequest(
            options: PromptSendOptions(
                text: "check the error",
                skillIDs: [" opencode ", "opencode", ""]
            )
        )
        #expect(body.text == "check the error")
        #expect(body.skills?.map(\.id) == ["opencode"])
    }

    @Test func v2SwitchModelRequest_mapsProviderModelAndVariant() {
        let body = adapter.v2SwitchModelRequest(
            options: PromptSendOptions(
                text: "Hello",
                model: PromptModelSelection(providerID: "anthropic", modelID: "claude-sonnet"),
                variant: "high"
            )
        )
        #expect(body?.model.id == "claude-sonnet")
        #expect(body?.model.providerID == "anthropic")
        #expect(body?.model.variant == "high")
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
        #expect(t.isSynthetic == false)
        #expect(t.isIgnored == false)
    }

    @Test func messagePart_textType_mapsSyntheticAndIgnoredFlags() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-1","type":"text","text":"file body","synthetic":true,"ignored":true}"#.data(using: .utf8)!
        )
        guard case .text(let t) = adapter.messagePart(from: dto) else {
            Issue.record("Expected text part")
            return
        }
        #expect(t.isSynthetic)
        #expect(t.isIgnored)
        #expect(!t.isDisplayable)
    }

    @Test func messagePart_reasoningType_returnsReasoningPart() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-1","type":"reasoning","text":"thinking..."}"#.data(using: .utf8)!
        )
        guard case .reasoning(let part) = adapter.messagePart(from: dto) else {
            Issue.record("Expected reasoning part")
            return
        }
        #expect(part.text == "thinking...")
        #expect(part.id == "prt-1")
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
        #expect(t.timeStartEpochMs == nil)
        if case let .string(query)? = t.input?["query"] {
            #expect(query == "opencode")
        } else {
            Issue.record("Expected query input")
        }
        #expect(adapter.compactToolLabel(for: t) == "\u{23F3} Searching...")
    }

    @Test func messagePart_runningMapsTimeStart() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-1","type":"tool","tool":"task","state":{"status":"running","time":{"start":1710000000000}}}"#.data(using: .utf8)!
        )
        guard case .tool(let t) = adapter.messagePart(from: dto) else {
            Issue.record("Expected tool part")
            return
        }
        #expect(t.timeStartEpochMs == 1_710_000_000_000)
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
        let hiddenTypes = ["step-start", "step-finish", "agent", "subtask", "retry", "snapshot", "patch"]
        for type in hiddenTypes {
            let dto = try decoder.decode(
                MessagePartDTO.self,
                from: #"{"id":"prt-1","type":"\#(type)"}"#.data(using: .utf8)!
            )
            let part = adapter.messagePart(from: dto)
            #expect(part == nil, "\(type) should return nil")
        }
    }

    @Test func messagePart_compaction_returnsCompactionPart() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-c","type":"compaction","auto":true}"#.data(using: .utf8)!
        )
        guard case .compaction(let part) = adapter.messagePart(from: dto) else {
            Issue.record("Expected compaction part")
            return
        }
        #expect(part.id == "prt-c")
        #expect(part.auto == true)
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

    @Test func messagePart_fileDataURLWithoutMime_infersMime() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-file","type":"file","filename":"Photo 1.jpeg","url":"data:image/jpeg;base64,AA=="}"#.data(using: .utf8)!
        )

        guard case .file(let file) = adapter.messagePart(from: dto) else {
            Issue.record("Expected file part")
            return
        }
        #expect(file.filename == "Photo 1.jpeg")
        #expect(file.mime == "image/jpeg")
        #expect(file.kind == .image)
        #expect(file.dataURL == "data:image/jpeg;base64,AA==")
    }

    @Test func messagePart_fileWithoutURL_keepsImagePlaceholder() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-file","type":"file","mime":"image/jpeg","filename":"Photo 1.jpeg"}"#.data(using: .utf8)!
        )

        guard case .file(let file) = adapter.messagePart(from: dto) else {
            Issue.record("Expected file part")
            return
        }
        #expect(file.id == "prt-file")
        #expect(file.filename == "Photo 1.jpeg")
        #expect(file.mime == "image/jpeg")
        #expect(file.kind == .image)
        #expect(file.dataURL.isEmpty)
    }

    @Test func messagePart_fileWithoutURLOrMime_infersImageFromFilename() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-file","type":"file","filename":"Photo 1.jpeg"}"#.data(using: .utf8)!
        )

        guard case .file(let file) = adapter.messagePart(from: dto) else {
            Issue.record("Expected file part")
            return
        }
        #expect(file.mime == "image/jpeg")
        #expect(file.kind == .image)
        #expect(file.dataURL.isEmpty)
    }

    @Test func messagePart_fileURLThatIsNotData_returnsNil() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-file","type":"file","mime":"text/plain","filename":"secret.txt","url":"file:///repo/secret.txt"}"#.data(using: .utf8)!
        )

        #expect(adapter.messagePart(from: dto) == nil)
    }

    @Test func messagePart_toolTypeMissingTool_returnsNamelessTool() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-1","type":"tool"}"#.data(using: .utf8)!
        )
        guard case let .tool(tool) = adapter.messagePart(from: dto) else {
            Issue.record("expected a nameless tool part")
            return
        }
        #expect(tool.toolName.isEmpty)
        #expect(tool.id == "prt-1")
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

    @Test func flattenedText_skipsSyntheticAndIgnoredTextParts() {
        let parts: [MessagePart] = [
            .text(TextPart(id: "prt-1", text: "User prompt", isStreaming: false)),
            .text(
                TextPart(
                    id: "prt-2",
                    text: #"Called the Read tool with the following input: {"filePath":"notes.txt"}"#,
                    isStreaming: false,
                    isSynthetic: true
                )
            ),
            .text(TextPart(id: "prt-3", text: "file body contents", isStreaming: false, isSynthetic: true)),
            .text(TextPart(id: "prt-4", text: "ignored", isStreaming: false, isIgnored: true)),
        ]
        #expect(adapter.flattenedText(from: parts) == "User prompt")
    }

    @Test func message_hidesSyntheticTextFileBodyFromDisplayText() throws {
        let data =
            """
            {
              "info": { "id": "msg-1", "role": "user", "sessionID": "ses-1" },
              "parts": [
                { "type": "text", "id": "prt-1", "text": "I attached logs" },
                {
                  "type": "text",
                  "id": "prt-2",
                  "text": "Called the Read tool with the following input: {\\"filePath\\":\\"notes.txt\\"}",
                  "synthetic": true
                },
                { "type": "text", "id": "prt-3", "text": "file body", "synthetic": true },
                {
                  "type": "file",
                  "id": "prt-4",
                  "mime": "text/plain",
                  "filename": "notes.txt",
                  "url": "data:text/plain;base64,ZmlsZSBib2R5"
                }
              ]
            }
            """.data(using: .utf8)!
        let dto = try decoder.decode(MessageDTO.self, from: data)
        let message = adapter.message(from: dto)
        #expect(message.text == "I attached logs")
        #expect(message.parts.count == 4)
        guard case .text(let synthetic) = message.parts[2] else {
            Issue.record("Expected synthetic text part")
            return
        }
        #expect(synthetic.isSynthetic)
        guard case .file(let file) = message.parts[3] else {
            Issue.record("Expected file part")
            return
        }
        #expect(file.filename == "notes.txt")
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

    @Test func projectV2MapsUserAssistantAndASwitchNotice() throws {
        let dtos = try decoder.decode(
            [V2SessionMessageDTO].self,
            from: Data(
                """
                [
                  {"id":"msg_switch","type":"agent-switched","agent":"build","time":{"created":1}},
                  {"id":"msg_user","type":"user","text":"Hello","time":{"created":2}},
                  {
                    "id":"msg_asst",
                    "type":"assistant",
                    "agent":"build",
                    "model":{"id":"gpt-5","providerID":"openai"},
                    "content":[
                      {"type":"text","id":"txt_1","text":"Hi there"},
                      {
                        "type":"tool",
                        "id":"tool_1",
                        "name":"bash",
                        "state":{
                          "status":"completed",
                          "input":{"command":"ls"},
                          "content":[{"type":"text","text":"src"}],
                          "structured":{}
                        }
                      }
                    ],
                    "cost":0.1,
                    "tokens":{"input":10,"output":4,"reasoning":0,"cache":{"read":0,"write":0}},
                    "time":{"created":3}
                  }
                ]
                """.utf8
            )
        )

        let all = adapter.messages(fromV2: dtos)
        #expect(all.map(\.role) == [.notice, .user, .assistant])
        let messages = all.filter { $0.role != .notice }

        #expect(messages.count == 2)
        #expect(messages[0].role == .user)
        #expect(messages[0].text == "Hello")
        #expect(messages[0].mode == "build")
        #expect(messages[1].role == .assistant)
        #expect(messages[1].text == "Hi there")
        #expect(messages[1].modelID == "gpt-5")
        #expect(messages[1].modelProviderID == "openai")
        guard case let .tool(tool) = messages[1].parts.first(where: { if case .tool = $0 { true } else { false } }) else {
            Issue.record("expected tool part")
            return
        }
        #expect(tool.toolName == "bash")
        #expect(tool.status == .completed)
        #expect(tool.output == "src")
    }

    @Test func projectV2ToolTitleComesFromMetadata() throws {
        let dtos = try decoder.decode(
            [V2SessionMessageDTO].self,
            from: Data(
                """
                [
                  {
                    "id":"msg_asst",
                    "type":"assistant",
                    "content":[
                      {"type":"tool","id":"call_1","name":"sim_task","state":{"status":"error","input":{"title":"Backfill users"},"error":{"type":"tool.execution","message":"lock timeout"},"metadata":{"title":"Backfill users · step 3/3"}}},
                      {"type":"tool","id":"call_2","name":"read","state":{"status":"completed","input":{"path":"README.md"},"metadata":{"truncated":false}}}
                    ],
                    "time":{"created":3}
                  }
                ]
                """.utf8
            )
        )

        let tools = adapter.messages(fromV2: dtos)[0].parts.compactMap { part -> ToolPart? in
            guard case let .tool(tool) = part else { return nil }
            return tool
        }
        #expect(tools.count == 2)
        #expect(tools.first?.title == "Backfill users · step 3/3")
        #expect(tools.last?.title == nil)
    }

    @Test func projectV2MapsReasoningContentIntoThoughtPart() throws {
        let dtos = try decoder.decode(
            [V2SessionMessageDTO].self,
            from: Data(
                """
                [
                  {
                    "id":"msg_asst",
                    "type":"assistant",
                    "content":[
                      {"type":"reasoning","id":"r1","text":"Need to greet the user."},
                      {"type":"text","id":"t1","text":"Hello!"}
                    ],
                    "time":{"created":3}
                  }
                ]
                """.utf8
            )
        )

        let parts = adapter.messages(fromV2: dtos)[0].parts
        guard case let .reasoning(reasoning) = parts.first(where: { if case .reasoning = $0 { true } else { false } }) else {
            Issue.record("expected reasoning part")
            return
        }
        #expect(reasoning.text == "Need to greet the user.")
        #expect(reasoning.id == "r1")
    }

    @Test func projectV2ThinkingAliasGetsReasoningPartAndId() throws {
        let dtos = try decoder.decode(
            [V2SessionMessageDTO].self,
            from: Data(
                """
                [
                  {
                    "id":"msg_asst",
                    "type":"assistant",
                    "content":[
                      {"type":"thinking","text":"Need to greet the user."},
                      {"type":"text","text":"Hello!"}
                    ],
                    "time":{"created":3}
                  }
                ]
                """.utf8
            )
        )

        let parts = adapter.messages(fromV2: dtos)[0].parts
        guard case let .reasoning(reasoning) = parts.first(where: { if case .reasoning = $0 { true } else { false } }) else {
            Issue.record("expected reasoning part")
            return
        }
        #expect(reasoning.text == "Need to greet the user.")
        #expect(reasoning.id == "msg_asst:reasoning:0")
        guard case let .text(text) = parts.first(where: { if case .text = $0 { true } else { false } }) else {
            Issue.record("expected text part")
            return
        }
        #expect(text.id == "msg_asst:text:0")
    }

    @Test func projectV2IDsTextAndReasoningByPerKindOrdinalLikeLiveEvents() throws {
        // Live v2 events number each kind from 0 per assistant message, so REST
        // must too, or a reload would add a second copy of the live text.
        let dtos = try decoder.decode(
            [V2SessionMessageDTO].self,
            from: Data(
                """
                [
                  {
                    "id":"msg_a",
                    "type":"assistant",
                    "content":[
                      {"type":"reasoning","text":"Plan"},
                      {"type":"text","text":"Let me look around."},
                      {"type":"tool","id":"call_1","name":"glob","state":{"status":"completed","input":{}}},
                      {"type":"reasoning","text":"More"},
                      {"type":"text","text":"Found it."}
                    ],
                    "time":{"created":3}
                  },
                  {"id":"msg_b","type":"assistant","content":[{"type":"text","text":"Done."}],"time":{"created":4}}
                ]
                """.utf8
            )
        )

        let messages = adapter.messages(fromV2: dtos)
        #expect(messages[0].parts.map(\.testPartID) == ["msg_a:reasoning:0", "msg_a:text:0", "call_1", "msg_a:reasoning:1", "msg_a:text:1"])
        #expect(messages[1].parts.map(\.testPartID) == ["msg_b:text:0"])
    }

    @Test func projectV2AssistantInheritsCarriedAgentAndModel() throws {
        let dtos = try decoder.decode(
            [V2SessionMessageDTO].self,
            from: Data(
                """
                [
                  {"id":"msg_agent","type":"agent-switched","agent":"build","time":{"created":1}},
                  {"id":"msg_model","type":"model-switched","model":{"id":"gpt-5","providerID":"openai"},"time":{"created":2}},
                  {
                    "id":"msg_asst",
                    "type":"assistant",
                    "content":[{"type":"text","id":"t1","text":"Hi"}],
                    "time":{"created":3}
                  }
                ]
                """.utf8
            )
        )

        let messages = adapter.messages(fromV2: dtos).filter { $0.role != .notice }
        let message = try #require(messages.first)
        #expect(messages.count == 1)
        #expect(message.mode == "build")
        #expect(message.modelID == "gpt-5")
        #expect(message.modelProviderID == "openai")
    }

    @Test func projectV2CountsAgentMentionTextForContextEstimate() throws {
        let dtos = try decoder.decode(
            [V2SessionMessageDTO].self,
            from: Data(
                """
                [
                  {
                    "id":"msg_user",
                    "type":"user",
                    "text":"",
                    "agents":[{"name":"review","mention":{"start":0,"end":17,"text":"agent prompt text"}}],
                    "time":{"created":1}
                  }
                ]
                """.utf8
            )
        )

        let message = try #require(adapter.messages(fromV2: dtos).first)
        #expect(message.contextEstimate.userCharacters == "agent prompt text".count)
    }

    @Test func projectV2ExpandsShellAndCompaction() throws {
        let dtos = try decoder.decode(
            [V2SessionMessageDTO].self,
            from: Data(
                """
                [
                  {
                    "id":"msg_shell",
                    "type":"shell",
                    "callID":"call_1",
                    "command":"pwd",
                    "output":"/repo",
                    "time":{"created":1,"completed":2}
                  },
                  {
                    "id":"msg_compact",
                    "type":"compaction",
                    "reason":"auto",
                    "summary":"Earlier work summarized",
                    "time":{"created":3}
                  }
                ]
                """.utf8
            )
        )

        let messages = adapter.messages(fromV2: dtos)

        #expect(messages.count == 3)
        #expect(messages[0].role == .user)
        #expect(messages[0].text == "pwd")
        #expect(messages[1].id == "msg_shell:assistant")
        guard case let .tool(shellTool) = messages[1].parts.first else {
            Issue.record("expected shell tool")
            return
        }
        #expect(shellTool.toolName == "bash")
        #expect(shellTool.output == "/repo")
        #expect(messages[2].mode == "compaction")
        // The summary is model context: it rides on the compaction part, never as reply text.
        #expect(messages[2].text.isEmpty)
        #expect(messages[2].parts.count == 1)
        guard case let .compaction(part) = messages[2].parts.first else {
            Issue.record("expected compaction part")
            return
        }
        #expect(part.auto)
        #expect(part.completed == false)
        #expect(part.summary == "Earlier work summarized")
    }

    @Test func projectV2CompactionStatusCompletedMarksPartComplete() throws {
        let dtos = try decoder.decode(
            [V2SessionMessageDTO].self,
            from: Data(
                """
                [
                  {
                    "id":"msg_compact",
                    "type":"compaction",
                    "reason":"manual",
                    "status":"completed",
                    "summary":"Done",
                    "time":{"created":3}
                  }
                ]
                """.utf8
            )
        )
        let messages = adapter.messages(fromV2: dtos)
        #expect(messages.count == 1)
        guard case let .compaction(part) = messages.first?.parts.first else {
            Issue.record("expected compaction part")
            return
        }
        #expect(part.completed)
        #expect(part.auto == false)
    }

    @Test func projectV2SubagentMapsMetadataSessionIdAndOmitsToolNameTitle() throws {
        let dtos = try decoder.decode(
            [V2SessionMessageDTO].self,
            from: Data(
                """
                [
                  {
                    "id":"msg_asst",
                    "type":"assistant",
                    "content":[
                      {
                        "type":"tool",
                        "id":"call_1",
                        "name":"subagent",
                        "state":{
                          "status":"completed",
                          "input":{"agent":"explore","description":"Find git projects"},
                          "metadata":{"sessionID":"ses_child","status":"completed"}
                        },
                        "time":{"created":10}
                      }
                    ],
                    "time":{"created":3}
                  }
                ]
                """.utf8
            )
        )

        let messages = adapter.messages(fromV2: dtos)
        let message = try #require(messages.first)
        #expect(messages.count == 1)
        guard case let .tool(tool) = message.parts.first(where: {
            if case .tool = $0 { true } else { false }
        }) else {
            Issue.record("expected tool part")
            return
        }
        #expect(tool.toolName == "subagent")
        #expect(tool.status == .completed)
        #expect(tool.title == nil)
        #expect(tool.input?["agent"]?.stringValue == "explore")
        #expect(tool.metadata?["sessionID"]?.stringValue == "ses_child")
        #expect(tool.timeStartEpochMs == 10)
    }

    @Test func projectV2UserSkillAndFile_keepsArgumentsAndSkillRefWithoutSkillBody() throws {
        let dtos = try decoder.decode(
            [V2SessionMessageDTO].self,
            from: Data(
                """
                [
                  {
                    "id":"msg_user",
                    "type":"user",
                    "text":"prototype me a better list item",
                    "files":[{"name":"16136.png","mime":"image/png","uri":"data:image/png;base64,YQ=="}],
                    "skills":[{
                      "id":"publish-prototype",
                      "name":"publish-prototype",
                      "text":"SKILL BODY should stay off the transcript"
                    }]
                  }
                ]
                """.utf8
            )
        )
        let message = try #require(adapter.messages(fromV2: dtos).first)
        #expect(message.text == "prototype me a better list item")
        #expect(!message.text.contains("SKILL BODY"))
        #expect(message.promptSkills == [PromptSkill(id: "publish-prototype", name: "publish-prototype")])
        let files = message.parts.compactMap { part -> FilePart? in
            if case let .file(file) = part { return file }
            return nil
        }
        #expect(files.count == 1)
        #expect(files.first?.filename == "16136.png")
    }

    @Test func projectV2UserInlineFileDataBuildsDataURL() throws {
        let dtos = try decoder.decode(
            [V2SessionMessageDTO].self,
            from: Data(
                """
                [
                  {
                    "id":"msg_user",
                    "type":"user",
                    "text":"see file",
                    "files":[{
                      "name":"notes.txt",
                      "mime":"text/plain",
                      "data":"SGk=",
                      "source":{"type":"inline"}
                    }]
                  }
                ]
                """.utf8
            )
        )
        let messages = adapter.messages(fromV2: dtos)
        guard case let .file(file) = messages.first?.parts.last else {
            Issue.record("expected file part")
            return
        }
        #expect(file.filename == "notes.txt")
        #expect(file.mime == "text/plain")
        #expect(file.dataURL == "data:text/plain;base64,SGk=")
    }

    @Test func projectV2UserFileWithoutURI_keepsImagePlaceholder() throws {
        let dtos = try decoder.decode(
            [V2SessionMessageDTO].self,
            from: Data(
                """
                [
                  {
                    "id":"msg_user",
                    "type":"user",
                    "text":"What",
                    "files":[{
                      "name":"Photo 1.jpeg",
                      "mime":"image/jpeg"
                    }]
                  }
                ]
                """.utf8
            )
        )
        let messages = adapter.messages(fromV2: dtos)
        guard case let .file(file) = messages.first?.parts.last else {
            Issue.record("expected file part")
            return
        }
        #expect(file.filename == "Photo 1.jpeg")
        #expect(file.mime == "image/jpeg")
        #expect(file.kind == .image)
        #expect(file.dataURL.isEmpty)
    }

    @Test func projectV2ShellOutputObjectDoesNotFailDecode() throws {
        let dtos = try decoder.decode(
            [V2SessionMessageDTO].self,
            from: Data(
                """
                [
                  {
                    "id":"msg_shell",
                    "type":"shell",
                    "command":"pwd",
                    "output":{"output":"/repo","truncated":false},
                    "time":{"created":1,"completed":2}
                  }
                ]
                """.utf8
            )
        )
        let messages = adapter.messages(fromV2: dtos)
        guard case let .tool(shellTool) = messages[1].parts.first else {
            Issue.record("expected shell tool")
            return
        }
        #expect(shellTool.output == "/repo")
    }

    @Test func projectV2ShellCarriesCommandInputAndExitCode() throws {
        let dtos = try decoder.decode(
            [V2SessionMessageDTO].self,
            from: Data(
                """
                [
                  {
                    "id":"msg_shell",
                    "type":"shell",
                    "shellID":"sh_1",
                    "command":"false",
                    "status":"exited",
                    "exit":1,
                    "output":{"output":"","truncated":false},
                    "time":{"created":1,"completed":2}
                  }
                ]
                """.utf8
            )
        )
        let messages = adapter.messages(fromV2: dtos)
        #expect(messages.map(\.id) == ["msg_shell", "msg_shell:assistant"])
        guard case let .tool(shellTool) = messages[1].parts.first else {
            Issue.record("expected shell tool")
            return
        }
        #expect(shellTool.id == "msg_shell:tool")
        #expect(shellTool.input?["command"] == .string("false"))
        #expect(shellTool.metadata?["exit"] == .int(1))
    }

    @Test func mergeInbox_marksExistingUserMessageWaiting() {
        let messages = [
            Message(id: "msg-1", role: .user, text: "Hello", model: nil, mode: nil, sentAtEpochMs: nil),
            Message(id: "msg-2", role: .assistant, text: "Hi", model: nil, mode: nil, sentAtEpochMs: nil),
        ]
        let merged = adapter.mergeInbox(
            messages: messages,
            inbox: [
                V2InboxItemDTO(
                    id: "msg-1",
                    timeCreated: 10,
                    type: "user",
                    payload: V2InboxUserPayloadDTO(text: "Hello"),
                    delivery: "steer"
                ),
            ]
        )

        #expect(merged.count == 2)
        #expect(merged[0].isWaitingForPickup)
        #expect(merged[0].followUpDelivery == .steer)
        #expect(!merged[1].isWaitingForPickup)
    }

    @Test func mergeInbox_materialisesMissingUserItem() {
        let messages = [
            Message(id: "msg-1", role: .user, text: "Hello", model: nil, mode: nil, sentAtEpochMs: nil),
        ]
        let merged = adapter.mergeInbox(
            messages: messages,
            inbox: [
                V2InboxItemDTO(
                    id: "inbox-2",
                    timeCreated: 20,
                    type: "user",
                    payload: V2InboxUserPayloadDTO(text: "Held prompt"),
                    delivery: "queue"
                ),
            ]
        )

        #expect(merged.count == 2)
        #expect(merged[1].id == "inbox-2")
        #expect(merged[1].text == "Held prompt")
        #expect(merged[1].role == .user)
        #expect(merged[1].isWaitingForPickup)
        #expect(merged[1].followUpDelivery == .queue)
        #expect(merged[1].sentAtEpochMs == 20)
    }

    @Test func mergeInbox_ignoresNonUserItems() {
        let messages = [
            Message(id: "msg-1", role: .user, text: "Hello", model: nil, mode: nil, sentAtEpochMs: nil),
        ]
        let merged = adapter.mergeInbox(
            messages: messages,
            inbox: [
                V2InboxItemDTO(id: "syn-1", timeCreated: 1, type: "synthetic"),
                V2InboxItemDTO(id: "cmp-1", timeCreated: 2, type: "compaction"),
                V2InboxItemDTO(id: "mov-1", timeCreated: 3, type: "move"),
            ]
        )
        #expect(merged == messages)
    }

    @Test func mergeInbox_emptyInboxIsNoOp() {
        let messages = [
            Message(id: "msg-1", role: .user, text: "Hello", model: nil, mode: nil, sentAtEpochMs: nil),
        ]
        #expect(adapter.mergeInbox(messages: messages, inbox: []) == messages)
    }

    @Test func projectV2KeepsAssistantErrorBesideReplyText() throws {
        let dtos = try decodeV2(
            """
            [{
              "id":"a1","type":"assistant","finish":"error",
              "content":[{"type":"text","text":"Partial answer"}],
              "error":{"type":"provider.rate-limit","message":"{\\"type\\":\\"message\\",\\"message\\":\\"Slow down\\"}","status":429,
                "response":{"body":"{\\"error\\":\\"overloaded\\"}"}}
            }]
            """
        )
        let message = try #require(adapter.messages(fromV2: dtos).first)
        #expect(message.text == "Partial answer")
        #expect(message.failure?.type == "provider.rate-limit")
        #expect(message.failure?.line() == "HTTP 429 · Slow down")
        #expect(message.failure?.providerBody == #"{"error":"overloaded"}"#)
        #expect(message.failure?.isNeutral == false)
    }

    @Test func projectV2HidesAbortAndProjectsFailedIdleAndCompaction() throws {
        let dtos = try decodeV2(
            """
            [
              {"id":"a1","type":"assistant","error":{"type":"aborted","message":"stopped"},"content":[{"type":"text","text":"Hi"}]},
              {"id":"c1","type":"compaction","summary":"Earlier","error":{"type":"compaction.failed","message":"Could not compact"}},
              {"id":"i1","type":"idle","outcome":"failed","error":{"type":"provider.auth","message":"Sign in again"}},
              {"id":"i2","type":"idle","outcome":"interrupted","error":{"type":"compaction.interrupted","message":"nope"}}
            ]
            """
        )
        let messages = adapter.messages(fromV2: dtos)
        #expect(messages.count == 3)
        #expect(messages[0].failure == nil)
        #expect(messages[1].failure?.message == "Could not compact")
        #expect(messages[1].failure?.source == .compaction)
        #expect(messages[1].failure?.action() == nil)
        #expect(messages[2].failure?.message == "Sign in again")
        #expect(messages[2].failure?.source == .execution)
        #expect(messages[2].failure?.action() == nil)
    }

    @Test func projectV2MalformedToolCallIsDistinctFromAFailedRun() throws {
        let dtos = try decodeV2(
            """
            [{
              "id":"a1","type":"assistant",
              "content":[{"type":"tool","id":"t1","name":"read","state":{
                "status":"error","executed":false,
                "error":{"type":"tool.input-json","message":"bad json"}
              }}]
            }]
            """
        )
        guard case let .tool(tool) = adapter.messages(fromV2: dtos).first?.parts.first else {
            Issue.record("expected tool part")
            return
        }
        #expect(tool.isMalformed)
        #expect(tool.error == "bad json")
    }

    @Test func projectV2IdleFailureIsSkippedWhenTheAssistantAlreadyShowsIt() throws {
        let dtos = try decodeV2(
            """
            [
              {"id":"a1","type":"assistant","content":[],"error":{"type":"unknown","message":"boom"}},
              {"id":"i1","type":"idle","outcome":"failed","error":{"type":"unknown","message":"boom"}}
            ]
            """
        )
        #expect(adapter.messages(fromV2: dtos).count == 1)
    }

    private func decodeMessage(_ json: String) throws -> MessageDTO {
        try decoder.decode(MessageDTO.self, from: Data(json.utf8))
    }

    private func decodeV2(_ json: String) throws -> [V2SessionMessageDTO] {
        try decoder.decode([V2SessionMessageDTO].self, from: Data(json.utf8))
    }
}

extension MessagePart {
    /// Test helper: the part's id regardless of kind.
    var testPartID: String? {
        switch self {
        case .text(let part): part.id
        case .file(let part): part.id
        case .tool(let part): part.id
        case .compaction(let part): part.id
        case .reasoning(let part): part.id
        case .notice(let notice): notice.id
        }
    }
}
