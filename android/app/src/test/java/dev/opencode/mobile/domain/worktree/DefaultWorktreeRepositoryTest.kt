package dev.opencode.mobile.domain.worktree

import dev.opencode.mobile.data.remote.V1WorkspaceHttp
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

            val loaded = repository.load("/repo", "proj")

            assertEquals(WorktreeLoad.Loaded(listOf("/worktrees/crisp-cactus"), listsSessions = false), loaded)
            assertEquals(listOf("proj"), remote.v2Lists)
            assertTrue(remote.v1Probes.isEmpty())
            assertTrue(repository.refresh("proj"))
            assertEquals(listOf("proj"), remote.v2Refreshes)
            assertFalse(repository.refresh(""))
        }

    @Test
    fun v2DoesNotCallWhenProjectIdIsMissingAndDoesNotProbeV1() =
        runTest {
            val remote = FakeWorktreeRemote()
            val repository = repository(remote, ServerProtocol.V2)

            assertEquals(WorktreeLoad.Unloaded, repository.load("/repo", null))
            assertEquals(WorktreeLoad.Unloaded, repository.load("/repo", " "))
            assertTrue(remote.v2Lists.isEmpty())
            assertTrue(remote.v1Probes.isEmpty())
            assertEquals(WorktreeLoad.Unloaded, repository.refetch(" "))
        }

    @Test
    fun v1ListsWorkspaceDirectoriesAndA404StopsUntilTheNextConnect() =
        runTest {
            val remote = FakeWorktreeRemote()
            val repository = repository(remote, ServerProtocol.V1)
            repository.onConnection("http://mac")

            val loaded = repository.load("/repo", null)

            assertEquals(
                WorktreeLoad.Loaded(listOf("/repo", "/worktrees/crisp-cactus"), listsSessions = true),
                loaded,
            )
            assertEquals(listOf("/repo"), remote.v1Probes)
            assertTrue(remote.v2Lists.isEmpty())

            remote.v1Result = V1WorkspaceHttp.NotFound
            assertEquals(WorktreeLoad.Stopped, repository.load("/repo", null))
            assertEquals(WorktreeLoad.Stopped, repository.load("/other", null))
            assertEquals(2, remote.v1Probes.size)

            repository.onConnection("http://mac")
            assertEquals(WorktreeLoad.Stopped, repository.load("/repo", null))
            assertEquals(2, remote.v1Probes.size)

            repository.onDisconnected()
            repository.onConnection("http://mac")
            remote.v1Result = V1WorkspaceHttp.Ok("""["/repo"]""")
            assertEquals(WorktreeLoad.Loaded(listOf("/repo"), listsSessions = true), repository.load("/repo", null))
            assertEquals(3, remote.v1Probes.size)
        }

    @Test
    fun v1Non404FailureDoesNotStopTheProbe() =
        runTest {
            val remote = FakeWorktreeRemote(v1Result = V1WorkspaceHttp.Failed(500))
            val repository = repository(remote, ServerProtocol.V1)
            repository.onConnection("http://mac")

            val failed = repository.load("/repo", "proj")

            assertTrue(failed is WorktreeLoad.Failed)
            assertTrue(repository.load("/repo", "proj") is WorktreeLoad.Failed)
            assertEquals(2, remote.v1Probes.size)
            assertTrue(remote.v2Lists.isEmpty())
            assertFalse(repository.refresh("proj"))
        }

    @Test
    fun unrecognizedV1BodyFailsWithoutStopping() =
        runTest {
            val remote = FakeWorktreeRemote(v1Result = V1WorkspaceHttp.Ok("""{"workspaces":[]}"""))
            val repository = repository(remote, ServerProtocol.V1)
            repository.onConnection("http://mac")

            assertTrue(repository.load("/repo", null) is WorktreeLoad.Failed)
            remote.v1Result = V1WorkspaceHttp.Ok("""["/repo"]""")
            assertEquals(WorktreeLoad.Loaded(listOf("/repo"), listsSessions = true), repository.load("/repo", null))
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
        var v1Result: V1WorkspaceHttp = V1WorkspaceHttp.Ok("""["/repo","/worktrees/crisp-cactus"]"""),
        private val refreshError: Exception? = null,
    ) : WorktreeRemote {
        val v2Lists = mutableListOf<String>()
        val v2Refreshes = mutableListOf<String>()
        val v1Probes = mutableListOf<String>()

        override suspend fun listV2(projectId: String): List<String> {
            v2Lists += projectId
            return listOf("/worktrees/crisp-cactus")
        }

        override suspend fun refreshV2(projectId: String) {
            v2Refreshes += projectId
            refreshError?.let { throw it }
        }

        override suspend fun probeV1(directory: String): V1WorkspaceHttp {
            v1Probes += directory
            return v1Result
        }
    }
}
