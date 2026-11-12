package dev.opencode.hexcode.app

import dev.opencode.hexcode.session.SessionSetup
import dev.opencode.hexcode.session.StatusSummary
import dev.opencode.hexcode.session.SummaryState
import dev.opencode.hexcode.session.TodayState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * app-plan.md, Home › Cold start: the splash stays only while the app reads what's saved on the phone, never for the
 * network; until there's a card, the date card says what the app is doing; the speech model loads about 1 s after the
 * first frame, or at once on the first talk press.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ColdStartTest {
    private var now = 1_000L
    private val card = StatusSummary.Empty("Thursday 6 November", started = false)

    @Test
    fun theSplashStaysUntilTheSavedStateIsRead() {
        val gate = ColdStartGate(clock = { now })
        assertTrue(gate.holding())
        now += 100
        assertTrue(gate.holding())
        gate.ready()
        assertFalse(gate.holding())
    }

    @Test
    fun theSplashGoesAfterHalfASecondWhateverHappens() {
        val gate = ColdStartGate(clock = { now })
        now += 499
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
    fun whatWaitsForTheSplashToGoRunsOnceItIs() =
        runTest {
            val gate = ColdStartGate(clock = { now })
            val gone = async { gate.awaitGone() }
            runCurrent()
            assertFalse(gone.isCompleted)
            gate.ready()
            runCurrent()
            assertTrue(gone.isCompleted)
        }

    @Test
    fun theSplashTimingOutCountsAsGoneToo() =
        runTest {
            val gate = ColdStartGate(clock = { now })
            val gone = async { gate.awaitGone() }
            now += ColdStart.TIMEOUT_MS
            gate.holding()
            runCurrent()
            assertTrue(gone.isCompleted)
        }

    @Test
    fun theSavedStateReadIsReadyWithNoCardAndSetupStillRunning() {
        assertTrue(ColdStart.settled(TodayState(setup = SessionSetup.Opening, restored = true)))
    }

    @Test
    fun theSavedCardReadIsReadyWhileSetupRuns() {
        assertTrue(ColdStart.settled(TodayState(setup = SessionSetup.Opening, summary = SummaryState(card), restored = true)))
    }

    @Test
    fun beforeTheSavedStateIsReadItIsNotReady() {
        assertFalse(ColdStart.settled(TodayState(setup = SessionSetup.Opening)))
    }

    @Test
    fun anythingSetupFoundIsReadyToo() {
        assertTrue(ColdStart.settled(TodayState(setup = SessionSetup.Ready("ses_1"), summary = SummaryState(loading = true))))
        assertTrue(ColdStart.settled(TodayState(setup = SessionSetup.Failed())))
        assertTrue(ColdStart.settled(TodayState(setup = SessionSetup.SignedOut)))
    }

    @Test
    fun theDateCardSaysItIsConnectingFromTheFirstFrame() {
        assertEquals(CatchingUp.Connecting, ColdStart.catchingUp(TodayState()))
        assertEquals(CatchingUp.Connecting, ColdStart.catchingUp(TodayState(setup = SessionSetup.Opening, restored = true)))
    }

    @Test
    fun onceTodaysSessionIsFoundItSaysItIsOpeningIt() {
        assertEquals(CatchingUp.Opening, ColdStart.catchingUp(TodayState(setup = SessionSetup.Ready("ses_1"))))
    }

    @Test
    fun whileTheFirstSummaryIsOnItsWayItSaysSo() {
        val today = TodayState(setup = SessionSetup.Ready("ses_1"), summary = SummaryState(loading = true))
        assertEquals(CatchingUp.Summarising, ColdStart.catchingUp(today))
        // A retry after a failure is running again.
        assertEquals(CatchingUp.Summarising, ColdStart.catchingUp(today.copy(summary = SummaryState(loading = true, failed = true))))
    }

    @Test
    fun aFailedFirstSummarySaysItCouldNotSummarise() {
        val today = TodayState(setup = SessionSetup.Ready("ses_1"), summary = SummaryState(failed = true))
        assertEquals(CatchingUp.Failed, ColdStart.catchingUp(today))
    }

    @Test
    fun anyCardTakesTheDateCardsPlace() {
        assertNull(ColdStart.catchingUp(TodayState(setup = SessionSetup.Opening, summary = SummaryState(card), restored = true)))
        assertNull(ColdStart.catchingUp(TodayState(setup = SessionSetup.Ready("ses_1"), summary = SummaryState(card, loading = true))))
        assertNull(ColdStart.catchingUp(TodayState(setup = SessionSetup.Ready("ses_1"), summary = SummaryState(card, failed = true))))
        val working = StatusSummary.Working("Thursday 6 November")
        assertNull(ColdStart.catchingUp(TodayState(setup = SessionSetup.Ready("ses_1"), summary = SummaryState(working))))
    }

    @Test
    fun failuresKeepTheirOwnViews() {
        assertNull(ColdStart.catchingUp(TodayState(setup = SessionSetup.Failed())))
        assertNull(ColdStart.catchingUp(TodayState(setup = SessionSetup.Failed(retrying = true))))
        assertNull(ColdStart.catchingUp(TodayState(setup = SessionSetup.SignedOut)))
    }

    @Test
    fun theSpeechModelLoadsAboutASecondAfterTheFirstFrame() =
        runTest {
            var loads = 0
            val speech = DeferredLoad(backgroundScope, ColdStart.SPEECH_DELAY_MS) { loads += 1 }
            runCurrent()
            assertEquals(0, loads)
            speech.afterFirstFrame()
            advanceTimeBy(999)
            runCurrent()
            assertEquals(0, loads)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, loads)
        }

    @Test
    fun aTalkPressBeforeThenLoadsItAtOnceAndOnlyOnce() =
        runTest {
            var loads = 0
            val speech = DeferredLoad(backgroundScope, ColdStart.SPEECH_DELAY_MS) { loads += 1 }
            speech.afterFirstFrame()
            advanceTimeBy(300)
            speech.now()
            assertEquals(1, loads)
            advanceTimeBy(2_000)
            runCurrent()
            speech.now()
            speech.afterFirstFrame()
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(1, loads)
        }
}
