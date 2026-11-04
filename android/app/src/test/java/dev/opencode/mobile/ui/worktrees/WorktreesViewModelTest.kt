package dev.opencode.mobile.ui.worktrees

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeWorktree
import dev.opencode.mobile.domain.home.HomeWorktreeRequest
import dev.opencode.mobile.domain.home.HomeWorktreeSession
import dev.opencode.mobile.domain.home.HomeWorktreesFeed
import dev.opencode.mobile.domain.home.HomeWorktreesSnapshot
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.VcsFileStatus
import dev.opencode.mobile.domain.worktree.RemoveWorktreeRequest
import dev.opencode.mobile.domain.worktree.WorktreeLoad
import dev.opencode.mobile.domain.worktree.WorktreeRemoveError
import dev.opencode.mobile.domain.worktree.WorktreeRepository
import dev.opencode.mobile.ui.text.EnglishStrings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class WorktreesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun groupsWorktreesUnderTheirProjectWithSessionsRunningAndChanges() =
        runTest(dispatcher) {
            val feed = feed(sessions = listOf(session("s1", "/wt/search", running = true), session("s2", "/wt/search")))
            val model = model(feed, changes = mapOf("/wt/search" to 3, "/wt/typo" to 0))
            advanceUntilIdle()

            val state = model.state.value
            assertEquals(2, state.worktreeCount)
            assertEquals(2, state.projectCount)
            val acme = state.groups.first()
            assertEquals("/repo", acme.mainCheckout)
            assertEquals(listOf("search", "typo"), acme.worktrees.map { it.name })
            val search = acme.worktrees.first()
            assertTrue(search.isRunning)
            assertEquals(3, search.changeCount)
            assertEquals(0, acme.worktrees[1].changeCount)
            assertTrue(state.groups[1].worktrees.isEmpty())
        }

    @Test
    fun aRunningSessionBlocksRemoveAndCanBeOpened() =
        runTest(dispatcher) {
            val feed = feed(sessions = listOf(session("s1", "/wt/search", running = true, title = "Search")))
            val model = model(feed)
            advanceUntilIdle()
            model.requestRemove("/wt/search")
            val dialog = model.state.value.dialog as WorktreeRemoveDialogUi.Blocked
            assertEquals("search", dialog.name)
            model.showRunningSession()
            advanceUntilIdle()

            assertNull(model.state.value.dialog)
            assertEquals(WorktreesEvent.OpenSession("s1", "Search", "/wt/search"), model.events.first())
        }

    @Test
    fun removeConfirmation_saysSessionsStayWithoutCountingThem() =
        runTest(dispatcher) {
            val feed = feed(sessions = listOf(session("s1", "/wt/search"), session("s2", "/wt/search")))
            val model = model(feed, changes = mapOf("/wt/search" to 0, "/wt/typo" to 0))
            advanceUntilIdle()

            model.requestRemove("/wt/search")
            assertTrue((model.state.value.dialog as WorktreeRemoveDialogUi.Confirm).hasSessions)
            model.dismissDialog()
            model.requestRemove("/wt/typo")
            assertFalse((model.state.value.dialog as WorktreeRemoveDialogUi.Confirm).hasSessions)
            // Home lists a window of sessions (#981), so the copy names no number.
            val note = EnglishStrings.string(R.string.worktrees_remove_sessions, emptyArray())
            assertFalse(note.any { it.isDigit() } || note.contains("%"))
        }

    @Test
    fun uncommittedChangesForceTheRemoveAndTheRowLeaves() =
        runTest(dispatcher) {
            val feed = feed(sessions = listOf(session("s2", "/wt/search")))
            val repository = FakeRemoveRepository()
            val model = model(feed, repository, changes = mapOf("/wt/search" to 2))
            advanceUntilIdle()

            model.requestRemove("/wt/search")
            val confirm = model.state.value.dialog as WorktreeRemoveDialogUi.Confirm
            assertEquals(2, confirm.changeCount)
            assertTrue(confirm.hasSessions)
            assertTrue(confirm.losesChanges)
            model.confirmRemove()
            advanceUntilIdle()

            assertEquals(listOf(RemoveWorktreeRequest("acme", "/repo", "/wt/search", force = true)), repository.requests)
            assertEquals(listOf("typo"), model.state.value.groups.first().worktrees.map { it.name })
            assertEquals("search", (model.state.value.snackbar as WorktreesSnackbarUi.Removed).name)
            assertEquals(mapOf("/wt/search" to "acme"), feed.removed.value)
        }

    @Test
    fun aCleanWorktreeIsRemovedWithoutForceAndShowsRemovingMeanwhile() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val repository = FakeRemoveRepository(gate = gate)
            val model = model(feed(), repository, changes = mapOf("/wt/typo" to 0))
            advanceUntilIdle()

            model.requestRemove("/wt/typo")
            assertFalse((model.state.value.dialog as WorktreeRemoveDialogUi.Confirm).losesChanges)
            model.confirmRemove()
            advanceUntilIdle()
            assertTrue(model.state.value.groups.first().worktrees.single { it.name == "typo" }.isRemoving)
            gate.complete(Unit)
            advanceUntilIdle()

            assertFalse(repository.requests.single().force)
        }

    @Test
    fun forceRequiredAsksAgainWithUnknownChanges() =
        runTest(dispatcher) {
            val repository = FakeRemoveRepository(errors = mutableListOf(WorktreeRemoveError.ForceRequired("dirty")))
            val model = model(feed(), repository)
            advanceUntilIdle()

            model.requestRemove("/wt/typo")
            model.confirmRemove()
            advanceUntilIdle()
            val again = model.state.value.dialog as WorktreeRemoveDialogUi.Confirm
            assertTrue(again.changesUnknown)
            model.confirmRemove()
            advanceUntilIdle()

            assertEquals(listOf(false, true), repository.requests.map { it.force })
        }

    @Test
    fun anUnreadableChangeCountWarnsInsteadOfActingClean() =
        runTest(dispatcher) {
            // No status for /wt/typo: the request fails, as on a server that cannot tell.
            val repository = FakeRemoveRepository()
            val model = model(feed(), repository)
            advanceUntilIdle()

            model.requestRemove("/wt/typo")
            val confirm = model.state.value.dialog as WorktreeRemoveDialogUi.Confirm
            assertTrue(confirm.changesUnchecked)
            assertTrue(confirm.warnsAboutChanges)
            assertFalse(confirm.losesChanges)
            model.confirmRemove()
            advanceUntilIdle()

            // v2 does not force on a guess; a dirty checkout comes back as ForceRequired.
            assertFalse(repository.requests.single().force)
        }

    @Test
    fun anotherServerDropsTheCachedChangeCounts() =
        runTest(dispatcher) {
            val feed = feed()
            val status = FakeStatusRepository(mapOf("/wt/search" to 1, "/wt/typo" to 0))
            WorktreesViewModel(feed, FakeRemoveRepository(), status)
            advanceUntilIdle()
            assertEquals(2, status.calls)

            feed.clear()
            advanceUntilIdle()
            feed.publish(feed().snapshot.value)
            advanceUntilIdle()

            assertEquals(4, status.calls)
        }

    @Test
    fun aFailureKeepsTheRowAndOffersRetry() =
        runTest(dispatcher) {
            val repository = FakeRemoveRepository(errors = mutableListOf(WorktreeRemoveError.Failed("Invalid worktree directory")))
            val feed = feed()
            val model = model(feed, repository, changes = mapOf("/wt/typo" to 0))
            advanceUntilIdle()

            model.requestRemove("/wt/typo")
            model.confirmRemove()
            advanceUntilIdle()

            val failed = model.state.value.snackbar as WorktreesSnackbarUi.Failed
            assertEquals("Invalid worktree directory", failed.message)
            val row = model.state.value.groups.first().worktrees.single { it.name == "typo" }
            assertFalse(row.isRemoving)
            assertTrue(feed.removed.value.isEmpty())

            model.retry(failed.directory)
            assertTrue(model.state.value.dialog is WorktreeRemoveDialogUi.Confirm)
        }

    @Test
    fun showOnHomeAndNewSessionGoBackToHome() =
        runTest(dispatcher) {
            val feed = feed()
            val model = model(feed)
            advanceUntilIdle()
            model.openActions("/wt/search")
            assertEquals("Acme", model.state.value.actions?.projectName)
            model.showOnHome("/wt/search")
            assertNull(model.state.value.actions)
            model.newSessionHere("/wt/typo")
            advanceUntilIdle()

            assertEquals(HomeWorktreeRequest.ShowSessions("acme", "/wt/search"), feed.requests.first())
            assertEquals(HomeWorktreeRequest.NewSession("/wt/typo"), feed.requests.first())
            assertEquals(WorktreesEvent.Close, model.events.first())
            assertEquals(WorktreesEvent.Close, model.events.first())
        }

    @Test
    fun menuCountsWorktreesAndTheProjectsThatHaveThem() {
        val snapshot = feed().snapshot.value

        assertEquals(2, snapshot.worktreeCount)
        assertEquals(1, snapshot.projectsWithWorktrees)
        assertEquals(0, HomeWorktreesSnapshot().worktreeCount)
    }

    @Test
    fun aFolderTheServerDidNotListAsAWorktreeIsNotARow() =
        runTest(dispatcher) {
            // The added path /tmp/repo is the main checkout through a symlink: Home groups it as a
            // non-main entry, but the server never listed it as a worktree.
            val feed = HomeWorktreesFeed()
            feed.publish(
                HomeWorktreesSnapshot(
                    projects =
                        listOf(
                            HomeProject(
                                key = "acme",
                                projectId = "acme",
                                displayName = "Acme",
                                memberPaths = listOf("/tmp/repo"),
                                isRepository = true,
                                worktrees =
                                    listOf(
                                        HomeWorktree("/private/tmp/repo", branch = "main", isMain = true),
                                        HomeWorktree("/tmp/repo", branch = "main"),
                                        HomeWorktree("/wt/a"),
                                    ),
                            ),
                        ),
                    removableDirectories = setOf("/wt/a"),
                ),
            )
            val model = model(feed)
            advanceUntilIdle()

            assertEquals(listOf("/wt/a"), model.state.value.groups.single().worktrees.map { it.directory })
            model.requestRemove("/tmp/repo")
            assertNull(model.state.value.dialog)
            assertEquals(1, feed.snapshot.value.worktreeCount)
        }

    @Test
    fun oneFolderUnderTwoSpellingsIsOneRow() =
        runTest(dispatcher) {
            // v1 lists /tmp/…; Home also knows the folder (and the main checkout) as /private/tmp/….
            val feed = HomeWorktreesFeed()
            feed.publish(
                HomeWorktreesSnapshot(
                    projects =
                        listOf(
                            HomeProject(
                                key = "acme",
                                projectId = "acme",
                                displayName = "Acme",
                                memberPaths = listOf("/tmp/repo"),
                                isRepository = true,
                                worktrees =
                                    listOf(
                                        HomeWorktree("/private/tmp/repo", branch = "main", isMain = true),
                                        HomeWorktree("/private/tmp/wt/v1-cleanup", branch = "opencode/v1-cleanup"),
                                        HomeWorktree("/tmp/repo", branch = "main"),
                                        HomeWorktree("/tmp/wt/v1-cleanup", branch = "opencode/v1-cleanup"),
                                    ),
                            ),
                        ),
                    sessions = listOf(session("s1", "/private/tmp/wt/v1-cleanup")),
                    removableDirectories = setOf("/tmp/wt/v1-cleanup", "/tmp/repo"),
                ),
            )
            val model = model(feed, changes = mapOf("/tmp/wt/v1-cleanup" to 0))
            advanceUntilIdle()

            val rows = model.state.value.groups.single().worktrees
            assertEquals(listOf("/tmp/wt/v1-cleanup"), rows.map { it.directory })
            assertEquals("v1-cleanup", rows.single().name)
            assertEquals(1, feed.snapshot.value.worktreeCount)
            model.requestRemove("/private/tmp/wt/v1-cleanup")
            assertEquals("/tmp/wt/v1-cleanup", (model.state.value.dialog as WorktreeRemoveDialogUi.Confirm).directory)
        }

    @Test
    fun anUnreachableServerGetsAPlainMessage() =
        runTest(dispatcher) {
            val repository =
                FakeRemoveRepository(errors = mutableListOf(WorktreeRemoveError.Unreachable("unexpected end of stream on http://10.0.2.2:4096/")))
            val model = model(feed(), repository, changes = mapOf("/wt/typo" to 0))
            advanceUntilIdle()

            model.requestRemove("/wt/typo")
            model.confirmRemove()
            advanceUntilIdle()

            val failed = model.state.value.snackbar as WorktreesSnackbarUi.Failed
            assertNull(failed.message)
        }

    @Test
    fun theMenuSaysNothingBeforeHomeListedWorktrees() {
        assertFalse(HomeWorktreesSnapshot(worktreesLoaded = false).worktreesLoaded)
        assertEquals(0, HomeWorktreesSnapshot(worktreesLoaded = false).worktreeCount)
    }

    @Test
    fun theMainCheckoutCannotBeRemoved() =
        runTest(dispatcher) {
            val model = model(feed())
            advanceUntilIdle()

            model.requestRemove("/repo")
            model.openActions("/repo")

            assertNull(model.state.value.dialog)
            assertNull(model.state.value.actions)
        }

    private fun TestScope.model(
        feed: HomeWorktreesFeed,
        repository: WorktreeRepository = FakeRemoveRepository(),
        changes: Map<String, Int> = emptyMap(),
    ) = WorktreesViewModel(feed, repository, FakeStatusRepository(changes))

    private fun feed(sessions: List<HomeWorktreeSession> = emptyList()) =
        HomeWorktreesFeed().apply {
            publish(
                HomeWorktreesSnapshot(
                    projects =
                        listOf(
                            HomeProject(
                                key = "acme",
                                projectId = "acme",
                                displayName = "Acme",
                                memberPaths = listOf("/repo"),
                                isRepository = true,
                                worktrees =
                                    listOf(
                                        HomeWorktree("/repo", branch = "main", isMain = true),
                                        HomeWorktree("/wt/search", branch = "feature/search"),
                                        HomeWorktree("/wt/typo"),
                                    ),
                            ),
                            HomeProject(
                                key = "web",
                                projectId = "web",
                                displayName = "Web",
                                memberPaths = listOf("/web"),
                                isRepository = true,
                                worktrees = listOf(HomeWorktree("/web", isMain = true)),
                            ),
                            HomeProject(
                                key = "path:/notes",
                                projectId = null,
                                displayName = "notes",
                                memberPaths = listOf("/notes"),
                                isRepository = false,
                                worktrees = emptyList(),
                            ),
                        ),
                    sessions = sessions,
                    removableDirectories = setOf("/wt/search", "/wt/typo"),
                ),
            )
        }

    private fun session(
        id: String,
        directory: String,
        running: Boolean = false,
        title: String = id,
    ) = HomeWorktreeSession(id = id, title = title, directory = directory, isRunning = running)

    private class FakeRemoveRepository(
        private val errors: MutableList<WorktreeRemoveError> = mutableListOf(),
        private val gate: CompletableDeferred<Unit>? = null,
    ) : WorktreeRepository {
        val requests = mutableListOf<RemoveWorktreeRequest>()

        override suspend fun remove(request: RemoveWorktreeRequest) {
            requests += request
            gate?.await()
            errors.removeFirstOrNull()?.let { throw it }
        }

        override suspend fun load(
            addedPath: String,
            projectId: String?,
            mainWorktree: String?,
        ): WorktreeLoad = WorktreeLoad.Unloaded

        override suspend fun loadRefreshed(
            addedPath: String,
            projectId: String?,
            mainWorktree: String?,
        ): WorktreeLoad = WorktreeLoad.Unloaded

        override suspend fun refresh(projectId: String): Boolean = false

        override suspend fun refetch(projectId: String): WorktreeLoad = WorktreeLoad.Unloaded

        override fun onDisconnected() = Unit

        override fun onConnection(key: String) = Unit
    }

    private class FakeStatusRepository(
        private val changes: Map<String, Int>,
    ) : SessionRepository by unsupportedSessionRepository() {
        var calls = 0

        override suspend fun getVcsStatus(directory: String?): List<VcsFileStatus> {
            calls += 1
            val count = changes[directory] ?: error("no status for $directory")
            return List(count) { VcsFileStatus("file$it.kt", FileDiffStatus.Modified, 1, 0) }
        }
    }
}

private fun unsupportedSessionRepository(): SessionRepository =
    Proxy.newProxyInstance(
        SessionRepository::class.java.classLoader,
        arrayOf(SessionRepository::class.java),
    ) { _, method, _ -> throw UnsupportedOperationException(method.name) } as SessionRepository
