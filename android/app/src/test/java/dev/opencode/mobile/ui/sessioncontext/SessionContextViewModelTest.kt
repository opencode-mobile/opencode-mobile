package dev.opencode.mobile.ui.sessioncontext

import dev.opencode.mobile.domain.sessioncontext.SessionContextMessageCounts
import dev.opencode.mobile.domain.sessioncontext.SessionContextRepository
import dev.opencode.mobile.domain.sessioncontext.SessionContextSnapshot
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
            assertEquals("Loaded", viewModel.uiState.value.display?.stats?.first { it.id == "session" }?.value)
            assertEquals(false, viewModel.uiState.value.isRefreshing)
            assertEquals("Refresh failed", viewModel.uiState.value.errorMessage)
            assertNotNull(viewModel.uiState.value.display)
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
            rawMessages = emptyList(),
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
