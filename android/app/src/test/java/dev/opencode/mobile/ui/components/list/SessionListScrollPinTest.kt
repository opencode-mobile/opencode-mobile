package dev.opencode.mobile.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListScrollPinTest {
    @Test
    fun isAtTop_zeroIndexAndOffset() {
        assertTrue(SessionListScrollPin.isAtTop(0, 0))
    }

    @Test
    fun isAtTop_rejectsScrolledOffset() {
        assertFalse(SessionListScrollPin.isAtTop(0, 2))
        assertFalse(SessionListScrollPin.isAtTop(1, 0))
    }

    @Test
    fun followingTop_userScrollAwayClearsFollow() {
        assertFalse(
            SessionListScrollPin.followingTop(
                currentlyFollowing = true,
                isUserScrolling = true,
                isAtTop = false,
            ),
        )
    }

    @Test
    fun followingTop_contentMutationKeepsFollow() {
        assertTrue(
            SessionListScrollPin.followingTop(
                currentlyFollowing = true,
                isUserScrolling = false,
                isAtTop = false,
            ),
        )
    }

    @Test
    fun followingTop_settledAtTopEnablesFollow() {
        assertTrue(
            SessionListScrollPin.followingTop(
                currentlyFollowing = false,
                isUserScrolling = false,
                isAtTop = true,
            ),
        )
    }

    @Test
    fun shouldScrollToTrueTop_followsNewestWhenPinned() {
        assertTrue(
            SessionListScrollPin.shouldScrollToTrueTop(
                followingTop = true,
                newestIdentityChanged = true,
                forceRevealNewest = false,
            ),
        )
    }

    @Test
    fun shouldScrollToTrueTop_leavesScrolledPlaceAlone() {
        assertFalse(
            SessionListScrollPin.shouldScrollToTrueTop(
                followingTop = false,
                newestIdentityChanged = true,
                forceRevealNewest = false,
            ),
        )
    }

    @Test
    fun shouldScrollToTrueTop_createForcesReveal() {
        assertTrue(
            SessionListScrollPin.shouldScrollToTrueTop(
                followingTop = false,
                newestIdentityChanged = false,
                forceRevealNewest = true,
            ),
        )
    }

    @Test
    fun shouldResetForStaleAbsence_afterTenMinutes() {
        assertTrue(
            SessionListScrollPin.shouldResetForStaleAbsence(
                lastHiddenAtEpochMs = 0L,
                nowEpochMs = SessionListScrollPin.STALE_AFTER_MS,
            ),
        )
    }

    @Test
    fun shouldResetForStaleAbsence_keepsFreshAbsence() {
        assertFalse(
            SessionListScrollPin.shouldResetForStaleAbsence(
                lastHiddenAtEpochMs = 0L,
                nowEpochMs = SessionListScrollPin.STALE_AFTER_MS - 1,
            ),
        )
    }

    @Test
    fun shouldResetForStaleAbsence_skipsWhenNeverHidden() {
        assertFalse(
            SessionListScrollPin.shouldResetForStaleAbsence(
                lastHiddenAtEpochMs = null,
                nowEpochMs = SessionListScrollPin.STALE_AFTER_MS,
            ),
        )
    }
}
