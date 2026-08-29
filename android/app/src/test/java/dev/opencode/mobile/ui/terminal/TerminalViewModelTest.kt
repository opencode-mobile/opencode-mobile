package dev.opencode.mobile.ui.terminal

import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.terminal.TerminalConnectRequest
import dev.opencode.mobile.domain.terminal.TerminalRepository
import dev.opencode.mobile.domain.terminal.TerminalSession
import dev.opencode.mobile.domain.terminal.TerminalSessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun appearSuccess_showsLive() =
        runTest(testDispatcher) {
            val viewModel =
                TerminalViewModel(
                    FakeTerminalRepository(
                        ArrayDeque(listOf(Result.success(runningSession()))),
                    ),
                )

            viewModel.appear("/repo")
            advanceUntilIdle()

            assertEquals(TerminalUiState.Live, viewModel.uiState.value)
        }

    @Test
    fun appearThrow_showsConnectFailed() =
        runTest(testDispatcher) {
            val viewModel =
                TerminalViewModel(
                    FakeTerminalRepository(
                        ArrayDeque(listOf(Result.failure(RuntimeException("nope")))),
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
                TerminalViewModel(
                    FakeTerminalRepository(
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

            assertEquals(TerminalUiState.Live, viewModel.uiState.value)
        }

    private fun runningSession(): TerminalSession =
        TerminalSession(
            id = "pty_1",
            title = "term",
            command = "/bin/zsh",
            cwd = "/repo",
            status = TerminalSessionStatus.Running,
        )
}

private class FakeTerminalRepository(
    private val results: ArrayDeque<Result<TerminalSession>>,
) : TerminalRepository {
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

    override suspend fun ensureRunning(directory: String): TerminalSession = results.removeFirst().getOrThrow()

    override fun connectRequest(
        id: String,
        directory: String,
        protocol: ServerProtocol,
        baseUrl: String,
        cursor: String,
        ticket: String?,
        username: String,
        password: String,
    ): TerminalConnectRequest = error("unused")
}
