package dev.opencode.mobile.domain.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolStartTimeTest {
    @Test
    fun normalize_acceptsSecondsMillisAndMicros() {
        assertEquals(1_710_000_000_000L, ToolStartTime.normalizeEpochMs(1_710_000_000L))
        assertEquals(1_710_000_000_000L, ToolStartTime.normalizeEpochMs(1_710_000_000_000L))
        assertEquals(1_710_000_000_000L, ToolStartTime.normalizeEpochMs(1_710_000_000_000_000L))
    }

    @Test
    fun normalize_dropsRelativeValues() {
        assertNull(ToolStartTime.normalizeEpochMs(null))
        assertNull(ToolStartTime.normalizeEpochMs(0L))
        assertNull(ToolStartTime.normalizeEpochMs(10L))
    }

    @Test
    fun resolve_keepsExistingThenIncomingThenStampsRunning() {
        val now = 1_710_000_010_000L
        assertEquals(
            1_710_000_005_000L,
            ToolStartTime.resolve(
                incoming = 1_710_000_000_000L,
                existing = 1_710_000_005_000L,
                status = ToolStatus.Running,
                nowEpochMs = now,
            ),
        )
        assertEquals(
            1_710_000_000_000L,
            ToolStartTime.resolve(
                incoming = 1_710_000_000_000L,
                existing = 10L,
                status = ToolStatus.Running,
                nowEpochMs = now,
            ),
        )
        assertEquals(
            now,
            ToolStartTime.resolve(
                incoming = null,
                existing = null,
                status = ToolStatus.Running,
                nowEpochMs = now,
            ),
        )
        assertNull(
            ToolStartTime.resolve(
                incoming = 10L,
                existing = null,
                status = ToolStatus.Completed,
                nowEpochMs = now,
            ),
        )
    }

    @Test
    fun resolve_rejectsStartMoreThanTwoSecondsInTheFuture() {
        val now = 1_710_000_000_000L
        assertEquals(
            now,
            ToolStartTime.resolve(
                incoming = now + 80_000L,
                existing = null,
                status = ToolStatus.Running,
                nowEpochMs = now,
            ),
        )
    }
}
