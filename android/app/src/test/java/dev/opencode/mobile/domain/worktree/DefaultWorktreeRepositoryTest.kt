package dev.opencode.mobile.domain.worktree

import dev.opencode.mobile.data.remote.V1CreatedWorktree
import dev.opencode.mobile.data.remote.V1WorktreeHttp
import dev.opencode.mobile.data.remote.WorktreeHttpException
import dev.opencode.mobile.data.remote.WorktreeRemote
import dev.opencode.mobile.domain.connection.ServerProtocol
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun v2CreatesFromTheSourceWithTheBaseAndNamesTheCheckoutByItsFolder() =
        runTest {
            val remote = FakeWorktreeRemote(v2Created = "/data/worktree/ab12cd/brave-otter-2/")
            val repository = repository(remote, ServerProtocol.V2)

            val created = repository.create(CreateWorktreeRequest("proj", "/repo", "brave-otter", baseRef = "feature/x"))

            assertEquals(CreatedWorktree("/data/worktree/ab12cd/brave-otter-2", "brave-otter-2", branch = null), created)
            assertEquals(listOf(V2Create("proj", "/repo", "brave-otter", "feature/x")), remote.v2Creates)
            assertTrue(remote.v1Creates.isEmpty())

            repository.create(CreateWorktreeRequest("proj", "/wt/a", "calm-river", baseRef = null))
            assertNull(remote.v2Creates.last().branch)
        }

    @Test
    fun v2CreateWithoutAProjectIdThrowsWithoutCalling() =
        runTest {
            val remote = FakeWorktreeRemote()
            val repository = repository(remote, ServerProtocol.V2)

            for (projectId in listOf(null, " ")) {
                try {
                    repository.create(CreateWorktreeRequest(projectId, "/repo", "one", baseRef = null))
                    fail("expected a failure")
                } catch (_: WorktreeCreateError) {
                }
            }
            assertTrue(remote.v2Creates.isEmpty())
        }

    @Test
    fun v1CreatesInTheSourceIgnoringProjectAndBaseAndUsesTheAnswer() =
        runTest {
            val remote =
                FakeWorktreeRemote(
                    v1Created = V1CreatedWorktree("probe-v1-tidy-tiger", "opencode/probe-v1-tidy-tiger", "/wt/probe-v1-tidy-tiger"),
                )
            val repository = repository(remote, ServerProtocol.V1)

            val created = repository.create(CreateWorktreeRequest("proj", "/repo", "probe-v1", baseRef = "feature/x"))

            assertEquals(CreatedWorktree("/wt/probe-v1-tidy-tiger", "probe-v1-tidy-tiger", "opencode/probe-v1-tidy-tiger"), created)
            assertEquals(listOf("/repo" to "probe-v1"), remote.v1Creates)
            assertTrue(remote.v2Creates.isEmpty())
        }

    @Test
    fun v1Create404SaysTheServerCannotCreateUntilTheNextConnection() =
        runTest {
            val remote = FakeWorktreeRemote(createError = WorktreeHttpException(404, "Not Found"))
            val repository = repository(remote, ServerProtocol.V1)
            repository.onConnection("http://mac")
            assertTrue(repository.canCreate.value)

            assertEquals(WorktreeCreateCopy.UNSUPPORTED, createFailure(repository))
            assertFalse(repository.canCreate.value)

            repository.onConnection("http://mac")
            assertFalse(repository.canCreate.value)
            repository.onConnection("http://other")
            assertTrue(repository.canCreate.value)
        }

    @Test
    fun createFailuresUseTheServerMessageElseTheBodyElseTheStatus() =
        runTest {
            val cases =
                listOf(
                    WorktreeHttpException(
                        400,
                        """{"_tag":"WorktreeError","name":"WorktreeError","data":{"message":"fatal: invalid reference: nope","forceRequired":false}}""",
                    ) to "fatal: invalid reference: nope",
                    WorktreeHttpException(
                        404,
                        """{"_tag":"ProjectNotFoundError","projectID":"global","message":"Project not found: global"}""",
                    ) to "Project not found: global",
                    WorktreeHttpException(
                        400,
                        """{"name":"WorktreeNotGitError","data":{"message":"Worktrees are only supported for git projects"}}""",
                    ) to "Worktrees are only supported for git projects",
                    WorktreeHttpException(502, "  Bad gateway  ") to "Bad gateway",
                    WorktreeHttpException(500, "") to "Request failed (HTTP 500)",
                )
            for ((error, expected) in cases) {
                val repository = repository(FakeWorktreeRemote(createError = error), ServerProtocol.V2)
                assertEquals(expected, createFailure(repository))
            }
            val v1 = repository(FakeWorktreeRemote(createError = cases[2].first), ServerProtocol.V1)
            assertEquals("Worktrees are only supported for git projects", createFailure(v1))
            assertTrue(v1.canCreate.value)
        }

    @Test
    fun baseBranchesListOnV2Only() =
        runTest {
            val remote = FakeWorktreeRemote()

            assertEquals(listOf("main", "feature/x"), repository(remote, ServerProtocol.V2).baseBranches("/repo"))
            assertEquals(emptyList<String>(), repository(remote, ServerProtocol.V1).baseBranches("/repo"))
            assertEquals(listOf("/repo"), remote.branchLists)
        }

    private suspend fun createFailure(repository: DefaultWorktreeRepository): String? =
        try {
            repository.create(CreateWorktreeRequest("proj", "/repo", "one", baseRef = null))
            null
        } catch (error: WorktreeCreateError) {
            error.message
        }

    private data class V2Create(
        val projectId: String,
        val from: String,
        val name: String,
        val branch: String?,
    )

    private fun repository(
        remote: FakeWorktreeRemote,
        protocol: ServerProtocol,
    ) = DefaultWorktreeRepository(remote) { protocol }

    private class FakeWorktreeRemote(
        var v1Result: V1WorktreeHttp = V1WorktreeHttp.Ok("""["/worktrees/crisp-cactus"]"""),
        private val refreshError: Exception? = null,
        private val v2Created: String = "/wt/created",
        private val v1Created: V1CreatedWorktree = V1CreatedWorktree("created", "opencode/created", "/wt/created"),
        private val createError: Exception? = null,
    ) : WorktreeRemote {
        val v2Creates = mutableListOf<V2Create>()
        val v1Creates = mutableListOf<Pair<String, String>>()
        val branchLists = mutableListOf<String>()
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

        override suspend fun createV2(
            projectId: String,
            from: String,
            name: String,
            branch: String?,
        ): String {
            v2Creates += V2Create(projectId, from, name, branch)
            createError?.let { throw it }
            return v2Created
        }

        override suspend fun createV1(
            directory: String,
            name: String,
        ): V1CreatedWorktree {
            v1Creates += directory to name
            createError?.let { throw it }
            return v1Created
        }

        override suspend fun branchesV2(directory: String): List<String> {
            branchLists += directory
            return listOf("main", "feature/x")
        }
    }
}
