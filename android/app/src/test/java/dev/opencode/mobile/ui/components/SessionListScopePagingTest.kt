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
    fun commitDirection_distanceSelectsNext() {
        assertEquals(
            SessionListScopePaging.Direction.Next,
            SessionListScopePaging.commitDirection(
                translation = -36f,
                velocity = 0f,
                canGoPrevious = true,
                canGoNext = true,
            ),
        )
    }

    @Test
    fun commitDirection_distanceSelectsPrevious() {
        assertEquals(
            SessionListScopePaging.Direction.Previous,
            SessionListScopePaging.commitDirection(
                translation = 36f,
                velocity = 0f,
                canGoPrevious = true,
                canGoNext = true,
            ),
        )
    }

    @Test
    fun commitDirection_shortDragWithoutFlickSnapsBack() {
        assertNull(
            SessionListScopePaging.commitDirection(
                translation = -35f,
                velocity = 0f,
                canGoPrevious = true,
                canGoNext = true,
            ),
        )
    }

    @Test
    fun commitDirection_flickSelectsNext() {
        assertEquals(
            SessionListScopePaging.Direction.Next,
            SessionListScopePaging.commitDirection(
                translation = -8f,
                velocity = -800f,
                canGoPrevious = true,
                canGoNext = true,
            ),
        )
    }

    @Test
    fun commitDirection_blockedEndDoesNotCommit() {
        assertNull(
            SessionListScopePaging.commitDirection(
                translation = -80f,
                velocity = -1200f,
                canGoPrevious = true,
                canGoNext = false,
            ),
        )
        assertNull(
            SessionListScopePaging.commitDirection(
                translation = 80f,
                velocity = 1200f,
                canGoPrevious = false,
                canGoNext = true,
            ),
        )
    }

    @Test
    fun displayedTranslation_midListTracksFinger() {
        assertEquals(
            -40f,
            SessionListScopePaging.displayedTranslation(
                translation = -40f,
                pageWidth = 200f,
                canGoPrevious = true,
                canGoNext = true,
            ),
        )
    }

    @Test
    fun displayedTranslation_startRubberBands() {
        val displayed =
            SessionListScopePaging.displayedTranslation(
                translation = 100f,
                pageWidth = 200f,
                canGoPrevious = false,
                canGoNext = true,
            )
        assertTrue(displayed > 0f)
        assertTrue(displayed < 100f)
    }
}
