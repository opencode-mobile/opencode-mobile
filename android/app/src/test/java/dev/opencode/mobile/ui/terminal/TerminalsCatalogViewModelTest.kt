package dev.opencode.mobile.ui.terminal

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.terminal.RunningTerminal
import dev.opencode.mobile.domain.terminal.TerminalConnectRequest
import dev.opencode.mobile.domain.terminal.TerminalRepository
import dev.opencode.mobile.domain.terminal.TerminalSession
import dev.opencode.mobile.domain.terminal.TerminalSessionStatus
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalsCatalogViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startsLoading() =
        runTest(testDispatcher) {
            val viewModel =
                viewModel(
                    repository = FakeCatalogTerminalRepository(running = emptyList()),
                    worktrees = listOf("/repo"),
                )

            assertTrue(viewModel.uiState.value.isLoading)
            assertTrue(
                viewModel.uiState.value.rows
                    .isEmpty(),
            )
        }

    @Test
    fun appear_loadsRunningRows() =
        runTest(testDispatcher) {
            val repository =
                FakeCatalogTerminalRepository(
                    running =
                        listOf(
                            RunningTerminal("/Users/ivan/Projects/opencode-mobile", session("pty_1", "zsh")),
                            RunningTerminal("/Users/ivan/Projects/playground", session("pty_2", "term")),
                        ),
                )
            val viewModel =
                viewModel(
                    repository = repository,
                    worktrees = listOf("/Users/ivan/Projects/opencode-mobile", "/Users/ivan/Projects/playground"),
                )

            viewModel.appear()
            advanceUntilIdle()

            assertEquals(
                listOf(
                    TerminalCatalogRow(
                        id = "pty_1",
                        title = "zsh",
                        projectTitle = "opencode-mobile",
                        worktree = "/Users/ivan/Projects/opencode-mobile",
                    ),
                    TerminalCatalogRow(
                        id = "pty_2",
                        title = "term",
                        projectTitle = "playground",
                        worktree = "/Users/ivan/Projects/playground",
                    ),
                ),
                viewModel.uiState.value.rows,
            )
            assertEquals(
                listOf("/Users/ivan/Projects/opencode-mobile", "/Users/ivan/Projects/playground"),
                repository.listedDirectories.single(),
            )
            assertEquals(false, viewModel.uiState.value.isLoading)
        }

    @Test
    fun appear_emptyList() =
        runTest(testDispatcher) {
            val viewModel =
                viewModel(
                    repository = FakeCatalogTerminalRepository(running = emptyList()),
                    worktrees = listOf("/repo"),
                )

            viewModel.appear()
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.rows
                    .isEmpty(),
            )
            assertEquals(false, viewModel.uiState.value.isLoading)
        }

    @Test
    fun catalogTitle_emptyCommandFallsBackToZsh() {
        val session =
            TerminalSession(
                id = "pty_1",
                title = "  ",
                command = "",
                cwd = "/repo",
                status = TerminalSessionStatus.Running,
            )
        assertEquals("zsh", catalogTitle(session))
    }

    @Test
    fun catalogTitle_usesCommandBasename() {
        val session =
            TerminalSession(
                id = "pty_1",
                title = "",
                command = "/bin/bash",
                cwd = "/repo",
                status = TerminalSessionStatus.Running,
            )
        assertEquals("bash", catalogTitle(session))
    }

    @Test
    fun requestClose_setsPendingWithoutRemoving() =
        runTest(testDispatcher) {
            val repository =
                FakeCatalogTerminalRepository(
                    running = listOf(RunningTerminal("/repo", session("pty_1", "zsh"))),
                )
            val viewModel = viewModel(repository = repository, worktrees = listOf("/repo"))

            viewModel.appear()
            advanceUntilIdle()
            val row =
                viewModel.uiState.value.rows
                    .single()
            viewModel.requestClose(row)

            assertEquals(row, viewModel.uiState.value.pendingClose)
            assertTrue(repository.removed.isEmpty())
        }

    @Test
    fun dismissClose_clearsPendingWithoutRemoving() =
        runTest(testDispatcher) {
            val repository =
                FakeCatalogTerminalRepository(
                    running = listOf(RunningTerminal("/repo", session("pty_1", "zsh"))),
                )
            val viewModel = viewModel(repository = repository, worktrees = listOf("/repo"))

            viewModel.appear()
            advanceUntilIdle()
            viewModel.requestClose(
                viewModel.uiState.value.rows
                    .single(),
            )
            viewModel.dismissClose()

            assertEquals(null, viewModel.uiState.value.pendingClose)
            assertTrue(repository.removed.isEmpty())
            assertEquals(
                listOf("pty_1"),
                viewModel.uiState.value.rows
                    .map { it.id },
            )
        }

    @Test
    fun confirmClose_removesViaRepositoryAndUpdatesState() =
        runTest(testDispatcher) {
            val repository =
                FakeCatalogTerminalRepository(
                    running =
                        listOf(
                            RunningTerminal("/repo", session("pty_1", "zsh")),
                            RunningTerminal("/play", session("pty_2", "zsh")),
                        ),
                )
            val viewModel = viewModel(repository = repository, worktrees = listOf("/repo", "/play"))

            viewModel.appear()
            advanceUntilIdle()
            viewModel.requestClose(
                viewModel.uiState.value.rows
                    .first(),
            )
            viewModel.confirmClose()
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.pendingClose)
            assertEquals(listOf("pty_1" to "/repo"), repository.removed)
            assertEquals(
                listOf("pty_2"),
                viewModel.uiState.value.rows
                    .map { it.id },
            )
        }

    @Test
    fun confirmClose_withoutPending_isNoOp() =
        runTest(testDispatcher) {
            val repository =
                FakeCatalogTerminalRepository(
                    running = listOf(RunningTerminal("/repo", session("pty_1", "zsh"))),
                )
            val viewModel = viewModel(repository = repository, worktrees = listOf("/repo"))

            viewModel.appear()
            advanceUntilIdle()
            viewModel.confirmClose()
            advanceUntilIdle()

            assertTrue(repository.removed.isEmpty())
            assertEquals(
                listOf("pty_1"),
                viewModel.uiState.value.rows
                    .map { it.id },
            )
        }

    @Test
    fun appear_exposesProjectsFromAddedWorktrees() =
        runTest(testDispatcher) {
            val viewModel =
                viewModel(
                    repository = FakeCatalogTerminalRepository(running = emptyList()),
                    worktrees =
                        listOf(
                            "/Users/ivan/Projects/opencode-mobile",
                            "/Users/ivan/Projects/playground",
                        ),
                )

            viewModel.appear()
            advanceUntilIdle()

            assertEquals(
                listOf(
                    TerminalCatalogProject(
                        worktree = "/Users/ivan/Projects/opencode-mobile",
                        title = "opencode-mobile",
                    ),
                    TerminalCatalogProject(
                        worktree = "/Users/ivan/Projects/playground",
                        title = "playground",
                    ),
                ),
                viewModel.uiState.value.projects,
            )
        }

    @Test
    fun showProjectPicker_setsVisible_dismissClearsVisibleAndCreateError() =
        runTest(testDispatcher) {
            val repository =
                FakeCatalogTerminalRepository(
                    running = emptyList(),
                    createFailure = IllegalStateException("nope"),
                )
            val viewModel = viewModel(repository = repository, worktrees = listOf("/repo"))

            viewModel.appear()
            advanceUntilIdle()
            viewModel.showProjectPicker()

            assertEquals(true, viewModel.uiState.value.isProjectPickerVisible)

            viewModel.createShell("/repo")
            viewModel.dismissProjectPicker()

            assertEquals(false, viewModel.uiState.value.isProjectPickerVisible)
            assertEquals(null, viewModel.uiState.value.createError)
        }

    @Test
    fun createShell_prependsRowAndClearsPicker() =
        runTest(testDispatcher) {
            val createdSession = session("pty_new", "zsh")
            val repository =
                FakeCatalogTerminalRepository(
                    running = listOf(RunningTerminal("/repo", session("pty_1", "term"))),
                    createSession = createdSession,
                )
            val viewModel = viewModel(repository = repository, worktrees = listOf("/repo"))

            viewModel.appear()
            advanceUntilIdle()
            viewModel.showProjectPicker()
            val row = viewModel.createShell("/repo")

            assertEquals(listOf("/repo" to "/repo"), repository.created)
            assertTrue(repository.ensureRunningCalls.isEmpty())
            assertEquals(
                TerminalCatalogRow(
                    id = "pty_new",
                    title = "zsh",
                    projectTitle = "repo",
                    worktree = "/repo",
                ),
                row,
            )
            assertEquals(
                listOf("pty_new", "pty_1"),
                viewModel.uiState.value.rows
                    .map { it.id },
            )
            assertEquals(false, viewModel.uiState.value.isProjectPickerVisible)
            assertEquals(null, viewModel.uiState.value.createError)
        }

    @Test
    fun createShell_failureLeavesRowsAndPickerAndSetsError() =
        runTest(testDispatcher) {
            val repository =
                FakeCatalogTerminalRepository(
                    running = listOf(RunningTerminal("/repo", session("pty_1", "term"))),
                    createFailure = IllegalStateException("nope"),
                )
            val viewModel = viewModel(repository = repository, worktrees = listOf("/repo"))

            viewModel.appear()
            advanceUntilIdle()
            viewModel.showProjectPicker()
            val existingRows = viewModel.uiState.value.rows
            val row = viewModel.createShell("/repo")

            assertEquals(null, row)
            assertEquals(existingRows, viewModel.uiState.value.rows)
            assertEquals(true, viewModel.uiState.value.isProjectPickerVisible)
            assertEquals("Could not start a shell.", viewModel.uiState.value.createError)
        }

    @Test
    fun createShell_whileInFlight_doesNotCallCreateAgain() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            val repository =
                FakeCatalogTerminalRepository(
                    running = emptyList(),
                    createSession = session("pty_new", "zsh"),
                    createGate = gate,
                )
            val viewModel = viewModel(repository = repository, worktrees = listOf("/repo"))

            viewModel.appear()
            advanceUntilIdle()
            val first = launch { viewModel.createShell("/repo") }
            advanceUntilIdle()

            assertEquals(listOf("/repo" to "/repo"), repository.created)

            val second = viewModel.createShell("/repo")
            assertEquals(null, second)
            assertEquals(listOf("/repo" to "/repo"), repository.created)

            gate.complete(Unit)
            first.join()
        }

    @Test
    fun createShell_unknownWorktree_doesNotCallCreate() =
        runTest(testDispatcher) {
            val repository =
                FakeCatalogTerminalRepository(
                    running = emptyList(),
                    createSession = session("pty_new", "zsh"),
                )
            val viewModel = viewModel(repository = repository, worktrees = listOf("/repo"))

            viewModel.appear()
            advanceUntilIdle()
            viewModel.showProjectPicker()
            val row = viewModel.createShell("/unknown")

            assertEquals(null, row)
            assertTrue(repository.created.isEmpty())
            assertEquals(true, viewModel.uiState.value.isProjectPickerVisible)
        }

    private suspend fun viewModel(
        repository: FakeCatalogTerminalRepository,
        worktrees: List<String>,
    ): TerminalsCatalogViewModel {
        val store = FakeCatalogProfileStore(worktrees)
        val manager = ActiveConnectionManager(store)
        manager.applyProfile(
            ConnectionProfile(
                baseUrl = "http://127.0.0.1:4096/",
                username = "opencode",
                password = "secret",
            ),
            serverProtocol = ServerProtocol.V1,
        )
        return TerminalsCatalogViewModel(
            terminalRepository = repository,
            activeConnectionManager = manager,
            connectionProfileStore = store,
        )
    }

    private fun session(
        id: String,
        title: String,
    ): TerminalSession =
        TerminalSession(
            id = id,
            title = title,
            command = "/bin/zsh",
            cwd = "/repo",
            status = TerminalSessionStatus.Running,
        )
}

private class FakeCatalogTerminalRepository(
    private val running: List<RunningTerminal>,
    private val createSession: TerminalSession? = null,
    private val createFailure: Throwable? = null,
    private val createGate: CompletableDeferred<Unit>? = null,
) : TerminalRepository {
    val listedDirectories = mutableListOf<List<String>>()
    val removed = mutableListOf<Pair<String, String>>()
    val created = mutableListOf<Pair<String, String>>()
    val ensureRunningCalls = mutableListOf<String>()

    override suspend fun list(directory: String): List<TerminalSession> = error("unused")

    override suspend fun create(
        directory: String,
        cwd: String,
    ): TerminalSession {
        created += directory to cwd
        createGate?.await()
        createFailure?.let { throw it }
        return createSession ?: error("unused")
    }

    override suspend fun get(
        id: String,
        directory: String,
    ): TerminalSession = error("unused")

    override suspend fun updateSize(
        id: String,
        directory: String,
        cols: Int,
        rows: Int,
    ): TerminalSession = error("unused")

    override suspend fun ensureRunning(directory: String): TerminalSession {
        ensureRunningCalls += directory
        error("unused")
    }

    override suspend fun remove(
        id: String,
        directory: String,
    ) {
        removed += id to directory
    }

    override suspend fun listRunning(directories: List<String>): List<RunningTerminal> {
        listedDirectories += directories
        return running.filter { it.directory in directories }
    }

    override fun connectRequest(
        id: String,
        directory: String,
        protocol: ServerProtocol,
        baseUrl: String,
        cursor: String,
        ticket: String?,
        username: String,
        password: String,
    ): TerminalConnectRequest = TerminalConnectRequest("ws://example.test/pty/$id/connect")
}

private class FakeCatalogProfileStore(
    private val worktrees: List<String>,
) : ConnectionProfileStore {
    private var profile: ConnectionProfile? = null

    override suspend fun activeProfile(): ConnectionProfile? = profile

    override suspend fun saveProfile(profile: ConnectionProfile) {
        this.profile = profile
    }

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = worktrees

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {}

    override suspend fun removeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {}

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) {}

    override suspend fun clearAll() {
        profile = null
    }
}
