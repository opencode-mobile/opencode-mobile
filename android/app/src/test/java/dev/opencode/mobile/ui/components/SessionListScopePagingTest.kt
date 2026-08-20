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
    fun rubberBandOffset_resistsBothDirections() {
        val pull = SessionListScopePaging.rubberBandOffset(translation = 100f)
        assertTrue(pull > 0f)
        assertTrue(pull < SessionListScopePaging.RUBBER_BAND_LIMIT_DP)
        assertTrue(pull < 100f)
        assertEquals(-pull, SessionListScopePaging.rubberBandOffset(translation = -100f))
        assertEquals(0f, SessionListScopePaging.rubberBandOffset(translation = 0f))
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
                resistOverscroll = true,
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
                resistOverscroll = true,
            )
        assertTrue(displayed > 0f)
        assertTrue(displayed < SessionListScopePaging.RUBBER_BAND_LIMIT_DP)
        assertTrue(displayed < 100f)
    }

    @Test
    fun displayedTranslation_singleTabResistsAndDiminishes() {
        val shortPull =
            SessionListScopePaging.displayedTranslation(
                translation = 50f,
                pageWidth = 200f,
                canGoPrevious = false,
                canGoNext = false,
                resistOverscroll = true,
            )
        val longPull =
            SessionListScopePaging.displayedTranslation(
                translation = 150f,
                pageWidth = 200f,
                canGoPrevious = false,
                canGoNext = false,
                resistOverscroll = true,
            )
        assertTrue(shortPull > 0f)
        assertTrue(longPull > shortPull)
        assertTrue(longPull - shortPull < shortPull)
        assertTrue(longPull < SessionListScopePaging.RUBBER_BAND_LIMIT_DP)
    }

    @Test
    fun displayedTranslation_releaseUsesRawOffset() {
        assertEquals(
            12f,
            SessionListScopePaging.displayedTranslation(
                translation = 12f,
                pageWidth = 200f,
                canGoPrevious = false,
                canGoNext = false,
                resistOverscroll = false,
            ),
        )
    }

    @Test
    fun settleTranslation_commitsAFullPageThenSnaps() {
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
