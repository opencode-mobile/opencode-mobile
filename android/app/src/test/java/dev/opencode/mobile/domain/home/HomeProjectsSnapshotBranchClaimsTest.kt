package dev.opencode.mobile.domain.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeProjectsSnapshotBranchClaimsTest {
    @Test
    fun claimsOfSeveralOwnersAddUpAndEachWithdrawsOnlyItsOwn() =
        runTest {
            val snapshot = HomeProjectsSnapshot()
            val shown = mutableListOf<Set<String>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { snapshot.shownBranchDirectories.collect { shown += it } }
            val move = Any()
            val draft = Any()

            snapshot.showBranches(move, listOf("/repo", " /wt/a/ "))
            snapshot.showBranches(draft, listOf("/wt/a", "/wt/b", ""))
            advanceUntilIdle()
            assertEquals(setOf("/repo", "/wt/a", "/wt/b"), shown.last())

            snapshot.showBranches(move, emptyList())
            advanceUntilIdle()
            assertEquals(setOf("/wt/a", "/wt/b"), shown.last())

            snapshot.showBranches(draft, emptyList())
            advanceUntilIdle()
            assertEquals(emptySet<String>(), shown.last())
        }
}
