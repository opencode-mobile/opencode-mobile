package dev.opencode.mobile.ui.archived

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.session.ArchivedSessionList
import dev.opencode.mobile.domain.session.ArchivedSessionPeriod
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.PermissionDecision
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.SessionTodo
import dev.opencode.mobile.ui.components.OpenCodeSnackbarVariant
import dev.opencode.mobile.ui.text.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivedSessionsModelTest {
    private val now = 1_000L * 60 * 60 * 24 * 100
    private val day = 1_000L * 60 * 60 * 24
    private val alpha = project("p_alpha", "alpha", "/work/alpha")
    private val beta = project("p_beta", "beta", "/work/beta")

    @Test
    fun groupsByArchiveTimeNewestFirst() =
        runTest {
            val repository =
                FakeArchivedRepository(
                    "/work/alpha" to
                        listOf(
                            archived("old", "/work/alpha", now - 20 * day),
                            archived("recent", "/work/alpha", now - 2 * day),
                            archived("newest", "/work/alpha", now - day),
                            archived("child", "/work/alpha", now - day, parentId = "newest"),
                        ),
                )
            val model = model(repository, listOf(alpha))

            model.bind("all")
            advanceUntilIdle()

            val content = model.state.value.content as ArchivedSessionsContent.Loaded
            assertEquals(listOf(ArchivedSessionPeriod.ThisWeek, ArchivedSessionPeriod.Earlier), content.sections.map { it.period })
            assertEquals(listOf("newest", "recent"), content.sections[0].rows.map { it.id })
            assertEquals(listOf("old"), content.sections[1].rows.map { it.id })
            assertEquals(UiText.resource(R.string.home_all_projects), model.state.value.scopeTitle)
        }

    @Test
    fun projectScopeShowsItsSessionsAndOffersAllProjectsWhenEmpty() =
        runTest {
            val repository =
                FakeArchivedRepository(
                    "/work/alpha" to listOf(archived("a1", "/work/alpha", now - day)),
                    "/work/beta" to emptyList(),
                )
            val model = model(repository, listOf(alpha, beta))

            model.bind(beta.key)
            advanceUntilIdle()

            val empty = model.state.value.content as ArchivedSessionsContent.Loaded
            assertTrue(empty.isEmpty)
            assertTrue(empty.canShowAllProjects)
            assertEquals(UiText.verbatim("beta"), model.state.value.scopeTitle)

            model.showAllProjects()

            val all = model.state.value.content as ArchivedSessionsContent.Loaded
            assertEquals(listOf("a1"), all.sections.flatMap { s -> s.rows.map { it.id } })
            assertFalse(all.canShowAllProjects)
        }

    @Test
    fun emptyProjectWithNothingElseArchivedOffersNoWayOut() =
        runTest {
            val model = model(FakeArchivedRepository("/work/alpha" to emptyList(), "/work/beta" to emptyList()), listOf(alpha, beta))

            model.bind(alpha.key)
            advanceUntilIdle()

            val content = model.state.value.content as ArchivedSessionsContent.Loaded
            assertTrue(content.isEmpty)
            assertFalse(content.canShowAllProjects)
        }

    @Test
    fun unarchiveRemovesTheRowAndUndoArchivesItAgain() =
        runTest {
            val repository = FakeArchivedRepository("/work/alpha" to listOf(archived("a1", "/work/alpha", now - day, title = "Fix login")))
            val model = model(repository, listOf(alpha))
            model.bind("all")
            advanceUntilIdle()

            model.unarchive("a1")
            assertTrue(rows(model).isEmpty())
            advanceUntilIdle()

            assertEquals(listOf("a1"), repository.unarchived)
            val snackbar = model.state.value.snackbar!!
            assertEquals(UiText.resource(R.string.archived_sessions_unarchived, "Fix login"), snackbar.text)
            assertEquals("a1", snackbar.undoSessionId)

            model.undoUnarchive("a1")
            assertEquals(listOf("a1"), rows(model))
            advanceUntilIdle()

            assertEquals(listOf("a1"), repository.archived)
            assertEquals(listOf("a1"), rows(model))
        }

    @Test
    fun failedUnarchiveBringsTheRowBackWithAnError() =
        runTest {
            val repository = FakeArchivedRepository("/work/alpha" to listOf(archived("a1", "/work/alpha", now - day)))
            repository.unarchiveError = IllegalStateException("offline")
            val model = model(repository, listOf(alpha))
            model.bind("all")
            advanceUntilIdle()

            model.unarchive("a1")
            advanceUntilIdle()

            assertEquals(listOf("a1"), rows(model))
            val snackbar = model.state.value.snackbar!!
            assertEquals(OpenCodeSnackbarVariant.Error, snackbar.variant)
            assertNull(snackbar.undoSessionId)
        }

    @Test
    fun sheetOpenReturnsTheRowAndDeleteConfirmsFirst() =
        runTest {
            val repository = FakeArchivedRepository("/work/alpha" to listOf(archived("a1", "/work/alpha", now - day), archived("a2", "/work/alpha", now - 2 * day)))
            val model = model(repository, listOf(alpha))
            model.bind("all")
            advanceUntilIdle()
            val first = (model.state.value.content as ArchivedSessionsContent.Loaded).sections[0].rows[0]

            model.openActions(first)
            assertTrue(model.state.value.sheetVisible)
            assertEquals("a1", model.chooseOpen()?.id)
            assertFalse(model.state.value.sheetVisible)

            model.openActions(first)
            model.chooseDelete()
            model.dismissActions()
            assertTrue(model.state.value.deleteConfirmVisible)
            assertEquals("a1", model.state.value.deleteTarget?.id)
            assertTrue(repository.deleted.isEmpty())
            model.confirmDelete()
            advanceUntilIdle()

            assertEquals(listOf("a1"), repository.deleted)
            assertEquals(listOf("a2"), rows(model))
        }

    @Test
    fun liveArchiveEventRefreshesTheList() =
        runTest {
            val repository = FakeArchivedRepository("/work/alpha" to emptyList())
            val events = MutableSharedFlow<OpencodeEvent>()
            val model = model(repository, listOf(alpha), events)
            model.bind("all")
            advanceUntilIdle()
            assertTrue(rows(model).isEmpty())

            repository.lists["/work/alpha"] = listOf(archived("a1", "/work/alpha", now - day))
            events.emit(OpencodeEvent(type = "session.archived", sessionId = "a1"))
            advanceUntilIdle()

            assertEquals(listOf("a1"), rows(model))
        }

    @Test
    fun failedFirstLoadShowsRetry() =
        runTest {
            val repository = FakeArchivedRepository("/work/alpha" to emptyList())
            repository.listError = IllegalStateException("offline")
            val model = model(repository, listOf(alpha))

            model.bind("all")
            advanceUntilIdle()
            assertTrue(model.state.value.content is ArchivedSessionsContent.Failed)

            repository.listError = null
            model.retry()
            advanceUntilIdle()
            assertTrue(model.state.value.content is ArchivedSessionsContent.Loaded)
        }

    @Test
    fun archivedSessionListSkipsAFailingDirectory() =
        runTest {
            val repository =
                FakeArchivedRepository("/work/alpha" to listOf(archived("a1", "/work/alpha", now)), "/work/beta" to emptyList())
            repository.failingDirectories += "/work/beta"

            val sessions = ArchivedSessionList.load(repository, listOf("/work/alpha", "/work/beta"))

            assertEquals(listOf("a1"), sessions.map { it.id })
        }

    @Test
    fun unarchiveLeavesTheServerReadStateAlone() =
        runTest {
            // Home's Done pill is server unread state; only opening the session in Session Chat acknowledges it.
            val repository = FakeArchivedRepository("/work/alpha" to listOf(archived("a1", "/work/alpha", now - day)))
            val model = model(repository, listOf(alpha))
            model.bind("all")
            advanceUntilIdle()

            model.unarchive("a1")
            advanceUntilIdle()

            assertEquals(listOf("a1"), repository.unarchived)
            assertTrue(repository.viewed.isEmpty())
        }

    @Test
    fun aLaggingListDoesNotBringAnUnarchivedRowBack() =
        runTest {
            val repository = FakeArchivedRepository("/work/alpha" to listOf(archived("a1", "/work/alpha", now - day)))
            repository.unarchiveLags = true
            val events = MutableSharedFlow<OpencodeEvent>()
            val model = model(repository, listOf(alpha), events)
            model.bind("all")
            advanceUntilIdle()

            model.unarchive("a1")
            advanceUntilIdle()
            events.emit(OpencodeEvent(type = "session.unarchived", sessionId = "a1"))
            advanceUntilIdle()

            assertTrue(rows(model).isEmpty())
        }

    @Test
    fun aFailingProjectKeepsTheOthersListed() =
        runTest {
            val repository =
                FakeArchivedRepository("/work/alpha" to listOf(archived("a1", "/work/alpha", now - day)), "/work/beta" to emptyList())
            repository.failingDirectories += "/work/beta"
            val model = model(repository, listOf(alpha, beta))

            model.bind("all")
            advanceUntilIdle()

            assertEquals(listOf("a1"), rows(model))
        }

    @Test
    fun undoKeepsARowWhoseDirectoryIsUnknown() =
        runTest {
            val repository = FakeArchivedRepository("/work/alpha" to listOf(archived("a1", "/elsewhere", now - day)))
            val model = model(repository, listOf(alpha))
            model.bind("all")
            advanceUntilIdle()
            model.unarchive("a1")
            advanceUntilIdle()

            model.undoUnarchive("a1")

            assertEquals(listOf("a1"), rows(model))
        }

    @Test
    fun homeMenuEntryCountsTheScopeAndHidesWithoutSupport() =
        runTest {
            val repository =
                FakeArchivedRepository(
                    "/work/alpha" to listOf(archived("a1", "/work/alpha", now - day), archived("a2", "/work/alpha", now - day)),
                    "/work/beta" to listOf(archived("b1", "/work/beta", now - day)),
                )
            var supported = true
            val menu =
                HomeArchivedMenu(
                    sessionRepository = repository,
                    projects = MutableStateFlow(listOf(alpha, beta)),
                    scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
                    supported = { supported },
                )

            menu.refresh("all", worktreesEnabled = true)
            assertNull(menu.state.value?.count)
            advanceUntilIdle()
            assertEquals(HomeArchivedMenuEntry("all", UiText.resource(R.string.home_all_projects), 3), menu.state.value)

            menu.refresh(beta.key, worktreesEnabled = true)
            advanceUntilIdle()
            assertEquals(HomeArchivedMenuEntry(beta.key, UiText.verbatim("beta"), 1), menu.state.value)

            supported = false
            menu.refresh("all", worktreesEnabled = true)
            assertNull(menu.state.value)

            // Capabilities that arrive after the menu opened bring the entry back without reopening it.
            supported = true
            menu.recheck()
            advanceUntilIdle()
            assertEquals(3, menu.state.value?.count)
        }

    private fun rows(model: ArchivedSessionsModel): List<String> =
        (model.state.value.content as ArchivedSessionsContent.Loaded).sections.flatMap { s -> s.rows.map { it.id } }

    private fun TestScope.model(
        repository: SessionRepository,
        projects: List<HomeProject>,
        events: MutableSharedFlow<OpencodeEvent> = MutableSharedFlow(),
    ): ArchivedSessionsModel =
        ArchivedSessionsModel(
            sessionRepository = repository,
            sessionDomainAdapter = SessionDomainAdapter(),
            projects = MutableStateFlow(projects),
            events = events,
            // Not backgroundScope: advanceUntilIdle does not run work that is only in the background.
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
            nowEpochMs = { now },
        )

    private fun project(
        key: String,
        name: String,
        path: String,
    ) = HomeProject(
        key = key,
        projectId = key,
        displayName = name,
        memberPaths = listOf(path),
        isRepository = false,
        worktrees = emptyList(),
    )

    private fun archived(
        id: String,
        directory: String,
        archivedAt: Long,
        title: String = id,
        parentId: String? = null,
    ) = Session(id = id, title = title, directory = directory, parentId = parentId, updatedAtEpochMs = archivedAt, archivedAtEpochMs = archivedAt)
}

private class FakeArchivedRepository(
    vararg lists: Pair<String, List<Session>>,
) : SessionRepository {
    val lists = mutableMapOf(*lists)
    val failingDirectories = mutableSetOf<String>()
    var listError: Throwable? = null
    var unarchiveError: Throwable? = null

    /** The archived list keeps an unarchived session for a while, like a lagging server read. */
    var unarchiveLags = false
    val unarchived = mutableListOf<String>()
    val archived = mutableListOf<String>()
    val deleted = mutableListOf<String>()

    override suspend fun listArchivedSessions(directory: String?): List<Session> {
        listError?.let { throw it }
        if (directory in failingDirectories) error("unreachable")
        return lists[directory].orEmpty()
    }

    override suspend fun unarchiveSession(
        id: String,
        directory: String?,
    ): Session {
        unarchiveError?.let { throw it }
        unarchived += id
        val session = lists.values.flatten().first { it.id == id }
        if (!unarchiveLags) lists.replaceAll { _, list -> list.filterNot { it.id == id } }
        return session.copy(archivedAtEpochMs = null)
    }

    override suspend fun archiveSession(
        id: String,
        directory: String?,
    ): Session {
        archived += id
        val session = Session(id = id, title = id, directory = directory, archivedAtEpochMs = 1L)
        lists[directory.orEmpty()] = lists[directory].orEmpty() + session
        return session
    }

    override suspend fun deleteSession(
        id: String,
        directory: String?,
    ): Boolean {
        deleted += id
        lists.replaceAll { _, list -> list.filterNot { it.id == id } }
        return true
    }

    override suspend fun getSession(
        id: String,
        directory: String?,
    ) = error("unused")

    val viewed = mutableListOf<String>()

    override suspend fun markSessionViewed(
        sessionId: String,
        idleAtEpochMs: Long,
    ) {
        viewed += sessionId
    }

    override suspend fun listSessions(directory: String?) = emptyList<Session>()

    override suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun getAllSessionStatuses(directory: String?) = emptyList<SessionStatus>()

    override suspend fun updateSession(
        id: String,
        title: String?,
        directory: String?,
    ) = error("unused")

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
