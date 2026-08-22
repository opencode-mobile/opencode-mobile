package dev.opencode.mobile.ui.connection

import dev.opencode.mobile.domain.composer.InMemoryComposerDraftStore
import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.connection.SecureCredentialStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import dev.opencode.mobile.domain.model.SessionModelSelection
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import dev.opencode.mobile.ui.components.OpenCodeSnackbarVariant
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
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun newForm_startsWithEmptyHostAndCollapsedCredentials() {
        val viewModel = viewModel(probe = neverProbe())
        viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
        val state = viewModel.uiState.value
        assertEquals("", state.baseUrl)
        assertEquals("Connect", state.primaryActionLabel)
        assertFalse(state.credentialsExpanded)
        assertTrue(ConnectionSetupViewModel.hostIsBlank(state.baseUrl))
    }

    @Test
    fun submit_emptyHost_showsSnackbar() =
        runTest(dispatcher) {
            val viewModel = viewModel(probe = neverProbe())
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            var saved = false
            viewModel.submit { saved = true }
            advanceUntilIdle()
            assertFalse(saved)
            assertEquals("Enter a host", viewModel.uiState.value.snackbar?.text)
        }

    @Test
    fun submit_create_probesThenSaves() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val viewModel =
                viewModel(
                    store = store,
                    probe = ConnectionProbing { ServerProtocol.V1 },
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.onBaseUrlChanged("http://10.0.0.1:4096/")
            viewModel.onPasswordChanged("secret")
            var saved = false
            viewModel.submit { saved = true }
            advanceUntilIdle()
            assertTrue(saved)
            assertEquals(1, store.saved.size)
            assertEquals(ServerProtocol.V1, store.saved.last().serverProtocol)
        }

    @Test
    fun submit_authFailure_expandsCredentialsAndSnackbars() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(
                    probe =
                        ConnectionProbing {
                            throw ServerProbeError.Http(401, "Unauthorized")
                        },
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.onBaseUrlChanged("http://10.0.0.1:4096/")
            viewModel.onPasswordChanged("wrong")
            var saved = false
            viewModel.submit { saved = true }
            advanceUntilIdle()
            assertFalse(saved)
            val state = viewModel.uiState.value
            assertTrue(state.credentialsExpanded)
            assertTrue(state.requestPasswordFocus)
            assertEquals("Authentication failed", state.snackbar?.text)
            assertEquals(OpenCodeSnackbarVariant.Error, state.snackbar?.variant)
        }

    @Test
    fun submit_transportFailure_staysCollapsed() =
        runTest(dispatcher) {
            val failing =
                viewModel(
                    probe =
                        ConnectionProbing {
                            throw ServerProbeError.Transport(IllegalStateException("down"))
                        },
                )
            failing.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            failing.onBaseUrlChanged("http://10.0.0.1:4096/")
            var saved = false
            failing.submit { saved = true }
            advanceUntilIdle()
            assertFalse(saved)
            assertFalse(failing.uiState.value.credentialsExpanded)
            assertEquals("Unable to reach the server", failing.uiState.value.snackbar?.text)
        }

    @Test
    fun revertedEdit_keepsSavedProtocol() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val viewModel =
                viewModel(
                    store = store,
                    probe = neverProbe(),
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
            assertEquals("Save", viewModel.uiState.value.primaryActionLabel)
            assertTrue(viewModel.uiState.value.canSubmitWithoutProbe)

            viewModel.onBaseUrlChanged("http://10.0.0.9:4096/")
            assertFalse(viewModel.uiState.value.canSubmitWithoutProbe)

            viewModel.onBaseUrlChanged("http://10.0.0.1:4096/")
            assertTrue(viewModel.uiState.value.canSubmitWithoutProbe)

            viewModel.submit {}
            advanceUntilIdle()

            assertEquals(ServerProtocol.V2, store.saved.last().serverProtocol)
        }

    @Test
    fun hostIsBlank_detectsSchemeOnly() {
        assertTrue(ConnectionSetupViewModel.hostIsBlank(""))
        assertTrue(ConnectionSetupViewModel.hostIsBlank("http://"))
        assertTrue(ConnectionSetupViewModel.hostIsBlank("https://"))
        assertFalse(ConnectionSetupViewModel.hostIsBlank("http://10.0.0.1:4096/"))
    }

    @Test
    fun applyPairingQR_fillsHostAndExpandsCredentials() {
        val viewModel = viewModel(probe = neverProbe())
        viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
        viewModel.applyPairingQR(
            """{"urls":["http://192.168.0.160:4096"],"username":"opencode","password":"secret"}""",
        )
        val state = viewModel.uiState.value
        assertEquals("http://192.168.0.160:4096/", state.baseUrl)
        assertEquals("opencode", state.username)
        assertEquals("secret", state.password)
        assertTrue(state.credentialsExpanded)
        assertEquals(null, state.snackbar)
    }

    @Test
    fun applyPairingQR_invalid_showsSnackbar() {
        val viewModel = viewModel(probe = neverProbe())
        viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
        viewModel.applyPairingQR("nope")
        assertEquals("This isn't an OpenCode 2 pairing QR", viewModel.uiState.value.snackbar?.text)
        assertEquals("", viewModel.uiState.value.baseUrl)
    }

    @Test
    fun pairingQRCameraDenied_showsSnackbar() {
        val viewModel = viewModel(probe = neverProbe())
        viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
        viewModel.pairingQRCameraDenied()
        assertEquals(
            "Allow camera access to scan the pairing QR",
            viewModel.uiState.value.snackbar?.text,
        )
    }

    @Test
    fun pairingQRCameraUnavailable_showsSnackbar() {
        val viewModel = viewModel(probe = neverProbe())
        viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
        viewModel.pairingQRCameraUnavailable()
        assertEquals(
            "Camera isn't available on this device",
            viewModel.uiState.value.snackbar?.text,
        )
    }

    private fun neverProbe(): ConnectionProbing = ConnectionProbing { error("probe should not run") }

    private fun viewModel(
        store: RecordingConnectionProfileStore = RecordingConnectionProfileStore(),
        probe: ConnectionProbing,
    ): ConnectionSetupViewModel =
        ConnectionSetupViewModel(
            connectionProbeService = probe,
            connectionSessionCoordinator = coordinator(store),
        )

    private fun coordinator(store: ConnectionProfileStore): ConnectionSessionCoordinator {
        val activeConnectionManager = ActiveConnectionManager(store)
        return ConnectionSessionCoordinator(
            activeConnectionManager = activeConnectionManager,
            connectionProfileStore = store,
            secureCredentialStore = NoOpSecureCredentialStore(),
            sessionModelSelectionStore = NoOpSessionModelSelectionStore(),
            composerDraftStore = InMemoryComposerDraftStore(),
            httpClient = OpencodeHttpClient(json, activeConnectionManager),
            sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
        )
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
