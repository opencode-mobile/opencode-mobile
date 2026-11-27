import Foundation
import Testing
@testable import OpenCode_Mobile

struct WorkingTurnIndicatorTests {
    @Test func labelForActiveTurn_nilWhenIdle() {
        #expect(WorkingTurnIndicator.labelForActiveTurn(statusLabel: nil) == nil)
    }

    @Test func labelForActiveTurn_showsWorkingWhenBusy() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(statusLabel: .working) == .working
        )
    }

    @Test func labelForActiveTurn_showsSendingPercentWhileBodyIsOnTheWire() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: .working,
                sendProgressPercent: 25
            ) == .text("Sending 25%")
        )
    }

    @Test func labelForActiveTurn_showsSendingEllipsisAtZero() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: .working,
                sendProgressPercent: 0
            ) == .text("Sending…")
        )
    }

    @Test func labelForActiveTurn_compactingWinsOverSending() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: .working,
                isCompacting: true,
                sendProgressPercent: 40
            ) == .text(SessionCompactChrome.statusLabel)
        )
    }

    @Test func labelForActiveTurn_showsRetrying() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(statusLabel: .text("Retrying")) == .text("Retrying")
        )
    }

    @Test func labelForActiveTurn_showsCompactingEvenWhenStatusIsWorking() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: .working,
                isCompacting: true
            ) == .text(SessionCompactChrome.statusLabel)
        )
    }

    @Test func labelForActiveTurn_showsCompactingWhenIdleStatus() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: nil,
                isCompacting: true
            ) == .text(SessionCompactChrome.statusLabel)
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
            activeLabel: .working,
            previous: fadingWorking,
            hold: visibleHold()
        )
        #expect(resolved.chrome == WorkingTurnIndicator.Chrome(label: .working, fading: false))
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

    @Test func labelForActiveTurn_pendingRequestKeepsTheTurnWithNoStatus() {
        #expect(WorkingTurnIndicator.labelForActiveTurn(statusLabel: nil, isAwaitingUser: true) == .working)
        #expect(WorkingTurnIndicator.labelForActiveTurn(statusLabel: .text("Retrying"), isAwaitingUser: true) == .text("Retrying"))
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(statusLabel: nil, isCompacting: true, isAwaitingUser: true)
                == .text(SessionCompactChrome.statusLabel)
        )
    }

    @Test func showsIdleMark_onlyForTheDoneMarker() {
        #expect(WorkingTurnIndicator.showsIdleMark(label: WorkingTurnIndicator.idleLabel))
        #expect(WorkingTurnIndicator.showsIdleMark(label: .working) == false)
        #expect(WorkingTurnIndicator.showsIdleMark(label: .text(SessionCompactChrome.statusLabel)) == false)
    }

    @Test func settle_resumeShowsNoDone() {
        let working = WorkingTurnIndicator.Chrome(label: .working, fading: false)
        let settling = resolve(activeLabel: nil, previous: working, nowMs: 0)
        let resumed = resolve(activeLabel: .working, previous: settling.chrome, hold: settling.hold, nowMs: 499)
        #expect(resumed.chrome == working)
        #expect(resumed.hold == nil)
        #expect(resumed.wakeAfterMs == nil)
    }

    @Test func labelForActiveTurn_retryingWinsOverSending() {
        #expect(
            WorkingTurnIndicator.labelForActiveTurn(
                statusLabel: .text("Retrying"),
                isRetrying: true,
                sendProgressPercent: 40
            ) == .text("Retrying")
        )
    }

    @Test func settle_keepsTheLastLabelThenDoneHoldsThenFades() {
        let working = WorkingTurnIndicator.Chrome(label: .text("Compacting…"), fading: false)
        let started = resolve(activeLabel: nil, previous: working, nowMs: 10_000)
        #expect(started.chrome == working)
        #expect(started.wakeAfterMs == 500)
        let hold = started.hold

        func at(_ ms: Int64) -> WorkingTurnIndicator.Resolution {
            resolve(activeLabel: nil, previous: working, hold: hold, nowMs: 10_000 + ms)
        }
        #expect(at(499).chrome.label == .text("Compacting…"))
        #expect(at(499).wakeAfterMs == 1)
        #expect(at(500).chrome == WorkingTurnIndicator.Chrome(label: .idle, fading: false))
        #expect(at(500).wakeAfterMs == 3_000)
        #expect(at(3_499).chrome.fading == false)
        #expect(at(3_500).chrome == WorkingTurnIndicator.Chrome(label: .idle, fading: true))
        #expect(at(3_500).wakeAfterMs == 240)
        #expect(at(3_740).chrome == .unmounted)
        #expect(at(3_740).hold == nil)
        #expect(at(3_740).wakeAfterMs == nil)
    }

    @Test func quietEnd_skipsDoneAndFadesWithTheLastLabel() {
        let working = WorkingTurnIndicator.Chrome(label: .working, fading: false)
        let hold = resolve(activeLabel: nil, previous: working, nowMs: 0).hold
        func at(_ ms: Int64, hold: WorkingTurnIndicator.Hold?) -> WorkingTurnIndicator.Resolution {
            WorkingTurnIndicator.resolveFooter(
                activeLabel: nil,
                ending: .quiet,
                hideImmediately: false,
                previous: working,
                hold: hold,
                nowMs: ms
            )
        }
        #expect(at(499, hold: hold).chrome == working)
        let fading = at(500, hold: hold)
        #expect(fading.chrome == WorkingTurnIndicator.Chrome(label: .working, fading: true))
        #expect(fading.wakeAfterMs == 240)
        #expect(at(740, hold: fading.hold).chrome == .unmounted)
    }

    @Test func lateStopSignal_fadesDoneImmediately() {
        let done = WorkingTurnIndicator.Chrome(label: .idle, fading: false)
        let hold = WorkingTurnIndicator.Hold(startedAtMs: 0, label: .working)
        let late = WorkingTurnIndicator.resolveFooter(
            activeLabel: nil,
            ending: .quiet,
            hideImmediately: false,
            previous: done,
            hold: hold,
            nowMs: 1_500
        )
        #expect(late.chrome == WorkingTurnIndicator.Chrome(label: .idle, fading: true))
        #expect(late.hold?.quietFadeAtMs == 1_500)
        #expect(late.wakeAfterMs == 240)
    }

    @Test func failedEnd_unmountsAtOnce() {
        let resolved = WorkingTurnIndicator.resolveFooter(
            activeLabel: nil,
            ending: .failed,
            hideImmediately: false,
            previous: WorkingTurnIndicator.Chrome(label: .working, fading: false),
            hold: nil,
            nowMs: 0
        )
        #expect(resolved.chrome == .unmounted)
        #expect(resolved.hold == nil)
    }

    @Test func connectionLost_keepsTheLastStateWithNoHold() {
        let working = WorkingTurnIndicator.Chrome(label: .text("Retrying"), fading: false)
        let resolved = WorkingTurnIndicator.resolveFooter(
            activeLabel: nil,
            connectionLost: true,
            hideImmediately: false,
            previous: working,
            hold: nil,
            nowMs: 0
        )
        #expect(resolved.chrome == working)
        #expect(resolved.hold == nil)
        #expect(resolved.wakeAfterMs == nil)
    }

    @Test func lastTurnInterrupted_readsTheNewestAssistantReply() {
        func message(_ id: String, _ role: MessageRole, stop: MessageStopReason? = nil, pickup: Bool = false) -> Message {
            var message = Message(
                id: id, role: role, text: "", model: nil, mode: nil, sentAtEpochMs: 1, isWaitingForPickup: pickup
            )
            message.stopReason = stop
            return message
        }
        let stopped = message("a", .assistant, stop: .interrupted)
        #expect(WorkingTurnIndicator.lastTurnInterrupted([message("u", .user), stopped]))
        #expect(WorkingTurnIndicator.lastTurnInterrupted([stopped, message("q", .user, pickup: true)]))
        #expect(!WorkingTurnIndicator.lastTurnInterrupted([stopped, message("u", .user)]))
        #expect(!WorkingTurnIndicator.lastTurnInterrupted([stopped, message("b", .assistant)]))
        #expect(!WorkingTurnIndicator.lastTurnInterrupted([message("a", .assistant, stop: .length)]))
        #expect(!WorkingTurnIndicator.lastTurnInterrupted([]))
    }

    private var fadingWorking: WorkingTurnIndicator.Chrome {
        WorkingTurnIndicator.Chrome(label: .working, fading: true)
    }

    private func visibleHold(startedAtMs: Int64 = 0) -> WorkingTurnIndicator.Hold {
        WorkingTurnIndicator.Hold(startedAtMs: startedAtMs, label: .working)
    }

    private func resolve(
        activeLabel: SessionTurnLabel?,
        hideImmediately: Bool = false,
        previous: WorkingTurnIndicator.Chrome = .unmounted,
        hold: WorkingTurnIndicator.Hold? = nil,
        nowMs: Int64 = 0
    ) -> WorkingTurnIndicator.Resolution {
        WorkingTurnIndicator.resolveFooter(
            activeLabel: activeLabel,
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
