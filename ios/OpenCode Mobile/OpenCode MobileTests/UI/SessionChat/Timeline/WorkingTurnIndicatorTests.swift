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

    @Test func resolveFooter_freshIdleStaysUnmounted() {
        let resolved = resolve(activeLabel: nil, previous: .unmounted, hold: nil)
        #expect(resolved.chrome == .unmounted)
        #expect(resolved.hold == nil)
        #expect(resolved.wakeAfterMs == nil)
    }

    @Test func resolveFooter_activeWorkingExpandedCancelsHold() {
        let resolved = resolve(
            activeLabel: "Working",
            previous: fadingWorking,
            hold: visibleHold()
        )
        #expect(resolved.chrome == WorkingTurnIndicator.Chrome(label: "Working", collapsed: false, fading: false))
        #expect(resolved.hold == nil)
        #expect(resolved.wakeAfterMs == nil)
    }

    @Test func resolveFooter_activeWorkingAndThinkingCollapsesWithoutFade() {
        let resolved = resolve(activeLabel: "Working", lastIsThinking: true)
        #expect(resolved.chrome == WorkingTurnIndicator.Chrome(label: "Working", collapsed: true, fading: false))
        #expect(resolved.hold == nil)
        #expect(resolved.wakeAfterMs == nil)
    }

    @Test func resolveFooter_visibleWorkingIdleStartsFadeHold() {
        let resolved = resolve(
            activeLabel: nil,
            previous: WorkingTurnIndicator.Chrome(label: "Working", collapsed: false, fading: false),
            nowMs: 10_000
        )
        #expect(resolved.chrome == WorkingTurnIndicator.Chrome(label: "Working", collapsed: false, fading: true))
        #expect(resolved.hold == WorkingTurnIndicator.Hold(label: "Working", startedAtMs: 10_000, fromCollapsed: false))
        #expect(resolved.wakeAfterMs == WorkingTurnIndicator.idleHoldMilliseconds)
    }

    @Test func resolveFooter_visibleHoldAt999StillFading() {
        let resolved = resolve(
            activeLabel: nil,
            previous: fadingWorking,
            hold: visibleHold(startedAtMs: 10_000),
            nowMs: 10_999
        )
        #expect(resolved.chrome == WorkingTurnIndicator.Chrome(label: "Working", collapsed: false, fading: true))
        #expect(resolved.hold == visibleHold(startedAtMs: 10_000))
        #expect(resolved.wakeAfterMs == 1)
    }

    @Test func resolveFooter_visibleHoldAt1000CollapsesAndKeepsLabel() {
        let resolved = resolve(
            activeLabel: nil,
            previous: fadingWorking,
            hold: visibleHold(startedAtMs: 10_000),
            nowMs: 11_000
        )
        #expect(resolved.chrome == WorkingTurnIndicator.Chrome(label: "Working", collapsed: true, fading: false))
        #expect(resolved.hold == visibleHold(startedAtMs: 10_000))
        #expect(resolved.wakeAfterMs == nil)
    }

    @Test func resolveFooter_collapsedThinkingIdleStaysCollapsed() {
        let resolved = resolve(
            activeLabel: nil,
            previous: WorkingTurnIndicator.Chrome(label: "Working", collapsed: true, fading: false),
            nowMs: 10_000
        )
        #expect(resolved.chrome == WorkingTurnIndicator.Chrome(label: "Working", collapsed: true, fading: false))
        #expect(resolved.hold == WorkingTurnIndicator.Hold(label: "Working", startedAtMs: 10_000, fromCollapsed: true))
        #expect(resolved.wakeAfterMs == nil)
    }

    @Test func resolveFooter_newActiveWorkingCancelsVisibleHold() {
        let resolved = resolve(
            activeLabel: "Working",
            previous: fadingWorking,
            hold: visibleHold()
        )
        #expect(resolved.chrome == WorkingTurnIndicator.Chrome(label: "Working", collapsed: false, fading: false))
        #expect(resolved.hold == nil)
        #expect(resolved.wakeAfterMs == nil)
    }

    @Test func resolveFooter_hideImmediatelyUnmountsAnyHold() {
        let resolved = resolve(
            activeLabel: nil,
            hideImmediately: true,
            previous: fadingWorking,
            hold: visibleHold()
        )
        #expect(resolved.chrome == .unmounted)
        #expect(resolved.hold == nil)
        #expect(resolved.wakeAfterMs == nil)
    }

    private var fadingWorking: WorkingTurnIndicator.Chrome {
        WorkingTurnIndicator.Chrome(label: "Working", collapsed: false, fading: true)
    }

    private func visibleHold(startedAtMs: Int64 = 0) -> WorkingTurnIndicator.Hold {
        WorkingTurnIndicator.Hold(label: "Working", startedAtMs: startedAtMs, fromCollapsed: false)
    }

    private func resolve(
        activeLabel: String?,
        lastIsThinking: Bool = false,
        hideImmediately: Bool = false,
        previous: WorkingTurnIndicator.Chrome = .unmounted,
        hold: WorkingTurnIndicator.Hold? = nil,
        nowMs: Int64 = 0
    ) -> WorkingTurnIndicator.Resolution {
        WorkingTurnIndicator.resolveFooter(
            activeLabel: activeLabel,
            lastIsThinking: lastIsThinking,
            hideImmediately: hideImmediately,
            previous: previous,
            hold: hold,
            nowMs: nowMs
        )
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
