package dev.opencode.mobile.ui.scroll

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollToEndVisibilityTest {
    @Test
    fun hideWhenBottomAnchorStillNearEnd() {
        val visible =
            listOf(
                TimelineVisibleItem(index = 0, offset = 1900, size = 1),
                TimelineVisibleItem(index = 1, offset = -5000, size = 6900),
            )
        assertFalse(
            shouldShowScrollToEnd(
                messageCount = 3,
                viewportHeightPx = 2000,
                viewportEndOffset = 2000,
                afterContentPadding = 0,
                revealThresholdMinPx = 120,
                visibleItems = visible,
            ),
        )
    }

    @Test
    fun showWhenBottomAnchorScrolledPastThreshold() {
        // revealThreshold = max(120, 0.2 * viewport) = 400 for a 2000px viewport.
        val visible =
            listOf(
                TimelineVisibleItem(index = 1, offset = -4400, size = 6900),
            )
        assertTrue(
            shouldShowScrollToEnd(
                messageCount = 3,
                viewportHeightPx = 2000,
                viewportEndOffset = 2000,
                afterContentPadding = 0,
                revealThresholdMinPx = 120,
                visibleItems = visible,
            ),
        )
    }

    @Test
    fun hideWhenBottomAnchorJustLeftViewportButThresholdNotReached() {
        val visible =
            listOf(
                TimelineVisibleItem(index = 1, offset = -4800, size = 6900),
            )
        assertFalse(
            shouldShowScrollToEnd(
                messageCount = 3,
                viewportHeightPx = 2000,
                viewportEndOffset = 2000,
                afterContentPadding = 0,
                revealThresholdMinPx = 120,
                visibleItems = visible,
            ),
        )
    }

    @Test
    fun showWhenScrolledInsideTallerThanViewportNewestMessage() {
        // Bottom anchor left the viewport; newest message still fills the screen.
        // Old item-bounds math treated this as near-end and hid the pill.
        val visible =
            listOf(
                TimelineVisibleItem(index = 1, offset = -3000, size = 8000),
            )
        assertTrue(
            shouldShowScrollToEnd(
                messageCount = 3,
                viewportHeightPx = 2000,
                viewportEndOffset = 2000,
                afterContentPadding = 12,
                revealThresholdMinPx = 120,
                visibleItems = visible,
            ),
        )
    }

    @Test
    fun showWhenNewestMessageNotVisible() {
        val visible =
            listOf(
                TimelineVisibleItem(index = 5, offset = 100, size = 400),
                TimelineVisibleItem(index = 6, offset = 500, size = 400),
            )
        assertTrue(
            shouldShowScrollToEnd(
                messageCount = 10,
                viewportHeightPx = 2000,
                viewportEndOffset = 2000,
                afterContentPadding = 0,
                revealThresholdMinPx = 120,
                visibleItems = visible,
            ),
        )
    }
}
