package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.network.ServerConnection
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
class MainScreenViewModelTest {
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
  fun uiState_loadsSessions_success() = runTest {
    val viewModel =
      MainScreenViewModel(
        sessionRepository = FakeSessionRepository(),
        sessionDomainAdapter = dev.opencode.mobile.domain.session.SessionDomainAdapter(),
        serverConnection = ServerConnection(baseUrl = "http://127.0.0.1:4096/"),
      )
    advanceUntilIdle()
    val state = viewModel.uiState.value
    assertTrue(state is MainScreenUiState.Success)
    assertEquals(
      listOf(SessionSummary(id = "1", title = "Alpha")),
      (state as MainScreenUiState.Success).sessions,
    )
  }

  @Test
  fun uiState_loadSessions_error() = runTest {
    val viewModel =
      MainScreenViewModel(
        sessionRepository = FakeSessionRepository(shouldFail = true),
        sessionDomainAdapter = dev.opencode.mobile.domain.session.SessionDomainAdapter(),
        serverConnection = ServerConnection(baseUrl = "http://127.0.0.1:4096/"),
      )
    advanceUntilIdle()
    assertTrue(viewModel.uiState.value is MainScreenUiState.Error)
  }
}

private class FakeSessionRepository(
  private val shouldFail: Boolean = false,
) : SessionRepository {
  override suspend fun listSessions(): List<Session> {
    if (shouldFail) error("network")
    return listOf(Session(id = "1", title = "Alpha"))
  }

  override suspend fun createSession(parentId: String?, title: String?) = error("unused")

  override suspend fun getAllSessionStatuses() = error("unused")

  override suspend fun getSession(id: String) = error("unused")

  override suspend fun updateSession(id: String, title: String?) = error("unused")

  override suspend fun deleteSession(id: String) = error("unused")

  override suspend fun listChildSessions(id: String) = error("unused")

  override suspend fun listSessionTodos(id: String) = error("unused")

  override suspend fun initializeSessionAgents(
    id: String,
    messageId: String,
    providerId: String,
    modelId: String,
  ) = error("unused")

  override suspend fun forkSession(id: String, atMessageId: String?) = error("unused")

  override suspend fun abortSession(id: String) = error("unused")

  override suspend fun shareSession(id: String) = error("unused")

  override suspend fun unshareSession(id: String) = error("unused")

  override suspend fun getSessionDiff(id: String, messageId: String?) = error("unused")

  override suspend fun summarizeSession(id: String, providerId: String, modelId: String) = error("unused")

  override suspend fun revertSession(id: String, messageId: String, partId: String?) = error("unused")

  override suspend fun unrevertSession(id: String) = error("unused")

  override suspend fun respondToPermission(
    sessionId: String,
    permissionId: String,
    decision: dev.opencode.mobile.domain.session.PermissionDecision,
  ) = error("unused")
}
