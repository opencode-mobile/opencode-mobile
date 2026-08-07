package dev.opencode.mobile.ui.sessionchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionInfoStatusTest {
    @Test
    fun withStatus_markOptimistic_whenUnresolvedIdle() {
        val next =
            SessionInfoRegionState().withStatus(
                authoritative = null,
                optimisticBusy = true,
            )

        assertTrue(next.optimisticBusy)
        assertEquals("Working", next.sessionStatusLabel)
        assertNull(next.authoritativeStatusLabel)
    }

    @Test
    fun withStatus_markOptimistic_whenAlreadyResolvedIdle() {
        val resolved =
            SessionInfoRegionState().withStatus(
                authoritative = null,
                hasResolved = true,
            )
        val next = resolved.withStatus(authoritative = null, optimisticBusy = true)

        assertTrue(next.optimisticBusy)
        assertEquals("Working", next.sessionStatusLabel)
        assertTrue(next.hasResolvedSessionStatus)
    }

    @Test
    fun withStatus_authoritativeBusy_clearsOptimistic() {
        val optimistic =
            SessionInfoRegionState().withStatus(
                authoritative = null,
                optimisticBusy = true,
            )
        val next = optimistic.withStatus(authoritative = "Working", hasResolved = true)

        assertFalse(next.optimisticBusy)
        assertEquals("Working", next.sessionStatusLabel)
        assertEquals("Working", next.authoritativeStatusLabel)
    }

    @Test
    fun withStatus_resolvedIdle_clearsOptimistic() {
        val optimistic =
            SessionInfoRegionState().withStatus(
                authoritative = null,
                optimisticBusy = true,
            )
        val next = optimistic.withStatus(authoritative = null, hasResolved = true)

        assertFalse(next.optimisticBusy)
        assertNull(next.sessionStatusLabel)
        assertTrue(next.hasResolvedSessionStatus)
    }

    @Test
    fun withStatus_unresolvedNull_keepsOptimistic() {
        val optimistic =
            SessionInfoRegionState().withStatus(
                authoritative = null,
                optimisticBusy = true,
            )
        val next = optimistic.withStatus(authoritative = null)

        assertTrue(next.optimisticBusy)
        assertEquals("Working", next.sessionStatusLabel)
        assertFalse(next.hasResolvedSessionStatus)
    }
}
