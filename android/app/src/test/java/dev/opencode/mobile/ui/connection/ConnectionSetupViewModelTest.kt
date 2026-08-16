package dev.opencode.mobile.ui.connection

import dev.opencode.mobile.domain.connection.ConnectionProbeService
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.connection.SecureCredentialStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSetupViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun revertedEdit_keepsSavedProtocol() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val json = Json { ignoreUnknownKeys = true }
            val activeConnectionManager = ActiveConnectionManager(store)
            val viewModel =
                ConnectionSetupViewModel(
                    connectionProbeService =
                        ConnectionProbeService(
                            json = json,
                            adapter = ProjectDomainAdapter(),
                            clientFactory = { error("probe should not run") },
                        ),
                    connectionSessionCoordinator =
                        ConnectionSessionCoordinator(
                            activeConnectionManager = activeConnectionManager,
                            connectionProfileStore = store,
                            secureCredentialStore = NoOpSecureCredentialStore(),
                            sessionModelSelectionStore = NoOpSessionModelSelectionStore(),
                            httpClient = OpencodeHttpClient(json, activeConnectionManager),
                            sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
                        ),
                )
            viewModel.configure(
                initialProfile =
                    ConnectionProfile(
                        baseUrl = "http://10.0.0.1:4096/",
                        username = "opencode",
                        password = "secret",
                        serverProtocol = ServerProtocol.V2,
                        id = "home",
                        name = "Home",
                    ),
                mode = ConnectionSetupMode.Edit,
            )
            assertTrue(viewModel.uiState.value.canSave)

            viewModel.onBaseUrlChanged("http://10.0.0.9:4096/")
            assertFalse(viewModel.uiState.value.canSave)

            viewModel.onBaseUrlChanged("http://10.0.0.1:4096/")
            assertTrue(viewModel.uiState.value.canSave)

            viewModel.saveConnection {}
            advanceUntilIdle()

            assertEquals(ServerProtocol.V2, store.saved.last().serverProtocol)
        }
}

private class RecordingConnectionProfileStore : ConnectionProfileStore {
    val saved = mutableListOf<ConnectionProfile>()

    override suspend fun activeProfile(): ConnectionProfile? = saved.lastOrNull()

    override suspend fun saveProfile(profile: ConnectionProfile) {
        saved += profile
    }

    override suspend fun addedWorktrees(serverBaseUrl: String): List<String> = emptyList()

    override suspend fun addWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) = Unit

    override suspend fun removeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) = Unit

    override suspend fun setAddedWorktrees(
        serverBaseUrl: String,
        worktrees: List<String>,
    ) = Unit

    override suspend fun clearAll() {
        saved.clear()
    }
}

private class NoOpSecureCredentialStore : SecureCredentialStore {
    override fun readPassword(serverBaseUrl: String): String? = null

    override fun writePassword(
        serverBaseUrl: String,
        password: String,
    ) = Unit

    override fun deletePassword(serverBaseUrl: String) = Unit

    override fun clearAll() = Unit
}

private class NoOpSessionModelSelectionStore : SessionModelSelectionStore {
    override suspend fun loadSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ): SessionModelSelection? = null

    override suspend fun saveSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
        selection: SessionModelSelection,
    ) = Unit

    override suspend fun deleteSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ) = Unit

    override suspend fun deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionId: String,
    ) = Unit

    override suspend fun loadNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
    ): SessionModelSelection? = null

    override suspend fun saveNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        selection: SessionModelSelection,
    ) = Unit

    override suspend fun clearAll() = Unit
}
