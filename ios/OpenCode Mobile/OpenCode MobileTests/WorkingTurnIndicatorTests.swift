import Foundation
import Testing
@testable import OpenCode_Mobile

struct WorkingTurnIndicatorTests {
    @Test func labelForActiveTurn_nilWhenIdle() {
        #expect(WorkingTurnIndicator.labelForActiveTurn(statusLabel: nil) == nil)
    }

    @Test func labelForActiveTurn_showsWorkingWhenBusy() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(statusLabel: "Working") == "Working"
        )
    }

    @Test func labelForActiveTurn_showsSendingPercentWhileBodyIsOnTheWire() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                sendProgressPercent: 25
            ) == "Sending 25%"
        )
    }

    @Test func labelForActiveTurn_showsSendingEllipsisAtZero() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                sendProgressPercent: 0
            ) == "Sending…"
        )
    }

    @Test func labelForActiveTurn_compactingWinsOverSending() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                isCompacting: true,
                sendProgressPercent: 40
            ) == SessionCompactChrome.statusLabel
        )
    }

    @Test func labelForActiveTurn_hidesSendingWhenAwaitingUser() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                isAwaitingUser: true,
                sendProgressPercent: 40
            ) == nil
        )
    }

    @Test func labelForActiveTurn_showsRetrying() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(statusLabel: "Retrying") == "Retrying"
        )
    }

    @Test func labelForActiveTurn_hidesWhenAwaitingUser() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                isAwaitingUser: true
            ) == nil
        )
    }

    @Test func labelForActiveTurn_hidesCompactingWhenAwaitingUser() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                isCompacting: true,
                isAwaitingUser: true
            ) == nil
        )
    }

    @Test func labelForActiveTurn_showsCompactingEvenWhenStatusIsWorking() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                isCompacting: true
            ) == SessionCompactChrome.statusLabel
        )
    }

    @Test func labelForActiveTurn_showsCompactingWhenIdleStatus() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: nil,
                isCompacting: true
            ) == SessionCompactChrome.statusLabel
        )
    }

    @Test func labelForActiveTurn_keepsWorkingWhenLastIsThinking() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(statusLabel: "Working") == "Working"
        )
        #expect(
            WorkingTurnIndicator.shouldCollapseReservedRow(
                lastIsThinking: true,
                label: "Working"
            )
        )
    }

    @Test func labelForActiveTurn_keepsCompactingWhenLastIsThinking() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                isCompacting: true
            ) == SessionCompactChrome.statusLabel
        )
        #expect(
            WorkingTurnIndicator.shouldCollapseReservedRow(
                lastIsThinking: true,
                label: SessionCompactChrome.statusLabel
            )
        )
    }

    @Test func shouldCollapseReservedRow_falseWhenNotThinking() {
        #expect(
            WorkingTurnIndicator.shouldCollapseReservedRow(
                lastIsThinking: false,
                label: "Working"
            ) == false
        )
    }

    @Test func shouldCollapseReservedRow_falseWhenLabelMissing() {
        #expect(
            WorkingTurnIndicator.shouldCollapseReservedRow(
                lastIsThinking: true,
                label: nil
            ) == false
        )
    }

    @Test func lastIsThinking_trueWhenLastBlockIsStreamingReasoning() {
        #expect(
            WorkingTurnIndicator.lastIsThinking(messages: [
                row(.reasoning(blockID: "r1", text: "hmm", isStreaming: true, durationMs: nil)),
            ])
        )
    }

    @Test func lastIsThinking_falseWhenLastIsSettledThought() {
        #expect(
            WorkingTurnIndicator.lastIsThinking(messages: [
                row(.reasoning(blockID: "r1", text: "hmm", isStreaming: false, durationMs: nil)),
            ]) == false
        )
    }

    @Test func lastIsThinking_falseWhenThinkingThenText() {
        #expect(
            WorkingTurnIndicator.lastIsThinking(messages: [
                row(
                    .reasoning(blockID: "r1", text: "hmm", isStreaming: true, durationMs: nil),
                    .text(blockID: "t1", text: "Hello", renderedMarkdown: nil)
                ),
            ]) == false
        )
    }

    @Test func lastIsThinking_falseWhenEmpty() {
        #expect(WorkingTurnIndicator.lastIsThinking(messages: []) == false)
    }

    private func row(_ blocks: SessionChatTimelineBlock...) -> SessionChatRow {
        SessionChatRow(
            id: "m1",
            role: .assistant,
            text: "",
            metadataLabel: "",
            blocks: blocks
        )
    }
}
