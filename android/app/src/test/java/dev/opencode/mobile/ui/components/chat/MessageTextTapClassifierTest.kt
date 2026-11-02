package dev.opencode.mobile.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTextTapClassifierTest {
    private val classifier = MessageTextTapClassifier()

    private fun touch(
        released: Boolean = true,
        moved: Boolean = false,
        linkHandled: Boolean = false,
        startedDuringScroll: Boolean = false,
    ): Boolean =
        classifier.isTap(
            released = released,
            moved = moved,
            linkHandled = linkHandled,
            startedDuringScroll = startedDuringScroll,
        )

    @Test
    fun mouseClickIsNeverTap() {
        // A click clears a mouse selection or starts a double-click word selection;
        // a right-click opens message actions instead.
        val click = { classifier.isTap(released = true, moved = false, linkHandled = false, startedDuringScroll = false, mouse = true) }
        assertFalse(click())
        assertFalse(click())
        // Touch behaviour is unchanged after mouse clicks.
        assertTrue(touch())
    }

    @Test
    fun quickStillTouchIsTap() {
        assertTrue(touch())
    }

    @Test
    fun touchThatStopsScrollIsNotTap() {
        assertFalse(touch(startedDuringScroll = true))
        assertTrue(touch())
    }

    @Test
    fun movedTouchIsNotTap() {
        assertFalse(touch(moved = true))
        assertFalse(touch(released = false, moved = true))
    }

    @Test
    fun linkTapIsNotMessageTap() {
        assertFalse(touch(linkHandled = true))
    }

    @Test
    fun firstTapAfterLongPressOnlyDismissesSelection() {
        assertFalse(touch(released = false))
        assertFalse(touch())
        assertTrue(touch())
    }

    @Test
    fun longPressThatStopsScrollStillStartsSelection() {
        assertFalse(touch(released = false, startedDuringScroll = true))
        assertFalse(touch())
        assertTrue(touch())
    }

    @Test
    fun linkTapClearsSelection() {
        assertFalse(touch(released = false))
        assertFalse(touch(linkHandled = true))
        assertTrue(touch())
    }
}
