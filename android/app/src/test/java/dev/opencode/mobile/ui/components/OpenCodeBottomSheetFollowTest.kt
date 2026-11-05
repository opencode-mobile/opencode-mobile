package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeBottomSheetFollowTest {
    private val threshold = 24f

    private fun following(): BottomSheetFollow = BottomSheetFollow().apply { paneChanged(follows = true, hasTarget = false) }

    @Test
    fun liveListOpensAtItsBottom() {
        val follow = following()

        assertTrue(follow.parked)
        assertEquals(BottomSheetFollowAction.Bottom, follow.contentChanged(follows = true))
    }

    @Test
    fun settledListOpensAtItsTop() {
        val follow = BottomSheetFollow().apply { paneChanged(follows = false, hasTarget = false) }

        assertFalse(follow.parked)
        assertEquals(BottomSheetFollowAction.None, follow.contentChanged(follows = false))
    }

    @Test
    fun newStepsFollowOnlyWhileParkedAtTheBottom() {
        val follow = following()

        follow.offsetChanged(distanceFromBottom = 10f, threshold = threshold)
        assertEquals(BottomSheetFollowAction.Bottom, follow.contentChanged(follows = true))

        follow.offsetChanged(distanceFromBottom = 300f, threshold = threshold)
        assertEquals(BottomSheetFollowAction.None, follow.contentChanged(follows = true))

        follow.offsetChanged(distanceFromBottom = 0f, threshold = threshold)
        assertEquals(BottomSheetFollowAction.Bottom, follow.contentChanged(follows = true))
    }

    @Test
    fun settlingWhileOpenStaysWhereTheListIs() {
        val follow = following()
        follow.offsetChanged(distanceFromBottom = 0f, threshold = threshold)

        assertEquals(BottomSheetFollowAction.None, follow.contentChanged(follows = false))
    }

    @Test
    fun backToAStepDoesNotJumpToTheBottom() {
        val follow = following()

        follow.targetScrolled()

        assertFalse(follow.parked)
        assertEquals(BottomSheetFollowAction.Target, follow.contentChanged(follows = true))
        // The step was far from the end, so new steps leave it in place.
        follow.offsetChanged(distanceFromBottom = 400f, threshold = threshold)
        assertEquals(BottomSheetFollowAction.None, follow.contentChanged(follows = true))
    }

    @Test
    fun backToAStepNearTheEndResumesFollowing() {
        val follow = following()

        follow.targetScrolled()
        follow.offsetChanged(distanceFromBottom = 0f, threshold = threshold)

        assertEquals(BottomSheetFollowAction.Bottom, follow.contentChanged(follows = true))
    }
}
