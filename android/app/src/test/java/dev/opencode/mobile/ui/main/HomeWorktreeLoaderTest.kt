package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.project.Project
import dev.opencode.mobile.domain.worktree.WorktreeInventory
import dev.opencode.mobile.domain.worktree.WorktreeLoad
import dev.opencode.mobile.domain.worktree.WorktreeRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWorktreeLoaderTest {
    private val repo = Project(id = "proj", worktree = "/repo", displayName = "repo", isRepository = true)

    @Test
    fun loadRecordsTheProjectBeforeRefreshAndDropsAnOverlappingRefetch() =
        runTest {
            val repository = ScriptedWorktreeRepository()
            val loader = HomeWorktreeLoader(repository) { repo }

            val load = async { loader.load("/repo") }
            repository.loadEntered.await()
            assertTrue(loader.knows("proj"))

            val refetch = async { loader.refetch("proj") }
            repository.refetchEntered.await()
            repository.releaseLoad.complete(Unit)
            val loaded = load.await()
            repository.releaseRefetch.complete(Unit)

            assertEquals(WorktreeInventory.loaded(listOf("/new")), loaded.inventory)
            assertEquals(emptyList<HomePathLoad>(), refetch.await())
        }

    @Test
    fun refetchAfterLoadUpdatesEveryAddedPathOfTheProject() =
        runTest {
            val repository = ScriptedWorktreeRepository(releaseImmediately = true)
            val loader = HomeWorktreeLoader(repository) { repo }

            loader.load("/repo")
            loader.load("/repo-wt")

            val refetched = loader.refetch("proj")
            assertEquals(listOf("/repo", "/repo-wt"), refetched.map { it.addedPath })
            assertTrue(refetched.all { it.inventory == WorktreeInventory.loaded(listOf("/listed")) })
        }

    @Test
    fun loadPassesTheMainCheckoutSoV1CanListIt() =
        runTest {
            val repository = ScriptedWorktreeRepository(releaseImmediately = true)
            val loader = HomeWorktreeLoader(repository) { repo.copy(worktree = "/repo") }

            loader.load("/repo-wt")

            assertEquals(listOf(Triple("/repo-wt", "proj", "/repo")), repository.loads)
        }

    @Test
    fun globalAndUnresolvedPathsDoNotListWorktrees() =
        runTest {
            val repository = ScriptedWorktreeRepository(releaseImmediately = true)
            val global = HomeWorktreeLoader(repository) { Project(id = "global", worktree = "/", displayName = "") }
            val failing = HomeWorktreeLoader(repository) { error("down") }

            val globalLoad = global.load("/plain")
            val failedLoad = failing.load("/plain")

            assertEquals(WorktreeInventory.Unloaded, globalLoad.inventory)
            assertFalse(globalLoad.resolution!!.isMergeable)
            assertNull(failedLoad.resolution)
            assertTrue(repository.loads.isEmpty())
        }

    @Test
    fun aFailedResolveKeepsTheLastResolution() =
        runTest {
            var fail = false
            val loader =
                HomeWorktreeLoader(ScriptedWorktreeRepository(releaseImmediately = true)) {
                    if (fail) error("down") else repo
                }

            assertEquals("proj", loader.resolve("/repo")?.projectId)
            fail = true
            assertEquals("proj", loader.resolve("/repo")?.projectId)
        }

    @Test
    fun extraDirectoriesAreV1WorktreesThatAreNotAddedPaths() {
        assertEquals(
            listOf("/wt-a", "/wt-b"),
            homeExtraDirectories(
                addedPaths = listOf("/repo", "/plain"),
                inventories = mapOf("/repo" to setOf("/repo", "/wt-a", "/wt-b"), "/plain" to setOf("/elsewhere")),
                listsSessions = setOf("/repo"),
            ),
        )
        assertEquals(
            emptyList<String>(),
            homeExtraDirectories(
                addedPaths = listOf("/repo", "/wt-a"),
                inventories = mapOf("/repo" to setOf("/repo", "/wt-a")),
                listsSessions = setOf("/repo"),
            ),
        )
    }

    private class ScriptedWorktreeRepository(
        private val releaseImmediately: Boolean = false,
    ) : WorktreeRepository {
        val loadEntered = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val refetchEntered = CompletableDeferred<Unit>()
        val releaseRefetch = CompletableDeferred<Unit>()
        val loads = mutableListOf<Triple<String, String?, String?>>()

        override suspend fun load(
            addedPath: String,
            projectId: String?,
            mainWorktree: String?,
        ): WorktreeLoad = WorktreeLoad.Unloaded

        override suspend fun loadRefreshed(
            addedPath: String,
            projectId: String?,
            mainWorktree: String?,
        ): WorktreeLoad {
            loads += Triple(addedPath, projectId, mainWorktree)
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
