package dev.opencode.mobile.ui.scroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageListVisibleAnchorScrollTest {
    private val chronologicalIds = listOf("oldest", "mid", "newest")

    @Test
    fun shouldCapture_unarmedLayoutAffecting_withoutScroll_captures() {
        assertTrue(
            MessageListVisibleAnchorScroll.shouldCapture(
                hasLayoutAffectingChange = true,
                isArmedOrPinned = false,
                isScrollInProgress = false,
            ),
        )
    }

    @Test
    fun shouldCapture_skipsWhenArmedOrPinned() {
        assertFalse(
            MessageListVisibleAnchorScroll.shouldCapture(
                hasLayoutAffectingChange = true,
                isArmedOrPinned = true,
                isScrollInProgress = false,
            ),
        )
    }

    @Test
    fun shouldCapture_skipsWhenScrollInProgress() {
        assertFalse(
            MessageListVisibleAnchorScroll.shouldCapture(
                hasLayoutAffectingChange = true,
                isArmedOrPinned = false,
                isScrollInProgress = true,
            ),
        )
    }

    @Test
    fun shouldCapture_skipsContentOnlyFrames() {
        assertFalse(
            MessageListVisibleAnchorScroll.shouldCapture(
                hasLayoutAffectingChange = false,
                isArmedOrPinned = false,
                isScrollInProgress = false,
            ),
        )
    }

    @Test
    fun shouldRestore_unarmedAfterSettle_restores() {
        val anchor = MessageListVisibleAnchor(messageId = "mid", scrollOffset = 24)
        assertTrue(
            MessageListVisibleAnchorScroll.shouldRestore(
                anchor = anchor,
                isArmedOrPinned = false,
                isScrollInProgress = false,
            ),
        )
    }

    @Test
    fun shouldRestore_skipsWhenArmedFollowWillSettleToEnd() {
        val anchor = MessageListVisibleAnchor(messageId = "mid", scrollOffset = 24)
        assertFalse(
            MessageListVisibleAnchorScroll.shouldRestore(
                anchor = anchor,
                isArmedOrPinned = true,
                isScrollInProgress = false,
            ),
        )
    }

    @Test
    fun shouldRestore_skipsWhenDragging() {
        val anchor = MessageListVisibleAnchor(messageId = "mid", scrollOffset = 24)
        assertFalse(
            MessageListVisibleAnchorScroll.shouldRestore(
                anchor = anchor,
                isArmedOrPinned = false,
                isScrollInProgress = true,
            ),
        )
    }

    @Test
    fun shouldRestore_skipsWhenNoAnchorCaptured() {
        assertFalse(
            MessageListVisibleAnchorScroll.shouldRestore(
                anchor = null,
                isArmedOrPinned = false,
                isScrollInProgress = false,
            ),
        )
    }

    @Test
    fun shouldTrack_onlyWhileUnarmedAndIdle() {
        assertTrue(
            MessageListVisibleAnchorScroll.shouldTrack(
                isArmedOrPinned = false,
                isScrollInProgress = false,
            ),
        )
        assertFalse(
            MessageListVisibleAnchorScroll.shouldTrack(
                isArmedOrPinned = true,
                isScrollInProgress = false,
            ),
        )
        assertFalse(
            MessageListVisibleAnchorScroll.shouldTrack(
                isArmedOrPinned = false,
                isScrollInProgress = true,
            ),
        )
    }

    @Test
    fun reverseLayout_indexMath_mapsOldestAndNewest() {
        assertEquals(
            3,
            MessageListVisibleAnchorScroll.lazyIndexForMessageId(chronologicalIds, "oldest"),
        )
        assertEquals(
            1,
            MessageListVisibleAnchorScroll.lazyIndexForMessageId(chronologicalIds, "newest"),
        )
        assertEquals(
            "oldest",
            MessageListVisibleAnchorScroll.messageIdAtLazyIndex(chronologicalIds, lazyIndex = 3),
        )
        assertEquals(
            "newest",
            MessageListVisibleAnchorScroll.messageIdAtLazyIndex(chronologicalIds, lazyIndex = 1),
        )
        assertNull(
            MessageListVisibleAnchorScroll.messageIdAtLazyIndex(chronologicalIds, lazyIndex = 0),
        )
    }

    @Test
    fun captureAndRestore_preservesIdAndOffsetThroughInsert() {
        val beforeIds = listOf("oldest", "mid", "newest")
        val captured =
            MessageListVisibleAnchorScroll.captureFromChronologicalIds(
                firstVisibleItemIndex = 3,
                firstVisibleItemScrollOffset = 42,
                messageIdsChronological = beforeIds,
            )
        assertEquals(MessageListVisibleAnchor(messageId = "oldest", scrollOffset = 42), captured)

        val afterIds = listOf("oldest", "mid", "newest", "appended")
        val target =
            MessageListVisibleAnchorScroll.restoreTarget(
                anchor = checkNotNull(captured),
                messageIdsChronological = afterIds,
            )
        assertEquals(
            MessageListVisibleAnchorRestoreTarget(lazyIndex = 4, scrollOffset = 42),
            target,
        )
    }

    @Test
    fun capture_skipsBottomAnchorSpacer() {
        assertNull(
            MessageListVisibleAnchorScroll.capture(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
                firstVisibleMessageId = "bottom-anchor",
            ),
        )
    }

    @Test
    fun restoreTarget_returnsNullWhenAnchorRemoved() {
        val anchor = MessageListVisibleAnchor(messageId = "gone", scrollOffset = 8)
        assertNull(
            MessageListVisibleAnchorScroll.restoreTarget(
                anchor = anchor,
                messageIdsChronological = chronologicalIds,
            ),
        )
    }
}
