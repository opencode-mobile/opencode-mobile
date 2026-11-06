package dev.opencode.hexcode.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class SummaryRefresherTest {
    private class FakeSource : SummarySource {
        val prompts = mutableListOf<String>()
        val replies = ArrayDeque<String>()
        var gate: CompletableDeferred<Unit>? = null
        var fail = false

        override suspend fun summarise(prompt: String): String {
            prompts += prompt
            gate?.await()
            if (fail) error("HTTP 500")
            return replies.removeFirst()
        }
    }

    private var now = 1_000_000L

    private fun TestScope.refresher(source: SummarySource) = SummaryRefresher(this, source, clock = { now })

    @Test
    fun twoRefreshesAtOnceMakeOneRequest() =
        runTest {
            val source = FakeSource().apply { replies += "## Task\nNow: Working." }
            val summary = refresher(source)
            assertTrue(summary.refresh())
            assertFalse(summary.refresh())
            advanceUntilIdle()
            assertEquals(1, source.prompts.size)
            assertEquals(SessionPrompts.SUMMARY, source.prompts.single())
            assertEquals(StatusSummary.Card("Task", listOf(NowLine("Working.")), null, emptyList()), summary.state.value.summary)
        }

    @Test
    fun aRefreshAfterTheLastOneEndedMakesAnother() =
        runTest {
            val source = FakeSource().apply { replies += listOf("## One\nNow: First.", "## Two\nNow: Second.") }
            val summary = refresher(source)
            summary.refresh()
            advanceUntilIdle()
            summary.refresh()
            advanceUntilIdle()
            assertEquals(2, source.prompts.size)
            assertEquals("Two", (summary.state.value.summary as StatusSummary.Card).title)
        }

    @Test
    fun itIsLoadingWhileTheRequestRuns() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val source = FakeSource().apply { replies += "## Task\nNow: Working." }.also { it.gate = gate }
            val summary = refresher(source)
            summary.refresh()
            runCurrent()
            assertTrue(summary.state.value.loading)
            assertEquals(RefreshLabel.Updating, summary.state.value.label(now))
            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(summary.state.value.loading)
            assertEquals(now, summary.state.value.updatedAt)
        }

    @Test
    fun aFailedRequestKeepsTheLastCard() =
        runTest {
            val source = FakeSource().apply { replies += "## Task\nNow: Working." }
            val summary = refresher(source)
            summary.refresh()
            advanceUntilIdle()
            val card = summary.state.value.summary
            val updatedAt = summary.state.value.updatedAt
            source.fail = true
            now += 120_000
            summary.refresh()
            advanceUntilIdle()
            assertEquals(card, summary.state.value.summary)
            assertEquals(updatedAt, summary.state.value.updatedAt)
            assertTrue(summary.state.value.failed)
            assertEquals(RefreshLabel.Failed, summary.state.value.label(now))
            // The next success clears it.
            source.fail = false
            source.replies += "## Task\nNow: Still working."
            summary.refresh()
            advanceUntilIdle()
            assertFalse(summary.state.value.failed)
            assertEquals("Still working.", (summary.state.value.summary as StatusSummary.Card).nowText)
        }

    @Test
    fun anEmptyReplyCountsAsAFailure() =
        runTest {
            val source = FakeSource().apply { replies += "  \n" }
            val summary = refresher(source)
            summary.refresh()
            advanceUntilIdle()
            assertTrue(summary.state.value.failed)
            assertNull(summary.state.value.summary)
        }

    @Test
    fun aNewSessionShowsTheEmptyCardWithoutARequest() =
        runTest {
            val source = FakeSource()
            val summary = refresher(source)
            summary.showEmpty(LocalDate.of(2026, 11, 5))
            advanceUntilIdle()
            assertEquals(0, source.prompts.size)
            assertEquals(StatusSummary.Empty("Thursday 5 November"), summary.state.value.summary)
            assertEquals(RefreshLabel.Now, summary.state.value.label(now))
        }

    @Test
    fun theLabelSaysNowUnder45sThenMinutesAgo() {
        val state = SummaryState(StatusSummary.Fallback("x"), updatedAt = 0)
        assertEquals(RefreshLabel.Now, state.label(0))
        assertEquals(RefreshLabel.Now, state.label(44_999))
        assertEquals(RefreshLabel.MinutesAgo(1), state.label(45_000))
        assertEquals(RefreshLabel.MinutesAgo(1), state.label(89_999))
        assertEquals(RefreshLabel.MinutesAgo(2), state.label(90_000))
        assertEquals(RefreshLabel.MinutesAgo(3), state.label(180_000))
        assertEquals(RefreshLabel.MinutesAgo(125), state.label(125 * 60_000L))
    }

    @Test
    fun updatingBeatsFailedBeatsTheAge() {
        val state = SummaryState(StatusSummary.Fallback("x"), updatedAt = 0, loading = true, failed = true)
        assertEquals(RefreshLabel.Updating, state.label(600_000))
        assertEquals(RefreshLabel.Failed, state.copy(loading = false).label(600_000))
        assertNull(SummaryState().label(0))
    }

    @Test
    fun noReplyWithin60sCountsAsAFailure() =
        runTest {
            val source = FakeSource().apply { replies += "## Task\nNow: Working." }
            val summary = refresher(source)
            summary.refresh()
            advanceUntilIdle()
            val card = summary.state.value.summary
            source.gate = CompletableDeferred()
            summary.refresh()
            advanceTimeBy(59_000)
            assertTrue(summary.state.value.loading)
            advanceTimeBy(2_000)
            assertFalse(summary.state.value.loading)
            assertTrue(summary.state.value.failed)
            assertEquals(card, summary.state.value.summary)
            source.gate?.complete(Unit)
        }
}
