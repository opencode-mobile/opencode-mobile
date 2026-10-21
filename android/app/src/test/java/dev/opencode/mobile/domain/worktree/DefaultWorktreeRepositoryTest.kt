package dev.opencode.mobile.domain.worktree

import dev.opencode.mobile.data.remote.V1WorktreeHttp
import dev.opencode.mobile.data.remote.WorktreeRemote
import dev.opencode.mobile.domain.connection.ServerProtocol
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultWorktreeRepositoryTest {
    @Test
    fun v2ListsByProjectIdThenRefreshDoesNotReplaceTheList() =
        runTest {
            val remote = FakeWorktreeRemote()
            val repository = repository(remote, ServerProtocol.V2)
            repository.onConnection("http://mac")

            val loaded = repository.load("/repo", "proj", mainWorktree = "/repo")

            assertEquals(WorktreeLoad.Loaded(listOf("/worktrees/crisp-cactus"), listsSessions = false), loaded)
            assertEquals(listOf("proj"), remote.v2Lists)
            assertTrue(remote.v1Lists.isEmpty())
            assertTrue(repository.refresh("proj"))
            assertEquals(listOf("proj"), remote.v2Refreshes)
            assertFalse(repository.refresh(""))
        }

    @Test
    fun v2DoesNotCallWhenProjectIdIsMissingAndDoesNotListV1() =
        runTest {
            val remote = FakeWorktreeRemote()
            val repository = repository(remote, ServerProtocol.V2)

            assertEquals(WorktreeLoad.Unloaded, repository.load("/repo", null))
            assertEquals(WorktreeLoad.Unloaded, repository.load("/repo", " "))
            assertTrue(remote.v2Lists.isEmpty())
            assertTrue(remote.v1Lists.isEmpty())
            assertEquals(WorktreeLoad.Unloaded, repository.refetch(" "))
        }

    @Test
    fun v1PutsTheMainCheckoutBeforeTheListedSandboxes() =
        runTest {
            val remote = FakeWorktreeRemote(v1Result = V1WorktreeHttp.Ok("""["/abs/wt-a","/abs/wt-b"]"""))
            val repository = repository(remote, ServerProtocol.V1)
            repository.onConnection("http://mac")

            val loaded = repository.load("/abs/wt-a", null, mainWorktree = "/abs/repo")

            assertEquals(
                WorktreeLoad.Loaded(listOf("/abs/repo", "/abs/wt-a", "/abs/wt-b"), listsSessions = true),
                loaded,
            )
            assertEquals(listOf("/abs/wt-a"), remote.v1Lists)
            assertTrue(remote.v2Lists.isEmpty())
        }

    @Test
    fun v1MainCheckoutIsNotListedTwice() =
        runTest {
            val remote = FakeWorktreeRemote(v1Result = V1WorktreeHttp.Ok("""["/repo","/wt"]"""))
            val repository = repository(remote, ServerProtocol.V1)

            assertEquals(
                WorktreeLoad.Loaded(listOf("/repo", "/wt"), listsSessions = true),
                repository.load("/repo", "proj", mainWorktree = "/repo"),
            )
        }

    @Test
    fun v1A404MeansNoExtraWorktreesAndStopsListingUntilTheNextConnect() =
        runTest {
            val remote = FakeWorktreeRemote(v1Result = V1WorktreeHttp.NotFound)
            val repository = repository(remote, ServerProtocol.V1)
            repository.onConnection("http://mac")

            assertEquals(
                WorktreeLoad.Loaded(listOf("/repo"), listsSessions = true),
                repository.load("/repo", "proj", mainWorktree = "/repo"),
            )
            assertEquals(WorktreeLoad.Stopped, repository.load("/other", null))
            assertEquals(1, remote.v1Lists.size)

            repository.onConnection("http://mac")
            assertEquals(
                WorktreeLoad.Loaded(listOf("/repo"), listsSessions = true),
                repository.load("/repo", "proj", mainWorktree = "/repo"),
            )
            assertEquals(1, remote.v1Lists.size)

            repository.onDisconnected()
            repository.onConnection("http://mac")
            remote.v1Result = V1WorktreeHttp.Ok("""["/wt"]""")
            assertEquals(
                WorktreeLoad.Loaded(listOf("/repo", "/wt"), listsSessions = true),
                repository.load("/repo", "proj", mainWorktree = "/repo"),
            )
            assertEquals(2, remote.v1Lists.size)
        }

    @Test
    fun v1Non404FailureDoesNotStopListing() =
        runTest {
            val remote = FakeWorktreeRemote(v1Result = V1WorktreeHttp.Failed(500))
            val repository = repository(remote, ServerProtocol.V1)
            repository.onConnection("http://mac")

            val failed = repository.load("/repo", "proj")

            assertTrue(failed is WorktreeLoad.Failed)
            assertTrue(repository.load("/repo", "proj") is WorktreeLoad.Failed)
            assertEquals(2, remote.v1Lists.size)
            assertTrue(remote.v2Lists.isEmpty())
            assertFalse(repository.refresh("proj"))
        }

    @Test
    fun unrecognizedV1BodyFailsWithoutStopping() =
        runTest {
            val remote = FakeWorktreeRemote(v1Result = V1WorktreeHttp.Ok("""{"worktrees":[]}"""))
            val repository = repository(remote, ServerProtocol.V1)
            repository.onConnection("http://mac")

            assertTrue(repository.load("/repo", null) is WorktreeLoad.Failed)
            remote.v1Result = V1WorktreeHttp.Ok("""["/wt"]""")
            assertEquals(WorktreeLoad.Loaded(listOf("/wt"), listsSessions = true), repository.load("/repo", null))
        }

    @Test
    fun loadRefreshedRefetchesAfterRefreshAndKeepsTheFirstListWhenRefreshFails() =
        runTest {
            val remote = FakeWorktreeRemote()
            val repository = repository(remote, ServerProtocol.V2)

            val loaded = repository.loadRefreshed("/repo", "proj")

            assertEquals(WorktreeLoad.Loaded(listOf("/worktrees/crisp-cactus"), listsSessions = false), loaded)
            assertEquals(listOf("proj", "proj"), remote.v2Lists)
            assertEquals(listOf("proj"), remote.v2Refreshes)

            val failing = FakeWorktreeRemote(refreshError = IllegalStateException("down"))
            val kept = repository(failing, ServerProtocol.V2).loadRefreshed("/repo", "proj")
            assertEquals(WorktreeLoad.Loaded(listOf("/worktrees/crisp-cactus"), listsSessions = false), kept)
            assertEquals(listOf("proj"), failing.v2Lists)
        }

    @Test
    fun refreshFailureDoesNotClearALoadedList() =
        runTest {
            val remote =
                FakeWorktreeRemote(
                    refreshError = IllegalStateException("down"),
                )
            val repository = repository(remote, ServerProtocol.V2)
            val loaded = repository.load("/repo", "proj")

            assertFalse(repository.refresh("proj"))
            assertEquals(WorktreeLoad.Loaded(listOf("/worktrees/crisp-cactus"), listsSessions = false), loaded)
        }

    private fun repository(
        remote: FakeWorktreeRemote,
        protocol: ServerProtocol,
    ) = DefaultWorktreeRepository(remote) { protocol }

    private class FakeWorktreeRemote(
        var v1Result: V1WorktreeHttp = V1WorktreeHttp.Ok("""["/worktrees/crisp-cactus"]"""),
        private val refreshError: Exception? = null,
    ) : WorktreeRemote {
        val v2Lists = mutableListOf<String>()
        val v2Refreshes = mutableListOf<String>()
        val v1Lists = mutableListOf<String>()

        override suspend fun listV2(projectId: String): List<String> {
            v2Lists += projectId
            return listOf("/worktrees/crisp-cactus")
        }

        override suspend fun refreshV2(projectId: String) {
            v2Refreshes += projectId
            refreshError?.let { throw it }
        }

        override suspend fun listV1(directory: String): V1WorktreeHttp {
            v1Lists += directory
            return v1Result
        }
    }
}
