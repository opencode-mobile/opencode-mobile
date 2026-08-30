package dev.opencode.mobile.ui.terminal

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.terminal.RunningTerminal
import dev.opencode.mobile.domain.terminal.TerminalConnectRequest
import dev.opencode.mobile.domain.terminal.TerminalError
import dev.opencode.mobile.domain.terminal.TerminalRepository
import dev.opencode.mobile.domain.terminal.TerminalSession
import dev.opencode.mobile.domain.terminal.TerminalSessionStatus
import dev.opencode.mobile.domain.terminal.TerminalSocketClient
import dev.opencode.mobile.domain.terminal.TerminalSocketListener
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
class TerminalViewModelTest {
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
    fun appearSuccess_showsLiveWithSessionId() =
        runTest(testDispatcher) {
            val socket = FakeTerminalSocketClient()
            val viewModel = viewModel(socket = socket)

            viewModel.appear("/repo")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is TerminalUiState.Live)
            assertEquals("pty_1", (state as TerminalUiState.Live).session.id)
        }

    @Test
    fun appearWithPtyId_connectsThatIdWithoutEnsureRunning() =
        runTest(testDispatcher) {
            val repository =
                FakeTerminalRepository(
                    ensureResults = ArrayDeque(),
                    getResults = ArrayDeque(listOf(Result.success(runningSession("pty_specific")))),
                )
            val viewModel = viewModel(repository = repository)

            viewModel.appear("/repo", ptyId = "pty_specific")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is TerminalUiState.Live)
            assertEquals("pty_specific", (state as TerminalUiState.Live).session.id)
            assertEquals(0, repository.ensureRunningCalls)
            assertEquals(listOf("pty_specific" to "/repo"), repository.getCalls)
        }

    @Test
    fun appearWithoutPtyId_stillEnsureRunning() =
        runTest(testDispatcher) {
            val repository =
                FakeTerminalRepository(
                    ensureResults = ArrayDeque(listOf(Result.success(runningSession()))),
                )
            val viewModel = viewModel(repository = repository)

            viewModel.appear("/repo")
            advanceUntilIdle()

            assertEquals(1, repository.ensureRunningCalls)
            assertTrue(repository.getCalls.isEmpty())
        }

    @Test
    fun appearThrow_showsConnectFailed() =
        runTest(testDispatcher) {
            val viewModel =
                viewModel(
                    repository =
                        FakeTerminalRepository(
                            ensureResults = ArrayDeque(listOf(Result.failure(RuntimeException("nope")))),
                        ),
                )

            viewModel.appear("/repo")
            advanceUntilIdle()

            assertEquals(TerminalUiState.ConnectFailed, viewModel.uiState.value)
        }

    @Test
    fun retryAfterFail_showsLive() =
        runTest(testDispatcher) {
            val viewModel =
                viewModel(
                    repository =
                        FakeTerminalRepository(
                            ensureResults =
                                ArrayDeque(
                                    listOf(
                                        Result.failure(RuntimeException("nope")),
                                        Result.success(runningSession()),
                                    ),
                                ),
                        ),
                )

            viewModel.appear("/repo")
            advanceUntilIdle()
            viewModel.retry()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is TerminalUiState.Live)
            assertEquals("pty_1", (state as TerminalUiState.Live).session.id)
        }

    @Test
    fun disappear_closesSocket() =
        runTest(testDispatcher) {
            val socket = FakeTerminalSocketClient()
            val viewModel = viewModel(socket = socket)

            viewModel.appear("/repo")
            advanceUntilIdle()
            viewModel.disappear()

            assertTrue(socket.closed)
        }

    @Test
    fun abnormalClose_getRunning_showsReconnecting() =
        runTest(testDispatcher) {
            val socket = FakeTerminalSocketClient()
            val viewModel =
                viewModel(
                    repository =
                        FakeTerminalRepository(
                            ensureResults = ArrayDeque(listOf(Result.success(runningSession()))),
                            getResults = ArrayDeque(listOf(Result.success(runningSession()))),
                        ),
                    socket = socket,
                )

            viewModel.appear("/repo")
            advanceUntilIdle()
            socket.autoOpen = false
            socket.abnormalClose()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state is TerminalUiState.Reconnecting)
            assertEquals("pty_1", (state as TerminalUiState.Reconnecting).session.id)
        }

    @Test
    fun abnormalClose_getExited_showsExited() =
        runTest(testDispatcher) {
            val socket = FakeTerminalSocketClient()
            val viewModel =
                viewModel(
                    repository =
                        FakeTerminalRepository(
                            ensureResults = ArrayDeque(listOf(Result.success(runningSession()))),
                            getResults =
                                ArrayDeque(
                                    listOf(
                                        Result.success(runningSession().copy(status = TerminalSessionStatus.Exited)),
                                    ),
                                ),
                        ),
                    socket = socket,
                )

            viewModel.appear("/repo")
            advanceUntilIdle()
            socket.abnormalClose()
            advanceUntilIdle()

            assertEquals(TerminalUiState.Exited, viewModel.uiState.value)
        }

    @Test
    fun abnormalClose_getNotFound_showsExited() =
        runTest(testDispatcher) {
            val socket = FakeTerminalSocketClient()
            val viewModel =
                viewModel(
                    repository =
                        FakeTerminalRepository(
                            ensureResults = ArrayDeque(listOf(Result.success(runningSession()))),
                            getResults =
                                ArrayDeque(
                                    listOf(Result.failure(TerminalError.Http(statusCode = 404, message = "gone"))),
                                ),
                        ),
                    socket = socket,
                )

            viewModel.appear("/repo")
            advanceUntilIdle()
            socket.abnormalClose()
            advanceUntilIdle()

            assertEquals(TerminalUiState.Exited, viewModel.uiState.value)
        }

    private suspend fun viewModel(
        repository: FakeTerminalRepository =
            FakeTerminalRepository(
                ensureResults = ArrayDeque(listOf(Result.success(runningSession()))),
            ),
        socket: FakeTerminalSocketClient = FakeTerminalSocketClient(),
    ): TerminalViewModel {
        val manager = ActiveConnectionManager(FakeProfileStore())
        manager.applyProfile(
            ConnectionProfile(
                baseUrl = "http://127.0.0.1:4096/",
                username = "opencode",
                password = "secret",
            ),
            serverProtocol = ServerProtocol.V1,
        )
        return TerminalViewModel(
            terminalRepository = repository,
            activeConnectionManager = manager,
            socketClient = socket,
        )
    }

    private fun runningSession(id: String = "pty_1"): TerminalSession =
        TerminalSession(
            id = id,
            title = "term",
            command = "/bin/zsh",
            cwd = "/repo",
            status = TerminalSessionStatus.Running,
        )
}

private class FakeTerminalSocketClient(
    var autoOpen: Boolean = true,
) : TerminalSocketClient {
    var listener: TerminalSocketListener? = null
    var closed: Boolean = false

    override fun open(
        url: String,
        listener: TerminalSocketListener,
    ) {
        closed = false
        this.listener = listener
        if (autoOpen) listener.onOpen()
    }

    override fun sendText(text: String) = Unit

    override fun close() {
        closed = true
    }

    fun abnormalClose(code: Int = 1006) {
        listener?.onClosed(code)
    }
}

private class FakeTerminalRepository(
    private val ensureResults: ArrayDeque<Result<TerminalSession>>,
    private val getResults: ArrayDeque<Result<TerminalSession>> = ArrayDeque(),
) : TerminalRepository {
    var ensureRunningCalls = 0
    val getCalls = mutableListOf<Pair<String, String>>()

    override suspend fun list(directory: String): List<TerminalSession> = error("unused")

    override suspend fun create(
        directory: String,
        cwd: String,
    ): TerminalSession = error("unused")

    override suspend fun get(
        id: String,
        directory: String,
    ): TerminalSession {
        getCalls += id to directory
        return getResults.removeFirst().getOrThrow()
    }

    override suspend fun updateSize(
        id: String,
        directory: String,
        cols: Int,
        rows: Int,
    ): TerminalSession = error("unused")

    override suspend fun ensureRunning(directory: String): TerminalSession {
        ensureRunningCalls += 1
        return ensureResults.removeFirst().getOrThrow()
    }

    override suspend fun remove(
        id: String,
        directory: String,
    ) = error("unused")

    override suspend fun listRunning(directories: List<String>): List<RunningTerminal> = error("unused")

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

private class FakeProfileStore : ConnectionProfileStore {
    private var profile: ConnectionProfile? = null

    override suspend fun activeProfile(): ConnectionProfile? = profile

    override suspend fun saveProfile(profile: ConnectionProfile) {
        this.profile = profile
    }

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = emptyList()

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
