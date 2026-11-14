package dev.opencode.mobile.ui.main

import dev.opencode.mobile.ui.main.HomeBranchLookups.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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

            lookups.lookUp(listOf("/repo"))
            runCurrent()
            assertEquals(emptyMap<String, String>(), lookups.branches)

            advanceTimeBy(1_001)
            assertEquals(mapOf("/repo" to "feature/tax-rounding"), lookups.branches)
            assertEquals(2, calls.size)
        }

    @Test
    fun aFolderThatNeverSettlesStopsAfterTheRetries() =
        runTest {
            var calls = 0
            val lookups =
                lookups {
                    calls += 1
                    Outcome.Unknown
                }

            lookups.lookUp(listOf("/plain"))
            advanceUntilIdle()
            assertEquals(3, calls)
            assertEquals(emptyMap<String, String>(), lookups.branches)

            // Nothing asks again on its own; the next inventory change or refresh does.
            advanceTimeBy(60_000)
            assertEquals(3, calls)
            lookups.lookUp(listOf("/plain"))
            advanceUntilIdle()
            assertEquals(6, calls)
        }

    @Test
    fun anUnknownAnswerKeepsTheLastKnownBranch() =
        runTest {
            val answers = ArrayDeque(listOf<Outcome>(Outcome.Branch("dev"), Outcome.Unknown, Outcome.Unknown, Outcome.Unknown))
            val lookups = lookups { answers.removeFirst() }

            lookups.lookUp(listOf("/repo"))
            advanceUntilIdle()
            lookups.refresh()
            lookups.lookUp(listOf("/repo"))
            advanceUntilIdle()

            assertEquals(mapOf("/repo" to "dev"), lookups.branches)
        }

    @Test
    fun aDecidedMissingBranchClearsIt() =
        runTest {
            val answers = ArrayDeque(listOf<Outcome>(Outcome.Branch("dev"), Outcome.Branch(null)))
            val lookups = lookups { answers.removeFirst() }

            lookups.lookUp(listOf("/repo"))
            advanceUntilIdle()
            lookups.refresh()
            lookups.lookUp(listOf("/repo"))
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

            lookups.lookUp(listOf("/repo"))
            runCurrent()
            lookups.refresh()
            lookups.lookUp(listOf("/repo"))
            runCurrent()
            first.complete(Outcome.Branch("old"))
            advanceUntilIdle()

            assertEquals(mapOf("/repo" to "new"), lookups.branches)
        }

    @Test
    fun answeredDirectoriesAreNotAskedAgainUntilRefresh() =
        runTest {
            var calls = 0
            val lookups =
                lookups {
                    calls += 1
                    Outcome.Branch("dev")
                }

            lookups.lookUp(listOf("/repo"))
            advanceUntilIdle()
            lookups.lookUp(listOf("/repo"))
            advanceUntilIdle()
            assertEquals(1, calls)

            lookups.refresh()
            lookups.lookUp(listOf("/repo"))
            advanceUntilIdle()
            assertEquals(2, calls)
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
            lookups.lookUp(listOf("/repo", "/wt/gone"))
            advanceUntilIdle()
            changes.clear()

            lookups.seed(mapOf("/repo" to "stale", "/wt/gone" to "stale", "/wt/tax" to "feature/tax-rounding"))
            assertEquals(mapOf("/repo" to "main", "/wt/tax" to "feature/tax-rounding"), lookups.branches)
            assertEquals(listOf(lookups.branches), changes)

            // Nothing new to fill: no change published.
            lookups.seed(mapOf("/wt/tax" to "other"))
            assertEquals(1, changes.size)

            // An unknown live answer keeps the cached branch; a settled one replaces it.
            lookups.lookUp(listOf("/wt/tax"))
            advanceUntilIdle()
            assertEquals("feature/tax-rounding", lookups.branches["/wt/tax"])
            answers["/wt/tax"] = Outcome.Branch("feature/tax-v2")
            lookups.refresh()
            lookups.lookUp(listOf("/wt/tax"))
            advanceUntilIdle()
            assertEquals("feature/tax-v2", lookups.branches["/wt/tax"])
        }

    private fun TestScope.lookups(fetch: suspend (String) -> Outcome): HomeBranchLookups =
        HomeBranchLookups(scope = this, fetch = fetch, onChange = {})
}
