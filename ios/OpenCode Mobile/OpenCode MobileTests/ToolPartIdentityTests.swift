import Foundation
import Testing
@testable import OpenCode_Mobile

struct ToolPartIdentityTests {
    @Test func placeholderIncludesBlankAndLiteralTool() {
        #expect(ToolPartIdentity.isPlaceholder(nil))
        #expect(ToolPartIdentity.isPlaceholder(""))
        #expect(ToolPartIdentity.isPlaceholder("tool"))
        #expect(ToolPartIdentity.isPlaceholder("TOOL"))
        #expect(!ToolPartIdentity.isPlaceholder("question"))
        #expect(!ToolPartIdentity.isPlaceholder("read"))
    }

    @Test func coalesceKeepsKnownNameWhenIncomingIsPlaceholder() {
        #expect(ToolPartIdentity.coalesce(incoming: "tool", existing: "question") == "question")
        #expect(ToolPartIdentity.coalesce(incoming: "", existing: "question") == "question")
        #expect(ToolPartIdentity.coalesce(incoming: "bash", existing: "question") == "bash")
        #expect(ToolPartIdentity.coalesce(incoming: "bash", existing: "tool") == "bash")
    }

    @Test func resolvedNameInfersQuestionOnlyWhenWireNameIsMissing() {
        let questions: [String: Any] = ["questions": [["header": "A"]]]
        #expect(ToolPartIdentity.resolvedName(explicit: nil, input: questions) == "question")
        #expect(ToolPartIdentity.resolvedName(explicit: "tool", input: questions) == "question")
        #expect(ToolPartIdentity.resolvedName(explicit: "read", input: questions) == "read")
        #expect(ToolPartIdentity.resolvedName(explicit: nil, input: nil) == "")
        #expect(ToolPartIdentity.resolvedName(explicit: nil, input: ["filePath": "src/Main.kt"]) == "read")
        #expect(ToolPartIdentity.resolvedName(explicit: nil, input: ["query": "opencode mobile"]) == "websearch")
        #expect(ToolPartIdentity.resolvedName(explicit: nil, input: ["path": "/home/ivan/Projects/session-test"]) == "")
    }

    @Test func resolvedNameInfersQuestionFromStoredAnyCodableInput() {
        let input: [String: AnyCodable] = [
            "questions": .array([.dictionary(["header": .string("A")])]),
        ]
        #expect(ToolPartIdentity.resolvedName(explicit: nil, input: input) == "question")
        #expect(ToolPartIdentity.resolvedName(explicit: "tool", input: input) == "question")
        #expect(ToolPartIdentity.resolvedName(explicit: "read", input: input) == "read")
        let query: [String: AnyCodable] = ["query": .string("opencode mobile")]
        #expect(ToolPartIdentity.resolvedName(explicit: nil, input: query) == "websearch")
        let file: [String: AnyCodable] = ["filePath": .string("src/Main.kt")]
        #expect(ToolPartIdentity.resolvedName(explicit: nil, input: file) == "read")
    }

    @Test func calledTitleDoesNotInventCalledTool() {
        #expect(ToolPartIdentity.calledTitle("tool") == "Tool")
        #expect(ToolPartIdentity.calledTitle("") == "Tool")
        #expect(ToolPartIdentity.calledTitle("read") == "Called read")
    }

    @Test func placeholderToolWithoutQuestionsIsHidden() {
        let part = ToolPart(id: "prt-1", toolName: "tool", status: .running)
        #expect(!ToolPartIdentity.isVisibleOnTimeline(part))
        #expect(!ToolPartIdentity.isVisibleOnTimeline(ToolPart(id: "prt-1", toolName: "tool", status: .completed)))
        #expect(ToolPartIdentity.isVisibleOnTimeline(ToolPart(id: "prt-2", toolName: "read", status: .running)))
        #expect(
            ToolPartIdentity.isVisibleOnTimeline(
                ToolPart(
                    id: "prt-3",
                    toolName: "tool",
                    status: .completed,
                    input: ["query": .string("opencode")]
                )
            )
        )
    }
}
