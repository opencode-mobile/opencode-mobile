import CoreGraphics
import Foundation
import Testing
@testable import OpenCode_Mobile

struct OpenCodeSpinnerMorphTests {
    private let tolerance = 0.001

    private func near(_ lhs: CGPoint, _ rhs: CGPoint) -> Bool {
        abs(lhs.x - rhs.x) < tolerance && abs(lhs.y - rhs.y) < tolerance
    }

    @Test func spinnerSweepBreathesBetweenItsLimits() {
        let tau = 2 * Double.pi
        #expect(abs(OpenCodeSpinnerGeometry.pose(atSeconds: 0).sweep - 0.28 * tau) < tolerance)
        #expect(abs(OpenCodeSpinnerGeometry.pose(atSeconds: 0.8).sweep - 0.72 * tau) < tolerance)
        for step in 0 ..< 160 {
            let sweep = OpenCodeSpinnerGeometry.pose(atSeconds: Double(step) / 100).sweep / tau
            #expect(sweep >= 0.28 - tolerance && sweep <= 0.72 + tolerance)
        }
    }

    @Test func spinnerPoseIsAFunctionOfTheClock() {
        #expect(OpenCodeSpinnerGeometry.pose(atSeconds: 12.34) == OpenCodeSpinnerGeometry.pose(atSeconds: 12.34))
    }

    @Test func checkIsSampledWithItsCornerExact() {
        let points = OpenCodeCheckMorph.sampledCheck()
        #expect(points.count == OpenCodeCheckMorph.pointCount + 1)
        #expect(near(points[0], OpenCodeCheckMorph.checkLine[0]))
        #expect(points.contains { near($0, OpenCodeCheckMorph.checkLine[1]) })
        #expect(near(points[OpenCodeCheckMorph.pointCount], OpenCodeCheckMorph.checkLine[2]))
    }

    @Test func morphStartsOnTheLiveArcAndEndsOnTheCheck() {
        let pose = OpenCodeSpinnerPose(start: 1.1, sweep: 2.4)
        let morph = OpenCodeCheckMorph(pose: pose)
        let first = morph.points(atMs: 0)
        for (index, point) in first.enumerated() {
            let angle = morph.angles[index]
            #expect(near(point, CGPoint(x: 8 + 6 * cos(angle), y: 8 + 6 * sin(angle))))
        }
        let last = morph.points(atMs: OpenCodeCheckMorph.durationMs)
        for (point, target) in zip(last, OpenCodeCheckMorph.sampledCheck()) {
            #expect(near(point, target))
        }
    }

    @Test func strokeColorAndPopRunToTheirEnds() {
        #expect(abs(OpenCodeCheckMorph.strokeWidth(atMs: 0) - 1.3) < tolerance)
        #expect(abs(OpenCodeCheckMorph.strokeWidth(atMs: 540) - 1) < tolerance)
        #expect(OpenCodeCheckMorph.colorProgress(atMs: 0) == 0)
        #expect(OpenCodeCheckMorph.colorProgress(atMs: 540) == 1)
        #expect(OpenCodeCheckMorph.scale(atMs: 429) == 1)
        #expect(OpenCodeCheckMorph.scale(atMs: 500) > 1)
        #expect(OpenCodeCheckMorph.scale(atMs: OpenCodeCheckMorph.totalMs) == 1)
    }
}

struct SessionChatTurnStatusSlotTests {
    private let now = Date(timeIntervalSince1970: 1_000)

    private func slot(
        label: String? = "Working",
        collapsed: Bool = false,
        fading: Bool = false,
        awaiting: Bool = false,
        items: [SessionChatActivityItem] = [],
        keyboard: Bool = false,
        away: Bool = false
    ) -> SessionChatTurnStatusSlot? {
        SessionChatPillSlots.turnStatus(
            label: label,
            collapsed: collapsed,
            fading: fading,
            isAwaitingUser: awaiting,
            items: items,
            keyboardVisible: keyboard,
            awayFromEnd: away,
            now: now
        )
    }

    @Test func aloneIsAFullPill() {
        #expect(slot() == SessionChatTurnStatusSlot(phase: .working(label: "Working"), compact: false, leaving: false))
        #expect(slot(label: WorkingTurnIndicator.idleLabel)?.phase == .done)
        #expect(slot(label: "Compacting…")?.phase == .working(label: "Compacting…"))
    }

    @Test func foldsBesideAnotherPillOrAwayFromTheEnd() {
        #expect(slot(items: [.queued(count: 1)])?.compact == true)
        #expect(slot(away: true)?.compact == true)
        #expect(slot(keyboard: true)?.compact == true)
    }

    @Test func aFadedFinishedItemDoesNotFoldIt() {
        let faded = SessionChatActivityItem.finished(id: "a", label: "a", finishedAt: now.addingTimeInterval(-10))
        #expect(slot(items: [faded])?.compact == false)
    }

    @Test func hiddenForRequestsAndFailures() {
        #expect(slot(awaiting: true) == nil)
        #expect(slot(items: [.reviewPermission]) == nil)
        #expect(slot(items: [.answerQuestion]) == nil)
        #expect(slot(items: [.requestFailed(rowID: "r", blockID: "b")]) == nil)
        #expect(slot(label: nil) == nil)
        #expect(slot(collapsed: true) == nil)
    }

    @Test func neverCountsTowardOverflow() {
        let items: [SessionChatActivityItem] = [.queued(count: 1)]
        #expect(slot(items: items) != nil)
        let activity = SessionChatPillSlots.activity(items: items, keyboardVisible: false, now: now)
        #expect(activity.overflow.isEmpty)
        #expect(!activity.showsOverflowButton)
    }

    @Test func leavingReleasesTheRow() {
        #expect(slot()?.reservesRow == true)
        #expect(slot(fading: true)?.reservesRow == false)
    }

    @Test func doneHoldsThenFadesThenCollapses() {
        func resolve(at nowMs: Int64) -> WorkingTurnIndicator.Resolution {
            WorkingTurnIndicator.resolveFooter(
                activeLabel: nil,
                lastIsThinking: false,
                hideImmediately: false,
                previous: WorkingTurnIndicator.Chrome(label: "Working", collapsed: false, fading: false),
                hold: WorkingTurnIndicator.Hold(startedAtMs: 0),
                nowMs: nowMs,
                holdMilliseconds: WorkingTurnIndicator.turnPillHoldMilliseconds,
                fadeMilliseconds: WorkingTurnIndicator.turnPillFadeMilliseconds
            )
        }
        #expect(resolve(at: 2_999).chrome.fading == false)
        #expect(resolve(at: 2_999).wakeAfterMs == 1)
        #expect(resolve(at: 3_000).chrome.fading == true)
        #expect(resolve(at: 3_000).wakeAfterMs == 240)
        #expect(resolve(at: 3_240).chrome.collapsed == true)
        #expect(resolve(at: 3_240).wakeAfterMs == nil)
    }

    @Test func otherStylesKeepTheFiveSecondIdle() {
        let resolution = WorkingTurnIndicator.resolveFooter(
            activeLabel: nil,
            lastIsThinking: false,
            hideImmediately: false,
            previous: WorkingTurnIndicator.Chrome(label: "Working", collapsed: false, fading: false),
            hold: WorkingTurnIndicator.Hold(startedAtMs: 0),
            nowMs: 4_999
        )
        #expect(resolution.chrome == WorkingTurnIndicator.Chrome(label: "Idle", collapsed: false, fading: false))
        #expect(resolution.wakeAfterMs == 1)
    }
}
