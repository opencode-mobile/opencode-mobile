import Foundation
import Testing
@testable import OpenCode_Mobile

struct WorkingTurnIndicatorTests {
    @Test func labelForActiveTurn_nilWhenIdle() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: nil,
                messages: [user("u1")]
            ) == nil
        )
    }

    @Test func labelForActiveTurn_hidesWorkingAfterUserWithNoAssistant() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                messages: [user("u1")]
            ) == nil
        )
    }

    @Test func labelForActiveTurn_hidesWhenAwaitingUserAfterSettledThought() {
        let thought: SessionChatTimelineBlock = .reasoning(
            blockID: "r1",
            text: "plan",
            isStreaming: false,
            durationMs: 2000
        )
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                messages: [user("u1"), assistant("a1", text: "", blocks: [thought])],
                isAwaitingUser: true
            ) == nil
        )
    }

    @Test func labelForActiveTurn_hidesThinkingWhenAssistantHasSettledText() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                messages: [user("u1"), assistant("a1", text: "Hello")]
            ) == nil
        )
    }

    @Test func labelForActiveTurn_showsRetryingAfterUserWithNoAssistant() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Retrying",
                messages: [user("u1")]
            ) == "Retrying"
        )
    }

    @Test func labelForActiveTurn_hidesWorkingWhenAssistantSlotExists() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                messages: [user("u1"), assistant("a1", text: "   ")]
            ) == nil
        )
    }

    @Test func labelForActiveTurn_hidesWorkingWhenUnprojectedAssistantTurnExists() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                messages: [user("u1")],
                assistantTurnExists: true
            ) == nil
        )
    }

    @Test func labelForActiveTurn_hidesWhenAssistantHasRunningToolCard() {
        let toolCard: SessionChatTimelineBlock = .toolCard(
            blockID: "tool-1",
            card: SessionChatToolCardBlock(
                toolName: "bash",
                status: .running,
                title: "bash"
            )
        )
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                messages: [user("u1"), assistant("a1", text: "", blocks: [toolCard])]
            ) == nil
        )
    }

    @Test func labelForActiveTurn_hidesThinkingAfterCompletedToolCard() {
        let toolCard: SessionChatTimelineBlock = .toolCard(
            blockID: "tool-1",
            card: SessionChatToolCardBlock(
                toolName: "bash",
                status: .completed,
                title: "bash"
            )
        )
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                messages: [user("u1"), assistant("a1", text: "", blocks: [toolCard])]
            ) == nil
        )
    }

    @Test func labelForActiveTurn_keepsRetryingAfterSettledContent() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Retrying",
                messages: [user("u1"), assistant("a1", text: "Hello")]
            ) == "Retrying"
        )
    }

    @Test func labelForActiveTurn_hidesWhenReasoningIsStreaming() {
        let reasoning: SessionChatTimelineBlock = .reasoning(
            blockID: "r1",
            text: "hmm",
            isStreaming: true,
            durationMs: nil
        )
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                messages: [user("u1"), assistant("a1", text: "", blocks: [reasoning])]
            ) == nil
        )
    }

    @Test func labelForActiveTurn_showsCompactingEvenWithAssistantContent() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                messages: [user("u1"), assistant("a1", text: "Hello")],
                isCompacting: true
            ) == SessionCompactChrome.statusLabel
        )
    }

    @Test func labelForActiveTurn_ignoresCompletedCompactionSnapshot() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                messages: [
                    user("u1"),
                    SessionChatRow(
                        id: "c1",
                        role: .assistant,
                        text: "summary",
                        metadataLabel: "compaction · m"
                    ),
                ]
            ) == nil
        )
    }

    @Test func labelForActiveTurn_showsCompactingWhenIdleStatus() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: nil,
                messages: [user("u1")],
                isCompacting: true
            ) == SessionCompactChrome.statusLabel
        )
    }

    private func user(_ id: String) -> SessionChatRow {
        SessionChatRow(id: id, role: .user, text: "Hi", metadataLabel: "")
    }

    private func assistant(
        _ id: String,
        text: String,
        blocks: [SessionChatTimelineBlock] = []
    ) -> SessionChatRow {
        SessionChatRow(id: id, role: .assistant, text: text, metadataLabel: "", blocks: blocks)
    }
}
