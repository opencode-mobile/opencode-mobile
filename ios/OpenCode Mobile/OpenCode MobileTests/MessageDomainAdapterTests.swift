import Foundation
import Testing
@testable import OpenCode_Mobile

struct MessageDomainAdapterTests {
    private let adapter = MessageDomainAdapter()
    private let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        return decoder
    }()

    // MARK: - Error fallback

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

    // MARK: - Send request

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

    // MARK: - Tool status labels

    @Test func toolStatusLabel_nilState_returnsHourglass() {
        #expect(adapter.toolStatusLabel(toolName: "webfetch", state: nil) == "\u{231B} webfetch")
    }

    @Test func toolStatusLabel_pending_returnsHourglass() throws {
        let state = try decoder.decode(
            MessageToolStateDTO.self,
            from: #"{"status":"pending"}"#.data(using: .utf8)!
        )
        #expect(adapter.toolStatusLabel(toolName: "webfetch", state: state) == "\u{231B} webfetch")
    }

    @Test func toolStatusLabel_runningWithoutTitle_returnsHourglassWithName() throws {
        let state = try decoder.decode(
            MessageToolStateDTO.self,
            from: #"{"status":"running"}"#.data(using: .utf8)!
        )
        #expect(adapter.toolStatusLabel(toolName: "webfetch", state: state) == "\u{23F3} webfetch")
    }

    @Test func toolStatusLabel_runningWithTitle_returnsHourglassWithTitle() throws {
        let state = try decoder.decode(
            MessageToolStateDTO.self,
            from: #"{"status":"running","title":"Searching..."}"#.data(using: .utf8)!
        )
        #expect(adapter.toolStatusLabel(toolName: "webfetch", state: state) == "\u{23F3} Searching...")
    }

    @Test func toolStatusLabel_completed_returnsCheckmark() throws {
        let state = try decoder.decode(
            MessageToolStateDTO.self,
            from: #"{"status":"completed"}"#.data(using: .utf8)!
        )
        #expect(adapter.toolStatusLabel(toolName: "webfetch", state: state) == "\u{2713} webfetch")
    }

    @Test func toolStatusLabel_errorWithoutMessage_returnsCrossWithDefault() throws {
        let state = try decoder.decode(
            MessageToolStateDTO.self,
            from: #"{"status":"error"}"#.data(using: .utf8)!
        )
        #expect(adapter.toolStatusLabel(toolName: "webfetch", state: state) == "\u{2715} webfetch: failed")
    }

    @Test func toolStatusLabel_errorWithMessage_returnsCrossWithMessage() throws {
        let state = try decoder.decode(
            MessageToolStateDTO.self,
            from: #"{"status":"error","error":"timeout"}"#.data(using: .utf8)!
        )
        #expect(adapter.toolStatusLabel(toolName: "webfetch", state: state) == "\u{2715} webfetch: timeout")
    }

    @Test func toolStatusLabel_unknownStatus_returnsPlainName() throws {
        let state = try decoder.decode(
            MessageToolStateDTO.self,
            from: #"{"status":"unknown"}"#.data(using: .utf8)!
        )
        #expect(adapter.toolStatusLabel(toolName: "webfetch", state: state) == "webfetch")
    }

    // MARK: - MessagePart parsing

    @Test func messagePart_textType_returnsTextPart() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-1","type":"text","text":"Hello"}"#.data(using: .utf8)!
        )
        let part = adapter.messagePart(from: dto)
        guard case .text(let t) = part else { Issue.record("Expected text part"); return }
        #expect(t.text == "Hello")
        #expect(t.isStreaming == false)
    }

    @Test func messagePart_reasoningType_returnsTextPart() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-1","type":"reasoning","text":"thinking..."}"#.data(using: .utf8)!
        )
        let part = adapter.messagePart(from: dto)
        guard case .text(let t) = part else { Issue.record("Expected text part"); return }
        #expect(t.text == "thinking...")
    }

    @Test func messagePart_toolType_returnsToolPart() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-1","type":"tool","tool":"webfetch","state":{"status":"completed"}}"#.data(using: .utf8)!
        )
        let part = adapter.messagePart(from: dto)
        guard case .tool(let t) = part else { Issue.record("Expected tool part"); return }
        #expect(t.toolName == "webfetch")
        #expect(t.statusText == "\u{2713} webfetch")
    }

    @Test func messagePart_hiddenTypes_returnNil() throws {
        let hiddenTypes = ["step-start", "step-finish", "agent", "subtask", "compaction", "retry", "snapshot", "patch"]
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

    @Test func messagePart_toolTypeMissingTool_returnsNil() throws {
        let dto = try decoder.decode(
            MessagePartDTO.self,
            from: #"{"id":"prt-1","type":"tool"}"#.data(using: .utf8)!
        )
        #expect(adapter.messagePart(from: dto) == nil)
    }

    // MARK: - Flattened text

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

    @Test func flattenedText_textAndToolParts() {
        let parts: [MessagePart] = [
            .text(TextPart(text: "Look at this", isStreaming: false)),
            .tool(ToolPart(toolName: "webfetch", statusText: "\u{2713} webfetch")),
        ]
        #expect(adapter.flattenedText(from: parts) == "Look at this\n\u{2713} webfetch")
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
        #expect(message.text.contains("Here is the answer."))
        #expect(message.text.contains("\u{2713} bash"))
        #expect(message.parts.count == 2)
    }
}
