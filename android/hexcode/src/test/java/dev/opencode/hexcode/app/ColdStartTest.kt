package dev.opencode.hexcode.app

import dev.opencode.hexcode.session.SessionSetup
import dev.opencode.hexcode.session.StatusSummary
import dev.opencode.hexcode.session.SummaryState
import dev.opencode.hexcode.session.TodayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** app-plan.md, Home › Cold start: the splash stays until the first state is ready, at most 1.5 s. */
class ColdStartTest {
    private var now = 1_000L
    private val card = StatusSummary.Empty("Thursday 6 November", started = false)

    @Test
    fun theSplashStaysUntilTheFirstStateIsReady() {
        val gate = ColdStartGate(clock = { now })
        assertTrue(gate.holding())
        now += 900
        assertTrue(gate.holding())
        gate.ready()
        assertFalse(gate.holding())
    }

    @Test
    fun theSplashGoesAfterOneAndAHalfSecondsWhateverHappens() {
        val gate = ColdStartGate(clock = { now })
        now += 1_499
        assertTrue(gate.holding())
        now += 1
        assertFalse(gate.holding())
    }

    @Test
    fun onceGoneItNeverComesBack() {
        val gate = ColdStartGate(clock = { now })
        gate.ready()
        now -= 10
        assertFalse(gate.holding())
    }

    @Test
    fun todaysSessionFoundAndItsSummaryReadIsReady() {
        assertTrue(ColdStart.settled(TodayState(setup = SessionSetup.Ready("ses_1"), summary = SummaryState(card))))
    }

    @Test
    fun aSummaryThatFailedWithNoCardIsReadyToo() {
        assertTrue(ColdStart.settled(TodayState(setup = SessionSetup.Ready("ses_1"), summary = SummaryState(failed = true))))
    }

    @Test
    fun todaysSessionFoundWhileItsSummaryIsStillOnItsWayIsNotReady() {
        assertFalse(ColdStart.settled(TodayState(setup = SessionSetup.Ready("ses_1"), summary = SummaryState(loading = true))))
    }

    @Test
    fun aSavedCardWhileSetupRunsIsNotReady() {
        assertFalse(ColdStart.settled(TodayState(setup = SessionSetup.Opening, summary = SummaryState(card))))
    }

    @Test
    fun setupFailureAndSignedOutAreReady() {
        assertTrue(ColdStart.settled(TodayState(setup = SessionSetup.Failed())))
        assertTrue(ColdStart.settled(TodayState(setup = SessionSetup.SignedOut)))
    }

    @Test
    fun theStatusAreaShowsNothingWhileSetupRunsWithNoSavedCard() {
        assertEquals(0, ColdStart.cardStage(TodayState(setup = SessionSetup.Opening)))
    }

    @Test
    fun theStatusAreaStepsUpAsTheCardArrives() {
        val opening = ColdStart.cardStage(TodayState(setup = SessionSetup.Opening))
        val meta = ColdStart.cardStage(TodayState(setup = SessionSetup.Ready("ses_1"), summary = SummaryState(loading = true)))
        val shown = ColdStart.cardStage(TodayState(setup = SessionSetup.Ready("ses_1"), summary = SummaryState(card)))
        assertTrue(opening < meta)
        assertTrue(meta < shown)
        assertEquals(shown, ColdStart.cardStage(TodayState(setup = SessionSetup.Opening, summary = SummaryState(card))))
        assertEquals(shown, ColdStart.cardStage(TodayState(setup = SessionSetup.Failed())))
        assertEquals(shown, ColdStart.cardStage(TodayState(setup = SessionSetup.SignedOut)))
    }
}
