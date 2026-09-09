import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionListScrollPinTests {
    @Test func isAtTop_zeroOffset() {
        #expect(SessionListScrollPin.isAtTop(offsetFromTop: 0))
    }

    @Test func isAtTop_smallSlop() {
        #expect(SessionListScrollPin.isAtTop(offsetFromTop: 1))
        #expect(!SessionListScrollPin.isAtTop(offsetFromTop: 2))
    }

    @Test func followingTop_userScrollAwayClearsFollow() {
        let next = SessionListScrollPin.followingTop(
            currentlyFollowing: true,
            isUserScrolling: true,
            isAtTop: false
        )
        #expect(!next)
    }

    @Test func followingTop_contentMutationKeepsFollow() {
        let next = SessionListScrollPin.followingTop(
            currentlyFollowing: true,
            isUserScrolling: false,
            isAtTop: false
        )
        #expect(next)
    }

    @Test func followingTop_settledAtTopEnablesFollow() {
        let next = SessionListScrollPin.followingTop(
            currentlyFollowing: false,
            isUserScrolling: false,
            isAtTop: true
        )
        #expect(next)
    }

    @Test func shouldScrollToTrueTop_followsNewestWhenPinned() {
        #expect(
            SessionListScrollPin.shouldScrollToTrueTop(
                followingTop: true,
                newestIdentityChanged: true,
                forceRevealNewest: false
            )
        )
    }

    @Test func shouldScrollToTrueTop_leavesScrolledPlaceAlone() {
        #expect(
            !SessionListScrollPin.shouldScrollToTrueTop(
                followingTop: false,
                newestIdentityChanged: true,
                forceRevealNewest: false
            )
        )
    }

    @Test func shouldScrollToTrueTop_createForcesReveal() {
        #expect(
            SessionListScrollPin.shouldScrollToTrueTop(
                followingTop: false,
                newestIdentityChanged: false,
                forceRevealNewest: true
            )
        )
    }

    @Test func shouldResetForStaleAbsence_afterTenMinutes() {
        #expect(
            SessionListScrollPin.shouldResetForStaleAbsence(
                lastHiddenAtEpochMs: 0,
                nowEpochMs: SessionListScrollPin.staleAfterMs
            )
        )
    }

    @Test func shouldResetForStaleAbsence_keepsFreshAbsence() {
        #expect(
            !SessionListScrollPin.shouldResetForStaleAbsence(
                lastHiddenAtEpochMs: 0,
                nowEpochMs: SessionListScrollPin.staleAfterMs - 1
            )
        )
    }

    @Test func shouldResetForStaleAbsence_skipsWhenNeverHidden() {
        #expect(
            !SessionListScrollPin.shouldResetForStaleAbsence(
                lastHiddenAtEpochMs: nil,
                nowEpochMs: SessionListScrollPin.staleAfterMs
            )
        )
    }
}
