package dev.opencode.mobile.ui.main

import dev.opencode.mobile.ui.main.HomeBranchLookups.Outcome
import dev.opencode.mobile.ui.main.HomeBranchLookups.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeBranchLookupsTest {
    @Test
    fun anUnsettledAnswerIsAskedAgainAndTheBranchShowsWithoutRefresh() =
        runTest {
            val answers = ArrayDeque(listOf<Outcome>(Outcome.Unknown, Outcome.Branch("feature/tax-rounding")))
            val calls = mutableListOf<String>()
            val lookups =
                lookups { directory ->
                    calls += directory
                    answers.removeFirst()
                }

            lookups.show(Source.HomeRows, listOf("/repo"))
            runCurrent()
            assertEquals(emptyMap<String, String>(), lookups.branches)

            advanceTimeBy(1_001)
            assertEquals(mapOf("/repo" to "feature/tax-rounding"), lookups.branches)
            assertEquals(2, calls.size)
        }

    @Test
    fun aColdMainCheckoutThatSettlesLateStillGetsItsBranch() =
        runTest {
            // A cold v2 location can take longer than a few seconds to detect git.
            val answers = ArrayDeque(listOf<Outcome>(Outcome.Unknown, Outcome.Unknown, Outcome.Unknown, Outcome.Branch("main")))
            val lookups = lookups { answers.removeFirst() }

            lookups.show(Source.MainCheckouts, listOf("/repo"))
            advanceTimeBy(1_000 + 3_000 + 1)
            assertEquals(emptyMap<String, String>(), lookups.branches)
            advanceTimeBy(10_000)
            assertEquals(mapOf("/repo" to "main"), lookups.branches)
        }

    @Test
    fun aFolderThatNeverSettlesStopsAfterTheRetriesUntilRefresh() =
        runTest {
            var calls = 0
            val lookups =
                lookups {
                    calls += 1
                    Outcome.Unknown
                }

            lookups.show(Source.HomeRows, listOf("/plain"))
            advanceUntilIdle()
            assertEquals(1 + HomeBranchLookups.DEFAULT_RETRY_DELAYS_MS.size, calls)
            assertEquals(emptyMap<String, String>(), lookups.branches)

            // Showing it again (Home reports on every scroll) asks nothing; a refresh does.
            lookups.show(Source.HomeRows, emptyList())
            lookups.show(Source.HomeRows, listOf("/plain"))
            lookups.show(Source.Picker, listOf("/plain"))
            advanceUntilIdle()
            assertEquals(5, calls)
            lookups.refresh()
            advanceUntilIdle()
            assertEquals(10, calls)
        }

    @Test
    fun aDecidedAnswerIsNotAskedAgainUntilRefresh() =
        runTest {
            var calls = 0
            val lookups =
                lookups {
                    calls += 1
                    Outcome.Branch(null)
                }

            lookups.show(Source.HomeRows, listOf("/gone"))
            advanceUntilIdle()
            lookups.show(Source.HomeRows, emptyList())
            lookups.show(Source.HomeRows, listOf("/gone"))
            advanceUntilIdle()
            assertEquals(1, calls)

            lookups.refresh()
            advanceUntilIdle()
            assertEquals(2, calls)
        }

    @Test
    fun atMostThreeLookupsAreInFlightAtOnce() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var inFlight = 0
            var peak = 0
            val lookups =
                lookups {
                    inFlight += 1
                    peak = maxOf(peak, inFlight)
                    gate.await()
                    inFlight -= 1
                    Outcome.Branch("dev")
                }

            lookups.show(Source.HomeRows, (1..10).map { "/wt/$it" })
            runCurrent()
            assertEquals(3, peak)

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(3, peak)
            assertEquals(10, lookups.branches.size)
        }

    @Test
    fun mainCheckoutsAreAskedBeforeRows() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val calls = mutableListOf<String>()
            val lookups =
                lookups { directory ->
                    calls += directory
                    gate.await()
                    Outcome.Branch("dev")
                }

            lookups.show(Source.HomeRows, listOf("/wt/a", "/wt/b", "/wt/c", "/wt/d"))
            lookups.show(Source.MainCheckouts, listOf("/repo"))
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()

            // The first three started before the main checkout was known; it goes next.
            assertEquals(listOf("/wt/a", "/wt/b", "/wt/c", "/repo", "/wt/d"), calls)
        }

    @Test
    fun aQueuedDirectoryNothingShowsAnyMoreIsNeverAsked() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val calls = mutableListOf<String>()
            val lookups =
                lookups { directory ->
                    calls += directory
                    gate.await()
                    Outcome.Branch("dev")
                }

            // A fast scroll: rows 1..10 pass by, rows 9 and 10 stay.
            lookups.show(Source.HomeRows, (1..10).map { "/wt/$it" })
            runCurrent()
            lookups.show(Source.HomeRows, listOf("/wt/9", "/wt/10"))
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("/wt/1", "/wt/2", "/wt/3", "/wt/9", "/wt/10"), calls)
            // A request that was already running keeps its answer.
            assertEquals(setOf("/wt/1", "/wt/2", "/wt/3", "/wt/9", "/wt/10"), lookups.branches.keys)
        }

    @Test
    fun aRetryWaitsOnlyWhileTheDirectoryIsShown() =
        runTest {
            var calls = 0
            val lookups =
                lookups {
                    calls += 1
                    Outcome.Unknown
                }

            lookups.show(Source.HomeRows, listOf("/wt/x"))
            runCurrent()
            assertEquals(1, calls)
            lookups.show(Source.HomeRows, emptyList())
            advanceUntilIdle()
            assertEquals(1, calls)

            // Shown again later: a fresh ladder.
            lookups.show(Source.HomeRows, listOf("/wt/x"))
            runCurrent()
            assertEquals(2, calls)
        }

    @Test
    fun aDirectoryShownByTwoSourcesStaysWhileEitherShowsIt() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val calls = mutableListOf<String>()
            val lookups =
                HomeBranchLookups(
                    scope = this,
                    fetch = { directory ->
                        calls += directory
                        gate.await()
                        Outcome.Branch("dev")
                    },
                    onChange = {},
                    maxInFlight = 1,
                )

            lookups.show(Source.HomeRows, listOf("/wt/a", "/wt/b"))
            lookups.show(Source.SessionChat, listOf("/wt/b"))
            runCurrent()
            lookups.show(Source.HomeRows, emptyList())
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("/wt/a", "/wt/b"), calls)
        }

    @Test
    fun anUnknownAnswerKeepsTheLastKnownBranch() =
        runTest {
            val answers = ArrayDeque(listOf<Outcome>(Outcome.Branch("dev")))
            val lookups = lookups { answers.removeFirstOrNull() ?: Outcome.Unknown }

            lookups.show(Source.HomeRows, listOf("/repo"))
            advanceUntilIdle()
            lookups.refresh()
            advanceUntilIdle()

            assertEquals(mapOf("/repo" to "dev"), lookups.branches)
        }

    @Test
    fun aDecidedMissingBranchClearsIt() =
        runTest {
            val answers = ArrayDeque(listOf<Outcome>(Outcome.Branch("dev"), Outcome.Branch(null)))
            val lookups = lookups { answers.removeFirst() }

            lookups.show(Source.HomeRows, listOf("/repo"))
            advanceUntilIdle()
            lookups.refresh()
            advanceUntilIdle()

            assertEquals(emptyMap<String, String>(), lookups.branches)
        }

    @Test
    fun anOlderLookupCannotOverwriteANewerAnswer() =
        runTest {
            val first = CompletableDeferred<Outcome>()
            var call = 0
            val lookups =
                lookups {
                    call += 1
                    if (call == 1) first.await() else Outcome.Branch("new")
                }

            lookups.show(Source.HomeRows, listOf("/repo"))
            runCurrent()
            lookups.refresh()
            runCurrent()
            first.complete(Outcome.Branch("old"))
            advanceUntilIdle()

            assertEquals(mapOf("/repo" to "new"), lookups.branches)
        }

    @Test
    fun refreshAsksOnlyWhatIsShown() =
        runTest {
            val calls = mutableListOf<String>()
            val lookups =
                lookups { directory ->
                    calls += directory
                    Outcome.Branch("dev")
                }

            lookups.show(Source.HomeRows, listOf("/wt/a", "/wt/b"))
            advanceUntilIdle()
            lookups.show(Source.HomeRows, listOf("/wt/b"))
            calls.clear()
            lookups.refresh()
            advanceUntilIdle()

            assertEquals(listOf("/wt/b"), calls)
        }

    @Test
    fun seedFillsOnlyUnansweredDirectoriesAndALiveAnswerReplacesIt() =
        runTest {
            val changes = mutableListOf<Map<String, String>>()
            val answers = mutableMapOf<String, Outcome>("/repo" to Outcome.Branch("main"), "/wt/gone" to Outcome.Branch(null))
            val lookups =
                HomeBranchLookups(
                    scope = this,
                    fetch = { answers[it] ?: Outcome.Unknown },
                    onChange = { changes += it },
                    retryDelaysMs = emptyList(),
                )
            lookups.show(Source.HomeRows, listOf("/repo", "/wt/gone"))
            advanceUntilIdle()
            changes.clear()

            lookups.seed(mapOf("/repo" to "stale", "/wt/gone" to "stale", "/wt/tax" to "feature/tax-rounding"))
            assertEquals(mapOf("/repo" to "main", "/wt/tax" to "feature/tax-rounding"), lookups.branches)
            assertEquals(listOf(lookups.branches), changes)

            // Nothing new to fill: no change published.
            lookups.seed(mapOf("/wt/tax" to "other"))
            assertEquals(1, changes.size)

            // An unknown live answer keeps the cached branch; a settled one replaces it.
            lookups.show(Source.Picker, listOf("/wt/tax"))
            advanceUntilIdle()
            assertEquals("feature/tax-rounding", lookups.branches["/wt/tax"])
            answers["/wt/tax"] = Outcome.Branch("feature/tax-v2")
            lookups.refresh()
            advanceUntilIdle()
            assertEquals("feature/tax-v2", lookups.branches["/wt/tax"])
        }

    @Test
    fun clearingWhileALookupTurnsItsCancellationIntoAnAnswerDoesNotCrash() =
        runTest {
            // Like a repository that wraps the cancelled request in a transport error, on an
            // immediate dispatcher where the cancelled job resumes inside cancel().
            val immediate = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
            var changes = 0
            val lookups =
                HomeBranchLookups(
                    scope = immediate,
                    fetch = {
                        try {
                            awaitCancellation()
                        } catch (_: CancellationException) {
                            Outcome.Unknown
                        }
                    },
                    onChange = { changes += 1 },
                    retryDelaysMs = emptyList(),
                )

            lookups.show(Source.HomeRows, listOf("/a", "/b", "/c", "/d"))
            lookups.clear()
            advanceUntilIdle()

            assertEquals(emptyMap<String, String>(), lookups.branches)
            assertEquals(0, changes)
            immediate.cancel()
        }

    @Test
    fun anImmediateDispatcherStillLimitsAndFinishesEveryLookup() =
        runTest {
            val immediate = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
            val lookups =
                HomeBranchLookups(
                    scope = immediate,
                    fetch = { directory -> if (directory == "/wt/plain") Outcome.Unknown else Outcome.Branch("dev") },
                    onChange = {},
                    retryDelaysMs = listOf(0L),
                )

            lookups.show(Source.HomeRows, (1..5).map { "/wt/$it" } + "/wt/plain")
            advanceUntilIdle()

            assertEquals((1..5).map { "/wt/$it" }.toSet(), lookups.branches.keys)
            immediate.cancel()
        }

    private fun TestScope.lookups(fetch: suspend (String) -> Outcome): HomeBranchLookups =
        HomeBranchLookups(scope = this, fetch = fetch, onChange = {})
}
