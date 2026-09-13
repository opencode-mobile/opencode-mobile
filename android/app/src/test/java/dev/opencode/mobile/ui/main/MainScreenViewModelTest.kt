package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.connection.SecureCredentialStore
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.event.SilentWorldSignals
import dev.opencode.mobile.domain.live.DefaultSessionLiveStore
import dev.opencode.mobile.domain.live.DefaultSessionReadyStore
import dev.opencode.mobile.domain.live.DefaultSessionStatusLiveStore
import dev.opencode.mobile.domain.live.EmptySessionTodoLiveStore
import dev.opencode.mobile.domain.live.InMemorySessionReadyPersistence
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.domain.live.PermissionLiveStore
import dev.opencode.mobile.domain.live.QuestionLiveStore
import dev.opencode.mobile.domain.live.SessionReadyClock
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.DisplayMessageListUpdate
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.ModelSelectionOption
import dev.opencode.mobile.domain.model.NewSessionModelSelectionResolver
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.filesystem.DirectoryListingPolicy
import dev.opencode.mobile.domain.filesystem.DirectoryNode
import dev.opencode.mobile.domain.filesystem.FilesystemRepository
import dev.opencode.mobile.domain.filesystem.ServerPaths
import dev.opencode.mobile.domain.project.Project
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.domain.session.OptimisticSessionCreate
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.domain.session.SessionActivityEntry
import dev.opencode.mobile.domain.session.SessionActivitySnapshot
import dev.opencode.mobile.domain.session.SessionActivitySource
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.sessioncache.NoOpSessionCache
import dev.opencode.mobile.domain.sync.testSyncCoordinator
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import dev.opencode.mobile.ui.components.SessionListScrollPin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainScreenViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val serverUrl = "http://127.0.0.1:4096/"
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_probeFailed_whenHealthFails() =
        runTest(testDispatcher) {
            val viewModel = createViewModel(shouldFailProbe = true)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is MainScreenUiState.ProbeFailed)
        }

    @Test
    fun uiState_staysEmpty_whenNoAddedProjects() =
        runTest(testDispatcher) {
            val store = FakeConnectionProfileStore()
            val viewModel =
                createViewModel(connectionProfileStore = store, bootWorktree = "/boot/project")
            advanceUntilIdle()
            assertEquals(emptyList<String>(), store.added(serverUrl))
            assertTrue(viewModel.uiState.value is MainScreenUiState.Empty)
        }

    @Test
    fun uiState_excludesArchivedSessionsFromActiveLists() =
        runTest(testDispatcher) {
            val sessions =
                listOf(
                    Session(id = "active", title = "Active", updatedAtEpochMs = 2L),
                    Session(id = "archived", title = "Archived", updatedAtEpochMs = 3L, archivedAtEpochMs = 1L),
                )
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionsByWorktree = mapOf("/repo" to sessions),
                )
            advanceUntilIdle()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            val section = loaded.sections.single()
            assertEquals(listOf("active"), section.allSessions.map { it.id })
            assertEquals(1, section.allSessions.size)
        }

    @Test
    fun uiState_includesAllSessionsInAllSessions() =
        runTest(testDispatcher) {
            val sessions =
                (1..7).map { index ->
                    Session(id = "$index", title = "Session $index", updatedAtEpochMs = index.toLong())
                }
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionsByWorktree = mapOf("/repo" to sessions),
                )
            advanceUntilIdle()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(7, loaded.sections.single().allSessions.size)
        }

    @Test
    fun requestRemoveProject_setsPendingRemoval() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/one", "/two")),
                    sessionsByWorktree = mapOf("/one" to emptyList(), "/two" to emptyList()),
                )
            advanceUntilIdle()
            viewModel.requestRemoveProject("/one")
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals("/one", loaded.pendingProjectRemoval?.worktree)
            assertEquals("one", loaded.pendingProjectRemoval?.displayName)
        }

    @Test
    fun dismissProjectRemoval_clearsPending() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/one")),
                    sessionsByWorktree = mapOf("/one" to emptyList()),
                )
            advanceUntilIdle()
            viewModel.requestRemoveProject("/one")
            viewModel.dismissProjectRemoval()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertNull(loaded.pendingProjectRemoval)
        }

    @Test
    fun confirmRemoveProject_dropsWorktreeAndShowsSnackbar() =
        runTest(testDispatcher) {
            val store = FakeConnectionProfileStore(initial = listOf("/one", "/two"))
            val viewModel =
                createViewModel(
                    connectionProfileStore = store,
                    sessionsByWorktree = mapOf("/one" to emptyList(), "/two" to emptyList()),
                )
            advanceUntilIdle()
            viewModel.requestRemoveProject("/one")
            viewModel.confirmRemoveProject()
            advanceUntilIdle()

            assertEquals(listOf("/two"), store.added(serverUrl))
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertNull(loaded.pendingProjectRemoval)
            assertEquals(listOf("/two"), loaded.sections.map { it.worktree })
            assertEquals("Removed \"one\" from home", viewModel.removalSnackbar.value?.text)

            viewModel.consumeProjectRemovalSnackbar()
            assertNull(viewModel.removalSnackbar.value)
        }

    @Test
    fun confirmRemoveProject_lastProject_showsEmpty() =
        runTest(testDispatcher) {
            val store = FakeConnectionProfileStore(initial = listOf("/one"))
            val viewModel =
                createViewModel(
                    connectionProfileStore = store,
                    sessionsByWorktree = mapOf("/one" to emptyList()),
                )
            advanceUntilIdle()
            viewModel.requestRemoveProject("/one")
            viewModel.confirmRemoveProject()
            advanceUntilIdle()

            assertEquals(emptyList<String>(), store.added(serverUrl))
            assertTrue(viewModel.uiState.value is MainScreenUiState.Empty)
            assertEquals("Removed \"one\" from home", viewModel.removalSnackbar.value?.text)
        }

    @Test
    fun showAddProjectSheet_startsAtHomeAndListsDirectories() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "/home", directory = "/home/work", worktree = "/"),
                    listings =
                        mapOf(
                            "/home" to
                                listOf(
                                    DirectoryNode(name = "projects", absolutePath = "/home/projects", isDirectory = true),
                                    DirectoryNode(name = ".cache", absolutePath = "/home/.cache", isDirectory = true),
                                    DirectoryNode(name = "readme", absolutePath = "/home/readme", isDirectory = false),
                                ),
                        ),
                )
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/one")),
                    filesystemRepository = filesystem,
                )
            advanceUntilIdle()
            viewModel.showAddProjectSheet()
            advanceUntilIdle()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            val navigator = loaded.addProjectNavigator
            assertTrue(loaded.showAddProjectSheet)
            assertEquals("/home", navigator?.currentPath)
            assertEquals(listOf("projects"), navigator?.entries?.map { it.name })
            assertTrue(navigator?.canGoUp == true)
        }

    @Test
    fun navigateAddProjectInto_loadsChildDirectory() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "/home", directory = "/home", worktree = "/"),
                    listings =
                        mapOf(
                            "/home" to listOf(DirectoryNode("projects", "/home/projects", true)),
                            "/home/projects" to listOf(DirectoryNode("app", "/home/projects/app", true)),
                        ),
                )
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/one")),
                    filesystemRepository = filesystem,
                )
            advanceUntilIdle()
            viewModel.showAddProjectSheet()
            advanceUntilIdle()
            viewModel.navigateAddProjectInto("/home/projects")
            advanceUntilIdle()
            val navigator = (viewModel.uiState.value as MainScreenUiState.Loaded).addProjectNavigator
            assertEquals("/home/projects", navigator?.currentPath)
            assertEquals(listOf("app"), navigator?.entries?.map { it.name })
        }

    @Test
    fun selectCurrentDirectory_validatesAndAddsProject() =
        runTest(testDispatcher) {
            val store = FakeConnectionProfileStore(initial = listOf("/one"))
            val projectRepository = FakeProjectRepository(bootWorktree = "/one")
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "/home", directory = "/home", worktree = "/"),
                    listings = mapOf("/home" to emptyList()),
                )
            val viewModel =
                createViewModel(
                    connectionProfileStore = store,
                    projectRepository = projectRepository,
                    filesystemRepository = filesystem,
                    sessionsByWorktree = mapOf("/one" to emptyList(), "/home" to emptyList()),
                )
            advanceUntilIdle()
            viewModel.showAddProjectSheet()
            advanceUntilIdle()
            viewModel.selectCurrentDirectory()
            advanceUntilIdle()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(listOf("/one", "/home"), store.added(serverUrl))
            assertTrue(projectRepository.validatedDirectories.contains("/home"))
            assertTrue(!loaded.showAddProjectSheet)
            assertEquals("/home", viewModel.pendingSelectedFilterId.value)
        }

    @Test
    fun addProject_alreadyAdded_reloadsWithoutRevalidate() =
        runTest(testDispatcher) {
            val store = FakeConnectionProfileStore(initial = listOf("/one", "/two"))
            val projectRepository = FakeProjectRepository(bootWorktree = "/one")
            val viewModel =
                createViewModel(
                    connectionProfileStore = store,
                    projectRepository = projectRepository,
                    sessionsByWorktree = mapOf("/one" to emptyList(), "/two" to emptyList()),
                )
            advanceUntilIdle()
            projectRepository.validatedDirectories.clear()
            viewModel.addProject("/two")
            advanceUntilIdle()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(listOf("/one", "/two"), store.added(serverUrl))
            // Already-added path skips GET /project/current validation before reload.
            // Disk-first home paints from Session Cache and does not revalidate directories.
            assertTrue(projectRepository.validatedDirectories.isEmpty())
            assertEquals("/two", viewModel.pendingSelectedFilterId.value)
            assertEquals("\"two\" is already on home", viewModel.removalSnackbar.value?.text)
        }

    @Test
    fun selectCurrentDirectory_persistsSelectedPathEvenWhenCurrentResolvesGlobal() =
        runTest(testDispatcher) {
            val store = FakeConnectionProfileStore(initial = listOf("/one"))
            val projectRepository =
                FakeProjectRepository(
                    bootWorktree = "/one",
                    projectsByDirectory =
                        mapOf(
                            "/home" to Project(id = "global", worktree = "/", displayName = "/"),
                        ),
                )
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "/home", directory = "/home", worktree = "/"),
                    listings = mapOf("/home" to emptyList()),
                )
            val viewModel =
                createViewModel(
                    connectionProfileStore = store,
                    projectRepository = projectRepository,
                    filesystemRepository = filesystem,
                    sessionsByWorktree = mapOf("/one" to emptyList(), "/home" to emptyList()),
                )
            advanceUntilIdle()
            viewModel.showAddProjectSheet()
            advanceUntilIdle()
            viewModel.selectCurrentDirectory()
            advanceUntilIdle()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(listOf("/one", "/home"), store.added(serverUrl))
            assertTrue(!loaded.showAddProjectSheet)
        }

    @Test
    fun selectCurrentDirectory_nestedPathPersistsSelectedDirectory() =
        runTest(testDispatcher) {
            val store = FakeConnectionProfileStore(initial = listOf("/one"))
            val projectRepository =
                FakeProjectRepository(
                    bootWorktree = "/one",
                    projectsByDirectory =
                        mapOf(
                            "/repo/android" to
                                Project(id = "repo", worktree = "/repo", displayName = "repo"),
                        ),
                )
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "/repo/android", directory = "/repo/android", worktree = "/"),
                    listings = mapOf("/repo/android" to emptyList()),
                )
            val viewModel =
                createViewModel(
                    connectionProfileStore = store,
                    projectRepository = projectRepository,
                    filesystemRepository = filesystem,
                    sessionsByWorktree = mapOf("/one" to emptyList(), "/repo/android" to emptyList()),
                )
            advanceUntilIdle()
            viewModel.showAddProjectSheet()
            advanceUntilIdle()
            viewModel.selectCurrentDirectory()
            advanceUntilIdle()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(listOf("/one", "/repo/android"), store.added(serverUrl))
            assertTrue(!loaded.showAddProjectSheet)
        }

    @Test
    fun selectCurrentDirectory_validationFailureKeepsSheetOpen() =
        runTest(testDispatcher) {
            val projectRepository =
                FakeProjectRepository(
                    bootWorktree = "/one",
                    currentProjectError = IllegalStateException("not a project"),
                )
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "/home", directory = "/home", worktree = "/"),
                    listings = mapOf("/home" to emptyList()),
                )
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/one")),
                    projectRepository = projectRepository,
                    filesystemRepository = filesystem,
                )
            advanceUntilIdle()
            viewModel.showAddProjectSheet()
            advanceUntilIdle()
            viewModel.selectCurrentDirectory()
            advanceUntilIdle()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertTrue(loaded.showAddProjectSheet)
            assertEquals("not a project", loaded.addProjectNavigator?.errorMessage)
            assertNull(viewModel.pendingSelectedFilterId.value)
        }

    @Test
    fun createSession_usesFocusedWorktreeAndClearsError() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository(mutableMapOf("/repo" to emptyList()))
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionRepository = sessionRepository,
                )
            advanceUntilIdle()
            assertEquals(0, viewModel.revealNewestToken.value)
            viewModel.createSession("/repo")
            advanceUntilIdle()
            assertEquals(emptyList<String>(), sessionRepository.createdDirectories)
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(1, viewModel.revealNewestToken.value)
            assertEquals(OptimisticSessionCreate.PLACEHOLDER_SESSION_ID, loaded.pendingOpenSession?.sessionId)
            assertEquals("/repo", loaded.pendingOpenSession?.worktree)
            assertNull(loaded.projectSessionCreateError)
        }

    @Test
    fun homeReveal_afterStaleAbsenceIncrementsRevealToken() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                )
            advanceUntilIdle()
            viewModel.markHomeHidden(nowEpochMs = 0L)
            viewModel.onHomeRevealed(nowEpochMs = SessionListScrollPin.STALE_AFTER_MS)
            assertEquals(1, viewModel.revealNewestToken.value)
        }

    @Test
    fun homeReveal_beforeStaleAbsenceKeepsScroll() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                )
            advanceUntilIdle()
            viewModel.markHomeHidden(nowEpochMs = 0L)
            viewModel.onHomeRevealed(nowEpochMs = SessionListScrollPin.STALE_AFTER_MS - 1)
            assertEquals(0, viewModel.revealNewestToken.value)
        }

    @Test
    fun refreshWhileLive_doesNotClobberLoadedSessionsWithEmptyCache() =
        runTest(testDispatcher) {
            val sessions =
                listOf(
                    Session(id = "ses-1", title = "Alpha", directory = "/repo", updatedAtEpochMs = 1L),
                )
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionRepository = FakeSessionRepository(mutableMapOf("/repo" to sessions)),
                )
            advanceUntilIdle()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(listOf("ses-1"), loaded.sections.flatMap { it.allSessions }.map { it.id })

            viewModel.refresh()
            advanceUntilIdle()
            val reloaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(listOf("ses-1"), reloaded.sections.flatMap { it.allSessions }.map { it.id })
        }

    @Test
    fun pullToRefresh_keepsLoadedContentWithoutFullScreenLoading() =
        runTest(testDispatcher) {
            val sessionsByWorktree =
                mutableMapOf(
                    "/repo" to listOf(Session(id = "1", title = "Session 1", updatedAtEpochMs = 1L)),
                )
            val sessionRepository = FakeSessionRepository(sessionsByWorktree)
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionsByWorktree = sessionsByWorktree,
                    sessionRepository = sessionRepository,
                )
            advanceUntilIdle()
            val before = viewModel.uiState.value as MainScreenUiState.Loaded
            val listCallsBefore = sessionRepository.listSessionsCallCount

            viewModel.pullToRefresh()
            advanceUntilIdle()

            assertFalse(viewModel.isRefreshing.value)
            val after = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(before.sections.single().worktree, after.sections.single().worktree)
            assertTrue(sessionRepository.listSessionsCallCount > listCallsBefore)
            assertTrue(viewModel.uiState.value is MainScreenUiState.Loaded)
        }

    @Test
    fun pullToRefresh_keepsRefreshingUntilLiveStoreCatchUpFinishes() =
        runTest(testDispatcher) {
            val sessionsByWorktree =
                mutableMapOf(
                    "/repo" to listOf(Session(id = "1", title = "Session 1", updatedAtEpochMs = 1L)),
                )
            val sessionRepository = FakeSessionRepository(sessionsByWorktree)
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionsByWorktree = sessionsByWorktree,
                    sessionRepository = sessionRepository,
                )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is MainScreenUiState.Loaded)

            val gate = CompletableDeferred<Unit>()
            sessionRepository.nextListGate = gate
            viewModel.pullToRefresh()
            // runCurrent, not advanceUntilIdle: the catch-up ack is bounded by
            // LiveStoreRefreshPolicy.INVALIDATION_ACK_TIMEOUT_MS, and advancing the
            // virtual clock would burn through that ceiling before the assert.
            runCurrent()

            assertTrue(viewModel.isRefreshing.value)

            gate.complete(Unit)
            advanceUntilIdle()

            assertFalse(viewModel.isRefreshing.value)
        }

    @Test
    fun pullToRefresh_recoversFromAStalledCatchUp_andStillWorksAfterwards() =
        runTest(testDispatcher) {
            val sessionsByWorktree =
                mutableMapOf(
                    "/repo" to listOf(Session(id = "1", title = "Session 1", updatedAtEpochMs = 1L)),
                )
            val sessionRepository = FakeSessionRepository(sessionsByWorktree)
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionsByWorktree = sessionsByWorktree,
                    sessionRepository = sessionRepository,
                )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value is MainScreenUiState.Loaded)

            val stalled = CompletableDeferred<Unit>()
            sessionRepository.nextListGate = stalled
            viewModel.pullToRefresh()
            advanceUntilIdle()

            assertFalse(
                "the spinner must drop once the ack ceiling passes; a never-acked invalidate " +
                    "leaves isRefreshing latched true forever",
                viewModel.isRefreshing.value,
            )

            stalled.complete(Unit)
            advanceUntilIdle()
            val listCallsBefore = sessionRepository.listSessionsCallCount
            viewModel.pullToRefresh()
            advanceUntilIdle()

            assertTrue(
                "a later pull-to-refresh must still reach the server; the isRefreshing guard " +
                    "makes a lost ack a permanent no-op",
                sessionRepository.listSessionsCallCount > listCallsBefore,
            )
            assertFalse(viewModel.isRefreshing.value)
        }

    @Test
    fun pullToRefresh_replacesStaleLiveStoreSnapshot() =
        runTest(testDispatcher) {
            val sessionsByWorktree =
                mutableMapOf(
                    "/repo" to listOf(Session(id = "1", title = "Session 1", updatedAtEpochMs = 1L)),
                )
            val sessionRepository = FakeSessionRepository(sessionsByWorktree)
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionsByWorktree = sessionsByWorktree,
                    sessionRepository = sessionRepository,
                )
            advanceUntilIdle()
            assertEquals(
                listOf("Session 1"),
                (viewModel.uiState.value as MainScreenUiState.Loaded)
                    .sections
                    .single()
                    .allSessions
                    .map { it.title },
            )

            sessionsByWorktree["/repo"] =
                listOf(
                    Session(id = "2", title = "New Session Greeting", updatedAtEpochMs = 2L),
                    Session(id = "1", title = "Session 1", updatedAtEpochMs = 1L),
                )
            viewModel.pullToRefresh()
            advanceUntilIdle()

            assertEquals(
                listOf("New Session Greeting", "Session 1"),
                (viewModel.uiState.value as MainScreenUiState.Loaded)
                    .sections
                    .single()
                    .allSessions
                    .map { it.title },
            )
        }

    @Test
    fun catchUpChrome_doesNotDriveHomePullToRefresh() =
        runTest(testDispatcher) {
            val messages = FakeMessageRepository()
            messages.catchUpChrome.value = true
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionsByWorktree =
                        mapOf(
                            "/repo" to listOf(Session(id = "1", title = "Session 1", updatedAtEpochMs = 1L)),
                        ),
                    messageRepository = messages,
                )
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value is MainScreenUiState.Loaded)
            assertFalse(
                "resume catch-up must not latch the pull-to-refresh spinner",
                viewModel.isRefreshing.value,
            )
        }

    @Test
    fun refreshOnSessionCreatedEvent() =
        runTest(testDispatcher) {
            val eventStream = FakeForegroundEventStream()
            eventStream.setConnectionState(EventStreamConnectionState.Connected)
            val sessionsByWorktree =
                mutableMapOf(
                    "/repo" to listOf(Session(id = "1", title = "Session 1", updatedAtEpochMs = 1L)),
                )
            val sessionRepository = FakeSessionRepository(sessionsByWorktree)
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    eventStream = eventStream,
                    sessionsByWorktree = sessionsByWorktree,
                    sessionRepository = sessionRepository,
                )
            advanceUntilIdle()
            val before = (viewModel.uiState.value as MainScreenUiState.Loaded).sections.single().allSessions.size

            sessionsByWorktree["/repo"] =
                listOf(
                    Session(id = "1", title = "Session 1", updatedAtEpochMs = 1L),
                    Session(id = "new", title = "Session new", updatedAtEpochMs = 2L),
                )
            eventStream.emit(OpencodeEvent(type = "session.created", sessionId = "new"))
            advanceTimeBy(550)
            advanceUntilIdle()

            val after = (viewModel.uiState.value as MainScreenUiState.Loaded).sections.single().allSessions.size
            assertEquals(before + 1, after)
            assertTrue(sessionRepository.listSessionsCallCount >= 2)
        }

    @Test
    fun onSessionLongPress_showsSheetAndEnablesForkWhenUserMessagesExist() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    messageRepository =
                        FakeMessageRepository(
                            messagesBySession =
                                mapOf(
                                    "s1" to listOf(Message(id = "m1", role = MessageRole.User, text = "Hello")),
                                ),
                        ),
                )
            advanceUntilIdle()

            viewModel.onSessionLongPress(sessionId = "s1", title = "Session one", worktree = "/repo")
            val opened = viewModel.sessionActions.value
            assertTrue(opened.sheetVisible)
            assertEquals("Session one", opened.target?.title)
            advanceUntilIdle()

            assertTrue(viewModel.sessionActions.value.canFork)
        }

    @Test
    fun onSessionLongPress_hidesForkWhenNoUserMessages() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    messageRepository =
                        FakeMessageRepository(
                            messagesBySession =
                                mapOf(
                                    "s1" to
                                        listOf(
                                            Message(id = "m1", role = MessageRole.Assistant, text = "Hi"),
                                        ),
                                ),
                        ),
                )
            advanceUntilIdle()

            viewModel.onSessionLongPress(sessionId = "s1", title = "Session one", worktree = "/repo")
            advanceUntilIdle()

            assertFalse(viewModel.sessionActions.value.canFork)

            viewModel.chooseFork()
            assertFalse(viewModel.sessionActions.value.forkVisible)
        }

    @Test
    fun onSessionLongPress_forkGateFailureLeavesForkHidden() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    messageRepository = FakeMessageRepository(listError = IllegalStateException("offline")),
                )
            advanceUntilIdle()

            viewModel.onSessionLongPress(sessionId = "s1", title = "Session one", worktree = "/repo")
            advanceUntilIdle()

            assertFalse(viewModel.sessionActions.value.canFork)
            assertTrue(viewModel.sessionActions.value.sheetVisible)
        }

    @Test
    fun chooseRename_thenConfirm_renamesSessionAndClearsActions() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository(mutableMapOf("/repo" to emptyList()))
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionRepository = sessionRepository,
                )
            advanceUntilIdle()

            viewModel.onSessionLongPress(sessionId = "s1", title = "Session one", worktree = "/repo")
            viewModel.chooseRename()
            assertFalse(viewModel.sessionActions.value.sheetVisible)
            assertTrue(viewModel.sessionActions.value.renameVisible)
            assertEquals("Session one", viewModel.sessionActions.value.renameDraft)

            viewModel.updateRenameDraft("Renamed")
            viewModel.confirmRename()
            advanceUntilIdle()

            assertEquals(listOf("s1" to "Renamed"), sessionRepository.renamedTitles)
            assertEquals(HomeSessionActionsUiState(), viewModel.sessionActions.value)
        }

    @Test
    fun confirmRename_emptyTitleKeepsDialogWithError() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository(mutableMapOf("/repo" to emptyList()))
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionRepository = sessionRepository,
                )
            advanceUntilIdle()

            viewModel.onSessionLongPress(sessionId = "s1", title = "Session one", worktree = "/repo")
            viewModel.chooseRename()
            viewModel.updateRenameDraft("   ")
            viewModel.confirmRename()
            advanceUntilIdle()

            assertTrue(viewModel.sessionActions.value.renameVisible)
            assertEquals("Enter a session title", viewModel.sessionActions.value.renameError)
            assertTrue(sessionRepository.renamedTitles.isEmpty())
        }

    @Test
    fun chooseArchive_failureShowsSnackbarAndStaysHome() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    sessionsByWorktree = mutableMapOf("/repo" to emptyList()),
                    archiveError = IllegalStateException("nope"),
                )
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionRepository = sessionRepository,
                )
            advanceUntilIdle()

            viewModel.onSessionLongPress(sessionId = "s1", title = "Session one", worktree = "/repo")
            viewModel.chooseArchive()
            advanceUntilIdle()

            assertEquals(listOf("s1"), sessionRepository.archivedIds)
            assertEquals("Couldn't archive session.", viewModel.removalSnackbar.value?.text)
            assertEquals(HomeSessionActionsUiState(), viewModel.sessionActions.value)
            assertTrue(viewModel.uiState.value is MainScreenUiState.Loaded)
        }

    @Test
    fun confirmDelete_failureShowsSnackbar() =
        runTest(testDispatcher) {
            val sessionRepository =
                FakeSessionRepository(
                    sessionsByWorktree = mutableMapOf("/repo" to emptyList()),
                    deleteResult = false,
                )
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionRepository = sessionRepository,
                )
            advanceUntilIdle()

            viewModel.onSessionLongPress(sessionId = "s1", title = "Session one", worktree = "/repo")
            viewModel.chooseDelete()
            assertTrue(viewModel.sessionActions.value.deleteConfirmVisible)

            viewModel.confirmDelete()
            advanceUntilIdle()

            assertEquals(listOf("s1"), sessionRepository.deletedIds)
            assertEquals("Couldn't delete session.", viewModel.removalSnackbar.value?.text)
            assertEquals(HomeSessionActionsUiState(), viewModel.sessionActions.value)
        }

    @Test
    fun confirmFork_thenOpen_setsPendingOpenSession() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository(mutableMapOf("/repo" to emptyList()))
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionRepository = sessionRepository,
                    messageRepository =
                        FakeMessageRepository(
                            messagesBySession =
                                mapOf(
                                    "s1" to listOf(Message(id = "m1", role = MessageRole.User, text = "Hello")),
                                ),
                        ),
                )
            advanceUntilIdle()

            viewModel.onSessionLongPress(sessionId = "s1", title = "Session one", worktree = "/repo")
            advanceUntilIdle()
            viewModel.chooseFork()
            assertEquals("Fork of Session one", viewModel.sessionActions.value.forkDraft)

            viewModel.confirmFork()
            advanceUntilIdle()

            assertEquals(listOf("s1"), sessionRepository.forkedIds)
            assertTrue(viewModel.sessionActions.value.forkResultVisible)
            assertEquals("Fork of Session one", viewModel.sessionActions.value.forkedTitle)

            viewModel.chooseForkOpen()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals("forked-s1", loaded.pendingOpenSession?.sessionId)
            assertEquals("/repo", loaded.pendingOpenSession?.worktree)
            assertEquals(HomeSessionActionsUiState(), viewModel.sessionActions.value)
        }

    @Test
    fun chooseForkStay_keepsHomeWithoutPendingOpen() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    messageRepository =
                        FakeMessageRepository(
                            messagesBySession =
                                mapOf(
                                    "s1" to listOf(Message(id = "m1", role = MessageRole.User, text = "Hello")),
                                ),
                        ),
                )
            advanceUntilIdle()

            viewModel.onSessionLongPress(sessionId = "s1", title = "Session one", worktree = "/repo")
            advanceUntilIdle()
            viewModel.chooseFork()
            viewModel.confirmFork()
            advanceUntilIdle()
            viewModel.chooseForkStay()

            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertNull(loaded.pendingOpenSession)
            assertEquals(HomeSessionActionsUiState(), viewModel.sessionActions.value)
        }

    @Test
    fun suggestedHomeForkTitle_fallsBackForBlankTitle() {
        assertEquals("Fork of Session one", suggestedHomeForkTitle("Session one"))
        assertEquals("Forked session", suggestedHomeForkTitle("   "))
    }

    @Test
    fun sessionStatusLabel_mapsWorkingAndRetryingFromLoader() =
        runTest(testDispatcher) {
            val sessions = listOf(Session(id = "ses-1", title = "Chat", updatedAtEpochMs = 1L))
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionsByWorktree = mapOf("/repo" to sessions),
                    sessionStatuses =
                        listOf(
                            SessionStatus(
                                sessionId = "ses-1",
                                raw = mapOf("type" to "retry", "attempt" to "2"),
                            ),
                        ),
                )
            advanceUntilIdle()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals("Retrying · 2", loaded.sections.single().allSessions.single().statusLabel)
        }

    @Test
    fun sessionStatusLabel_mapsWorkingFromBusyStatus() =
        runTest(testDispatcher) {
            val sessions = listOf(Session(id = "ses-1", title = "Chat", updatedAtEpochMs = 1L))
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionsByWorktree = mapOf("/repo" to sessions),
                    sessionStatuses =
                        listOf(SessionStatus(sessionId = "ses-1", raw = mapOf("type" to "busy"))),
                )
            advanceUntilIdle()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals("Working", loaded.sections.single().allSessions.single().statusLabel)
        }

    @Test
    fun sessionStatusLabel_isHiddenUntilSeeded() =
        runTest(testDispatcher) {
            val sessions = listOf(Session(id = "ses-1", title = "Chat", updatedAtEpochMs = 1L))
            val activity =
                MutableStateFlow(
                    SessionActivitySnapshot(
                        entries =
                            mapOf(
                                "ses-1" to
                                    SessionActivityEntry(
                                        activity = SessionActivity.Running,
                                        source = SessionActivitySource.Server,
                                        observedAtMs = 0L,
                                    ),
                            ),
                        seeded = false,
                    ),
                )
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionsByWorktree = mapOf("/repo" to sessions),
                    statusLiveStoreOverride = FakeHomeStatusLiveStore(activity),
                )
            advanceUntilIdle()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertNull(loaded.sections.single().allSessions.single().statusLabel)
        }

    private fun createViewModel(
        shouldFailProbe: Boolean = false,
        connectionProfileStore: FakeConnectionProfileStore = FakeConnectionProfileStore(),
        bootWorktree: String = "/boot/project",
        sessionsByWorktree: Map<String, List<Session>> = emptyMap(),
        eventStream: FakeForegroundEventStream =
            FakeForegroundEventStream().also {
                it.setConnectionState(EventStreamConnectionState.Connected)
            },
        sessionRepository: FakeSessionRepository = FakeSessionRepository(sessionsByWorktree.toMutableMap()),
        messageRepository: MessageRepository = FakeMessageRepository(),
        selectionStore: FakeSessionModelSelectionStore = FakeSessionModelSelectionStore(),
        projectRepository: FakeProjectRepository = FakeProjectRepository(shouldFailProbe, bootWorktree),
        filesystemRepository: FilesystemRepository = FakeFilesystemRepository(),
        sessionStatuses: List<SessionStatus> = emptyList(),
        statusLiveStoreOverride: SessionStatusLiveStore? = null,
        modelRepository: ModelRepository =
            object : ModelRepository {
                override suspend fun listModelOptions(directory: String?): ModelSelectionCatalog = ModelSelectionCatalog(emptyList(), null)
            },
    ): MainScreenViewModel {
        val activeConnectionManager = ActiveConnectionManager(connectionProfileStore)
        runBlocking {
            activeConnectionManager.applyProfile(
                ConnectionProfile(baseUrl = serverUrl, username = "opencode", password = ""),
            )
        }
        val coordinator =
            ConnectionSessionCoordinator(
                activeConnectionManager = activeConnectionManager,
                connectionProfileStore = connectionProfileStore,
                secureCredentialStore = FakeSecureCredentialStore(),
                sessionModelSelectionStore = selectionStore,
                composerDraftStore = dev.opencode.mobile.domain.composer.InMemoryComposerDraftStore(),
                httpClient = OpencodeHttpClient(json, activeConnectionManager),
                sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
                eventStream = eventStream,
            )
        if (sessionStatuses.isNotEmpty()) {
            sessionRepository.statuses = sessionStatuses
        }
        val liveStoreScope = LiveStoreScope(CoroutineScope(SupervisorJob() + testDispatcher))
        val coordinatorSync =
            testSyncCoordinator(
                eventStream = eventStream,
                worldSignals = SilentWorldSignals,
                scope = liveStoreScope.coroutineScope,
                nowMs = { testDispatcher.scheduler.currentTime },
                wait = { kotlinx.coroutines.delay(it) },
            )
        val sessionStatusLiveStore =
            statusLiveStoreOverride
                ?: DefaultSessionStatusLiveStore(
                    sessionRepository,
                    eventStream,
                    liveStoreScope,
                    EventDomainAdapter(json),
                    SilentWorldSignals,
                    coordinatorSync,
                )
        val sessionLiveStore =
            DefaultSessionLiveStore(
                sessionRepository,
                eventStream,
                liveStoreScope,
                NoOpSessionCache,
                coordinatorSync,
            )
        val sessionReadyStore =
            DefaultSessionReadyStore(
                sessionStatusLiveStore,
                eventStream,
                liveStoreScope,
                InMemorySessionReadyPersistence(),
                SessionReadyClock { System.currentTimeMillis() },
            )
        return MainScreenViewModel(
            projectRepository = projectRepository,
            filesystemRepository = filesystemRepository,
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            connectionProfileStore = connectionProfileStore,
            sessionDomainAdapter = SessionDomainAdapter(),
            eventDomainAdapter = EventDomainAdapter(json),
            sessionLiveStore = sessionLiveStore,
            sessionStatusLiveStore = sessionStatusLiveStore,
            permissionLiveStore = object : PermissionLiveStore {
                override fun observePermissions(directory: String) = emptyFlow<List<dev.opencode.mobile.domain.session.PermissionRequest>>()
                override fun clearCache() = Unit
            },
            questionLiveStore = object : QuestionLiveStore {
                override fun observeQuestions(directory: String) = emptyFlow<List<dev.opencode.mobile.domain.session.QuestionRequest>>()
                override fun clearCache() = Unit
            },
            sessionTodoLiveStore = EmptySessionTodoLiveStore,
            sessionReadyStore = sessionReadyStore,
            foregroundEventStream = eventStream,
            activeConnectionManager = activeConnectionManager,
            connectionSessionCoordinator = coordinator,
            newSessionModelSelectionResolver =
                NewSessionModelSelectionResolver(
                    selectionStore,
                    modelRepository,
                ),
            sessionCache = NoOpSessionCache,
        )
    }
}

private class FakeProjectRepository(
    private val shouldFailProbe: Boolean = false,
    private val bootWorktree: String = "/boot/project",
    private val currentProjectError: Throwable? = null,
    private val projectsByDirectory: Map<String, Project> = emptyMap(),
) : ProjectRepository {
    val validatedDirectories = mutableListOf<String?>()

    override suspend fun probeServer() {
        if (shouldFailProbe) throw ServerProbeError.Transport(IllegalStateException("down"))
    }

    override suspend fun listProjects(): List<Project> = emptyList()

    override suspend fun getCurrentProject(directory: String?): Project {
        validatedDirectories += directory
        currentProjectError?.let { throw it }
        if (directory != null) {
            projectsByDirectory[directory]?.let { return it }
        }
        return Project(id = "p1", worktree = directory ?: bootWorktree, displayName = "demo")
    }
}

private class FakeFilesystemRepository(
    private val paths: ServerPaths = ServerPaths(home = "/home", directory = "/home", worktree = "/"),
    private val listings: Map<String, List<DirectoryNode>> = emptyMap(),
) : FilesystemRepository {
    override suspend fun getPaths(): ServerPaths = paths

    override suspend fun listDirectory(path: String): List<DirectoryNode> =
        DirectoryListingPolicy.prepare(listings[path].orEmpty())
}

private class FakeSessionRepository(
    private val sessionsByWorktree: MutableMap<String, List<Session>>,
    private val createError: Throwable? = null,
    private val updateError: Throwable? = null,
    private val archiveError: Throwable? = null,
    private val deleteError: Throwable? = null,
    private val forkError: Throwable? = null,
    private val deleteResult: Boolean = true,
) : SessionRepository {
    var listSessionsCallCount = 0
    var nextListGate: CompletableDeferred<Unit>? = null
    val createdDirectories = mutableListOf<String?>()
    val renamedTitles = mutableListOf<Pair<String, String?>>()
    val archivedIds = mutableListOf<String>()
    val deletedIds = mutableListOf<String>()
    val forkedIds = mutableListOf<String>()

    override suspend fun listSessions(directory: String?): List<Session> {
        listSessionsCallCount += 1
        nextListGate?.also {
            nextListGate = null
            it.await()
        }
        return sessionsByWorktree[directory].orEmpty()
    }

    override suspend fun createSession(
        parentId: String?,
        title: String?,
        directory: String?,
    ): Session {
        createdDirectories += directory
        createError?.let { throw it }
        return Session(id = "created", title = "New Session", directory = directory, parentId = parentId, updatedAtEpochMs = 10L)
    }

    override suspend fun getAllSessionStatuses(directory: String?) = statuses

    var statuses: List<dev.opencode.mobile.domain.session.SessionStatus> = emptyList()

    override suspend fun getSession(
        id: String,
        directory: String?,
    ): Session =
        sessionsByWorktree[directory]?.firstOrNull { it.id == id }
            ?: Session(id = id, title = "Session $id", directory = directory)

    override suspend fun updateSession(
        id: String,
        title: String?,
        directory: String?,
    ): Session {
        renamedTitles += id to title
        updateError?.let { throw it }
        return Session(id = id, title = title.orEmpty(), directory = directory, updatedAtEpochMs = 20L)
    }

    override suspend fun archiveSession(
        id: String,
        directory: String?,
    ): Session {
        archivedIds += id
        archiveError?.let { throw it }
        return Session(
            id = id,
            title = "Archived",
            directory = directory,
            updatedAtEpochMs = 20L,
            archivedAtEpochMs = 20L,
        )
    }

    override suspend fun deleteSession(
        id: String,
        directory: String?,
    ): Boolean {
        deletedIds += id
        deleteError?.let { throw it }
        return deleteResult
    }

    override suspend fun listChildSessions(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun listSessionTodos(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun initializeSessionAgents(
        id: String,
        messageId: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ) = error("unused")

    override suspend fun forkSession(
        id: String,
        atMessageId: String?,
        directory: String?,
    ): Session {
        forkedIds += id
        forkError?.let { throw it }
        return Session(id = "forked-$id", title = "Fork", directory = directory, updatedAtEpochMs = 20L)
    }

    override suspend fun abortSession(
        id: String,
        directory: String?,
    ) = true

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
    ) = error("unused")

    override suspend fun summarizeSession(
        id: String,
        providerId: String,
        modelId: String,
        directory: String?,
    ) = error("unused")

    override suspend fun revertSession(
        id: String,
        messageId: String,
        partId: String?,
        directory: String?,
    ) = error("unused")

    override suspend fun unrevertSession(
        id: String,
        directory: String?,
    ) = error("unused")

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        decision: dev.opencode.mobile.domain.session.PermissionDecision,
        directory: String?,
    ) = error("unused")
}

private class FakeMessageRepository(
    private val messagesBySession: Map<String, List<Message>> = emptyMap(),
    private val listError: Throwable? = null,
) : MessageRepository {
    val catchUpChrome = MutableStateFlow(false)

    override fun observeCatchUpChrome(): Flow<Boolean> = catchUpChrome

    override fun observeMessages(
        sessionId: String,
        directory: String?,
    ): Flow<MessageListUpdate> = emptyFlow()

    override fun observeDisplayMessages(
        sessionId: String,
        directory: String?,
    ): Flow<DisplayMessageListUpdate> = emptyFlow()

    override suspend fun refreshMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> = listMessages(sessionId, directory)

    override suspend fun refreshDisplayMessages(
        sessionId: String,
        directory: String?,
    ): List<DisplayMessage> = listMessages(sessionId, directory).map(::DisplayMessage)

    override suspend fun prepareOptimisticUserMessage(
        id: String,
        text: String,
    ): DisplayMessage = DisplayMessage(Message(id = id, role = MessageRole.User, text = text))

    override fun clearMessageCache() = Unit

    override suspend fun listMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> {
        listError?.let { throw it }
        return messagesBySession[sessionId].orEmpty()
    }

    override suspend fun getMessage(
        sessionId: String,
        messageId: String,
        directory: String?,
    ): Message = error("unused")

    override suspend fun sendMessage(
        sessionId: String,
        text: String,
        directory: String?,
    ): Message = error("unused")

    override suspend fun sendPromptAsync(
        sessionId: String,
        options: PromptSendOptions,
        directory: String?,
    ) = Unit
}

private class FakeConnectionProfileStore(
    initial: List<String> = emptyList(),
) : ConnectionProfileStore {
    private val data = mutableMapOf<String, List<String>>()

    init {
        if (initial.isNotEmpty()) {
            data[serverUrlKey()] = initial
        }
    }

    fun added(serverBaseUrl: String): List<String> = data[serverBaseUrl].orEmpty()

    override suspend fun activeProfile(): ConnectionProfile? =
        ConnectionProfile(baseUrl = serverUrlKey(), username = "opencode", password = "")

    override suspend fun saveProfile(profile: ConnectionProfile) = Unit

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = data[serverBaseUrl].orEmpty()

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {
        val current = data[serverBaseUrl].orEmpty()
        if (worktree !in current) {
            data[serverBaseUrl] = current + worktree
        }
    }

    override suspend fun removeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {
        data[serverBaseUrl] = data[serverBaseUrl].orEmpty().filterNot { it == worktree }
    }

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) {
        data[serverBaseUrl] = worktrees
    }

    override suspend fun clearAll() {
        data.clear()
    }

    private fun serverUrlKey(): String = "http://127.0.0.1:4096/"
}

private class FakeSecureCredentialStore : SecureCredentialStore {
    private val passwords = mutableMapOf<String, String>()

    override fun readPassword(serverBaseUrl: String): String? = passwords[serverBaseUrl]

    override fun writePassword(
        serverBaseUrl: String,
        password: String,
    ) {
        passwords[serverBaseUrl] = password
    }

    override fun deletePassword(serverBaseUrl: String) {
        passwords.remove(serverBaseUrl)
    }

    override fun clearAll() {
        passwords.clear()
    }
}

private class FakeSessionModelSelectionStore : SessionModelSelectionStore {
    private val sessions = mutableMapOf<String, SessionModelSelection>()
    private val preferences = mutableMapOf<String, SessionModelSelection>()

    suspend fun load(
        serverBaseUrl: String,
        worktree: String,
        sessionId: String,
    ): SessionModelSelection? = null

    suspend fun save(
        serverBaseUrl: String,
        worktree: String,
        sessionId: String,
        selection: SessionModelSelection,
    ) = Unit

    override suspend fun clearAll() = Unit

    override suspend fun loadSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ): SessionModelSelection? = sessions["${connectionIdentity.serverBaseUrl}|${connectionIdentity.username}|$worktree|$sessionId"]

    override suspend fun saveSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
        selection: SessionModelSelection,
    ) {
        sessions["${connectionIdentity.serverBaseUrl}|${connectionIdentity.username}|$worktree|$sessionId"] = selection
    }

    override suspend fun deleteSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ) {
        sessions.remove("${connectionIdentity.serverBaseUrl}|${connectionIdentity.username}|$worktree|$sessionId")
    }

    override suspend fun deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionId: String,
    ) {
        val prefix = "${connectionIdentity.serverBaseUrl}|${connectionIdentity.username}|"
        sessions.keys.removeAll { it.startsWith(prefix) && it.endsWith("|$sessionId") }
    }

    override suspend fun loadNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
    ): SessionModelSelection? = preferences["${connectionIdentity.serverBaseUrl}|${connectionIdentity.username}|$worktree"]

    override suspend fun saveNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        selection: SessionModelSelection,
    ) {
        preferences["${connectionIdentity.serverBaseUrl}|${connectionIdentity.username}|$worktree"] = selection
    }
}

private class FakeHomeStatusLiveStore(
    private val snapshots: MutableStateFlow<SessionActivitySnapshot>,
) : SessionStatusLiveStore {
    override fun observeActivity(directory: String): Flow<SessionActivitySnapshot> = snapshots

    override fun clearCache() = Unit
}
