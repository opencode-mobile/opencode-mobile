package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListScopePagingTest {
    @Test
    fun neighborIndex_previousAtStartIsNull() {
        assertNull(
            SessionListScopePaging.neighborIndex(
                selectedIndex = 0,
                count = 3,
                direction = SessionListScopePaging.Direction.Previous,
            ),
        )
    }

    @Test
    fun neighborIndex_nextAtEndIsNull() {
        assertNull(
            SessionListScopePaging.neighborIndex(
                selectedIndex = 2,
                count = 3,
                direction = SessionListScopePaging.Direction.Next,
            ),
        )
    }

    @Test
    fun neighborIndex_pagesWithoutWrapping() {
        assertEquals(
            1,
            SessionListScopePaging.neighborIndex(
                selectedIndex = 0,
                count = 3,
                direction = SessionListScopePaging.Direction.Next,
            ),
        )
        assertEquals(
            0,
            SessionListScopePaging.neighborIndex(
                selectedIndex = 1,
                count = 3,
                direction = SessionListScopePaging.Direction.Previous,
            ),
        )
    }

    @Test
    fun commitDirection_visualThresholdSelectsNext() {
        assertEquals(
            SessionListScopePaging.Direction.Next,
            SessionListScopePaging.commitDirection(
                visualOffset = -28f,
                canGoPrevious = true,
                canGoNext = true,
            ),
        )
    }

    @Test
    fun commitDirection_visualThresholdSelectsPrevious() {
        assertEquals(
            SessionListScopePaging.Direction.Previous,
            SessionListScopePaging.commitDirection(
                visualOffset = 28f,
                canGoPrevious = true,
                canGoNext = true,
            ),
        )
    }

    @Test
    fun commitDirection_shortPullSnapsBack() {
        assertNull(
            SessionListScopePaging.commitDirection(
                visualOffset = -27f,
                canGoPrevious = true,
                canGoNext = true,
            ),
        )
    }

    @Test
    fun commitDirection_blockedEndDoesNotCommit() {
        assertNull(
            SessionListScopePaging.commitDirection(
                visualOffset = -40f,
                canGoPrevious = true,
                canGoNext = false,
            ),
        )
        assertNull(
            SessionListScopePaging.commitDirection(
                visualOffset = 40f,
                canGoPrevious = false,
                canGoNext = true,
            ),
        )
    }

    @Test
    fun rubberBandOffset_resistsBothDirections() {
        val pull = SessionListScopePaging.rubberBandOffset(translation = 100f)
        assertTrue(pull > 0f)
        assertTrue(pull < SessionListScopePaging.RUBBER_BAND_LIMIT_DP)
        assertTrue(pull < 100f)
        assertEquals(-pull, SessionListScopePaging.rubberBandOffset(translation = -100f))
        assertEquals(0f, SessionListScopePaging.rubberBandOffset(translation = 0f))
    }

    @Test
    fun settleTranslation_exitsAFullSlot() {
        assertEquals(
            -200f,
            SessionListScopePaging.settleTranslation(
                direction = SessionListScopePaging.Direction.Next,
                pageWidth = 200f,
            ),
        )
        assertEquals(
            200f,
            SessionListScopePaging.settleTranslation(
                direction = SessionListScopePaging.Direction.Previous,
                pageWidth = 200f,
            ),
        )
        assertEquals(
            0f,
            SessionListScopePaging.settleTranslation(
                direction = null,
                pageWidth = 200f,
            ),
        )
    }
}
