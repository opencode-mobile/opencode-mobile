package dev.opencode.mobile.domain.event

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SseEventDeduperTest {
    @Test
    fun accept_dropsDuplicateIdsAndKeepsMissingIds() {
        val deduper = SseEventDeduper(capacity = 2)
        assertTrue(deduper.accept(null))
        assertTrue(deduper.accept("evt-1"))
        assertFalse(deduper.accept("evt-1"))
        assertTrue(deduper.accept("evt-2"))
        assertTrue(deduper.accept("evt-3"))
        assertTrue(deduper.accept("evt-1"))
    }
}
