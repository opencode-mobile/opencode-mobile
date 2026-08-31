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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    fun close_removesViaRepositoryAndUpdatesState() =
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
            viewModel.close("pty_1", "/repo")
            advanceUntilIdle()

            assertEquals(listOf("pty_1" to "/repo"), repository.removed)
            assertEquals(
                listOf("pty_2"),
                viewModel.uiState.value.rows
                    .map { it.id },
            )
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
) : TerminalRepository {
    val listedDirectories = mutableListOf<List<String>>()
    val removed = mutableListOf<Pair<String, String>>()

    override suspend fun list(directory: String): List<TerminalSession> = error("unused")

    override suspend fun create(
        directory: String,
        cwd: String,
    ): TerminalSession = error("unused")

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

    override suspend fun ensureRunning(directory: String): TerminalSession = error("unused")

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
