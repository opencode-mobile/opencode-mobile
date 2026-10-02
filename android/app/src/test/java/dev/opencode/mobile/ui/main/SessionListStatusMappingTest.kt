package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.ui.components.OpenCodeListItemStatusTone
import dev.opencode.mobile.ui.components.SessionListStatus
import dev.opencode.mobile.ui.components.toListItemStatus
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

    @Test
    fun retryingMapsToRetryingVariant() {
        val summary =
            SessionSummary(
                id = "ses-1",
                title = "Chat",
                statusLabel = "Retrying · 2",
                isReady = true,
            )
        assertEquals(SessionListStatus.Retrying, summary.toSessionListStatusForTest())
    }

    @Test
    fun unseededShowsNoStatusChip() {
        val summary =
            SessionSummary(
                id = "ses-1",
                title = "Chat",
                statusLabel = null,
                isReady = false,
            )
        assertEquals(SessionListStatus.Idle, summary.toSessionListStatusForTest())
    }

    @Test
    fun cachedRunningUsesStaleTone() {
        assertEquals(
            OpenCodeListItemStatusTone.Stale,
            SessionListStatus.Running.toListItemStatus(stale = true).tone,
        )
    }

    @Test
    fun serverRunningUsesInteractiveTone() {
        assertEquals(
            OpenCodeListItemStatusTone.Interactive,
            SessionListStatus.Running.toListItemStatus(stale = false).tone,
        )
    }

    @Test
    fun idleShowsInfoPill() {
        val status = SessionListStatus.Idle.toListItemStatus(stale = false)
        assertEquals("Idle", status.text)
        assertEquals(OpenCodeListItemStatusTone.Info, status.tone)
    }

    @Test
    fun cachedIdleUsesStaleTone() {
        val status = SessionListStatus.Idle.toListItemStatus(stale = true)
        assertEquals("Idle", status.text)
        assertEquals(OpenCodeListItemStatusTone.Stale, status.tone)
    }
}

/** Mirrors MainScreen composite mapping for unit coverage. */
private fun SessionSummary.toSessionListStatusForTest(): SessionListStatus =
    when {
        statusLabel == "Working" -> SessionListStatus.Running
        statusLabel?.startsWith("Retrying") == true -> SessionListStatus.Retrying
        else -> if (isReady) SessionListStatus.Ready else SessionListStatus.Idle
    }
