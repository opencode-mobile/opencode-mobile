package dev.opencode.mobile.ui.sessioncontext

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.sessioncontext.SessionContextMessageCounts
import dev.opencode.mobile.domain.sessioncontext.SessionContextRepository
import dev.opencode.mobile.domain.sessioncontext.SessionContextSnapshot
import dev.opencode.mobile.ui.text.UiText
import dev.opencode.mobile.ui.text.english
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionContextViewModelTest {
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
    fun refreshFailureKeepsLoadedSnapshotAndStoresErrorMessage() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionContextRepository(
                    results =
                        ArrayDeque(
                            listOf(
                                Result.success(snapshot("Loaded")),
                                Result.failure(RuntimeException("Refresh failed")),
                            ),
                        ),
                )
            val viewModel = SessionContextViewModel(repository)

            viewModel.bind("ses-1", "/repo")
            advanceUntilIdle()
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(SessionContextTimelineState.Ready, viewModel.uiState.value.timeline)
            assertEquals(
                "Loaded",
                viewModel.uiState.value.display
                    ?.stats
                    ?.first { it.id == "session" }
                    ?.value,
            )
            assertEquals(false, viewModel.uiState.value.isRefreshing)
            assertEquals(UiText.verbatim("Refresh failed"), viewModel.uiState.value.errorMessage)
            assertNotNull(viewModel.uiState.value.display)
        }

    @Test
    fun loadFailureWithoutAMessageFallsBackToTheAppText() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionContextRepository(
                    results = ArrayDeque(listOf(Result.failure(RuntimeException()))),
                )
            val viewModel = SessionContextViewModel(repository)

            viewModel.bind("ses-1", "/repo")
            advanceUntilIdle()

            assertEquals(SessionContextTimelineState.Error, viewModel.uiState.value.timeline)
            assertEquals(UiText.resource(R.string.session_context_load_failed), viewModel.uiState.value.errorMessage)
            assertEquals("Unable to load session context", viewModel.uiState.value.errorMessage?.english())
        }

    @Test
    fun statLabelsAreAppTextAndValuesStayVerbatim() =
        runTest(testDispatcher) {
            val repository =
                FakeSessionContextRepository(results = ArrayDeque(listOf(Result.success(snapshot("Loaded")))))
            val viewModel = SessionContextViewModel(repository)

            viewModel.bind("ses-1", "/repo")
            advanceUntilIdle()

            val stats = viewModel.uiState.value.display?.stats.orEmpty()
            val session = stats.first { it.id == "session" }
            assertEquals(UiText.resource(R.string.session_context_stat_session), session.label)
            assertEquals("Loaded", session.value)
            assertEquals("Cache Tokens (read/write)", stats.first { it.id == "cache" }.label.english())
            assertEquals("Last Activity", stats.first { it.id == "activity" }.label.english())
        }

    private fun snapshot(title: String): SessionContextSnapshot =
        SessionContextSnapshot(
            sessionId = "ses-1",
            sessionTitle = title,
            providerLabel = "Provider",
            modelLabel = "Model",
            tokenStats = null,
            messageCounts = SessionContextMessageCounts(total = 0, user = 0, assistant = 0),
            totalCost = 0.0,
            createdAtEpochMs = null,
            lastActivityEpochMs = null,
            breakdown = emptyList(),
        )
}

private class FakeSessionContextRepository(
    private val results: ArrayDeque<Result<SessionContextSnapshot>>,
) : SessionContextRepository {
    override suspend fun loadSessionContext(
        sessionId: String,
        directory: String?,
    ): SessionContextSnapshot = results.removeFirst().getOrThrow()
}
