package dev.opencode.mobile.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListPagingTest {
    private fun trigger(
        nearEnd: Boolean = true,
        canLoadMore: Boolean = true,
        loadingMore: Boolean = false,
    ) = SessionListPaging.Trigger(
        nearEnd = nearEnd,
        totalItemCount = 0,
        canLoadMore = canLoadMore,
        loadingMore = loadingMore,
    )

    @Test
    fun loadsMoreOnlyNearTheEndWithMoreToLoadAndNoLoadRunning() {
        assertTrue(trigger().loadsMore)
        assertFalse(trigger(nearEnd = false).loadsMore)
        assertFalse(trigger(canLoadMore = false).loadsMore)
        assertFalse(trigger(loadingMore = true).loadsMore)
    }

    @Test
    fun aWindowThatGainsMoreOrALoadThatEndsSamplesAgain() {
        // The list only asks again when the sample changes (#981).
        assertTrue(trigger(canLoadMore = false) != trigger())
        assertTrue(trigger(loadingMore = true) != trigger())
    }
}
