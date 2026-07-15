import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionChatTimelineBlocksTests {
    @Test func build_mergesAdjacentTextParts() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .text(TextPart(text: "Hello", isStreaming: false)),
                .text(TextPart(text: "World", isStreaming: false)),
            ]
        )
        #expect(blocks == [.text("Hello\nWorld")])
    }

    @Test func build_interleavesTextAndGenericToolCards() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .text(TextPart(text: "Before", isStreaming: false)),
                .tool(ToolPart(toolName: "bash", status: .completed)),
                .text(TextPart(text: "After", isStreaming: false)),
            ]
        )
        #expect(blocks.count == 3)
        #expect(blocks[0] == .text("Before"))
        guard case .toolCard(let tool) = blocks[1] else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Called bash")
        #expect(tool.status == .completed)
        #expect(tool.subtitle == nil)
        #expect(tool.errorText == nil)
        #expect(blocks[2] == .text("After"))
    }

    @Test func build_runningToolUsesTitleAsSubtitle() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(ToolPart(toolName: "grep", status: .running, title: "Searching…")),
            ]
        )
        guard case .toolCard(let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Called grep")
        #expect(tool.subtitle == "Searching…")
        #expect(tool.status == .running)
    }

    @Test func build_pendingAndCompletedDoNotUseTitleSubtitle() {
        let pending = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [.tool(ToolPart(toolName: "read", status: .pending, title: "file.kt"))]
        )
        guard case .toolCard(let pendingCard) = pending.first else {
            Issue.record("Expected pending tool card")
            return
        }
        #expect(pendingCard.subtitle == nil)

        let completed = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [.tool(ToolPart(toolName: "read", status: .completed, title: "file.kt"))]
        )
        guard case .toolCard(let completedCard) = completed.first else {
            Issue.record("Expected completed tool card")
            return
        }
        #expect(completedCard.subtitle == nil)
    }

    @Test func build_errorUsesErrorSlot() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(ToolPart(toolName: "webfetch", status: .error, title: "https://example.com", error: "timeout")),
            ]
        )
        guard case .toolCard(let tool) = blocks.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Called webfetch")
        #expect(tool.subtitle == nil)
        #expect(tool.errorText == "timeout")
        #expect(tool.status == .error)
    }

    @Test func build_hidesTodowriteAlways() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .text(TextPart(text: "Visible", isStreaming: false)),
                .tool(ToolPart(toolName: "todowrite", status: .completed)),
            ]
        )
        #expect(blocks == [.text("Visible")])
    }

    @Test func build_hidesInFlightQuestion_showsCompletedQuestion() {
        let hidden = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(ToolPart(toolName: "question", status: .pending)),
                .tool(ToolPart(toolName: "question", status: .running)),
            ]
        )
        #expect(hidden.isEmpty)

        let shown = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [.tool(ToolPart(toolName: "question", status: .completed))]
        )
        guard case .toolCard(let tool) = shown.first else {
            Issue.record("Expected tool card")
            return
        }
        #expect(tool.title == "Called question")
    }

    @Test func build_nonAssistantRolesAreTextOnly() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .user,
            parts: [
                .text(TextPart(text: "Prompt", isStreaming: false)),
                .tool(ToolPart(toolName: "bash", status: .completed)),
            ]
        )
        #expect(blocks == [.text("Prompt")])
    }

    @Test func build_returnsEmptyWhenOnlyHiddenTools() {
        let blocks = SessionChatTimelineBlocks.build(
            role: .assistant,
            parts: [
                .tool(ToolPart(toolName: "todowrite", status: .running)),
                .tool(ToolPart(toolName: "question", status: .pending)),
            ]
        )
        #expect(blocks.isEmpty)
    }

    @Test func build_genericChromeForSpecializedToolIds() {
        for name in ["bash", "edit", "task", "webfetch", "skill"] {
            let blocks = SessionChatTimelineBlocks.build(
                role: .assistant,
                parts: [.tool(ToolPart(toolName: name, status: .completed))]
            )
            guard case .toolCard(let tool) = blocks.first else {
                Issue.record("Expected tool card for \(name)")
                return
            }
            #expect(tool.title == "Called \(name)")
        }
    }
}
