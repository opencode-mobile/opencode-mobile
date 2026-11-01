package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.SecureCredentialStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.connection.testConnectionSessionCoordinator
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.event.SilentWorldSignals
import dev.opencode.mobile.domain.filesystem.DirectoryListing
import dev.opencode.mobile.domain.filesystem.DirectoryListingPolicy
import dev.opencode.mobile.domain.filesystem.DirectoryNode
import dev.opencode.mobile.domain.filesystem.DirectoryNotFoundException
import dev.opencode.mobile.domain.filesystem.FilesystemRepository
import dev.opencode.mobile.domain.filesystem.ServerPaths
import dev.opencode.mobile.domain.live.DefaultSessionLiveStore
import dev.opencode.mobile.domain.live.DefaultSessionReadyStore
import dev.opencode.mobile.domain.live.DefaultSessionStatusLiveStore
import dev.opencode.mobile.domain.live.InMemorySessionReadyPersistence
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.domain.live.SessionReadyClock
import dev.opencode.mobile.domain.live.SessionReadyStore
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.DisplayMessageListUpdate
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.PromptSendOptions
import dev.opencode.mobile.domain.message.ShellCommandOptions
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.ModelSelectionCatalog
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
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
import dev.opencode.mobile.domain.session.SessionIdleOutcome
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.domain.session.VcsInfo
import dev.opencode.mobile.domain.sessioncache.NoOpSessionCache
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.sync.testSyncCoordinator
import dev.opencode.mobile.domain.worktree.CreateWorktreeRequest
import dev.opencode.mobile.domain.worktree.CreatedWorktree
import dev.opencode.mobile.domain.worktree.UnavailableWorktreeRepository
import dev.opencode.mobile.domain.worktree.WorktreeCreateError
import dev.opencode.mobile.domain.worktree.WorktreeInventory
import dev.opencode.mobile.domain.worktree.WorktreeLoad
import dev.opencode.mobile.domain.worktree.WorktreeRepository
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import dev.opencode.mobile.ui.components.SessionListScrollPin
import dev.opencode.mobile.ui.components.SessionListStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
            assertEquals(
                7,
                loaded.sections
                    .single()
                    .allSessions.size,
            )
        }

    @Test
    fun reorderProjects_reordersSectionsAndPersistsOrder() =
        runTest(testDispatcher) {
            val store = FakeConnectionProfileStore(initial = listOf("/one", "/two", "/three"))
            val viewModel =
                createViewModel(
                    connectionProfileStore = store,
                    sessionsByWorktree = mapOf("/one" to emptyList(), "/two" to emptyList(), "/three" to emptyList()),
                )
            advanceUntilIdle()

            viewModel.reorderProjects(listOf("/three", "/one", "/two"))
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(listOf("/three", "/one", "/two"), loaded.sections.map { it.worktree })

            advanceUntilIdle()
            assertEquals(listOf("/three", "/one", "/two"), store.added(serverUrl))
        }

    @Test
    fun reorderProjects_ignoresOrdersThatChangeTheProjectSet() =
        runTest(testDispatcher) {
            val store = FakeConnectionProfileStore(initial = listOf("/one", "/two"))
            val viewModel =
                createViewModel(
                    connectionProfileStore = store,
                    sessionsByWorktree = mapOf("/one" to emptyList(), "/two" to emptyList()),
                )
            advanceUntilIdle()

            viewModel.reorderProjects(listOf("/two"))
            viewModel.reorderProjects(listOf("/two", "/one", "/extra"))
            viewModel.reorderProjects(listOf("/one", "/two"))
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(listOf("/one", "/two"), loaded.sections.map { it.worktree })
            assertEquals(listOf("/one", "/two"), store.added(serverUrl))
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
    fun showAddProjectSheet_opensAtHomeWithSuggestionsAndRoots() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "/Users/me", directory = "/Users/me/work", worktree = "/"),
                    listings =
                        mapOf(
                            "/Users/me" to
                                listOf(
                                    DirectoryNode(name = "code", absolutePath = "/Users/me/code", isDirectory = true),
                                    DirectoryNode(name = ".cache", absolutePath = "/Users/me/.cache", isDirectory = true),
                                    DirectoryNode(name = "readme", absolutePath = "/Users/me/readme", isDirectory = false),
                                ),
                            "/Volumes" to
                                listOf(
                                    DirectoryNode("Work SSD", "/Volumes/Work SSD", true),
                                    DirectoryNode(".timemachine", "/Volumes/.timemachine", true),
                                ),
                        ),
                )
            val projectRepository =
                FakeProjectRepository(
                    bootWorktree = "/one",
                    projects =
                        listOf(
                            Project(id = "global", worktree = "/", displayName = "/", activityAtEpochMs = 9, isRepository = true),
                            Project(id = "old", worktree = "/Users/me/app", displayName = "app", activityAtEpochMs = 1, isRepository = true),
                            Project(id = "web", worktree = "/Users/me/web", displayName = "web", activityAtEpochMs = 5, isRepository = true),
                            Project(id = "app", worktree = "/Users/me/app", displayName = "app", activityAtEpochMs = 4, isRepository = true),
                            Project(id = "one", worktree = "/one", displayName = "one", activityAtEpochMs = 8, isRepository = true),
                        ),
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

            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            val sheet = requireNotNull(loaded.addProjectSheet)
            assertTrue(loaded.showAddProjectSheet)
            assertEquals("/Users/me/", sheet.fieldText)
            assertEquals("/Users/me", sheet.directory)
            assertEquals("", sheet.filter)
            assertEquals(listOf("code"), sheet.entries.map { it.name })
            assertNull(sheet.truncationHint)
            assertTrue(sheet.showSuggestions)
            assertEquals(listOf("/Users/me/web", "/Users/me/app"), sheet.suggestions.map { it.path })
            assertEquals("In me", sheet.folderSectionTitle)
            assertEquals(listOf("Home", "/", "Work SSD"), sheet.roots.map { it.label })
            assertEquals(listOf("/Users/me", "/", "/Volumes/Work SSD"), sheet.roots.map { it.path })
            assertEquals(listOf(true, false, false), sheet.roots.map { it.selected })
            assertEquals("/Users", sheet.upTarget)
            assertEquals("/Users/me", sheet.addTarget)
            assertEquals(AddProjectSheetUi.ADD_HOME_FOLDER, sheet.addTitle)
            assertTrue(sheet.addEnabled)
            assertFalse(sheet.isBrowsing)
        }

    @Test
    fun browseAddProjectFolders_opensTheBrowserAndBackReturnsToSuggestions() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    filesystemRepository =
                        FakeFilesystemRepository(
                            paths = ServerPaths(home = "/home", directory = "/home", worktree = "/"),
                            listings = mapOf("/home" to listOf(DirectoryNode("code", "/home/code", true))),
                        ),
                )
            advanceUntilIdle()
            viewModel.showAddProjectSheet()
            advanceUntilIdle()

            viewModel.browseAddProjectFolders()
            val browsing = requireNotNull(currentSheet(viewModel))
            assertTrue(browsing.isBrowsing)
            assertTrue(addProjectSheetVisible(viewModel))
            assertEquals("/home/", browsing.fieldText)

            viewModel.closeAddProjectBrowser()
            val suggestions = requireNotNull(currentSheet(viewModel))
            assertFalse(suggestions.isBrowsing)
            assertTrue(addProjectSheetVisible(viewModel))
            assertEquals("/home/", suggestions.fieldText)
        }

    @Test
    fun closeAddProjectBrowser_afterEnteringAFolder_restoresHomeSuggestions() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "/Users/me", directory = "/Users/me", worktree = "/"),
                    listings =
                        mapOf(
                            "/Users/me" to listOf(DirectoryNode("code", "/Users/me/code", true)),
                            "/Users/me/code" to listOf(DirectoryNode("app", "/Users/me/code/app", true)),
                        ),
                )
            val viewModel =
                openSheet(
                    filesystem = filesystem,
                    projectRepository =
                        FakeProjectRepository(
                            bootWorktree = "/one",
                            projects =
                                listOf(
                                    Project(
                                        id = "web",
                                        worktree = "/Users/me/web",
                                        displayName = "web",
                                        activityAtEpochMs = 5,
                                        isRepository = true,
                                    ),
                                ),
                        ),
                )
            assertTrue(currentSheet(viewModel)?.showSuggestions == true)

            viewModel.browseAddProjectFolders()
            viewModel.openAddProjectFolder("/Users/me/code")
            advanceUntilIdle()
            assertEquals("/Users/me/code", currentSheet(viewModel)?.directory)
            assertFalse(currentSheet(viewModel)?.showSuggestions == true)

            viewModel.closeAddProjectBrowser()
            val sheet = requireNotNull(currentSheet(viewModel))
            assertFalse(sheet.isBrowsing)
            assertEquals("/Users/me/", sheet.fieldText)
            assertEquals("/Users/me", sheet.directory)
            assertTrue(sheet.showSuggestions)
            assertEquals(listOf("/Users/me/web"), sheet.suggestions.map { it.path })
        }

    @Test
    fun showAddProjectSheet_showsAtMostFiveSuggestions() =
        runTest(testDispatcher) {
            val projectRepository =
                FakeProjectRepository(
                    bootWorktree = "/one",
                    projects =
                        (1L..18L).map {
                            Project(id = "p$it", worktree = "/srv/p$it", displayName = "p$it", activityAtEpochMs = it, isRepository = true)
                        },
                )
            val viewModel = openSheet(projectRepository = projectRepository)

            val suggestions = requireNotNull(currentSheet(viewModel)).suggestions
            assertEquals(listOf("p18", "p17", "p16", "p15", "p14"), suggestions.map { it.name })
        }

    @Test
    fun posixHome_leadingDoubleSlashListsRootFolder() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "/Users/me", directory = "/Users/me", worktree = "/"),
                    listings = mapOf("/Volumes" to listOf(DirectoryNode("OpenChamber", "/Volumes/OpenChamber", true))),
                )
            val viewModel = openSheet(filesystem = filesystem)

            viewModel.updateAddProjectField("//Volumes/O")
            advanceUntilIdle()

            val sheet = requireNotNull(currentSheet(viewModel))
            assertEquals("/Volumes", sheet.directory)
            assertEquals(listOf("OpenChamber"), sheet.entries.map { it.name })
        }

    @Test
    fun updateAddProjectField_filtersCachedListingWithoutRefetch() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "/Users/me", directory = "/Users/me", worktree = "/"),
                    listings =
                        mapOf(
                            "/Users/me" to
                                listOf(
                                    DirectoryNode("code", "/Users/me/code", true),
                                    DirectoryNode("Courses", "/Users/me/Courses", true),
                                    DirectoryNode("docs", "/Users/me/docs", true),
                                ),
                        ),
                )
            val viewModel = openSheet(filesystem = filesystem)

            viewModel.updateAddProjectField("/Users/me/co")
            advanceUntilIdle()
            var sheet = requireNotNull(currentSheet(viewModel))
            assertEquals("/Users/me", sheet.directory)
            assertEquals("co", sheet.filter)
            assertEquals(listOf("code", "Courses"), sheet.entries.map { it.name })
            assertFalse(sheet.showSuggestions)
            assertEquals("/Users/me", sheet.upTarget)
            assertEquals(AddProjectSheetUi.PICK_A_FOLDER, sheet.addTitle)
            assertFalse(sheet.addEnabled)
            assertTrue(sheet.roots.none { it.selected })

            viewModel.updateAddProjectField("/Users/me/x")
            advanceUntilIdle()
            sheet = requireNotNull(currentSheet(viewModel))
            assertEquals(emptyList<AddProjectFolderUi>(), sheet.entries)
            assertEquals("Nothing in me starts with \u201Cx\u201D", sheet.emptyMessage)
            assertEquals(1, filesystem.listCalls.count { it == "/Users/me" })
        }

    @Test
    fun updateAddProjectField_filtersBeforeCappingLongListing() =
        runTest(testDispatcher) {
            val folders = (0 until 250).map { DirectoryNode("f%03d".format(it), "/Users/me/f%03d".format(it), true) }
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "/Users/me", directory = "/Users/me", worktree = "/"),
                    listings =
                        mapOf(
                            "/Users/me" to
                                folders.reversed() +
                                listOf(
                                    DirectoryNode(".config", "/Users/me/.config", true),
                                    DirectoryNode(".cache", "/Users/me/.cache", true),
                                ),
                        ),
                )
            val viewModel = openSheet(filesystem = filesystem)

            var sheet = requireNotNull(currentSheet(viewModel))
            assertEquals(DirectoryListingPolicy.MAX_VISIBLE_DIRECTORIES, sheet.entries.size)
            assertEquals("f000", sheet.entries.first().name)
            assertEquals("f199", sheet.entries.last().name)
            assertEquals(AddProjectSheetUi.TRUNCATION_HINT, sheet.truncationHint)
            assertEquals(
                "Showing the first 200 folders. Type more of the name to narrow the list.",
                sheet.truncationHint,
            )
            assertNull(sheet.emptyMessage)

            viewModel.updateAddProjectField("/Users/me/f24")
            advanceUntilIdle()
            sheet = requireNotNull(currentSheet(viewModel))
            assertEquals((240..249).map { "f$it" }, sheet.entries.map { it.name })
            assertNull(sheet.truncationHint)
            assertNull(sheet.emptyMessage)
            assertFalse(sheet.addEnabled)

            viewModel.updateAddProjectField("/Users/me/f249")
            advanceUntilIdle()
            sheet = requireNotNull(currentSheet(viewModel))
            assertEquals(listOf("f249"), sheet.entries.map { it.name })
            assertEquals("/Users/me/f249", sheet.addTarget)
            assertEquals(AddProjectSheetUi.ADD_THIS_FOLDER, sheet.addTitle)
            assertTrue(sheet.addEnabled)

            viewModel.updateAddProjectField("/Users/me/.")
            advanceUntilIdle()
            sheet = requireNotNull(currentSheet(viewModel))
            assertEquals(listOf(".cache", ".config"), sheet.entries.map { it.name })
            assertNull(sheet.addTarget)

            viewModel.updateAddProjectField("/Users/me/.config")
            advanceUntilIdle()
            sheet = requireNotNull(currentSheet(viewModel))
            assertEquals(listOf(".config"), sheet.entries.map { it.name })
            assertEquals("/Users/me/.config", sheet.addTarget)
            assertEquals(AddProjectSheetUi.ADD_THIS_FOLDER, sheet.addTitle)
            assertTrue(sheet.addEnabled)

            viewModel.updateAddProjectField("/Users/me/f3")
            advanceUntilIdle()
            sheet = requireNotNull(currentSheet(viewModel))
            assertEquals("Nothing in me starts with \u201Cf3\u201D", sheet.emptyMessage)
            assertEquals(1, filesystem.listCalls.count { it == "/Users/me" })
        }

    @Test
    fun updateAddProjectField_debouncesListingOfNewFolder() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "/Users/me", directory = "/Users/me", worktree = "/"),
                    listings =
                        mapOf(
                            "/Users/me" to listOf(DirectoryNode("code", "/Users/me/code", true)),
                            "/Users/me/code" to listOf(DirectoryNode("app", "/Users/me/code/app", true)),
                        ),
                )
            val viewModel = openSheet(filesystem = filesystem)

            viewModel.updateAddProjectField("/Users/me/c/")
            advanceTimeBy(100)
            runCurrent()
            viewModel.updateAddProjectField("/Users/me/code/")
            advanceTimeBy(200)
            runCurrent()
            assertFalse(filesystem.listCalls.contains("/Users/me/code"))
            assertTrue(currentSheet(viewModel)?.isLoading == true)

            advanceTimeBy(100)
            runCurrent()
            advanceUntilIdle()
            val sheet = requireNotNull(currentSheet(viewModel))
            assertEquals(listOf("app"), sheet.entries.map { it.name })
            assertFalse(sheet.isLoading)
            assertFalse(filesystem.listCalls.contains("/Users/me/c"))
            assertEquals(1, filesystem.listCalls.count { it == "/Users/me/code" })
        }

    @Test
    fun openAddProjectFolder_listsTappedFolder() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "/Users/me", directory = "/Users/me", worktree = "/"),
                    listings =
                        mapOf(
                            "/Users/me" to listOf(DirectoryNode("code", "/Users/me/code", true)),
                            "/Users/me/code" to listOf(DirectoryNode("app", "/Users/me/code/app", true)),
                        ),
                )
            val viewModel = openSheet(filesystem = filesystem)

            viewModel.openAddProjectFolder("/Users/me/code")
            runCurrent()

            val sheet = requireNotNull(currentSheet(viewModel))
            assertEquals("/Users/me/code/", sheet.fieldText)
            assertEquals("/Users/me/code", sheet.directory)
            assertEquals(listOf("app"), sheet.entries.map { it.name })
            assertEquals(AddProjectSheetUi.ADD_THIS_FOLDER, sheet.addTitle)
            assertNull(sheet.folderSectionTitle)
        }

    @Test
    fun addProjectUp_fromHomeStopsAtRoot() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "/Users/me", directory = "/Users/me", worktree = "/"),
                    listings =
                        mapOf(
                            "/Users" to listOf(DirectoryNode("me", "/Users/me", true)),
                            "/" to listOf(DirectoryNode("Users", "/Users", true)),
                        ),
                )
            val viewModel = openSheet(filesystem = filesystem)

            viewModel.addProjectUp()
            advanceUntilIdle()
            assertEquals("/Users/", currentSheet(viewModel)?.fieldText)
            assertEquals(listOf("me"), currentSheet(viewModel)?.entries?.map { it.name })

            viewModel.addProjectUp()
            advanceUntilIdle()
            val sheet = requireNotNull(currentSheet(viewModel))
            assertEquals("/", sheet.fieldText)
            assertEquals(listOf("Users"), sheet.entries.map { it.name })
            assertNull(sheet.upTarget)
            assertTrue(sheet.roots.single { it.path == "/" }.selected)

            viewModel.addProjectUp()
            advanceUntilIdle()
            assertEquals("/", currentSheet(viewModel)?.fieldText)
        }

    @Test
    fun addProjectUp_withFilterGoesToListedFolder() =
        runTest(testDispatcher) {
            val viewModel = openSheet()

            viewModel.updateAddProjectField("/home/co")
            advanceUntilIdle()
            viewModel.addProjectUp()
            advanceUntilIdle()

            assertEquals("/home/", currentSheet(viewModel)?.fieldText)
        }

    @Test
    fun windowsHome_goesUpToDriveRootAndStops() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "C:\\Users\\me", directory = "C:\\Users\\me", worktree = "/"),
                    listings = mapOf("C:\\" to listOf(DirectoryNode("Users", "C:\\Users", true))),
                )
            val viewModel = openSheet(filesystem = filesystem)
            assertEquals("C:\\Users\\me\\", currentSheet(viewModel)?.fieldText)
            assertFalse(filesystem.listCalls.contains("/Volumes"))

            viewModel.addProjectUp()
            advanceUntilIdle()
            viewModel.addProjectUp()
            advanceUntilIdle()

            val sheet = requireNotNull(currentSheet(viewModel))
            assertEquals("C:\\", sheet.fieldText)
            assertEquals("C:\\", sheet.directory)
            assertEquals(listOf("Users"), sheet.entries.map { it.name })
            assertNull(sheet.upTarget)
        }

    @Test
    fun windowsRoots_comeFromHomeServerProjectsAndAddedProjects() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "C:\\Users\\me", directory = "C:\\Users\\me", worktree = "/"),
                )
            val projectRepository =
                FakeProjectRepository(
                    bootWorktree = "/one",
                    projects =
                        listOf(
                            Project(id = "d", worktree = "D:\\work\\app", displayName = "app"),
                            Project(id = "c", worktree = "c:\\src\\tool", displayName = "tool"),
                        ),
                )
            val viewModel =
                openSheet(
                    filesystem = filesystem,
                    projectRepository = projectRepository,
                    added = listOf("\\\\nas\\share\\repo"),
                )

            val roots = requireNotNull(currentSheet(viewModel)).roots
            assertEquals(listOf("Home", "C:", "D:", "\\\\nas\\share"), roots.map { it.label })
            assertEquals(listOf("C:\\Users\\me", "C:\\", "D:\\", "\\\\nas\\share"), roots.map { it.path })
        }

    @Test
    fun typingDriveLetter_listsDriveAndAddsChip() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "C:\\Users\\me", directory = "C:\\Users\\me", worktree = "/"),
                    listings = mapOf("E:\\" to listOf(DirectoryNode("code", "E:\\code", true))),
                )
            val viewModel = openSheet(filesystem = filesystem)

            viewModel.updateAddProjectField("e:")
            advanceUntilIdle()

            val sheet = requireNotNull(currentSheet(viewModel))
            assertEquals("E:\\", sheet.directory)
            assertEquals(listOf("code"), sheet.entries.map { it.name })
            assertTrue(sheet.roots.single { it.label == "E:" }.selected)

            viewModel.selectAddProjectRoot("C:\\Users\\me")
            advanceUntilIdle()
            assertEquals("C:\\Users\\me\\", currentSheet(viewModel)?.fieldText)
            assertTrue(currentSheet(viewModel)?.roots?.any { it.label == "E:" } == true)
        }

    @Test
    fun typingMissingDrive_showsListingErrorWithoutAddingChip() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "C:\\Users\\me", directory = "C:\\Users\\me", worktree = "/"),
                    errors = mutableMapOf("X:\\" to IllegalStateException("No such drive")),
                )
            val viewModel = openSheet(filesystem = filesystem)

            viewModel.updateAddProjectField("X:")
            advanceUntilIdle()

            var sheet = requireNotNull(currentSheet(viewModel))
            assertEquals("X:\\", sheet.directory)
            assertEquals(AddProjectSheetUi.LIST_ERROR_MESSAGE, sheet.listError)
            assertEquals(listOf("Home", "C:"), sheet.roots.map { it.label })

            viewModel.selectAddProjectRoot("C:\\Users\\me")
            advanceUntilIdle()
            sheet = requireNotNull(currentSheet(viewModel))
            assertEquals(listOf("Home", "C:"), sheet.roots.map { it.label })
        }

    @Test
    fun addTarget_matchesExactChildAndBlocksAlreadyAdded() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "/Users/me", directory = "/Users/me", worktree = "/"),
                    listings =
                        mapOf(
                            "/Users/me" to
                                listOf(
                                    DirectoryNode("app", "/Users/me/app", true),
                                    DirectoryNode("code", "/Users/me/code", true),
                                ),
                        ),
                )
            val viewModel = openSheet(filesystem = filesystem, added = listOf("/one", "/Users/me/app"))

            var sheet = requireNotNull(currentSheet(viewModel))
            assertEquals(listOf(true, false), sheet.entries.map { it.isOnHome })

            viewModel.updateAddProjectField("/Users/me/code")
            advanceUntilIdle()
            sheet = requireNotNull(currentSheet(viewModel))
            assertEquals("/Users/me/code", sheet.addTarget)
            assertEquals(AddProjectSheetUi.ADD_THIS_FOLDER, sheet.addTitle)
            assertTrue(sheet.addEnabled)

            // POSIX names are case-sensitive.
            viewModel.updateAddProjectField("/Users/me/Code")
            advanceUntilIdle()
            sheet = requireNotNull(currentSheet(viewModel))
            assertEquals(listOf("code"), sheet.entries.map { it.name })
            assertNull(sheet.addTarget)
            assertEquals(AddProjectSheetUi.PICK_A_FOLDER, sheet.addTitle)

            viewModel.updateAddProjectField("/Users/me/app")
            advanceUntilIdle()
            sheet = requireNotNull(currentSheet(viewModel))
            assertEquals(AddProjectSheetUi.ALREADY_ON_HOME, sheet.addTitle)
            assertFalse(sheet.addEnabled)
        }

    @Test
    fun addTarget_windowsNamesMatchIgnoringCase() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "C:\\Users\\me", directory = "C:\\Users\\me", worktree = "/"),
                    listings = mapOf("C:\\Users\\me" to listOf(DirectoryNode("Code", "C:\\Users\\me\\Code", true))),
                )
            val viewModel = openSheet(filesystem = filesystem)

            viewModel.updateAddProjectField("C:\\Users\\me\\code")
            advanceUntilIdle()

            assertEquals("C:\\Users\\me\\Code", currentSheet(viewModel)?.addTarget)
            assertEquals(AddProjectSheetUi.ADD_THIS_FOLDER, currentSheet(viewModel)?.addTitle)
        }

    @Test
    fun submitAddProjectField_opensExactChildOrOnlyMatchAndNeverAdds() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "/Users/me", directory = "/Users/me", worktree = "/"),
                    listings =
                        mapOf(
                            "/Users/me" to
                                listOf(
                                    DirectoryNode("code", "/Users/me/code", true),
                                    DirectoryNode("code-old", "/Users/me/code-old", true),
                                    DirectoryNode("docs", "/Users/me/docs", true),
                                ),
                            "/Users/me/code" to listOf(DirectoryNode("app", "/Users/me/code/app", true)),
                        ),
                )
            val store = FakeConnectionProfileStore(initial = listOf("/one"))
            val projectRepository = FakeProjectRepository(bootWorktree = "/one")
            val viewModel = openSheet(filesystem = filesystem, projectRepository = projectRepository, store = store)
            viewModel.browseAddProjectFolders()

            // Two matches, neither exact: Return stays.
            viewModel.updateAddProjectField("/Users/me/co")
            advanceUntilIdle()
            assertNull(currentSheet(viewModel)?.goTarget)
            viewModel.submitAddProjectField()
            advanceUntilIdle()
            assertEquals("/Users/me/co", currentSheet(viewModel)?.fieldText)

            // The exact child wins over other prefix matches.
            viewModel.updateAddProjectField("/Users/me/code")
            advanceUntilIdle()
            assertEquals("/Users/me/code", currentSheet(viewModel)?.goTarget)
            viewModel.submitAddProjectField()
            runCurrent()
            var sheet = requireNotNull(currentSheet(viewModel))
            assertEquals("/Users/me/code/", sheet.fieldText)
            assertEquals(listOf("app"), sheet.entries.map { it.name })
            assertNull(sheet.goTarget)

            // The only match opens.
            viewModel.updateAddProjectField("/Users/me/d")
            advanceUntilIdle()
            viewModel.submitAddProjectField()
            advanceUntilIdle()
            sheet = requireNotNull(currentSheet(viewModel))
            assertEquals("/Users/me/docs/", sheet.fieldText)
            assertEquals(AddProjectSheetUi.NO_SUBFOLDERS, sheet.emptyMessage)

            assertTrue(sheet.isBrowsing)
            assertEquals(listOf("/one"), store.added(serverUrl))
            assertTrue(projectRepository.validatedDirectories.none { it?.startsWith("/Users") == true })
        }

    @Test
    fun missingFolder_showsPathNotFoundWhileOtherFailuresShowRetry() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    listings = mapOf("/home" to listOf(DirectoryNode("code", "/home/code", true))),
                    errors =
                        mutableMapOf(
                            "/home/gone" to DirectoryNotFoundException("/home/gone"),
                            "/home/locked" to IllegalStateException("EACCES"),
                        ),
                )
            val viewModel = openSheet(filesystem = filesystem)
            viewModel.browseAddProjectFolders()

            viewModel.updateAddProjectField("/home/gone/")
            advanceUntilIdle()
            var sheet = requireNotNull(currentSheet(viewModel))
            assertTrue(sheet.pathNotFound)
            assertNull(sheet.listError)
            assertFalse(sheet.isLoading)
            assertEquals(emptyList<AddProjectFolderUi>(), sheet.entries)
            assertEquals(AddProjectSheetUi.PATH_NOT_FOUND, sheet.emptyMessage)
            assertEquals(AddProjectSheetUi.PATH_NOT_FOUND, sheet.addTitle)
            assertFalse(sheet.addEnabled)
            assertNull(sheet.goTarget)
            assertEquals(AddProjectBarTargetUi(name = "gone", path = "/home/gone", isGitRepository = false), sheet.barTarget)

            viewModel.updateAddProjectField("/home/locked/")
            advanceUntilIdle()
            sheet = requireNotNull(currentSheet(viewModel))
            assertFalse(sheet.pathNotFound)
            assertEquals(AddProjectSheetUi.LIST_ERROR_MESSAGE, sheet.listError)
            assertEquals(AddProjectSheetUi.ADD_THIS_FOLDER, sheet.addTitle)
            assertFalse(sheet.addEnabled)
        }

    @Test
    fun rowSummaries_showCountsAndGitAndFillTheListingCache() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    listings =
                        mapOf(
                            "/home" to
                                listOf(
                                    DirectoryNode("app", "/home/app", true),
                                    DirectoryNode("code", "/home/code", true),
                                    DirectoryNode("notes", "/home/notes", true),
                                ),
                            "/home/app" to
                                listOf(
                                    DirectoryNode("src", "/home/app/src", true),
                                    DirectoryNode(".git", "/home/app/.git", true),
                                    DirectoryNode("README.md", "/home/app/README.md", false),
                                ),
                            "/home/notes" to
                                listOf(
                                    DirectoryNode("a", "/home/notes/a", true),
                                    DirectoryNode("b", "/home/notes/b", true),
                                ),
                        ),
                )
            val projectRepository =
                FakeProjectRepository(
                    bootWorktree = "/one",
                    projects = listOf(Project(id = "n", worktree = "/home/notes", displayName = "notes", isRepository = true)),
                )
            val viewModel =
                openSheet(filesystem = filesystem, projectRepository = projectRepository, added = listOf("/one", "/home/code"))
            viewModel.browseAddProjectFolders()

            // Before any summary: a server repository shows git right away.
            var rows = requireNotNull(currentSheet(viewModel)).entries
            assertEquals(listOf(null, "On Home", null), rows.map { it.subtitle })
            assertEquals(listOf(false, false, true), rows.map { it.isGitRepository })

            rows.forEach { viewModel.addProjectFolderRowAppeared(it.path) }
            advanceUntilIdle()
            rows = requireNotNull(currentSheet(viewModel)).entries
            assertEquals(listOf(1, 0, 2), rows.map { it.folderCount })
            assertEquals(listOf("1 folder", "On Home · Empty", "2 folders"), rows.map { it.subtitle })
            assertEquals(listOf(true, false, true), rows.map { it.isGitRepository })

            // Entering a summarised folder reuses its listing.
            viewModel.openAddProjectFolder("/home/app")
            runCurrent()
            val sheet = requireNotNull(currentSheet(viewModel))
            assertFalse(sheet.isLoading)
            assertEquals(listOf("src"), sheet.entries.map { it.name })
            assertEquals(AddProjectBarTargetUi(name = "app", path = "/home/app", isGitRepository = true), sheet.barTarget)
            assertEquals(1, filesystem.listCalls.count { it == "/home/app" })
        }

    @Test
    fun rowSummaries_runFourAtATimeInOrderAndDisappearDequeues() =
        runTest(testDispatcher) {
            val names = (1..6).map { "f$it" }
            val filesystem =
                FakeFilesystemRepository(
                    listings = mapOf("/home" to names.map { DirectoryNode(it, "/home/$it", true) }),
                )
            names.forEach { filesystem.gates["/home/$it"] = CompletableDeferred() }
            val viewModel = openSheet(filesystem = filesystem)
            viewModel.browseAddProjectFolders()

            fun summaryCalls() = filesystem.listCalls.filter { it.startsWith("/home/") }

            names.forEach { viewModel.addProjectFolderRowAppeared("/home/$it") }
            names.forEach { viewModel.addProjectFolderRowAppeared("/home/$it") }
            runCurrent()
            assertEquals(listOf("/home/f1", "/home/f2", "/home/f3", "/home/f4"), summaryCalls())
            assertEquals(AddProjectSheetModel.FOLDER_SUMMARY_CONCURRENCY, filesystem.inFlight)

            viewModel.addProjectFolderRowDisappeared("/home/f5")
            filesystem.gates.getValue("/home/f1").complete(Unit)
            runCurrent()
            assertEquals(listOf("/home/f1", "/home/f2", "/home/f3", "/home/f4", "/home/f6"), summaryCalls())

            filesystem.gates.values.forEach { it.complete(Unit) }
            advanceUntilIdle()
            assertEquals(AddProjectSheetModel.FOLDER_SUMMARY_CONCURRENCY, filesystem.maxInFlight)
            val rows = requireNotNull(currentSheet(viewModel)).entries
            assertEquals(listOf("Empty", "Empty", "Empty", "Empty", null, "Empty"), rows.map { it.subtitle })

            // Scrolled back into view, the dropped row loads.
            viewModel.addProjectFolderRowAppeared("/home/f5")
            advanceUntilIdle()
            assertEquals("Empty", currentSheet(viewModel)?.entries?.single { it.name == "f5" }?.subtitle)
            assertEquals(1, filesystem.listCalls.count { it == "/home/f5" })
        }

    @Test
    fun rowSummaryFailures_areNotRetriedOrShownAsErrors() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    listings =
                        mapOf(
                            "/home" to
                                listOf(
                                    DirectoryNode("gone", "/home/gone", true),
                                    DirectoryNode("locked", "/home/locked", true),
                                ),
                        ),
                    errors =
                        mutableMapOf(
                            "/home/gone" to DirectoryNotFoundException("/home/gone"),
                            "/home/locked" to IllegalStateException("EACCES"),
                        ),
                )
            val viewModel = openSheet(filesystem = filesystem)
            viewModel.browseAddProjectFolders()

            repeat(2) {
                listOf("/home/gone", "/home/locked").forEach { path ->
                    viewModel.addProjectFolderRowAppeared(path)
                    advanceUntilIdle()
                    viewModel.addProjectFolderRowDisappeared(path)
                }
            }
            var sheet = requireNotNull(currentSheet(viewModel))
            assertNull(sheet.listError)
            assertFalse(sheet.pathNotFound)
            assertEquals(listOf(null, null), sheet.entries.map { it.folderCount })
            assertEquals(listOf(null, null), sheet.entries.map { it.subtitle })
            assertEquals(1, filesystem.listCalls.count { it == "/home/gone" })
            assertEquals(1, filesystem.listCalls.count { it == "/home/locked" })

            // Opening the folder still lists it, and shows its own error.
            viewModel.openAddProjectFolder("/home/locked")
            advanceUntilIdle()
            sheet = requireNotNull(currentSheet(viewModel))
            assertEquals(AddProjectSheetUi.LIST_ERROR_MESSAGE, sheet.listError)
            assertEquals(2, filesystem.listCalls.count { it == "/home/locked" })
        }

    @Test
    fun folderListing_doesNotWaitForRowSummaries() =
        runTest(testDispatcher) {
            val names = (1..5).map { "f$it" }
            val filesystem =
                FakeFilesystemRepository(
                    listings =
                        mapOf(
                            "/home" to names.map { DirectoryNode(it, "/home/$it", true) },
                            "/home/f5" to listOf(DirectoryNode("inner", "/home/f5/inner", true)),
                        ),
                )
            names.take(4).forEach { filesystem.gates["/home/$it"] = CompletableDeferred() }
            val viewModel = openSheet(filesystem = filesystem)
            viewModel.browseAddProjectFolders()
            names.take(4).forEach { viewModel.addProjectFolderRowAppeared("/home/$it") }
            runCurrent()

            viewModel.openAddProjectFolder("/home/f5")
            runCurrent()

            val sheet = requireNotNull(currentSheet(viewModel))
            assertFalse(sheet.isLoading)
            assertEquals(listOf("inner"), sheet.entries.map { it.name })
            filesystem.gates.values.forEach { it.complete(Unit) }
            advanceUntilIdle()
        }

    @Test
    fun rowSummaries_fromAnEarlierSheetAreDropped() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    listings = mapOf("/home" to listOf(DirectoryNode("code", "/home/code", true))),
                )
            filesystem.gates["/home/code"] = CompletableDeferred()
            val viewModel = openSheet(filesystem = filesystem)
            viewModel.browseAddProjectFolders()
            viewModel.addProjectFolderRowAppeared("/home/code")
            runCurrent()

            viewModel.dismissAddProjectSheet()
            viewModel.showAddProjectSheet()
            advanceUntilIdle()
            filesystem.gates.getValue("/home/code").complete(Unit)
            advanceUntilIdle()

            val row = requireNotNull(currentSheet(viewModel)).entries.single()
            assertNull(row.folderCount)
            assertNull(row.subtitle)
        }

    @Test
    fun listingFailure_showsErrorAndRetryRefetches() =
        runTest(testDispatcher) {
            val filesystem =
                FakeFilesystemRepository(
                    paths = ServerPaths(home = "/home", directory = "/home", worktree = "/"),
                    listings = mapOf("/home/gone" to listOf(DirectoryNode("back", "/home/gone/back", true))),
                    errors = mutableMapOf("/home/gone" to IllegalStateException("No such folder")),
                )
            val viewModel = openSheet(filesystem = filesystem)

            viewModel.updateAddProjectField("/home/gone/")
            advanceUntilIdle()
            var sheet = requireNotNull(currentSheet(viewModel))
            assertEquals(AddProjectSheetUi.LIST_ERROR_MESSAGE, sheet.listError)
            assertFalse(sheet.addEnabled)

            filesystem.errors.clear()
            viewModel.retryAddProjectListing()
            advanceUntilIdle()
            sheet = requireNotNull(currentSheet(viewModel))
            assertNull(sheet.listError)
            assertEquals(listOf("back"), sheet.entries.map { it.name })
        }

    @Test
    fun confirmAddProject_validatesAndAddsHome() =
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
            assertEquals(AddProjectSheetUi.NO_SUBFOLDERS, currentSheet(viewModel)?.emptyMessage)
            viewModel.confirmAddProject()
            advanceUntilIdle()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(listOf("/one", "/home"), store.added(serverUrl))
            assertTrue(projectRepository.validatedDirectories.contains("/home"))
            assertTrue(!loaded.showAddProjectSheet)
            assertNull(loaded.addProjectSheet)
            assertEquals(HomeScopeRequest(filterId = "/home", evenFromAll = true), viewModel.pendingScopeRequest.value)
        }

    @Test
    fun selectAddProjectSuggestion_addsServerProject() =
        runTest(testDispatcher) {
            val store = FakeConnectionProfileStore(initial = listOf("/one"))
            val projectRepository =
                FakeProjectRepository(
                    bootWorktree = "/one",
                    projects = listOf(Project(id = "web", worktree = "/srv/web", displayName = "web", activityAtEpochMs = 1, isRepository = true)),
                )
            val viewModel =
                openSheet(
                    store = store,
                    projectRepository = projectRepository,
                    sessionsByWorktree = mapOf("/one" to emptyList(), "/srv/web" to emptyList()),
                )
            assertEquals(listOf("/srv/web"), currentSheet(viewModel)?.suggestions?.map { it.path })

            viewModel.selectAddProjectSuggestion("/srv/web")
            advanceUntilIdle()

            assertEquals(listOf("/one", "/srv/web"), store.added(serverUrl))
            assertTrue(projectRepository.validatedDirectories.contains("/srv/web"))
            assertEquals(HomeScopeRequest(filterId = "/srv/web", evenFromAll = true), viewModel.pendingScopeRequest.value)
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
            assertEquals(HomeScopeRequest(filterId = "/two", evenFromAll = false), viewModel.pendingScopeRequest.value)
            assertEquals("\"two\" is already on home", viewModel.removalSnackbar.value?.text)
        }

    @Test
    fun addProject_switchesScopeFromAll_showsLoadingAfterGrace_thenRows() =
        runTest(testDispatcher) {
            val (viewModel, repository) =
                viewModelWithOneProject(
                    newSessions = listOf(Session(id = "n1", title = "N1", updatedAtEpochMs = 2L)),
                )
            val gate = CompletableDeferred<Unit>()
            repository.listGates["/new"] = gate

            viewModel.addProject("/new")
            runCurrent()

            assertEquals(HomeScopeRequest(filterId = "/new", evenFromAll = true), viewModel.pendingScopeRequest.value)
            assertTrue("loading stays hidden inside the grace delay", loaded(viewModel).projectLoads.isEmpty())

            advanceTimeBy(ProjectFirstLoadTiming.DEFAULT_GRACE_MS + 1)
            assertEquals(ProjectFirstLoad.Loading(isSlow = false), loaded(viewModel).projectLoads["/new"])

            gate.complete(Unit)
            runCurrent()
            assertEquals(
                "loading stays for its minimum time once shown",
                ProjectFirstLoad.Loading(isSlow = false),
                loaded(viewModel).projectLoads["/new"],
            )

            advanceTimeBy(ProjectFirstLoadTiming.DEFAULT_MINIMUM_VISIBLE_MS)
            runCurrent()
            assertTrue(loaded(viewModel).projectLoads.isEmpty())
            assertEquals(listOf("n1"), section(viewModel, "/new").allSessions.map { it.id })
        }

    @Test
    fun addProject_loadInsideGrace_neverExposesLoading() =
        runTest(testDispatcher) {
            val (viewModel, _) = viewModelWithOneProject(newSessions = listOf(Session(id = "n1", title = "N1", updatedAtEpochMs = 2L)))
            val exposed = mutableListOf<Map<String, ProjectFirstLoad>>()
            backgroundScope.launch {
                viewModel.uiState.collect { state -> (state as? MainScreenUiState.Loaded)?.let { exposed += it.projectLoads } }
            }

            viewModel.addProject("/new")
            advanceUntilIdle()

            assertTrue(exposed.isNotEmpty())
            assertTrue("never exposed: $exposed", exposed.all { it.isEmpty() })
            assertEquals(listOf("n1"), section(viewModel, "/new").allSessions.map { it.id })
        }

    @Test
    fun addProject_zeroSessions_clearsLoadingWithoutRows() =
        runTest(testDispatcher) {
            val (viewModel, repository) = viewModelWithOneProject(newSessions = emptyList())
            val gate = CompletableDeferred<Unit>()
            repository.listGates["/new"] = gate

            viewModel.addProject("/new")
            advanceTimeBy(ProjectFirstLoadTiming.DEFAULT_GRACE_MS + 1)
            assertEquals(ProjectFirstLoad.Loading(isSlow = false), loaded(viewModel).projectLoads["/new"])

            gate.complete(Unit)
            advanceTimeBy(ProjectFirstLoadTiming.DEFAULT_MINIMUM_VISIBLE_MS + 1)

            assertTrue(loaded(viewModel).projectLoads.isEmpty())
            assertTrue(section(viewModel, "/new").allSessions.isEmpty())
        }

    @Test
    fun addProject_storeFailure_failsThenRetryLoads() =
        runTest(testDispatcher) {
            val (viewModel, repository) =
                viewModelWithOneProject(
                    newSessions = listOf(Session(id = "n1", title = "N1", updatedAtEpochMs = 2L)),
                )
            repository.listErrors["/new"] = IllegalStateException("list failed")

            viewModel.addProject("/new")
            advanceTimeBy(ProjectFirstLoadTiming.DEFAULT_GRACE_MS + 1)
            assertEquals(
                ProjectFirstLoad.Failed(ProjectFirstLoad.FAILED_MESSAGE),
                loaded(viewModel).projectLoads["/new"],
            )

            repository.listErrors.clear()
            val gate = CompletableDeferred<Unit>()
            repository.listGates["/new"] = gate
            viewModel.retryProjectLoad("/new")
            runCurrent()
            assertEquals(ProjectFirstLoad.Loading(isSlow = false), loaded(viewModel).projectLoads["/new"])

            gate.complete(Unit)
            advanceTimeBy(ProjectFirstLoadTiming.DEFAULT_MINIMUM_VISIBLE_MS + 1)
            assertTrue(loaded(viewModel).projectLoads.isEmpty())
            assertEquals(listOf("n1"), section(viewModel, "/new").allSessions.map { it.id })
        }

    @Test
    fun addProject_longLoadTurnsSlow() =
        runTest(testDispatcher) {
            val (viewModel, repository) = viewModelWithOneProject(newSessions = emptyList())
            val gate = CompletableDeferred<Unit>()
            repository.listGates["/new"] = gate

            viewModel.addProject("/new")
            advanceTimeBy(ProjectFirstLoadTiming.DEFAULT_SLOW_AFTER_MS - 1)
            assertEquals(ProjectFirstLoad.Loading(isSlow = false), loaded(viewModel).projectLoads["/new"])
            advanceTimeBy(2)
            assertEquals(ProjectFirstLoad.Loading(isSlow = true), loaded(viewModel).projectLoads["/new"])

            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(loaded(viewModel).projectLoads.isEmpty())
        }

    @Test
    fun addProject_safetyTimeoutFails_thenLateSuccessLoads() =
        runTest(testDispatcher) {
            val timing = ProjectFirstLoadTiming(graceMs = 0, minimumVisibleMs = 0, slowAfterMs = 100, timeoutMs = 1_000)
            val (viewModel, repository) =
                viewModelWithOneProject(
                    newSessions = listOf(Session(id = "n1", title = "N1", updatedAtEpochMs = 2L)),
                    timing = timing,
                )
            val gate = CompletableDeferred<Unit>()
            repository.listGates["/new"] = gate

            viewModel.addProject("/new")
            advanceTimeBy(150)
            assertEquals(ProjectFirstLoad.Loading(isSlow = true), loaded(viewModel).projectLoads["/new"])
            advanceTimeBy(1_000)
            assertEquals(
                ProjectFirstLoad.Failed(ProjectFirstLoad.FAILED_MESSAGE),
                loaded(viewModel).projectLoads["/new"],
            )

            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(loaded(viewModel).projectLoads.isEmpty())
            assertEquals(listOf("n1"), section(viewModel, "/new").allSessions.map { it.id })
        }

    @Test
    fun addProject_firstServerSnapshotPaintsRowsWhileStatusIsSilent() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionRepository(
                    mutableMapOf(
                        "/one" to listOf(Session(id = "a", title = "A", updatedAtEpochMs = 1L)),
                        "/new" to
                            listOf(
                                Session(id = "n1", title = "N1", updatedAtEpochMs = 3L),
                                Session(id = "n2", title = "N2", updatedAtEpochMs = 2L),
                                Session(id = "n3", title = "N3", updatedAtEpochMs = 1L),
                            ),
                    ),
                )
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/one")),
                    sessionRepository = repository,
                    statusLiveStoreOverride = SilentHomeStatusLiveStore(),
                )
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            repository.listGates["/new"] = gate

            viewModel.addProject("/new")
            advanceTimeBy(ProjectFirstLoadTiming.DEFAULT_GRACE_MS + 1)
            assertEquals(ProjectFirstLoad.Loading(isSlow = false), loaded(viewModel).projectLoads["/new"])

            gate.complete(Unit)
            advanceTimeBy(ProjectFirstLoadTiming.DEFAULT_MINIMUM_VISIBLE_MS + 1)

            assertTrue(loaded(viewModel).projectLoads.isEmpty())
            val newSection = section(viewModel, "/new")
            assertEquals(listOf("n1", "n2", "n3"), newSection.allSessions.map { it.id })
            assertFalse(newSection.sessionsFromCache)
        }

    @Test
    fun removeProject_clearsItsFirstLoad() =
        runTest(testDispatcher) {
            val (viewModel, repository) = viewModelWithOneProject(newSessions = emptyList())
            val gate = CompletableDeferred<Unit>()
            repository.listGates["/new"] = gate
            viewModel.addProject("/new")
            advanceTimeBy(ProjectFirstLoadTiming.DEFAULT_GRACE_MS + 1)
            assertEquals(ProjectFirstLoad.Loading(isSlow = false), loaded(viewModel).projectLoads["/new"])

            viewModel.requestRemoveProject("/new")
            viewModel.confirmRemoveProject()
            advanceTimeBy(ProjectFirstLoadTiming.DEFAULT_TIMEOUT_MS + 1)

            assertTrue(loaded(viewModel).projectLoads.isEmpty())
            gate.complete(Unit)
        }

    private fun kotlinx.coroutines.test.TestScope.viewModelWithOneProject(
        newSessions: List<Session>,
        timing: ProjectFirstLoadTiming = ProjectFirstLoadTiming(),
    ): Pair<MainScreenViewModel, FakeSessionRepository> {
        val repository =
            FakeSessionRepository(
                mutableMapOf(
                    "/one" to listOf(Session(id = "a", title = "A", updatedAtEpochMs = 1L)),
                    "/new" to newSessions,
                ),
            )
        val viewModel =
            createViewModel(
                connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/one")),
                sessionRepository = repository,
                projectFirstLoadTiming = timing,
            )
        advanceUntilIdle()
        return viewModel to repository
    }

    private fun loaded(viewModel: MainScreenViewModel): MainScreenUiState.Loaded = viewModel.uiState.value as MainScreenUiState.Loaded

    private fun section(
        viewModel: MainScreenViewModel,
        worktree: String,
    ): ProjectSectionUi = loaded(viewModel).sections.single { it.worktree == worktree }

    @Test
    fun confirmAddProject_persistsSelectedPathEvenWhenCurrentResolvesGlobal() =
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
                openSheet(
                    store = store,
                    projectRepository = projectRepository,
                    filesystem = filesystem,
                    sessionsByWorktree = mapOf("/one" to emptyList(), "/home" to emptyList()),
                )
            viewModel.confirmAddProject()
            advanceUntilIdle()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(listOf("/one", "/home"), store.added(serverUrl))
            assertTrue(!loaded.showAddProjectSheet)
        }

    @Test
    fun confirmAddProject_nestedPathPersistsSelectedDirectory() =
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
                    paths = ServerPaths(home = "/repo", directory = "/repo", worktree = "/"),
                    listings =
                        mapOf(
                            "/repo" to listOf(DirectoryNode("android", "/repo/android", true)),
                            "/repo/android" to emptyList(),
                        ),
                )
            val viewModel =
                openSheet(
                    store = store,
                    projectRepository = projectRepository,
                    filesystem = filesystem,
                    sessionsByWorktree = mapOf("/one" to emptyList(), "/repo/android" to emptyList()),
                )
            viewModel.openAddProjectFolder("/repo/android")
            advanceUntilIdle()
            assertEquals(AddProjectSheetUi.ADD_THIS_FOLDER, currentSheet(viewModel)?.addTitle)
            viewModel.confirmAddProject()
            advanceUntilIdle()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(listOf("/one", "/repo/android"), store.added(serverUrl))
            assertTrue(!loaded.showAddProjectSheet)
        }

    @Test
    fun confirmAddProject_validationFailureKeepsSheetOpenWithFieldError() =
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
            val viewModel = openSheet(projectRepository = projectRepository, filesystem = filesystem)
            viewModel.confirmAddProject()
            advanceUntilIdle()
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertTrue(loaded.showAddProjectSheet)
            assertEquals("not a project", loaded.addProjectSheet?.fieldError)
            assertFalse(loaded.addProjectSheet?.isValidating == true)
            assertTrue(loaded.addProjectSheet?.addEnabled == true)
            assertNull(viewModel.pendingScopeRequest.value)

            viewModel.updateAddProjectField("/home/x")
            assertNull(currentSheet(viewModel)?.fieldError)
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
            val before =
                (viewModel.uiState.value as MainScreenUiState.Loaded)
                    .sections
                    .single()
                    .allSessions.size

            sessionsByWorktree["/repo"] =
                listOf(
                    Session(id = "1", title = "Session 1", updatedAtEpochMs = 1L),
                    Session(id = "new", title = "Session new", updatedAtEpochMs = 2L),
                )
            eventStream.emit(OpencodeEvent(type = "session.created", sessionId = "new"))
            advanceTimeBy(550)
            advanceUntilIdle()

            val after =
                (viewModel.uiState.value as MainScreenUiState.Loaded)
                    .sections
                    .single()
                    .allSessions.size
            assertEquals(before + 1, after)
            assertTrue(sessionRepository.listSessionsCallCount >= 2)
        }

    @Test
    fun onSessionLongPress_showsSheetWithoutFetchingMessages() =
        runTest(testDispatcher) {
            val messageRepository =
                FakeMessageRepository(
                    messagesBySession =
                        mapOf(
                            "s1" to listOf(Message(id = "m1", role = MessageRole.User, text = "Hello")),
                        ),
                )
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    messageRepository = messageRepository,
                )
            advanceUntilIdle()

            viewModel.onSessionLongPress(sessionId = "s1", title = "Session one", worktree = "/repo")
            advanceUntilIdle()

            val opened = viewModel.sessionActions.value
            assertTrue(opened.sheetVisible)
            assertEquals("Session one", opened.target?.title)
            assertEquals(0, messageRepository.listCallCount)
        }

    @Test
    fun chooseFork_opensDialogAndAllowsForkWhenUserMessagesExist() =
        runTest(testDispatcher) {
            val messageRepository =
                FakeMessageRepository(
                    messagesBySession =
                        mapOf(
                            "s1" to listOf(Message(id = "m1", role = MessageRole.User, text = "Hello")),
                        ),
                )
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    messageRepository = messageRepository,
                )
            advanceUntilIdle()

            viewModel.onSessionLongPress(sessionId = "s1", title = "Session one", worktree = "/repo")
            viewModel.chooseFork()
            assertTrue(viewModel.sessionActions.value.forkVisible)
            advanceUntilIdle()

            assertEquals(1, messageRepository.listCallCount)
            assertNull(viewModel.sessionActions.value.forkBlockedMessage)
        }

    @Test
    fun chooseFork_blocksDialogWhenNoUserMessages() =
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
                                    "s1" to
                                        listOf(
                                            Message(id = "m1", role = MessageRole.Assistant, text = "Hi"),
                                        ),
                                ),
                        ),
                )
            advanceUntilIdle()

            viewModel.onSessionLongPress(sessionId = "s1", title = "Session one", worktree = "/repo")
            viewModel.chooseFork()
            advanceUntilIdle()

            assertTrue(viewModel.sessionActions.value.forkVisible)
            assertEquals("Nothing to fork yet", viewModel.sessionActions.value.forkBlockedMessage)

            viewModel.confirmFork()
            advanceUntilIdle()
            assertTrue(sessionRepository.forkedIds.isEmpty())
        }

    @Test
    fun confirmFork_beforeCheckFinishesWaitsAndBlocksEmptySession() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository(mutableMapOf("/repo" to emptyList()))
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionRepository = sessionRepository,
                    messageRepository = FakeMessageRepository(),
                )
            advanceUntilIdle()

            viewModel.onSessionLongPress(sessionId = "s1", title = "Session one", worktree = "/repo")
            viewModel.chooseFork()
            viewModel.confirmFork()
            advanceUntilIdle()

            assertTrue(sessionRepository.forkedIds.isEmpty())
            assertFalse(viewModel.sessionActions.value.forkSubmitting)
            assertEquals("Nothing to fork yet", viewModel.sessionActions.value.forkBlockedMessage)
        }

    @Test
    fun chooseFork_checkFailureLeavesForkToServer() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository(mutableMapOf("/repo" to emptyList()))
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionRepository = sessionRepository,
                    messageRepository = FakeMessageRepository(listError = IllegalStateException("offline")),
                )
            advanceUntilIdle()

            viewModel.onSessionLongPress(sessionId = "s1", title = "Session one", worktree = "/repo")
            viewModel.chooseFork()
            advanceUntilIdle()
            assertNull(viewModel.sessionActions.value.forkBlockedMessage)

            viewModel.confirmFork()
            advanceUntilIdle()
            assertEquals(listOf("s1"), sessionRepository.forkedIds)
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
            assertEquals(
                "Retrying · 2",
                loaded.sections
                    .single()
                    .allSessions
                    .single()
                    .statusLabel,
            )
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
            assertEquals(
                "Working",
                loaded.sections
                    .single()
                    .allSessions
                    .single()
                    .statusLabel,
            )
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
            assertNull(
                loaded.sections
                    .single()
                    .allSessions
                    .single()
                    .statusLabel,
            )
        }

    @Test
    fun sessionStatusLabel_fromCacheShowsStaleUntilSeeded() =
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
                                        source = SessionActivitySource.Cached,
                                        observedAtMs = 0L,
                                    ),
                            ),
                        seeded = false,
                        fromCache = true,
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
            val summary =
                loaded.sections
                    .single()
                    .allSessions
                    .single()
            assertEquals("Working", summary.statusLabel)
            assertTrue(summary.statusStale)
        }

    @Test
    fun v2Home_usesServerUnreadAndIgnoresLocalReadyIds() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo"), serverProtocol = ServerProtocol.V2),
                    sessionsByWorktree = mapOf("/repo" to readStateSessions()),
                    readyStoreOverride = FixedReadyStore(readStateSessions().map { it.id }.toSet()),
                    serverProtocol = ServerProtocol.V2,
                )
            advanceUntilIdle()

            val byId = homeSummaries(viewModel)
            assertEquals(SessionListStatus.Done, byId.getValue("ses-done").toSessionListStatus())
            assertEquals(SessionListStatus.Failed, byId.getValue("ses-failed").toSessionListStatus())
            assertEquals(SessionListStatus.Stopped, byId.getValue("ses-stopped").toSessionListStatus())
            assertEquals(SessionListStatus.Idle, byId.getValue("ses-read").toSessionListStatus())
            assertEquals(SessionListStatus.Idle, byId.getValue("ses-never-ran").toSessionListStatus())
            assertTrue(byId.values.none { it.isReady })
        }

    @Test
    fun home_runningChildMarksIdleRoot() =
        runTest(testDispatcher) {
            val sessions =
                listOf(
                    Session(id = "root", title = "Issue", updatedAtEpochMs = 2L),
                    Session(id = "child", title = "Subagent", parentId = "root", updatedAtEpochMs = 1L),
                )
            val activity =
                MutableStateFlow(
                    SessionActivitySnapshot(
                        entries =
                            mapOf(
                                "child" to
                                    SessionActivityEntry(
                                        activity = SessionActivity.Running,
                                        source = SessionActivitySource.Server,
                                        observedAtMs = 1L,
                                    ),
                            ),
                        seeded = true,
                    ),
                )
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    sessionsByWorktree = mapOf("/repo" to sessions),
                    statusLiveStoreOverride = FakeHomeStatusLiveStore(activity),
                )
            advanceUntilIdle()

            val byId = homeSummaries(viewModel)
            assertEquals("Working", byId.getValue("root").statusLabel)
            assertEquals(SessionListStatus.Running, byId.getValue("root").toSessionListStatus())
            assertNull(byId["child"])
        }

    @Test
    fun v2Home_unreadOutcomeWaitsForSeededStatus() =
        runTest(testDispatcher) {
            // Running again, but an older run's `time.idle` is still unviewed.
            val sessions = listOf(Session(id = "ses-1", title = "Chat", updatedAtEpochMs = 1L, idleAtEpochMs = 30L))
            val running =
                mapOf(
                    "ses-1" to
                        SessionActivityEntry(
                            activity = SessionActivity.Running,
                            source = SessionActivitySource.Server,
                            observedAtMs = 0L,
                        ),
                )
            val activity = MutableStateFlow(SessionActivitySnapshot(entries = running, seeded = false))
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo"), serverProtocol = ServerProtocol.V2),
                    sessionsByWorktree = mapOf("/repo" to sessions),
                    statusLiveStoreOverride = FakeHomeStatusLiveStore(activity),
                    serverProtocol = ServerProtocol.V2,
                )
            advanceUntilIdle()

            assertNull(homeSummaries(viewModel).getValue("ses-1").unreadOutcome)

            activity.value = SessionActivitySnapshot(entries = running, seeded = true)
            advanceUntilIdle()

            val summary = homeSummaries(viewModel).getValue("ses-1")
            assertEquals(SessionIdleOutcome.Succeeded, summary.unreadOutcome)
            // The label wins over the outcome once both are painted.
            assertEquals(SessionListStatus.Running, summary.toSessionListStatus())
        }

    @Test
    fun v2Home_addingProjectNeverPaintsRunningSessionAsDoneFromCache() =
        runTest(testDispatcher) {
            val sessions = listOf(Session(id = "ses-1", title = "Chat", updatedAtEpochMs = 1L, idleAtEpochMs = 30L))
            val running =
                mapOf(
                    "ses-1" to
                        SessionActivityEntry(
                            activity = SessionActivity.Running,
                            source = SessionActivitySource.Server,
                            observedAtMs = 0L,
                        ),
                )
            val sessionsByWorktree = mapOf("/repo" to sessions, "/two" to emptyList())
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo"), serverProtocol = ServerProtocol.V2),
                    sessionsByWorktree = sessionsByWorktree,
                    statusLiveStoreOverride =
                        FakeHomeStatusLiveStore(MutableStateFlow(SessionActivitySnapshot(entries = running, seeded = true))),
                    serverProtocol = ServerProtocol.V2,
                    sessionCache =
                        object : SessionCache by NoOpSessionCache {
                            override suspend fun seedSessions(worktree: String): List<Session>? = sessionsByWorktree[worktree]
                        },
                )
            advanceUntilIdle()
            assertEquals(SessionListStatus.Running, homeSummaries(viewModel).getValue("ses-1").toSessionListStatus())

            val painted = mutableListOf<SessionListStatus>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect { state ->
                    (state as? MainScreenUiState.Loaded)
                        ?.sections
                        ?.flatMap { it.allSessions }
                        ?.firstOrNull { it.id == "ses-1" }
                        ?.let { painted += it.toSessionListStatus() }
                }
            }
            // Changing the worktree list repaints from cache before live observations restart.
            viewModel.addProject("/two")
            advanceUntilIdle()

            assertTrue("painted $painted", SessionListStatus.Done !in painted)
            assertEquals(SessionListStatus.Running, painted.last())
        }

    @Test
    fun v1Home_keepsLocalReadyAndNeverShowsServerUnread() =
        runTest(testDispatcher) {
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo"), serverProtocol = ServerProtocol.V1),
                    sessionsByWorktree = mapOf("/repo" to readStateSessions()),
                    readyStoreOverride = FixedReadyStore(setOf("ses-read")),
                    serverProtocol = ServerProtocol.V1,
                )
            advanceUntilIdle()

            val byId = homeSummaries(viewModel)
            assertEquals(SessionListStatus.Ready, byId.getValue("ses-read").toSessionListStatus())
            assertEquals(SessionListStatus.Idle, byId.getValue("ses-done").toSessionListStatus())
            assertEquals(SessionListStatus.Idle, byId.getValue("ses-failed").toSessionListStatus())
            assertTrue(byId.values.all { it.unreadOutcome == null })
        }

    private fun homeSummaries(viewModel: MainScreenViewModel): Map<String, SessionSummary> =
        (viewModel.uiState.value as MainScreenUiState.Loaded)
            .sections
            .single()
            .allSessions
            .associateBy { it.id }

    private fun readStateSessions(): List<Session> =
        listOf(
            Session(id = "ses-done", title = "Done", updatedAtEpochMs = 5L, idleAtEpochMs = 30L),
            Session(
                id = "ses-failed",
                title = "Failed",
                updatedAtEpochMs = 4L,
                idleAtEpochMs = 30L,
                viewedAtEpochMs = 10L,
                idleOutcome = SessionIdleOutcome.Failed,
            ),
            Session(
                id = "ses-stopped",
                title = "Stopped",
                updatedAtEpochMs = 3L,
                idleAtEpochMs = 30L,
                idleOutcome = SessionIdleOutcome.Interrupted,
            ),
            Session(
                id = "ses-read",
                title = "Read",
                updatedAtEpochMs = 2L,
                idleAtEpochMs = 30L,
                viewedAtEpochMs = 30L,
                idleOutcome = SessionIdleOutcome.Failed,
            ),
            Session(id = "ses-never-ran", title = "Never ran", updatedAtEpochMs = 1L),
        )

    private fun addProjectSheetVisible(viewModel: MainScreenViewModel): Boolean =
        when (val state = viewModel.uiState.value) {
            is MainScreenUiState.Loaded -> state.showAddProjectSheet
            is MainScreenUiState.Empty -> state.showAddProjectSheet
            else -> false
        }

    private fun currentSheet(viewModel: MainScreenViewModel): AddProjectSheetUi? =
        when (val state = viewModel.uiState.value) {
            is MainScreenUiState.Loaded -> state.addProjectSheet
            is MainScreenUiState.Empty -> state.addProjectSheet
            else -> null
        }

    /** Home with one project, then the Add project sheet opened and settled. */
    private fun kotlinx.coroutines.test.TestScope.openSheet(
        filesystem: FakeFilesystemRepository = FakeFilesystemRepository(),
        projectRepository: FakeProjectRepository = FakeProjectRepository(bootWorktree = "/one"),
        added: List<String> = listOf("/one"),
        store: FakeConnectionProfileStore = FakeConnectionProfileStore(initial = added),
        sessionsByWorktree: Map<String, List<Session>> = emptyMap(),
    ): MainScreenViewModel {
        val viewModel =
            createViewModel(
                connectionProfileStore = store,
                projectRepository = projectRepository,
                filesystemRepository = filesystem,
                sessionsByWorktree = sessionsByWorktree,
            )
        advanceUntilIdle()
        viewModel.showAddProjectSheet()
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun worktreesOfOneRepository_groupIntoOneProjectWithBranches() =
        runTest(testDispatcher) {
            val shared =
                listOf(
                    Session(id = "a", title = "A", directory = "/repo", updatedAtEpochMs = 2L),
                    Session(id = "b", title = "B", directory = "/repo-wt", updatedAtEpochMs = 3L),
                )
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo", "/repo-wt", "/plain")),
                    projectRepository = FakeProjectRepository(projectsByDirectory = repoProjects),
                    sessionRepository =
                        FakeSessionRepository(
                            mutableMapOf("/repo" to shared, "/repo-wt" to shared, "/plain" to emptyList()),
                            branches = mapOf("/repo" to "dev", "/repo-wt" to "feature/x"),
                        ),
                )
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(listOf("proj", "path:/plain"), loaded.projects.map { it.key })
            assertEquals(listOf("/repo", "/repo-wt"), loaded.projects.first().memberPaths)
            assertEquals(
                listOf("dev", "feature/x"),
                loaded.projects
                    .first()
                    .worktrees
                    .map { it.branch },
            )
            assertEquals(listOf("b", "a"), loaded.homeSessions().map { it.id })
        }

    @Test
    fun addProject_ofARepositoryOnHome_joinsItAsAWorktree() =
        runTest(testDispatcher) {
            val store = FakeConnectionProfileStore(initial = listOf("/repo"))
            val viewModel =
                createViewModel(
                    connectionProfileStore = store,
                    projectRepository = FakeProjectRepository(projectsByDirectory = repoProjects),
                    sessionsByWorktree = mapOf("/repo" to emptyList(), "/repo-wt" to emptyList()),
                )
            advanceUntilIdle()

            viewModel.addProject("/repo-wt")
            advanceUntilIdle()

            assertEquals(listOf("/repo", "/repo-wt"), store.added(serverUrl))
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(listOf("/repo", "/repo-wt"), loaded.projects.single().memberPaths)
            assertEquals("Same repo as repo. Showing it as a worktree there.", viewModel.removalSnackbar.value?.text)
            assertEquals(HomeScopeRequest(filterId = "/repo-wt", evenFromAll = true), viewModel.pendingScopeRequest.value)
        }

    @Test
    fun removeProject_removesEveryAddedPathOfIt() =
        runTest(testDispatcher) {
            val store = FakeConnectionProfileStore(initial = listOf("/repo", "/plain", "/repo-wt"))
            val viewModel =
                createViewModel(
                    connectionProfileStore = store,
                    projectRepository = FakeProjectRepository(projectsByDirectory = repoProjects),
                    sessionsByWorktree = mapOf("/repo" to emptyList(), "/repo-wt" to emptyList(), "/plain" to emptyList()),
                )
            advanceUntilIdle()

            viewModel.requestRemoveProject("proj")
            assertEquals(
                listOf("/repo", "/repo-wt"),
                (viewModel.uiState.value as MainScreenUiState.Loaded).pendingProjectRemoval?.worktrees,
            )
            viewModel.confirmRemoveProject()
            advanceUntilIdle()

            assertEquals(listOf("/plain"), store.added(serverUrl))
            assertEquals("Removed \"repo\" from home", viewModel.removalSnackbar.value?.text)
        }

    @Test
    fun reorderProjects_movesEveryAddedPathOfAProject() =
        runTest(testDispatcher) {
            val store = FakeConnectionProfileStore(initial = listOf("/repo", "/plain", "/repo-wt"))
            val viewModel =
                createViewModel(
                    connectionProfileStore = store,
                    projectRepository = FakeProjectRepository(projectsByDirectory = repoProjects),
                    sessionsByWorktree = mapOf("/repo" to emptyList(), "/repo-wt" to emptyList(), "/plain" to emptyList()),
                )
            advanceUntilIdle()

            viewModel.reorderProjects(listOf("path:/plain", "proj"))
            advanceUntilIdle()

            assertEquals(listOf("/plain", "/repo", "/repo-wt"), store.added(serverUrl))
        }

    @Test
    fun v1WorktreeThatIsNotAdded_listsLiveAndRefreshesOnSessionEvents() =
        runTest(testDispatcher) {
            val eventStream = FakeForegroundEventStream().also { it.setConnectionState(EventStreamConnectionState.Connected) }
            val sessionsByWorktree =
                mutableMapOf(
                    "/repo" to listOf(Session(id = "main", title = "Main", updatedAtEpochMs = 1L)),
                    "/wt-unadded" to listOf(Session(id = "extra", title = "Extra", updatedAtEpochMs = 2L)),
                )
            val sessionRepository = FakeSessionRepository(sessionsByWorktree)
            val v1Worktrees =
                object : WorktreeRepository by UnavailableWorktreeRepository {
                    override suspend fun loadRefreshed(
                        addedPath: String,
                        projectId: String?,
                        mainWorktree: String?,
                    ): WorktreeLoad = WorktreeLoad.Loaded(listOfNotNull(mainWorktree) + "/wt-unadded", listsSessions = true)
                }
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    projectRepository = FakeProjectRepository(projectsByDirectory = repoProjects),
                    eventStream = eventStream,
                    sessionRepository = sessionRepository,
                    worktreeRepository = v1Worktrees,
                )
            advanceUntilIdle()

            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(listOf("extra", "main"), loaded.homeSessions().map { it.id })
            assertEquals("/wt-unadded", loaded.homeSessions().first().directory)
            assertEquals(
                listOf("/repo", "/wt-unadded"),
                loaded.projects
                    .single()
                    .worktrees
                    .map { it.directory },
            )

            sessionsByWorktree["/wt-unadded"] =
                listOf(
                    Session(id = "extra", title = "Extra", updatedAtEpochMs = 2L),
                    Session(id = "fresh", title = "Fresh", updatedAtEpochMs = 3L),
                )
            eventStream.emit(OpencodeEvent(type = "session.created", sessionId = "fresh"))
            advanceTimeBy(550)
            advanceUntilIdle()

            val refreshed = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals(listOf("fresh", "extra", "main"), refreshed.homeSessions().map { it.id })
            assertEquals(2, sessionRepository.listedDirectories.count { it == "/wt-unadded" })
        }

    @Test
    fun runInNewWorktreeCreatesThenStartsASessionThereAndListsIt() =
        runTest(testDispatcher) {
            var listed = listOf("/repo")
            val requests = mutableListOf<CreateWorktreeRequest>()
            val worktrees =
                object : WorktreeRepository by UnavailableWorktreeRepository {
                    override suspend fun loadRefreshed(
                        addedPath: String,
                        projectId: String?,
                        mainWorktree: String?,
                    ): WorktreeLoad = WorktreeLoad.Loaded(listed, listsSessions = false)

                    override suspend fun create(request: CreateWorktreeRequest): CreatedWorktree {
                        requests += request
                        listed = listed + "/wt/brave-otter"
                        return CreatedWorktree("/wt/brave-otter", "brave-otter", branch = null)
                    }
                }
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    projectRepository = FakeProjectRepository(projectsByDirectory = repoProjects),
                    sessionsByWorktree = mapOf("/repo" to emptyList()),
                    worktreeRepository = worktrees,
                    serverProtocol = ServerProtocol.V2,
                )
            advanceUntilIdle()

            viewModel.openRunInNewWorktree("proj")
            advanceUntilIdle()
            val form = viewModel.worktreeCreateForm.value!!
            assertEquals("/repo", form.sourceDirectory)
            viewModel.updateNewWorktreeName("brave-otter")
            viewModel.submitNewWorktree()
            advanceUntilIdle()

            assertEquals(listOf(CreateWorktreeRequest("proj", "/repo", "brave-otter", baseRef = null)), requests)
            assertNull(viewModel.worktreeCreateForm.value)
            val loaded = viewModel.uiState.value as MainScreenUiState.Loaded
            assertEquals("/wt/brave-otter", loaded.pendingOpenSession?.worktree)
            assertEquals(
                listOf("/repo", "/wt/brave-otter"),
                loaded.projects
                    .single()
                    .worktrees
                    .map { it.directory },
            )
        }

    @Test
    fun runInNewWorktreeFailureStartsNoSession() =
        runTest(testDispatcher) {
            val worktrees =
                object : WorktreeRepository by UnavailableWorktreeRepository {
                    override suspend fun create(request: CreateWorktreeRequest): CreatedWorktree =
                        throw WorktreeCreateError("Worktrees are only supported for git projects")
                }
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    projectRepository = FakeProjectRepository(projectsByDirectory = repoProjects),
                    sessionsByWorktree = mapOf("/repo" to emptyList()),
                    worktreeRepository = worktrees,
                    serverProtocol = ServerProtocol.V1,
                )
            advanceUntilIdle()

            viewModel.openRunInNewWorktree("proj")
            viewModel.submitNewWorktree()
            advanceUntilIdle()

            assertEquals(
                "Couldn't create the worktree: Worktrees are only supported for git projects",
                viewModel.worktreeCreateForm.value?.errorMessage,
            )
            assertNull((viewModel.uiState.value as MainScreenUiState.Loaded).pendingOpenSession)
        }

    @Test
    fun loadedWorktreeInventoriesAreSharedForSessionChat() =
        runTest(testDispatcher) {
            val cache =
                dev.opencode.mobile.domain.worktree
                    .WorktreeInventoryCache()
            val worktrees =
                object : WorktreeRepository by UnavailableWorktreeRepository {
                    override suspend fun loadRefreshed(
                        addedPath: String,
                        projectId: String?,
                        mainWorktree: String?,
                    ): WorktreeLoad = WorktreeLoad.Loaded(listOf("/repo", "/wt/tax"), listsSessions = false)
                }
            createViewModel(
                connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                projectRepository = FakeProjectRepository(projectsByDirectory = repoProjects),
                sessionsByWorktree = mapOf("/repo" to emptyList()),
                worktreeRepository = worktrees,
                worktreeInventoryCache = cache,
            )
            advanceUntilIdle()

            val snapshot = cache.snapshot(serverUrl)
            assertEquals(listOf("/repo"), snapshot?.addedPaths)
            assertEquals(WorktreeInventory.loaded(listOf("/repo", "/wt/tax")), snapshot?.inventoryFor("/repo"))
        }

    @Test
    fun v2BranchThatIsNotSettledOnTheFirstAnswerShowsWithoutRefresh() =
        runTest(testDispatcher) {
            val sessionRepository = FakeSessionRepository(mutableMapOf("/repo" to emptyList()))
            sessionRepository.vcsAnswers =
                ArrayDeque(
                    listOf(
                        VcsInfo(currentBranch = null, defaultBranch = null, isSettled = false),
                        VcsInfo(currentBranch = "feature/tax-rounding", defaultBranch = "main"),
                    ),
                )
            val viewModel =
                createViewModel(
                    connectionProfileStore = FakeConnectionProfileStore(initial = listOf("/repo")),
                    projectRepository = FakeProjectRepository(projectsByDirectory = repoProjects),
                    sessionRepository = sessionRepository,
                )
            runCurrent()
            advanceTimeBy(500)
            assertEquals(
                listOf(null),
                (viewModel.uiState.value as MainScreenUiState.Loaded)
                    .projects
                    .single()
                    .worktrees
                    .map { it.branch },
            )

            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(
                listOf("feature/tax-rounding"),
                (viewModel.uiState.value as MainScreenUiState.Loaded)
                    .projects
                    .single()
                    .worktrees
                    .map { it.branch },
            )
            assertEquals(2, sessionRepository.vcsCalls)
        }

    private val repoProjects =
        mapOf(
            "/repo" to Project(id = "proj", worktree = "/repo", displayName = "repo", isRepository = true),
            "/repo-wt" to Project(id = "proj", worktree = "/repo", displayName = "repo", isRepository = true),
            "/plain" to Project(id = "global", worktree = "/", displayName = ""),
        )

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
        readyStoreOverride: SessionReadyStore? = null,
        serverProtocol: ServerProtocol? = null,
        sessionCache: SessionCache = NoOpSessionCache,
        projectFirstLoadTiming: ProjectFirstLoadTiming = ProjectFirstLoadTiming(),
        modelRepository: ModelRepository =
            object : ModelRepository {
                override suspend fun listModelOptions(directory: String?): ModelSelectionCatalog = ModelSelectionCatalog(emptyList(), null)
            },
        worktreeRepository: WorktreeRepository = UnavailableWorktreeRepository,
        worktreeInventoryCache: dev.opencode.mobile.domain.worktree.WorktreeInventoryCache =
            dev.opencode.mobile.domain.worktree
                .WorktreeInventoryCache(),
    ): MainScreenViewModel {
        val activeConnectionManager = ActiveConnectionManager(connectionProfileStore)
        runBlocking {
            activeConnectionManager.applyProfile(
                ConnectionProfile(baseUrl = serverUrl, username = "opencode", password = ""),
                serverProtocol = serverProtocol,
            )
        }
        val coordinator =
            testConnectionSessionCoordinator(
                activeConnectionManager = activeConnectionManager,
                connectionProfileStore = connectionProfileStore,
                secureCredentialStore = FakeSecureCredentialStore(),
                sessionModelSelectionStore = selectionStore,
                composerDraftStore =
                    dev.opencode.mobile.domain.composer
                        .InMemoryComposerDraftStore(),
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
            readyStoreOverride ?: DefaultSessionReadyStore(
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
            sessionLiveStore = sessionLiveStore,
            sessionStatusLiveStore = sessionStatusLiveStore,
            sessionReadyStore = sessionReadyStore,
            activeConnectionManager = activeConnectionManager,
            connectionSessionCoordinator = coordinator,
            sessionCache = sessionCache,
            worktreeRepository = worktreeRepository,
            projectFirstLoadTiming = projectFirstLoadTiming,
            worktreeInventoryCache = worktreeInventoryCache,
        )
    }
}

private class FakeProjectRepository(
    private val shouldFailProbe: Boolean = false,
    private val bootWorktree: String = "/boot/project",
    private val currentProjectError: Throwable? = null,
    private val projectsByDirectory: Map<String, Project> = emptyMap(),
    private val projects: List<Project> = emptyList(),
) : ProjectRepository {
    val validatedDirectories = mutableListOf<String?>()

    override suspend fun probeServer() {
        if (shouldFailProbe) throw ServerProbeError.Transport(IllegalStateException("down"))
    }

    override suspend fun listProjects(): List<Project> = projects

    override suspend fun getCurrentProject(directory: String?): Project {
        validatedDirectories += directory
        currentProjectError?.let { throw it }
        if (directory != null) {
            projectsByDirectory[directory]?.let { return it }
        }
        // Distinct folders are distinct projects on a real server.
        val worktree = directory ?: bootWorktree
        return Project(id = "p:$worktree", worktree = worktree, displayName = "demo")
    }
}

private class FakeFilesystemRepository(
    private val paths: ServerPaths = ServerPaths(home = "/home", directory = "/home", worktree = "/"),
    private val listings: Map<String, List<DirectoryNode>> = emptyMap(),
    val errors: MutableMap<String, Throwable> = mutableMapOf(),
) : FilesystemRepository {
    val listCalls = mutableListOf<String>()

    /** A gate holds that path's listing until completed. */
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    var inFlight = 0
    var maxInFlight = 0

    override suspend fun getPaths(): ServerPaths = paths

    override suspend fun listDirectory(path: String): DirectoryListing {
        listCalls += path
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            gates[path]?.await()
            errors[path]?.let { throw it }
            return DirectoryListing.from(listings[path].orEmpty())
        } finally {
            inFlight--
        }
    }
}

private class FakeSessionRepository(
    private val sessionsByWorktree: MutableMap<String, List<Session>>,
    private val createError: Throwable? = null,
    private val updateError: Throwable? = null,
    private val archiveError: Throwable? = null,
    private val deleteError: Throwable? = null,
    private val forkError: Throwable? = null,
    private val deleteResult: Boolean = true,
    private val branches: Map<String, String> = emptyMap(),
) : SessionRepository {
    var listSessionsCallCount = 0
    val listedDirectories = mutableListOf<String?>()
    var nextListGate: CompletableDeferred<Unit>? = null

    /** Per-directory list hooks: a gate holds that directory's list call, an error fails it. */
    val listGates = mutableMapOf<String, CompletableDeferred<Unit>>()
    val listErrors = mutableMapOf<String, Throwable>()
    val createdDirectories = mutableListOf<String?>()
    val renamedTitles = mutableListOf<Pair<String, String?>>()
    val archivedIds = mutableListOf<String>()
    val deletedIds = mutableListOf<String>()
    val forkedIds = mutableListOf<String>()

    /** Overrides [branches] when set: one scripted answer per call. */
    var vcsAnswers: ArrayDeque<VcsInfo>? = null
    var vcsCalls = 0

    override suspend fun getVcsInfo(directory: String?): VcsInfo {
        vcsCalls += 1
        vcsAnswers?.let { answers -> return answers.removeFirstOrNull() ?: answers.lastOrNull() ?: VcsInfo(null, null) }
        return VcsInfo(currentBranch = branches[directory], defaultBranch = "main")
    }

    override suspend fun listSessions(directory: String?): List<Session> {
        listSessionsCallCount += 1
        listedDirectories += directory
        nextListGate?.also {
            nextListGate = null
            it.await()
        }
        directory?.let { listGates.remove(it) }?.await()
        directory?.let { listErrors[it] }?.let { throw it }
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
    var listCallCount = 0

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
        listCallCount += 1
        listError?.let { throw it }
        return messagesBySession[sessionId].orEmpty()
    }

    override suspend fun sendPromptAsync(
        sessionId: String,
        options: PromptSendOptions,
        directory: String?,
    ) = Unit

    override suspend fun runShellCommand(
        sessionId: String,
        options: ShellCommandOptions,
        directory: String?,
    ) = Unit
}

private class FakeConnectionProfileStore(
    initial: List<String> = emptyList(),
    private val serverProtocol: ServerProtocol? = null,
) : ConnectionProfileStore {
    private val data = mutableMapOf<String, List<String>>()

    init {
        if (initial.isNotEmpty()) {
            data[serverUrlKey()] = initial
        }
    }

    fun added(serverBaseUrl: String): List<String> = data[serverBaseUrl].orEmpty()

    override suspend fun activeProfile(): ConnectionProfile? =
        ConnectionProfile(baseUrl = serverUrlKey(), username = "opencode", password = "", serverProtocol = serverProtocol)

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

/** Status store whose stream never emits, so the combined Home stream stays gated. */
private class SilentHomeStatusLiveStore : SessionStatusLiveStore {
    override fun observeActivity(directory: String): Flow<SessionActivitySnapshot> = MutableSharedFlow()

    override fun clearCache() = Unit
}

private class FakeHomeStatusLiveStore(
    private val snapshots: MutableStateFlow<SessionActivitySnapshot>,
) : SessionStatusLiveStore {
    override fun observeActivity(directory: String): Flow<SessionActivitySnapshot> = snapshots

    override fun clearCache() = Unit
}

private class FixedReadyStore(
    private val readyIds: Set<String>,
) : SessionReadyStore {
    override fun observeReadyIds(directory: String): Flow<Set<String>> = flowOf(readyIds)

    override fun notifyPromptSent(
        sessionId: String,
        directory: String,
    ) = Unit

    override fun setFocusedSession(
        sessionId: String?,
        directory: String?,
    ) = Unit

    override fun releaseFocusedSession(sessionId: String) = Unit

    override fun bindProfile(profileId: String?) = Unit

    override fun clearCache() = Unit
}
