package dev.opencode.mobile.ui.scroll

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageListOlderHistoryTest {
    @Test
    fun triggersOnceWhenOldestVisibleAndIdle() {
        assertTrue(
            MessageListOlderHistory.shouldLoadOlder(
                hasOlder = true,
                isLoadingOlder = false,
                oldestVisible = true,
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
            ),
        )
        assertFalse(
            MessageListOlderHistory.shouldLoadOlder(
                hasOlder = false,
                isLoadingOlder = false,
                oldestVisible = true,
            ),
        )
        assertFalse(
            MessageListOlderHistory.shouldLoadOlder(
                hasOlder = true,
                isLoadingOlder = false,
                oldestVisible = false,
            ),
        )
    }
}
