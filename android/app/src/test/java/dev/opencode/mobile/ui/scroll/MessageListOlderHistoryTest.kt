package dev.opencode.mobile.ui.scroll

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageListOlderHistoryTest {
    @Test
    fun triggersOnceWhenOldestVisibleAfterUserScroll() {
        assertTrue(
            MessageListOlderHistory.shouldLoadOlder(
                hasOlder = true,
                isLoadingOlder = false,
                oldestVisible = true,
                userScrolledTowardOldest = true,
            ),
        )
    }

    @Test
    fun doesNotTriggerOnInitialOldestVisibleWithoutUserScroll() {
        assertFalse(
            MessageListOlderHistory.shouldLoadOlder(
                hasOlder = true,
                isLoadingOlder = false,
                oldestVisible = true,
                userScrolledTowardOldest = false,
            ),
        )
    }

    @Test
    fun doesNotTriggerWhileLoadingOrAtEnd() {
        assertFalse(
            MessageListOlderHistory.shouldLoadOlder(
                hasOlder = true,
                isLoadingOlder = true,
                oldestVisible = true,
                userScrolledTowardOldest = true,
            ),
        )
        assertFalse(
            MessageListOlderHistory.shouldLoadOlder(
                hasOlder = false,
                isLoadingOlder = false,
                oldestVisible = true,
                userScrolledTowardOldest = true,
            ),
        )
        assertFalse(
            MessageListOlderHistory.shouldLoadOlder(
                hasOlder = true,
                isLoadingOlder = false,
                oldestVisible = false,
                userScrolledTowardOldest = true,
            ),
        )
    }

    @Test
    fun towardOldestUsesHigherReverseLayoutIndex() {
        assertTrue(MessageListOlderHistory.isTowardOldest(0, 0, 1, 0))
        assertTrue(MessageListOlderHistory.isTowardOldest(2, 10, 2, 24))
        assertFalse(MessageListOlderHistory.isTowardOldest(3, 8, 2, 40))
        assertFalse(MessageListOlderHistory.isTowardOldest(1, 12, 1, 4))
    }

    @Test
    fun intentIgnoresOldestVisibleUntilUserDragAfterPresentation() {
        val intent = MessageListOlderLoadIntent()
        intent.onUserScroll(inProgress = false, oldestVisible = true, towardOldest = false)
        assertFalse(
            intent.shouldLoadOlder(hasOlder = true, isLoadingOlder = false, oldestVisible = true),
        )

        intent.markPresentationSettled()
        intent.onUserScroll(inProgress = false, oldestVisible = true, towardOldest = false)
        assertFalse(
            intent.shouldLoadOlder(hasOlder = true, isLoadingOlder = false, oldestVisible = true),
        )

        intent.onUserScroll(inProgress = true, oldestVisible = true, towardOldest = false)
        assertTrue(
            intent.shouldLoadOlder(hasOlder = true, isLoadingOlder = false, oldestVisible = true),
        )
    }

    @Test
    fun intentConsumesOneLoadPerGestureAndAllowsLaterScroll() {
        val intent = MessageListOlderLoadIntent()
        intent.markPresentationSettled()
        intent.onUserScroll(inProgress = true, oldestVisible = false, towardOldest = true)
        intent.onUserScroll(inProgress = true, oldestVisible = true, towardOldest = true)
        assertTrue(
            intent.shouldLoadOlder(hasOlder = true, isLoadingOlder = false, oldestVisible = true),
        )

        intent.consume()
        assertFalse(
            intent.shouldLoadOlder(hasOlder = true, isLoadingOlder = false, oldestVisible = true),
        )
        intent.onUserScroll(inProgress = true, oldestVisible = true, towardOldest = true)
        assertFalse(
            intent.shouldLoadOlder(hasOlder = true, isLoadingOlder = false, oldestVisible = true),
        )

        intent.onUserScroll(inProgress = false, oldestVisible = true, towardOldest = false)
        intent.onUserScroll(inProgress = true, oldestVisible = true, towardOldest = false)
        assertTrue(
            intent.shouldLoadOlder(hasOlder = true, isLoadingOlder = false, oldestVisible = true),
        )
    }
}
