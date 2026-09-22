package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.domain.session.VcsFileStatus
import dev.opencode.mobile.domain.session.VcsInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionChatRepoStripTest {
    private val dirty =
        listOf(
            VcsFileStatus("a/HealthApi.kt", FileDiffStatus.Modified, 42, 7),
            VcsFileStatus("docs/probe.md", FileDiffStatus.Deleted, 0, 19),
        )

    @Test
    fun newSession_dirtyTree_startsExpanded() =
        runTest {
            val region = region(FakeVcsRepository(info = VcsInfo("fix/probe", "main"), status = dirty))
            region.bind("/repo", startExpanded = true)
            advanceUntilIdle()

            val state = region.state.value
            assertTrue(state.isAvailable)
            assertEquals("fix/probe", state.branch)
            assertEquals("main", state.defaultBranch)
            assertEquals(dirty, state.files)
            assertTrue(state.isExpanded)
        }

    @Test
    fun activeSession_dirtyTree_startsCollapsed() =
        runTest {
            val region = region(FakeVcsRepository(info = VcsInfo("fix/probe", "main"), status = dirty))
            region.bind("/repo", startExpanded = false)
            advanceUntilIdle()

            assertTrue(region.state.value.isAvailable)
            assertFalse(region.state.value.isExpanded)
        }

    @Test
    fun cleanTree_neverExpands() =
        runTest {
            val region = region(FakeVcsRepository(info = VcsInfo("main", "main"), status = emptyList()))
            region.bind("/repo", startExpanded = true)
            advanceUntilIdle()

            assertTrue(region.state.value.isAvailable)
            assertFalse(region.state.value.isExpanded)
            region.toggle()
            assertFalse(region.state.value.isExpanded)
        }

    @Test
    fun noVcs_isUnavailable() =
        runTest {
            val region = region(FakeVcsRepository(infoError = SessionError.Http(404, "no vcs")))
            region.bind("/repo", startExpanded = true)
            advanceUntilIdle()

            assertFalse(region.state.value.isAvailable)
        }

    @Test
    fun statusRouteMissing_showsBranchRowOnly() =
        runTest {
            val region =
                region(
                    FakeVcsRepository(
                        info = VcsInfo("fix/probe", "main"),
                        statusError = SessionError.Http(404, "no status"),
                    ),
                )
            region.bind("/repo", startExpanded = true)
            advanceUntilIdle()

            assertTrue(region.state.value.isAvailable)
            assertEquals("fix/probe", region.state.value.branch)
            assertTrue(
                region.state.value.files
                    .isEmpty(),
            )
        }

    @Test
    fun toggle_flipsAndSurvivesRefresh() =
        runTest {
            val repository = FakeVcsRepository(info = VcsInfo("fix/probe", "main"), status = dirty)
            val region = region(repository)
            region.bind("/repo", startExpanded = false)
            advanceUntilIdle()

            region.toggle()
            assertTrue(region.state.value.isExpanded)

            region.refresh()
            advanceUntilIdle()
            assertTrue(region.state.value.isExpanded)
        }

    @Test
    fun turnCompletion_refetchesStatus() =
        runTest {
            val repository = FakeVcsRepository(info = VcsInfo("fix/probe", "main"), status = dirty)
            val region = region(repository)
            region.bind("/repo", startExpanded = false)
            advanceUntilIdle()
            assertEquals(1, repository.statusCalls)

            region.updateTurnActive(true)
            advanceUntilIdle()
            assertEquals(1, repository.statusCalls)

            repository.status = dirty.take(1)
            region.updateTurnActive(false)
            advanceUntilIdle()
            assertEquals(2, repository.statusCalls)
            assertEquals(dirty.take(1), region.state.value.files)
        }

    @Test
    fun branchUpdatedEvent_refetchesInfo() =
        runTest {
            val repository = FakeVcsRepository(info = VcsInfo("fix/probe", "main"), status = dirty)
            val events = FakeForegroundEventStream()
            val region = region(repository, events)
            region.bind("/repo", startExpanded = false)
            advanceUntilIdle()
            assertEquals("fix/probe", region.state.value.branch)

            repository.info = VcsInfo("main", "main")
            events.emit(OpencodeEvent(type = SessionChatRepoStrip.BRANCH_UPDATED_EVENT, sessionId = null))
            advanceUntilIdle()
            assertEquals("main", region.state.value.branch)
        }

    @Test
    fun rebindSameWorktree_keepsState() =
        runTest {
            val repository = FakeVcsRepository(info = VcsInfo("fix/probe", "main"), status = dirty)
            val region = region(repository)
            region.bind("/repo", startExpanded = true)
            advanceUntilIdle()
            region.toggle()
            assertFalse(region.state.value.isExpanded)

            region.bind("/repo", startExpanded = true)
            advanceUntilIdle()
            assertEquals(1, repository.statusCalls)
            assertFalse(region.state.value.isExpanded)
        }

    private val regionScopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        regionScopes.forEach { it.cancel() }
    }

    /** Region scope on the test scheduler; kept outside runTest's job tree so the event collector may outlive the body. */
    private fun TestScope.region(
        repository: SessionRepository,
        events: FakeForegroundEventStream? = null,
    ): SessionChatRepoStrip {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob()).also(regionScopes::add)
        return SessionChatRepoStrip(
            sessionRepository = repository,
            foregroundEventStream = events,
            scope = scope,
        ).also { it.ioDispatcher = dispatcher }
    }
}

private class FakeVcsRepository(
    var info: VcsInfo? = null,
    var status: List<VcsFileStatus> = emptyList(),
    private val infoError: Throwable? = null,
    private val statusError: Throwable? = null,
) : SessionRepository {
    var statusCalls = 0

    override suspend fun getVcsInfo(directory: String?): VcsInfo {
        infoError?.let { throw it }
        return requireNotNull(info)
    }

    override suspend fun getVcsStatus(directory: String?): List<VcsFileStatus> {
        statusCalls += 1
        statusError?.let { throw it }
        return status
    }

    override suspend fun listSessions(directory: String?) = emptyList<Session>()

    override suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun getAllSessionStatuses(directory: String?) = emptyList<SessionStatus>()

    override suspend fun getSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun updateSession(
        id: String,
        title: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun archiveSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun deleteSession(
        id: String,
        directory: String?,
    ) = false

    override suspend fun listChildSessions(
        id: String,
        directory: String?,
    ) = emptyList<Session>()

    override suspend fun listSessionTodos(
        id: String,
        directory: String?,
    ) = emptyList<SessionTodo>()

    override suspend fun initializeSessionAgents(
        id: String,
        messageId: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ) = false

    override suspend fun forkSession(
        id: String,
        atMessageId: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun abortSession(
        id: String,
        directory: String?,
    ) = false

    override suspend fun shareSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun unshareSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun getSessionDiff(
        id: String,
        messageId: String?,
        directory: String?,
    ) = emptyList<FileDiff>()

    override suspend fun summarizeSession(
        id: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ) = false

    override suspend fun revertSession(
        id: String,
        messageId: String,
        partId: String?,
        directory: String?,
    ) = false

    override suspend fun unrevertSession(
        id: String,
        directory: String?,
    ) = false

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        decision: PermissionDecision,
        directory: String?,
    ) = false
}
