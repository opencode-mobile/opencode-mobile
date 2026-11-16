import Foundation
import Testing
@testable import OpenCode_Mobile

/// #1033: projecting the timeline classifies no tool result and splits no body into lines; the
/// inspector is built when the detail opens, with the content it had before.
struct ToolCardDeferredDetailTests {
    private static let bigFile = (1 ... 200_000).map { "val line\($0) = \($0)" }.joined(separator: "\n")
    private static let bigOutput = (1 ... 200_000).map { "src/feature/File\($0).kt" }.joined(separator: "\n")

    private static let write = ToolPart(
        id: "prt-write",
        toolName: "write",
        status: .completed,
        input: ["filePath": .string("src/Big.kt"), "content": .string(bigFile)],
        output: "Wrote file successfully."
    )
    private static let shell = ToolPart(
        id: "prt-shell",
        toolName: "bash",
        status: .completed,
        input: ["command": .string("find src")],
        output: bigOutput
    )

    @Test func projectionKeepsTheClassifierInputs_notAPresentation() throws {
        let cards = Self.cards([.tool(Self.write), .tool(Self.shell)])
        #expect(cards.count == 2)
        for card in cards {
            #expect(card.opensDetail)
            // The card carries the part's own strings (shared storage), not classified lines.
            #expect(card.resultRequest != nil)
        }
        // The write's +N comes from counting newlines, not from splitting the body.
        #expect(cards[0].additions == 200_000)
        #expect(cards[1].resultRequest?.output == Self.bigOutput)
    }

    @Test func detailBuildsTheInspectorOnOpen_withTheSameContent() throws {
        let cards = Self.cards([.tool(Self.write), .tool(Self.shell)])

        let writeDetail = try #require(SessionChatStepTapActions.detailTarget(for: cards[0]))
        guard case .source(let source) = try #require(writeDetail.presentation) else {
            Issue.record("expected a source presentation")
            return
        }
        #expect(source.lines.count == 200_000)
        #expect(source.lines.last?.text == "val line200000 = 200000")

        let shellDetail = try #require(SessionChatStepTapActions.detailTarget(for: cards[1]))
        #expect(shellDetail.body == Self.bigOutput)
        guard case .pathList = try #require(shellDetail.presentation) else {
            Issue.record("expected a path list presentation")
            return
        }
    }

    @Test func hasResultMatchesWhetherClassifyBuildsAPresentation() {
        let requests: [ToolResultRequest] = [
            ToolResultRequest(toolName: "bash", fallbackTitle: .verbatim("T")),
            ToolResultRequest(toolName: "bash", fallbackTitle: .verbatim("T"), output: "   "),
            ToolResultRequest(toolName: "bash", fallbackTitle: .verbatim("T"), output: "\n x"),
            ToolResultRequest(toolName: "bash", fallbackTitle: .verbatim("T"), error: "boom"),
            ToolResultRequest(toolName: "write", fallbackTitle: .verbatim("T"), path: "a.kt", content: "x"),
            ToolResultRequest(toolName: "write", fallbackTitle: .verbatim("T"), path: "  ", content: "x"),
            ToolResultRequest(toolName: "write", fallbackTitle: .verbatim("T"), path: "a.kt", content: ""),
            ToolResultRequest(toolName: "grep", fallbackTitle: .verbatim("T"), pattern: "x"),
            ToolResultRequest(toolName: "edit", fallbackTitle: .verbatim("T"), path: "a.kt", oldString: "a", newString: "b"),
            ToolResultRequest(toolName: "edit", fallbackTitle: .verbatim("T"), path: "a.kt", oldString: "a\n", newString: "a"),
            ToolResultRequest(toolName: "edit", fallbackTitle: .verbatim("T"), path: "a.kt", newString: "\n"),
            ToolResultRequest(toolName: "edit", fallbackTitle: .verbatim("T"), path: "a.kt", oldString: "a"),
            ToolResultRequest(toolName: "edit", fallbackTitle: .verbatim("T"), path: "a.kt"),
            ToolResultRequest(toolName: "apply_patch", fallbackTitle: .verbatim("T"), patch: "@@ -1 +1 @@\n-a\n+b"),
            ToolResultRequest(toolName: "patch", fallbackTitle: .verbatim("T"), patch: "no hunks"),
            ToolResultRequest(toolName: "webfetch", fallbackTitle: .verbatim("T")),
        ]
        for request in requests {
            #expect(ToolResultInspector.hasResult(request) == (ToolResultInspector.classify(request) != nil), "\(request)")
        }
    }

    private static func cards(_ parts: [MessagePart]) -> [SessionChatToolCardBlock] {
        guard case .ok(let blocks) = SessionChatTimelineBlocks.build(role: .assistant, parts: parts) else { return [] }
        return blocks.compactMap { block in
            if case .toolCard(_, let card) = block { return card }
            return nil
        }
    }
}
