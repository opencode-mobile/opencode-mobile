package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionIdleOutcome
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.ui.components.OpenCodeListItemStatusTone
import dev.opencode.mobile.ui.components.SessionListStatus
import dev.opencode.mobile.ui.components.toListItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListStatusMappingTest {
    @Test
    fun needsApprovalOutranksRunningRetryingAndSnoozeSignal() {
        val running =
            SessionSummary(
                id = "ses-1",
                title = "Chat",
                statusLabel = "Working",
                needsApproval = true,
            )
        val retrying =
            SessionSummary(
                id = "ses-1",
                title = "Chat",
                statusLabel = "Retrying · 2",
                unreadOutcome = SessionIdleOutcome.Failed,
                needsApproval = true,
            )
        val pill = SessionListStatus.NeedsApproval.toListItemStatus()

        assertEquals(SessionListStatus.NeedsApproval, running.toSessionListStatusForTest())
        assertEquals(SessionListStatus.NeedsApproval, retrying.toSessionListStatusForTest())
        assertEquals("Needs approval", pill.text)
        assertEquals(OpenCodeListItemStatusTone.Warning, pill.tone)
        assertTrue(SessionListStatus.NeedsApproval.needsAttention)
        assertFalse(SessionListStatus.NeedsApproval.isUnreadFinish)
    }

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

    @Test
    fun serverUnreadOutcomesMapToDoneFailedStopped() {
        fun unread(outcome: SessionIdleOutcome) =
            SessionSummary(id = "ses-1", title = "Chat", unreadOutcome = outcome).toSessionListStatusForTest()

        assertEquals(SessionListStatus.Done, unread(SessionIdleOutcome.Succeeded))
        assertEquals(SessionListStatus.Failed, unread(SessionIdleOutcome.Failed))
        assertEquals(SessionListStatus.Stopped, unread(SessionIdleOutcome.Interrupted))
    }

    @Test
    fun runningAndRetryingOutrankServerUnread() {
        val running =
            SessionSummary(id = "ses-1", title = "Chat", statusLabel = "Working", unreadOutcome = SessionIdleOutcome.Failed)
        val retrying =
            SessionSummary(id = "ses-1", title = "Chat", statusLabel = "Retrying · 1", unreadOutcome = SessionIdleOutcome.Failed)

        assertEquals(SessionListStatus.Running, running.toSessionListStatusForTest())
        assertEquals(SessionListStatus.Retrying, retrying.toSessionListStatusForTest())
    }

    @Test
    fun v2HomeMapping_unreadMissingOutcomeIsDoneAndReadIsIdle() {
        val unreadNoOutcome = Session(id = "a", title = "A", idleAtEpochMs = 30L)
        val read = Session(id = "b", title = "B", idleAtEpochMs = 30L, viewedAtEpochMs = 30L, idleOutcome = SessionIdleOutcome.Failed)

        assertEquals(SessionIdleOutcome.Succeeded, unreadNoOutcome.homeUnreadOutcome(serverReadState = true))
        assertNull(read.homeUnreadOutcome(serverReadState = true))
        assertNull(unreadNoOutcome.homeUnreadOutcome(serverReadState = false))
    }

    @Test
    fun unreadPillsUseOutcomeTones() {
        val done = SessionListStatus.Done.toListItemStatus()
        val failed = SessionListStatus.Failed.toListItemStatus()
        val stopped = SessionListStatus.Stopped.toListItemStatus()

        assertEquals("Done" to OpenCodeListItemStatusTone.Success, done.text to done.tone)
        assertEquals("Failed" to OpenCodeListItemStatusTone.Error, failed.text to failed.tone)
        assertEquals("Stopped" to OpenCodeListItemStatusTone.Warning, stopped.text to stopped.tone)
    }

    @Test
    fun unreadStatusesBehaveLikeReadyForAttention() {
        for (status in listOf(SessionListStatus.Ready, SessionListStatus.Done, SessionListStatus.Failed, SessionListStatus.Stopped)) {
            assertTrue(status.isUnreadFinish)
            assertTrue(status.needsAttention)
        }
        assertFalse(SessionListStatus.Idle.needsAttention)
        assertFalse(SessionListStatus.Running.isUnreadFinish)
        assertTrue(SessionListStatus.Running.needsAttention)
    }
}

private fun SessionSummary.toSessionListStatusForTest(): SessionListStatus = toSessionListStatus()
