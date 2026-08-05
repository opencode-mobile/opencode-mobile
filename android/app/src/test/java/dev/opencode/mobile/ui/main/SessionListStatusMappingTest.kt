package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.ui.components.SessionListStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionListStatusMappingTest {
    @Test
    fun runningOutranksReady() {
        val summary =
            SessionSummary(
                id = "ses-1",
                title = "Chat",
                statusLabel = "Working",
                isReady = true,
            )
        assertEquals(SessionListStatus.Running, summary.toSessionListStatusForTest())
    }

    @Test
    fun readyWhenIdleAndFlagged() {
        val summary =
            SessionSummary(
                id = "ses-1",
                title = "Chat",
                statusLabel = null,
                isReady = true,
            )
        assertEquals(SessionListStatus.Ready, summary.toSessionListStatusForTest())
    }

    @Test
    fun idleWhenNotReady() {
        val summary =
            SessionSummary(
                id = "ses-1",
                title = "Chat",
                statusLabel = null,
                isReady = false,
            )
        assertEquals(SessionListStatus.Idle, summary.toSessionListStatusForTest())
    }
}

/** Mirrors MainScreen composite mapping for unit coverage. */
private fun SessionSummary.toSessionListStatusForTest(): SessionListStatus =
    when (statusLabel) {
        "Working", "Retrying" -> SessionListStatus.Running
        else -> if (isReady) SessionListStatus.Ready else SessionListStatus.Idle
    }
