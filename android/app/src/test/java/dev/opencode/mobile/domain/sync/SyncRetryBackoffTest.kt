package dev.opencode.mobile.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncRetryBackoffTest {
    @Test
    fun delayMs_is500Then1s2s4sCappedAt5s() {
        assertEquals(500L, SyncRetryBackoff.delayMs(0))
        assertEquals(1_000L, SyncRetryBackoff.delayMs(1))
        assertEquals(2_000L, SyncRetryBackoff.delayMs(2))
        assertEquals(4_000L, SyncRetryBackoff.delayMs(3))
        assertEquals(5_000L, SyncRetryBackoff.delayMs(4))
        assertEquals(5_000L, SyncRetryBackoff.delayMs(8))
    }
}
