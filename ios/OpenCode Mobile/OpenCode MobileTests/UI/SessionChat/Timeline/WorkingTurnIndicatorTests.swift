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

    @Test func labelForActiveTurn_ignoresThinkingWhenIdleStatus() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: nil,
                isThinking: true
            ) == nil
        )
    }

    @Test func labelForActiveTurn_showsThinkingInPlace() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                isThinking: true
            ) == WorkingTurnIndicator.thinkingLabel
        )
        #expect(
            WorkingTurnIndicator.shouldCollapseReservedRow(
                lastIsThinking: true,
                label: WorkingTurnIndicator.thinkingLabel
            ) == false
        )
    }

    @Test func labelForActiveTurn_keepsCompactingWhenLastIsThinking() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: "Working",
                isCompacting: true,
                isThinking: true
            ) == SessionCompactChrome.statusLabel
        )
        #expect(
            WorkingTurnIndicator.shouldCollapseReservedRow(
                lastIsThinking: true,
                label: SessionCompactChrome.statusLabel
            ) == false
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

    @Test func mountedLabel_dropsCollapsedFooter() {
        #expect(
            WorkingTurnIndicator.mountedLabel(label: "Idle", collapsed: true) == nil
        )
        #expect(
            WorkingTurnIndicator.mountedLabel(label: "Working", collapsed: false) == "Working"
        )
        #expect(
            WorkingTurnIndicator.mountedLabel(label: nil, collapsed: false) == nil
        )
    }

    @Test func showsIdleMark_onlyForIdleLabel() {
        #expect(WorkingTurnIndicator.showsIdleMark(label: WorkingTurnIndicator.idleLabel))
        #expect(WorkingTurnIndicator.showsIdleMark(label: "Working") == false)
        #expect(WorkingTurnIndicator.showsIdleMark(label: WorkingTurnIndicator.thinkingLabel) == false)
        #expect(WorkingTurnIndicator.showsIdleMark(label: SessionCompactChrome.statusLabel) == false)
    }

    @Test func workingRowHeightEstimate_matchesFixedRowHeight() {
        let expanded = SessionChatRow(
            id: SessionChatStatusChrome.workingRowID,
            role: .assistant,
            text: "Working",
            metadataLabel: ""
        )
        #expect(expanded.timelineRowHeightEstimate == WorkingTurnIndicator.rowHeight)

        let collapsed = SessionChatRow(
            id: SessionChatStatusChrome.workingRowID,
            role: .assistant,
            text: "Working",
            metadataLabel: "",
            isLayoutCollapsed: true
        )
        #expect(collapsed.timelineRowHeightEstimate == 0)
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

    @Test func lastIsThinking_trueWhenStreamingReasoningThenText() {
        #expect(
            WorkingTurnIndicator.lastIsThinking(messages: [
                row(
                    .reasoning(blockID: "r1", text: "hmm", isStreaming: true, durationMs: nil),
                    .text(blockID: "t1", text: "Hello", renderedMarkdown: nil)
                ),
            ])
        )
    }

    @Test func lastIsThinking_falseWhenEmpty() {
        #expect(WorkingTurnIndicator.lastIsThinking(messages: []) == false)
    }

    @Test func isStreamingThinking_trueForEmptyStreamingReasoning() {
        #expect(
            WorkingTurnIndicator.isStreamingThinking(messages: [
                Message(
                    id: "a1",
                    role: .assistant,
                    text: "",
                    parts: [
                        .reasoning(ReasoningPart(id: "r1", text: "", isStreaming: true)),
                    ],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ])
        )
    }

    @Test func isStreamingThinking_falseWhenSettled() {
        #expect(
            WorkingTurnIndicator.isStreamingThinking(messages: [
                Message(
                    id: "a1",
                    role: .assistant,
                    text: "",
                    parts: [
                        .reasoning(ReasoningPart(id: "r1", text: "done", isStreaming: false)),
                    ],
                    model: nil,
                    mode: nil,
                    sentAtEpochMs: nil
                ),
            ]) == false
        )
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

    @Test func resolveFooter_activeThinkingStaysExpanded() {
        let resolved = resolve(activeLabel: WorkingTurnIndicator.thinkingLabel, lastIsThinking: true)
        #expect(
            resolved.chrome == WorkingTurnIndicator.Chrome(
                label: WorkingTurnIndicator.thinkingLabel,
                collapsed: false,
                fading: false
            )
        )
        #expect(resolved.hold == nil)
        #expect(resolved.wakeAfterMs == nil)
    }

    @Test func resolveFooter_visibleWorkingIdleShowsIdle() {
        let resolved = resolve(
            activeLabel: nil,
            previous: WorkingTurnIndicator.Chrome(label: "Working", collapsed: false, fading: false),
            nowMs: 10_000
        )
        #expect(resolved.chrome == WorkingTurnIndicator.Chrome(label: WorkingTurnIndicator.idleLabel, collapsed: false, fading: false))
        #expect(resolved.hold == WorkingTurnIndicator.Hold(startedAtMs: 10_000))
        #expect(resolved.wakeAfterMs == WorkingTurnIndicator.idleHoldMilliseconds)
    }

    @Test func resolveFooter_visibleHoldBeforeDeadlineStillIdle() {
        let resolved = resolve(
            activeLabel: nil,
            previous: fadingWorking,
            hold: visibleHold(startedAtMs: 10_000),
            nowMs: 14_999
        )
        #expect(resolved.chrome == WorkingTurnIndicator.Chrome(label: WorkingTurnIndicator.idleLabel, collapsed: false, fading: false))
        #expect(resolved.hold == visibleHold(startedAtMs: 10_000))
        #expect(resolved.wakeAfterMs == 1)
    }

    @Test func resolveFooter_visibleHoldAtDeadlineCollapsesIdle() {
        let resolved = resolve(
            activeLabel: nil,
            previous: fadingWorking,
            hold: visibleHold(startedAtMs: 10_000),
            nowMs: 15_000
        )
        #expect(resolved.chrome == WorkingTurnIndicator.Chrome(label: WorkingTurnIndicator.idleLabel, collapsed: true, fading: false))
        #expect(resolved.hold == visibleHold(startedAtMs: 10_000))
        #expect(resolved.wakeAfterMs == nil)
    }

    @Test func resolveFooter_collapsedThinkingIdleShowsIdle() {
        let resolved = resolve(
            activeLabel: nil,
            previous: WorkingTurnIndicator.Chrome(label: "Working", collapsed: true, fading: false),
            nowMs: 10_000
        )
        #expect(resolved.chrome == WorkingTurnIndicator.Chrome(label: WorkingTurnIndicator.idleLabel, collapsed: false, fading: false))
        #expect(resolved.hold == WorkingTurnIndicator.Hold(startedAtMs: 10_000))
        #expect(resolved.wakeAfterMs == WorkingTurnIndicator.idleHoldMilliseconds)
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
        WorkingTurnIndicator.Hold(startedAtMs: startedAtMs)
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
