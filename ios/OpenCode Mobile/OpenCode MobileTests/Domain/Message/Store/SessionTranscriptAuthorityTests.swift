import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionTranscriptAuthorityTests {
    @Test func lastAssistantNeedsAuthority_hiddenReasoningAfterUser() {
        let messages = [
            Message(id: "u1", role: .user, text: "Hi", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(
                id: "a1",
                role: .assistant,
                text: "",
                parts: [.reasoning(ReasoningPart(text: "", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 2
            ),
        ]
        #expect(SessionTranscriptAuthority.lastAssistantNeedsAuthority(messages))
    }

    @Test func lastAssistantNeedsAuthority_visibleTextDoesNot() {
        let messages = [
            Message(id: "u1", role: .user, text: "Hi", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(
                id: "a1",
                role: .assistant,
                text: "Done",
                parts: [.text(TextPart(text: "Done", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 2
            ),
        ]
        #expect(!SessionTranscriptAuthority.lastAssistantNeedsAuthority(messages))
    }

    @Test func lastAssistantNeedsAuthority_earlierToolsDoNotHideLastEmptyAssistant() {
        let messages = [
            Message(id: "u1", role: .user, text: "Hi", model: nil, mode: nil, sentAtEpochMs: 1),
            Message(
                id: "a1",
                role: .assistant,
                text: "",
                parts: [.tool(ToolPart(id: "prt-1", toolName: "read", status: .completed))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 2
            ),
            Message(
                id: "a2",
                role: .assistant,
                text: "",
                parts: [.reasoning(ReasoningPart(text: "", isStreaming: false))],
                model: nil,
                mode: nil,
                sentAtEpochMs: 3
            ),
        ]
        #expect(SessionTranscriptAuthority.lastAssistantNeedsAuthority(messages))
    }

    @Test func lastAssistantNeedsAuthority_emptySessionDoesNot() {
        #expect(!SessionTranscriptAuthority.lastAssistantNeedsAuthority([]))
        #expect(
            !SessionTranscriptAuthority.lastAssistantNeedsAuthority(
                [Message(id: "u1", role: .user, text: "Hi", model: nil, mode: nil, sentAtEpochMs: 1)]
            )
        )
    }
}
