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
}
