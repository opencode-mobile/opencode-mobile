package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.MessageStopReason
import dev.opencode.mobile.ui.sessionchat.WorkingTurnIndicator.Chrome
import dev.opencode.mobile.ui.sessionchat.WorkingTurnIndicator.Ending
import dev.opencode.mobile.ui.sessionchat.WorkingTurnIndicator.Hold
import dev.opencode.mobile.ui.sessionchat.WorkingTurnIndicator.IDLE_LABEL
import dev.opencode.mobile.ui.sessionchat.WorkingTurnIndicator.TURN_DONE_HOLD_MS
import dev.opencode.mobile.ui.sessionchat.WorkingTurnIndicator.TURN_FADE_MS
import dev.opencode.mobile.ui.sessionchat.WorkingTurnIndicator.TURN_SETTLE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingTurnIndicatorTest {
    private val working = Chrome("Working", fading = false)

    // Active label

    @Test
    fun label_nullWhenIdle() {
        assertNull(WorkingTurnIndicator.labelForActiveTurn(statusLabel = null))
    }

    @Test
    fun label_showsTheStatus() {
        assertEquals("Working", WorkingTurnIndicator.labelForActiveTurn(statusLabel = "Working"))
        assertEquals("Retrying", WorkingTurnIndicator.labelForActiveTurn(statusLabel = "Retrying", isRetrying = true))
    }

    @Test
    fun label_sendingBeatsWorking() {
        assertEquals("Sending 25%", WorkingTurnIndicator.labelForActiveTurn("Working", sendProgressPercent = 25))
        assertEquals("Sending…", WorkingTurnIndicator.labelForActiveTurn("Working", sendProgressPercent = 0))
    }

    @Test
    fun label_retryingAndCompactingBeatSending() {
        assertEquals(
            "Retrying",
            WorkingTurnIndicator.labelForActiveTurn("Retrying", isRetrying = true, sendProgressPercent = 40),
        )
        assertEquals(
            SessionCompactChrome.STATUS_LABEL,
            WorkingTurnIndicator.labelForActiveTurn("Working", isCompacting = true, sendProgressPercent = 40),
        )
        assertEquals(SessionCompactChrome.STATUS_LABEL, WorkingTurnIndicator.labelForActiveTurn(null, isCompacting = true))
    }

    @Test
    fun label_aPendingRequestKeepsTheTurnActiveWithANullStatus() {
        assertEquals("Working", WorkingTurnIndicator.labelForActiveTurn(null, hasPendingRequest = true))
        assertEquals("Working", WorkingTurnIndicator.labelForActiveTurn("Working", hasPendingRequest = true))
    }

    @Test
    fun showsIdleMark_onlyForIdleLabel() {
        assertTrue(WorkingTurnIndicator.showsIdleMark(IDLE_LABEL))
        assertFalse(WorkingTurnIndicator.showsIdleMark("Working"))
        assertFalse(WorkingTurnIndicator.showsIdleMark(SessionCompactChrome.STATUS_LABEL))
    }

    // Stopped signal

    @Test
    fun lastTurnInterrupted_readsTheNewestReplysStopReason() {
        assertTrue(WorkingTurnIndicator.lastTurnInterrupted(listOf(user("u1"), assistant("a1", MessageStopReason.Interrupted))))
        assertFalse(WorkingTurnIndicator.lastTurnInterrupted(listOf(user("u1"), assistant("a1", null))))
        assertFalse(WorkingTurnIndicator.lastTurnInterrupted(listOf(user("u1"), assistant("a1", MessageStopReason.Length))))
        assertFalse(WorkingTurnIndicator.lastTurnInterrupted(emptyList()))
    }

    @Test
    fun lastTurnInterrupted_aNewerPromptStartsAnotherTurn() {
        val stopped = assistant("a1", MessageStopReason.Interrupted)

        assertFalse(WorkingTurnIndicator.lastTurnInterrupted(listOf(stopped, user("u2"))))
        assertTrue(WorkingTurnIndicator.lastTurnInterrupted(listOf(stopped, user("u2").copy(isWaitingForPickup = true))))
    }

    // Mounting

    @Test
    fun anIdleSessionStaysUnmounted() {
        val resolved = resolve(activeLabel = null, previous = Chrome.Unmounted)

        assertEquals(Chrome.Unmounted, resolved.chrome)
        assertNull(resolved.hold)
        assertNull(resolved.wakeAfterMs)
    }

    @Test
    fun aRunningSessionShowsItsStateImmediately() {
        assertEquals(working, resolve(activeLabel = "Working", previous = Chrome.Unmounted).chrome)
    }

    @Test
    fun onlyAnUnboundSessionUnmountsImmediately() {
        val resolved = resolve(activeLabel = "Working", hideImmediately = true, previous = working)

        assertEquals(Chrome.Unmounted, resolved.chrome)
        assertNull(resolved.hold)
    }

    // Settle, Done, fade

    @Test
    fun idleKeepsTheLastLabelThroughTheSettle() {
        val resolved = resolve(activeLabel = null, previous = Chrome("Compacting…", fading = false), nowMs = 1_000)

        assertEquals(Chrome("Compacting…", fading = false), resolved.chrome)
        assertEquals(Hold(1_000, "Compacting…"), resolved.hold)
        assertEquals(TURN_SETTLE_MS, resolved.wakeAfterMs)
    }

    @Test
    fun workResumingInsideTheSettleNeverShowsDone() {
        val settling = resolve(activeLabel = null, previous = working, nowMs = 0)
        val resumed = resolve("Working", previous = settling.chrome, hold = settling.hold, nowMs = TURN_SETTLE_MS - 1)

        assertEquals(working, resumed.chrome)
        assertNull(resumed.hold)
        assertNull(resumed.wakeAfterMs)
        // The next idle starts a full settle again.
        val again = resolve(activeLabel = null, previous = resumed.chrome, hold = resumed.hold, nowMs = TURN_SETTLE_MS)
        assertEquals(working, again.chrome)
        assertEquals(TURN_SETTLE_MS, again.wakeAfterMs)
    }

    @Test
    fun doneHoldsThenFadesThenUnmounts() {
        val hold = Hold(0, "Working")
        val doneStart = resolve(null, previous = working, hold = hold, nowMs = TURN_SETTLE_MS)
        assertEquals(Chrome(IDLE_LABEL, fading = false), doneStart.chrome)
        assertEquals(TURN_DONE_HOLD_MS, doneStart.wakeAfterMs)

        val fadeAt = TURN_SETTLE_MS + TURN_DONE_HOLD_MS
        assertFalse(resolve(null, previous = doneStart.chrome, hold = hold, nowMs = fadeAt - 1).chrome.fading)
        val fading = resolve(null, previous = doneStart.chrome, hold = hold, nowMs = fadeAt)
        assertEquals(Chrome(IDLE_LABEL, fading = true), fading.chrome)
        assertEquals(TURN_FADE_MS, fading.wakeAfterMs)

        val gone = resolve(null, previous = fading.chrome, hold = hold, nowMs = fadeAt + TURN_FADE_MS)
        assertEquals(Chrome.Unmounted, gone.chrome)
        assertNull(gone.hold)
        assertNull(gone.wakeAfterMs)
    }

    @Test
    fun workResumingDuringDoneReturnsToWorking() {
        val resumed =
            resolve("Working", previous = Chrome(IDLE_LABEL, fading = true), hold = Hold(0, "Working"), nowMs = 3_600)

        assertEquals(working, resumed.chrome)
        assertNull(resumed.hold)
    }

    // Stopped

    @Test
    fun aStoppedTurnLeavesQuietlyWithNoDone() {
        val hold = Hold(0, "Working")
        val settling = resolve(null, ending = Ending.Quiet, previous = working, hold = hold, nowMs = TURN_SETTLE_MS - 1)
        assertEquals(working, settling.chrome)

        val fading = resolve(null, ending = Ending.Quiet, previous = working, hold = hold, nowMs = TURN_SETTLE_MS)
        assertEquals(Chrome("Working", fading = true), fading.chrome)
        assertEquals(TURN_FADE_MS, fading.wakeAfterMs)

        val gone =
            resolve(
                null,
                ending = Ending.Quiet,
                previous = fading.chrome,
                hold = fading.hold,
                nowMs = TURN_SETTLE_MS + TURN_FADE_MS,
            )
        assertEquals(Chrome.Unmounted, gone.chrome)
    }

    @Test
    fun aLateWakeOfAStoppedTurnStillNeverSaysDone() {
        val fading = resolve(null, ending = Ending.Quiet, previous = working, hold = Hold(0, "Working"), nowMs = TURN_SETTLE_MS + 7)

        assertEquals(Chrome("Working", fading = true), fading.chrome)
    }

    @Test
    fun aStopReasonArrivingDuringDoneFadesDoneAtOnce() {
        val done = Chrome(IDLE_LABEL, fading = false)
        val fading = resolve(null, ending = Ending.Quiet, previous = done, hold = Hold(0, "Working"), nowMs = 1_000)

        assertEquals(Chrome(IDLE_LABEL, fading = true), fading.chrome)
        assertEquals(TURN_FADE_MS, fading.wakeAfterMs)
    }

    // Failed

    @Test
    fun aFailedTurnGivesThePillToItsFailureImmediately() {
        val resolved = resolve(null, ending = Ending.Failed, previous = working, nowMs = 1_000)

        assertEquals(Chrome.Unmounted, resolved.chrome)
        assertNull(resolved.hold)
        assertNull(resolved.wakeAfterMs)
    }

    // Disconnected

    @Test
    fun aLostConnectionKeepsTheLastState() {
        val resolved = resolve(null, connectionLost = true, previous = working, nowMs = 1_000)

        assertEquals(working, resolved.chrome)
        assertNull(resolved.hold)
        assertNull(resolved.wakeAfterMs)
    }

    @Test
    fun aTurnThatEndedWhileDisconnectedHidesWithNoDone() {
        val held = resolve(null, connectionLost = true, previous = working, nowMs = 1_000)
        val back = resolve(null, ending = Ending.Quiet, previous = held.chrome, hold = held.hold, nowMs = 9_000)
        assertEquals(working, back.chrome)

        val fading =
            resolve(null, ending = Ending.Quiet, previous = back.chrome, hold = back.hold, nowMs = 9_000 + TURN_SETTLE_MS)
        assertEquals(Chrome("Working", fading = true), fading.chrome)
    }

    @Test
    fun aDoneAlreadyShowingKeepsItsTimersOffline() {
        val done = Chrome(IDLE_LABEL, fading = false)
        val resolved = resolve(null, connectionLost = true, previous = done, hold = Hold(0, "Working"), nowMs = 1_000)

        assertEquals(done, resolved.chrome)
        assertEquals(TURN_SETTLE_MS + TURN_DONE_HOLD_MS - 1_000, resolved.wakeAfterMs)
    }

    private fun resolve(
        activeLabel: String?,
        ending: Ending = Ending.Completed,
        connectionLost: Boolean = false,
        hideImmediately: Boolean = false,
        previous: Chrome = Chrome.Unmounted,
        hold: Hold? = null,
        nowMs: Long = 0L,
    ) = WorkingTurnIndicator.resolveFooter(
        activeLabel = activeLabel,
        ending = ending,
        connectionLost = connectionLost,
        hideImmediately = hideImmediately,
        previous = previous,
        hold = hold,
        nowMs = nowMs,
    )

    private fun user(id: String) = Message(id = id, role = MessageRole.User, text = "Hi")

    private fun assistant(
        id: String,
        stopReason: MessageStopReason?,
    ) = Message(id = id, role = MessageRole.Assistant, text = "", stopReason = stopReason)
}
