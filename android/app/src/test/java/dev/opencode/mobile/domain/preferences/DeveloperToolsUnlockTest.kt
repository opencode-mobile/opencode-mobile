package dev.opencode.mobile.domain.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class DeveloperToolsUnlockTest {
    @Test
    fun firstTap_startsAtOne() {
        assertEquals(1, DeveloperToolsUnlock.nextCount(0, 0L, 10_000L))
    }

    @Test
    fun consecutiveTaps_countUp() {
        var count = 0
        var last = 0L
        for (i in 1..10) {
            val now = last + 200L
            count = DeveloperToolsUnlock.nextCount(count, last, now)
            last = now
        }
        assertEquals(10, count)
    }

    @Test
    fun staleTap_resetsToOne() {
        val next =
            DeveloperToolsUnlock.nextCount(
                previousCount = 7,
                lastTapAtMs = 1_000L,
                nowMs = 1_000L + DeveloperToolsUnlock.TAP_WINDOW_MS + 1,
            )
        assertEquals(1, next)
    }

    @Test
    fun tapInsideWindow_continues() {
        val next =
            DeveloperToolsUnlock.nextCount(
                previousCount = 7,
                lastTapAtMs = 1_000L,
                nowMs = 1_000L + DeveloperToolsUnlock.TAP_WINDOW_MS,
            )
        assertEquals(8, next)
    }
}
