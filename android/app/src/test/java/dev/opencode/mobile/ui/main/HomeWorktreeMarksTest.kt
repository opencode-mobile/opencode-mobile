package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.worktree.WorktreeInventory
import dev.opencode.mobile.domain.worktree.WorktreeLoad
import dev.opencode.mobile.domain.worktree.WorktreeRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWorktreeMarksTest {
    @Test
    fun loadRecordsTheProjectBeforeRefreshAndDropsAnOverlappingRefetch() =
        runTest {
            val repository = ScriptedWorktreeRepository()
            val marks = HomeWorktreeMarks(repository, { "proj" }, { emptyList() })

            val load = async { marks.load("/repo") }
            repository.loadEntered.await()
            assertTrue(marks.knows("proj"))

            val refetch = async { marks.refetch("proj") }
            repository.refetchEntered.await()
            repository.releaseLoad.complete(Unit)
            val loaded = load.await()
            repository.releaseRefetch.complete(Unit)

            assertEquals(WorktreeInventory.loaded(listOf("/new")), loaded.inventory)
            assertNull(refetch.await())
        }

    @Test
    fun refetchAfterLoadReturnsTheCurrentList() =
        runTest {
            val repository = ScriptedWorktreeRepository(releaseImmediately = true)
            val marks = HomeWorktreeMarks(repository, { "proj" }, { emptyList() })

            marks.load("/repo")

            assertEquals(WorktreeInventory.loaded(listOf("/listed")), marks.refetch("proj")?.inventory)
        }

    private class ScriptedWorktreeRepository(
        private val releaseImmediately: Boolean = false,
    ) : WorktreeRepository {
        val loadEntered = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val refetchEntered = CompletableDeferred<Unit>()
        val releaseRefetch = CompletableDeferred<Unit>()

        override suspend fun load(
            addedPath: String,
            projectId: String?,
        ): WorktreeLoad = WorktreeLoad.Unloaded

        override suspend fun loadRefreshed(
            addedPath: String,
            projectId: String?,
        ): WorktreeLoad {
            loadEntered.complete(Unit)
            if (!releaseImmediately) releaseLoad.await()
            return WorktreeLoad.Loaded(listOf("/new"), listsSessions = false)
        }

        override suspend fun refresh(projectId: String): Boolean = true

        override suspend fun refetch(projectId: String): WorktreeLoad {
            if (releaseImmediately) return WorktreeLoad.Loaded(listOf("/listed"), listsSessions = false)
            refetchEntered.complete(Unit)
            releaseRefetch.await()
            return WorktreeLoad.Loaded(listOf("/stale"), listsSessions = false)
        }

        override fun onDisconnected() = Unit

        override fun onConnection(key: String) = Unit
    }
}
