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
    private let waitingPill = SessionChatBackgroundWorkPill(
        label: "general needs input · 1 running",
        needsInput: true,
        accessibilityLabel: "general needs input, 1 subagent running",
        askingLabel: "general needs input",
        stillRunning: .init(label: "1 subagent", accessibilityLabel: "1 subagent running")
    )
    private let failure = SessionChatActivityItem.requestFailed(rowID: "r", blockID: "b")

    private func slot(
        label: String? = "Working",
        fading: Bool = false,
        awaiting: Bool = false,
        items: [SessionChatActivityItem] = [],
        keyboard: Bool = false,
        away: Bool = false
    ) -> SessionChatTurnStatusSlot? {
        SessionChatPillSlots.turnStatus(
            label: label,
            fading: fading,
            isAwaitingUser: awaiting,
            items: items,
            keyboardVisible: keyboard,
            awayFromEnd: away
        )
    }

    @Test func aloneIsAFullPill() {
        #expect(slot() == SessionChatTurnStatusSlot(phase: .working(label: "Working"), compact: false, leaving: false))
        #expect(slot(label: WorkingTurnIndicator.idleLabel)?.phase == .done)
        #expect(slot(label: "Compacting…")?.phase == .working(label: "Compacting…"))
        #expect(slot(label: nil) == nil)
    }

    @Test func priorityLadder() {
        let all: [SessionChatActivityItem] = [.subagents(waitingPill), .answerQuestion, .reviewPermission, failure]
        #expect(slot(awaiting: true, items: all)?.phase == .failed(label: "Request failed"))
        let noFailure = Array(all.dropLast())
        #expect(slot(awaiting: true, items: noFailure)?.phase == .attention(label: "Review permission", mark: .arrowUp))
        let question = [SessionChatActivityItem.subagents(waitingPill), .answerQuestion]
        #expect(slot(awaiting: true, items: question)?.phase == .attention(label: "Answer question", mark: .arrowUp))
        let subagent = [SessionChatActivityItem.subagents(waitingPill)]
        #expect(slot(awaiting: true, items: subagent)?.phase == .attention(label: "Waiting for you", mark: .pulsingDot))
        #expect(slot(items: subagent)?.phase == .attention(label: "general needs input", mark: .pulsingDot))
        #expect(slot(label: "Retrying", items: [.queued(count: 1)])?.phase == .working(label: "Retrying"))
    }

    @Test func blockersShowWithNoTurnMounted() {
        #expect(slot(label: nil, items: [failure])?.phase == .failed(label: "Request failed"))
        #expect(slot(label: nil, items: [.reviewPermission])?.tapItem == .reviewPermission)
    }

    @Test func onlyTheContractStatesAreTappableOrClosable() {
        #expect(slot(items: [failure])?.tapItem == failure)
        #expect(slot(items: [failure])?.isClosable == true)
        #expect(slot(items: [.reviewPermission])?.tapItem == .reviewPermission)
        #expect(slot(items: [.reviewPermission])?.isClosable == false)
        #expect(slot(items: [.subagents(waitingPill)])?.tapItem == .subagents(waitingPill))
        #expect(slot(awaiting: true)?.tapItem == nil)
        #expect(slot()?.tapItem == nil)
        #expect(slot(label: WorkingTurnIndicator.idleLabel)?.tapItem == nil)
    }

    @Test func accessibilityReusesTheActivityStrings() {
        #expect(slot(items: [failure])?.accessibilityLabel == "Request failed. Show in chat")
        #expect(slot(items: [.reviewPermission])?.accessibilityLabel == "Review permission request")
        #expect(slot(items: [.subagents(waitingPill)])?.accessibilityLabel == "general needs input. Open request")
        #expect(slot(awaiting: true)?.accessibilityLabel == "Waiting for you")
    }

    @Test func workingAndDoneFoldAwayOrWithTheKeyboardButNotBesideASecondary() {
        #expect(slot(items: [.queued(count: 1)])?.compact == false)
        #expect(slot(away: true)?.compact == true)
        #expect(slot(keyboard: true)?.compact == true)
        #expect(slot(label: WorkingTurnIndicator.idleLabel, away: true)?.compact == true)
    }

    @Test func attentionAndFailedNeverFold() {
        for items in [[failure], [.reviewPermission], [.subagents(waitingPill)]] as [[SessionChatActivityItem]] {
            #expect(slot(items: items, keyboard: true, away: true)?.compact == false)
        }
        #expect(slot(awaiting: true, keyboard: true, away: true)?.compact == false)
    }

    @Test func mergedItemsLeaveTheSecondaryRow() {
        let activity = SessionChatPillSlots.activity(
            items: [.reviewPermission, .answerQuestion, failure, .subagents(waitingPill), .queued(count: 1)],
            keyboardVisible: false
        )
        let remainder = SessionChatBackgroundWorkPill(
            label: "1 subagent", needsInput: false, accessibilityLabel: "1 subagent running"
        )
        #expect(activity.primary == .subagents(remainder))
        #expect(activity.overflow == [.queued(count: 1)])
        #expect(activity.allItems == [.subagents(remainder), .queued(count: 1)])
    }

    @Test func aWaitingSubagentWithNothingRunningLeavesNoSecondary() {
        let alone = SessionChatBackgroundWorkPill(
            label: "general needs input", needsInput: true, accessibilityLabel: "general needs input",
            askingLabel: "general needs input"
        )
        #expect(SessionChatPillSlots.activity(items: [.subagents(alone)], keyboardVisible: false) == .empty)
    }

    @Test func reserveStaysThroughCardAndWaitingChanges() {
        let phases = [
            slot(), slot(label: "Compacting…"), slot(awaiting: true), slot(items: [.reviewPermission]),
            slot(items: [.subagents(waitingPill)]), slot(), slot(label: WorkingTurnIndicator.idleLabel),
        ]
        #expect(phases.allSatisfy { $0?.reservesRow == true })
        #expect(slot(label: WorkingTurnIndicator.idleLabel, fading: true)?.reservesRow == false)
    }

    @Test func reserveDoesNotChangeWhileAwayFromTheEnd() {
        #expect(SessionChatPillSlots.reservesPillRow(wanted: false, held: true, awayFromEnd: true))
        #expect(!SessionChatPillSlots.reservesPillRow(wanted: true, held: false, awayFromEnd: true))
        #expect(SessionChatPillSlots.reservesPillRow(wanted: true, held: false, awayFromEnd: false))
        #expect(!SessionChatPillSlots.reservesPillRow(wanted: false, held: true, awayFromEnd: false))
    }

    // MARK: Fold

    private let working = OpenCodeTurnStatusPillPhase.working(label: "Working")
    private let attention = OpenCodeTurnStatusPillPhase.attention(label: "Review permission", mark: .arrowUp)

    @Test func foldNothingWhenTheRowFits() {
        // 100+8 + 80+8 + 40+8 + 50 = 294.
        let widths = SessionChatPillRowWidths(available: 294, turn: 100, secondary: 80, overflow: 40, trailing: 50)
        #expect(SessionChatPillSlots.pillRowFold(widths: widths, turnCanFold: true) == SessionChatPillRowFold())
    }

    @Test func foldWorkingFirstWhenOnePointShort() {
        let widths = SessionChatPillRowWidths(available: 293, turn: 100, secondary: 80, overflow: 40, trailing: 50)
        #expect(SessionChatPillSlots.pillRowFold(widths: widths, turnCanFold: true) == SessionChatPillRowFold(turnCompact: true))
        #expect(SessionChatPillSlots.pillRowFold(widths: widths, turnCanFold: OpenCodeTurnStatusPillPhase.done.folds).turnCompact)
    }

    @Test func foldTheSecondaryToo_whenAFoldedWorkingStillDoesNotFit() {
        let widths = SessionChatPillRowWidths(available: 150, turn: 100, secondary: 120)
        #expect(
            SessionChatPillSlots.pillRowFold(widths: widths, turnCanFold: true)
                == SessionChatPillRowFold(turnCompact: true, secondaryCompact: true)
        )
    }

    @Test func foldTheSecondaryForAttentionAndFailed() {
        let widths = SessionChatPillRowWidths(available: 210, turn: 150, secondary: 80)
        for phase in [attention, .failed(label: "Request failed")] {
            let fold = SessionChatPillSlots.pillRowFold(widths: widths, turnCanFold: phase.folds)
            #expect(!fold.turnCompact)
            #expect(fold.secondaryCompact)
            #expect(fold.turnMaxWidth == nil)
        }
    }

    @Test func aPinnedPillTruncatesWhenEvenTheFoldedSecondaryDoesNotFit() {
        // 300 + 8 + 36 = 344, 144 over.
        let widths = SessionChatPillRowWidths(available: 200, turn: 300, secondary: 80)
        let fold = SessionChatPillSlots.pillRowFold(widths: widths, turnCanFold: attention.folds)
        #expect(!fold.turnCompact)
        #expect(fold.secondaryCompact)
        #expect(fold.turnMaxWidth == 156)
    }

    @Test func foldWaitsForAMeasuredRow() {
        let widths = SessionChatPillRowWidths(available: 0, turn: 100, secondary: 80)
        #expect(SessionChatPillSlots.pillRowFold(widths: widths, turnCanFold: true) == SessionChatPillRowFold())
    }

    @Test func foldsOnlyWorkingAndDonePhases() {
        #expect(working.folds)
        #expect(OpenCodeTurnStatusPillPhase.done.folds)
        #expect(!attention.folds)
        #expect(!OpenCodeTurnStatusPillPhase.failed(label: "Request failed").folds)
    }
}
