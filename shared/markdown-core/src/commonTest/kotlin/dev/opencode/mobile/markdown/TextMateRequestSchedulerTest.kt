package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextMateRequestSchedulerTest {
    @Test
    fun latestWinsCancelsOlderQueuedRevision() {
        val clock = FakeClock(0)
        val scheduler = TextMateRequestScheduler(clock = clock::millis)
        val first = scheduler.admit(request(key = "a", revision = 1, intent = HighlightIntent.BackgroundHistory))
        assertNotNull(first.ticketId)

        val second = scheduler.admit(request(key = "a", revision = 2, intent = HighlightIntent.InteractiveFinal))
        assertNotNull(second.ticketId)
        assertEquals(1, second.completed.size)
        assertEquals(TextMateParseOutcome.Cancelled, second.completed.single().result.outcome)
        assertEquals(TextMateSchedulingReason.Supersession, second.completed.single().reason)

        val job = scheduler.pickNext()
        assertEquals(2L, job?.request?.revision?.value)
    }

    @Test
    fun interactiveAtRiskBeatsBackground() {
        val clock = FakeClock(0)
        val scheduler = TextMateRequestScheduler(clock = clock::millis)
        scheduler.admit(request(key = "bg", revision = 1, intent = HighlightIntent.BackgroundHistory, source = "x"))
        clock.now = 20
        scheduler.admit(request(key = "fg", revision = 1, intent = HighlightIntent.InteractiveFinal, source = "y"))

        val job = scheduler.pickNext()
        assertEquals("fg", job?.request?.key?.value)
    }

    @Test
    fun queuePressureDropsBackgroundBeforeInteractive() {
        val clock = FakeClock(0)
        val scheduler =
            TextMateRequestScheduler(
                limits = TextMateSchedulerLimits(maxPendingInteractive = 2, maxPendingHistory = 1),
                clock = clock::millis,
            )
        scheduler.admit(request(key = "h1", revision = 1, intent = HighlightIntent.BackgroundHistory, source = "a"))
        val overflow =
            scheduler.admit(request(key = "h2", revision = 1, intent = HighlightIntent.VisibleHistory, source = "b"))
        assertTrue(overflow.completed.any { it.result.outcome == TextMateParseOutcome.BudgetExceeded })
        assertEquals(TextMateSchedulingReason.QueuePressure, overflow.completed.single().reason)

        scheduler.admit(request(key = "i1", revision = 1, intent = HighlightIntent.InteractiveFinal, source = "c"))
        scheduler.admit(request(key = "i2", revision = 1, intent = HighlightIntent.InteractiveFinal, source = "d"))
        val thirdInteractive =
            scheduler.admit(request(key = "i3", revision = 1, intent = HighlightIntent.InteractiveFinal, source = "e"))
        assertTrue(thirdInteractive.completed.any { it.result.outcome == TextMateParseOutcome.BudgetExceeded })
        assertEquals(2, scheduler.snapshot().pendingInteractive)
    }

    @Test
    fun trimModerateDropsBackgroundAndDisablesSpeculativeWork() {
        val clock = FakeClock(0)
        val scheduler = TextMateRequestScheduler(clock = clock::millis)
        scheduler.admit(request(key = "bg", revision = 1, intent = HighlightIntent.BackgroundHistory))
        val dropped = scheduler.trim(MemoryPressure.Moderate)
        assertEquals(1, dropped.size)
        assertEquals(TextMateParseOutcome.BudgetExceeded, dropped.single().result.outcome)

        val rejected = scheduler.admit(request(key = "bg2", revision = 1, intent = HighlightIntent.BackgroundHistory))
        assertNull(rejected.ticketId)
        assertEquals(TextMateParseOutcome.BudgetExceeded, rejected.completed.single().result.outcome)
    }

    @Test
    fun finishActiveRewritesSupersededWorkToCancelled() {
        val clock = FakeClock(0)
        val scheduler = TextMateRequestScheduler(clock = clock::millis)
        scheduler.admit(request(key = "a", revision = 1, intent = HighlightIntent.InteractiveFinal))
        val job = assertNotNull(scheduler.pickNext())
        scheduler.admit(request(key = "a", revision = 2, intent = HighlightIntent.InteractiveFinal))
        assertTrue(scheduler.snapshot().activeCancelRequested)

        val completed =
            scheduler.finishActive(
                job.ticketId,
                emptyResult(
                    request = job.request,
                    outcome = TextMateParseOutcome.Highlighted,
                    queueWaitNanoseconds = 0,
                    schedulingReason = null,
                ),
            )
        assertEquals(TextMateParseOutcome.Cancelled, completed.single().result.outcome)
    }

    @Test
    fun blockedActiveJobDoesNotPreventLaterPickAfterFinish() {
        val clock = FakeClock(0)
        val scheduler = TextMateRequestScheduler(clock = clock::millis)
        scheduler.admit(request(key = "slow", revision = 1, intent = HighlightIntent.BackgroundHistory))
        val slow = assertNotNull(scheduler.pickNext())
        assertNull(scheduler.pickNext())

        scheduler.admit(request(key = "fast", revision = 1, intent = HighlightIntent.InteractiveFinal))
        assertNull(scheduler.pickNext())

        scheduler.finishActive(
            slow.ticketId,
            emptyResult(
                request = slow.request,
                outcome = TextMateParseOutcome.Highlighted,
                queueWaitNanoseconds = 0,
                schedulingReason = null,
            ),
        )
        assertEquals("fast", scheduler.pickNext()?.request?.key?.value)
    }

    private fun request(
        key: String,
        revision: Long,
        intent: HighlightIntent,
        source: String = "source",
    ): HighlightRequest =
        HighlightRequest(
            key = CodeBlockKey(key),
            revision = HighlightRevision(revision),
            language = "kotlin",
            source = source,
            intent = intent,
            policyEpoch = 1,
        )

    private class FakeClock(
        var now: Long,
    ) {
        fun millis(): Long = now
    }
}
