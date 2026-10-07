package dev.opencode.mobile.ui.connection

import dev.opencode.mobile.domain.composer.InMemoryComposerDraftStore
import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.connection.PairingCodeRedeemException
import dev.opencode.mobile.domain.connection.PairingCodeRedeeming
import dev.opencode.mobile.domain.connection.SecureCredentialStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.connection.testConnectionSessionCoordinator
import dev.opencode.mobile.domain.event.FakeForegroundEventStream
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

private const val qrScanFailureMessage =
    "QR didn't connect. OpenCode 2's pairing API is still changing, so a scan can fail while this app is working. " +
        "Enter the host and connect. Password is optional. A pairing code works too."

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
        assertEquals(ConnectionCredentialSegment.Password, state.credentialSegment)
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
            assertEquals(
                "Enter a host",
                viewModel.uiState.value.snackbar
                    ?.text,
            )
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
            assertTrue(store.saved.last().serverUrls.isEmpty())
            assertFalse(viewModel.uiState.value.usesQrLayout)
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
            assertEquals(ConnectionCredentialSegment.Password, state.credentialSegment)
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
            assertEquals(ConnectionCredentialSegment.Password, failing.uiState.value.credentialSegment)
            assertEquals(
                "Unable to reach the server",
                failing.uiState.value.snackbar
                    ?.text,
            )
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
            assertEquals(ConnectionCredentialSegment.Password, viewModel.uiState.value.credentialSegment)
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
    fun edit_savedQrProfile_renameShowsSaveAndPersistsAddresses() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val viewModel =
                viewModel(
                    store = store,
                    probe = neverProbe(),
                )
            val serverUrls = listOf("http://192.168.0.160:4096/", "http://100.84.57.15:4096/")
            viewModel.configure(
                initialProfile =
                    ConnectionProfile(
                        baseUrl = "http://100.84.57.15:4096/",
                        username = "opencode",
                        password = "secret",
                        serverProtocol = ServerProtocol.V2,
                        id = "home",
                        name = "Home",
                        serverUrls = serverUrls,
                    ),
                mode = ConnectionSetupMode.Edit,
            )
            val before = viewModel.uiState.value
            assertTrue(before.showsPrimaryAction)
            assertEquals("Save", before.primaryActionLabel)
            assertTrue(before.usesQrLayout)
            assertTrue(before.canSubmitWithoutProbe)

            viewModel.onNameChanged("Studio")
            var saved = false
            viewModel.submit { saved = true }
            advanceUntilIdle()

            assertTrue(saved)
            assertEquals("Studio", store.saved.last().name)
            assertEquals(serverUrls, store.saved.last().serverUrls)
            assertEquals(ServerProtocol.V2, store.saved.last().serverProtocol)
            assertEquals("secret", store.saved.last().password)
        }

    @Test
    fun hostIsBlank_detectsSchemeOnly() {
        assertTrue(ConnectionSetupViewModel.hostIsBlank(""))
        assertTrue(ConnectionSetupViewModel.hostIsBlank("http://"))
        assertTrue(ConnectionSetupViewModel.hostIsBlank("https://"))
        assertFalse(ConnectionSetupViewModel.hostIsBlank("http://10.0.0.1:4096/"))
    }

    @Test
    fun applyPairingQR_usesQrLayoutWithoutPasswordField() {
        val viewModel = viewModel(probe = neverProbe())
        viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
        viewModel.applyPairingQR(
            """{"urls":["http://192.168.0.160:4096"],"username":"opencode","password":"secret"}""",
        )
        val state = viewModel.uiState.value
        assertEquals("http://192.168.0.160:4096/", state.baseUrl)
        assertEquals("opencode", state.username)
        assertEquals("secret", state.password)
        assertTrue(state.usesQrLayout)
        assertEquals(listOf("http://192.168.0.160:4096/"), state.serverUrls)
        assertEquals(listOf("Saved"), qrAddressRows(state).map { it.badge })
        assertEquals(
            "Both addresses are saved. Nothing to pick. The app uses Tailscale when it answers, " +
                "and the local address when it does not. No password.",
            QrAddressHint,
        )
        assertEquals(null, state.snackbar)
    }

    @Test
    fun applyPairingQR_fillsTailscaleHostWhenPresent() {
        val viewModel = viewModel(probe = neverProbe())
        viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
        viewModel.applyPairingQR(
            """{"urls":["http://192.168.0.160:4096","http://100.84.57.15:4096"],"username":"opencode","password":"secret"}""",
        )
        assertEquals("http://100.84.57.15:4096/", viewModel.uiState.value.baseUrl)
    }

    @Test
    fun submit_pairingQr_bothReachable_savesTailscale() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val probed = mutableListOf<String>()
            val viewModel =
                viewModel(
                    store = store,
                    probe =
                        ConnectionProbing { connection ->
                            probed += connection.baseUrl
                            ServerProtocol.V2
                        },
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.applyPairingQR(
                """{"urls":["http://192.168.0.160:4096","http://100.84.57.15:4096"],"username":"opencode","password":"secret"}""",
            )
            advanceUntilIdle()
            var saved = false
            viewModel.submit { saved = true }
            advanceUntilIdle()
            assertTrue(saved)
            assertEquals(
                setOf("http://192.168.0.160:4096/", "http://100.84.57.15:4096/"),
                probed.toSet(),
            )
            assertEquals("http://100.84.57.15:4096/", store.saved.last().baseUrl)
            assertEquals(
                listOf("http://192.168.0.160:4096/", "http://100.84.57.15:4096/"),
                store.saved.last().serverUrls,
            )
            assertEquals("secret", store.saved.last().password)
            assertTrue(viewModel.uiState.value.usesQrLayout)
            assertEquals("http://100.84.57.15:4096/", viewModel.uiState.value.baseUrl)
            val rows = qrAddressRows(viewModel.uiState.value)
            assertEquals("Using now", rows.first { it.title == "Tailscale" }.badge)
            assertEquals("Saved", rows.first { it.title == "Local network" }.badge)
            assertFalse(rows.any { it.dimmed })
        }

    @Test
    fun submit_pairingQr_tailscaleDown_savesLan() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val viewModel =
                viewModel(
                    store = store,
                    probe =
                        ConnectionProbing { connection ->
                            if (connection.baseUrl.contains("100.84.57.15")) {
                                throw ServerProbeError.Transport(IllegalStateException("tailscale down"))
                            }
                            ServerProtocol.V2
                        },
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.applyPairingQR(
                """{"urls":["http://192.168.0.160:4096","http://100.84.57.15:4096"],"username":"opencode","password":"secret"}""",
            )
            advanceUntilIdle()
            var saved = false
            viewModel.submit { saved = true }
            advanceUntilIdle()
            assertTrue(saved)
            assertEquals("http://192.168.0.160:4096/", store.saved.last().baseUrl)
            assertEquals(
                listOf("http://192.168.0.160:4096/", "http://100.84.57.15:4096/"),
                store.saved.last().serverUrls,
            )
            assertEquals("http://192.168.0.160:4096/", viewModel.uiState.value.baseUrl)
            val rows = qrAddressRows(viewModel.uiState.value)
            assertEquals("Using now", rows.first { it.title == "Local network" }.badge)
            val tailscale = rows.first { it.title == "Tailscale" }
            assertEquals("Saved", tailscale.badge)
            assertTrue(tailscale.dimmed)
            assertTrue(tailscale.subtitle.endsWith("· not reachable"))
        }

    @Test
    fun submit_pairingQr_authFailure_doesNotSave() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val viewModel =
                viewModel(
                    store = store,
                    probe =
                        ConnectionProbing {
                            throw ServerProbeError.Http(401, "Unauthorized")
                        },
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.applyPairingQR(
                """{"urls":["http://192.168.0.160:4096","http://100.84.57.15:4096"],"username":"opencode","password":"wrong"}""",
            )
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertFalse(state.usesQrLayout)
            assertTrue(state.serverUrls.isEmpty())
            assertEquals("http://100.84.57.15:4096/", state.baseUrl)
            assertEquals("wrong", state.password)
            assertEquals(ConnectionCredentialSegment.Password, state.credentialSegment)
            assertTrue(state.showsPrimaryAction)
            assertTrue(state.primaryEnabled)
            assertEquals("Connect", state.primaryActionLabel)
            assertEquals(qrScanFailureMessage, state.snackbar?.text)
            assertEquals(OpenCodeSnackbarVariant.Error, state.snackbar?.variant)
            assertTrue(store.saved.isEmpty())
        }

    @Test
    fun onBaseUrlChanged_whileServerUrlsSet_clearsServerUrls() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val probed = mutableListOf<String>()
            val viewModel =
                viewModel(
                    store = store,
                    probe =
                        ConnectionProbing { connection ->
                            probed += connection.baseUrl
                            ServerProtocol.V1
                        },
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.applyPairingQR(
                """{"urls":["http://192.168.0.160:4096","http://100.84.57.15:4096"],"username":"opencode","password":"secret"}""",
            )
            viewModel.onNameChanged("Studio")
            assertTrue(viewModel.uiState.value.usesQrLayout)
            assertEquals("Studio", viewModel.uiState.value.name)
            viewModel.onBaseUrlChanged("http://10.0.0.1:4096/")
            assertFalse(viewModel.uiState.value.usesQrLayout)
            assertTrue(viewModel.uiState.value.serverUrls.isEmpty())
            var saved = false
            viewModel.submit { saved = true }
            advanceUntilIdle()
            assertTrue(saved)
            assertEquals(listOf("http://10.0.0.1:4096/"), probed)
            assertEquals("http://10.0.0.1:4096/", store.saved.last().baseUrl)
            assertTrue(store.saved.last().serverUrls.isEmpty())
            assertEquals("secret", store.saved.last().password)
        }

    @Test
    fun applyPairingQR_codeJson_pairsAndOffersSave() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val redeem = RecordingPairingCodeRedeeming(token = "paired-token")
            val viewModel =
                viewModel(
                    store = store,
                    probe = unauthorizedUntilPassword(),
                    redeem = redeem,
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.applyPairingQR(
                """{"code":"abc_DEF-123","urls":["http://192.168.1.2:49374","http://100.64.0.3:49374"]}""",
            )
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals("http://100.64.0.3:49374/", state.baseUrl)
            assertEquals("opencode", state.username)
            assertEquals("paired-token", state.password)
            assertTrue(state.qrPairingReady)
            assertEquals("Save", state.primaryActionLabel)
            assertTrue(state.showsPrimaryAction)
            assertTrue(store.saved.isEmpty())
            assertEquals(null, state.snackbar)
            assertEquals("Using now", qrAddressRows(state).first { it.title == "Tailscale" }.badge)
        }

    @Test
    fun submit_pairingCode_bothUnauthorized_redeemsTailscaleOnce() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val redeem = RecordingPairingCodeRedeeming(token = "pairing-token")
            val viewModel =
                viewModel(
                    store = store,
                    probe = unauthorizedUntilPassword(),
                    redeem = redeem,
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.applyPairingQR(
                """{"code":"abc_DEF-123","urls":["http://192.168.1.2:49374","http://100.64.0.3:49374"]}""",
            )
            advanceUntilIdle()
            var saved = false
            viewModel.submit { saved = true }
            advanceUntilIdle()
            assertTrue(saved)
            assertEquals(listOf("http://100.64.0.3:49374/" to "abc_DEF-123"), redeem.calls)
            assertEquals("pairing-token", store.saved.last().password)
            assertEquals("http://100.64.0.3:49374/", store.saved.last().baseUrl)
            assertEquals(
                listOf("http://192.168.1.2:49374/", "http://100.64.0.3:49374/"),
                store.saved.last().serverUrls,
            )
            assertTrue(viewModel.uiState.value.usesQrLayout)
            val rows = qrAddressRows(viewModel.uiState.value)
            assertEquals("Using now", rows.first { it.title == "Tailscale" }.badge)
            assertEquals("Saved", rows.first { it.title == "Local network" }.badge)
        }

    @Test
    fun submit_pairingCode_tailscaleDown_redeemsLan() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val redeem = RecordingPairingCodeRedeeming(token = "lan-token")
            val viewModel =
                viewModel(
                    store = store,
                    probe =
                        ConnectionProbing { connection ->
                            if (!connection.password.isNullOrEmpty()) return@ConnectionProbing ServerProtocol.V2
                            if (connection.baseUrl.contains("100.64.0.3")) {
                                throw ServerProbeError.Transport(IllegalStateException("tailscale down"))
                            }
                            throw ServerProbeError.Http(401, "Unauthorized")
                        },
                    redeem = redeem,
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.applyPairingQR(
                """{"code":"abc_DEF-123","urls":["http://192.168.1.2:49374","http://100.64.0.3:49374"]}""",
            )
            advanceUntilIdle()
            var saved = false
            viewModel.submit { saved = true }
            advanceUntilIdle()
            assertTrue(saved)
            assertEquals(listOf("http://192.168.1.2:49374/" to "abc_DEF-123"), redeem.calls)
            assertEquals("lan-token", store.saved.last().password)
            assertEquals("http://192.168.1.2:49374/", store.saved.last().baseUrl)
            assertEquals(
                listOf("http://192.168.1.2:49374/", "http://100.64.0.3:49374/"),
                store.saved.last().serverUrls,
            )
        }

    @Test
    fun submit_pairingCode_allUnreachable_doesNotRedeem() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val redeem = RecordingPairingCodeRedeeming()
            val viewModel =
                viewModel(
                    store = store,
                    probe =
                        ConnectionProbing {
                            throw ServerProbeError.Transport(IllegalStateException("down"))
                        },
                    redeem = redeem,
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.applyPairingQR(
                """{"code":"abc_DEF-123","urls":["http://192.168.1.2:49374","http://100.64.0.3:49374"]}""",
            )
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertTrue(redeem.calls.isEmpty())
            assertTrue(store.saved.isEmpty())
            assertFalse(state.usesQrLayout)
            assertEquals("http://100.64.0.3:49374/", state.baseUrl)
            assertEquals("", state.password)
            assertEquals("", state.pairingCode)
            assertEquals(ConnectionCredentialSegment.Password, state.credentialSegment)
            assertTrue(state.showsPrimaryAction)
            assertTrue(state.primaryEnabled)
            assertEquals(qrScanFailureMessage, state.snackbar?.text)
            assertEquals(OpenCodeSnackbarVariant.Error, state.snackbar?.variant)
        }

    @Test
    fun submit_pairingCode_redeemExpired_showsUsedLinkMessage() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val redeem =
                RecordingPairingCodeRedeeming(
                    failure = PairingCodeRedeemException.Expired(),
                )
            val viewModel =
                viewModel(
                    store = store,
                    probe = unauthorizedUntilPassword(),
                    redeem = redeem,
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.applyPairingQR(
                """{"code":"abc_DEF-123","urls":["http://192.168.1.2:49374","http://100.64.0.3:49374"]}""",
            )
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals(1, redeem.calls.size)
            assertTrue(store.saved.isEmpty())
            assertFalse(state.usesQrLayout)
            assertEquals("http://100.64.0.3:49374/", state.baseUrl)
            assertEquals("", state.password)
            assertEquals(ConnectionCredentialSegment.Password, state.credentialSegment)
            assertTrue(state.showsPrimaryAction)
            assertEquals(qrScanFailureMessage, state.snackbar?.text)
            assertEquals(OpenCodeSnackbarVariant.Error, state.snackbar?.variant)
        }

    @Test
    fun submit_pastedConnectLink_redeemsAndSavesToken() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val redeem = RecordingPairingCodeRedeeming(token = "pasted-token")
            val viewModel =
                viewModel(
                    store = store,
                    probe = unauthorizedUntilPassword(),
                    redeem = redeem,
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.onBaseUrlChanged("http://192.168.0.183:4096/auth/connect/XJdLmg9llko9nmn3jN2XLw")
            var saved = false
            viewModel.submit { saved = true }
            advanceUntilIdle()
            assertTrue(saved)
            assertEquals(
                listOf("http://192.168.0.183:4096/" to "XJdLmg9llko9nmn3jN2XLw"),
                redeem.calls,
            )
            assertEquals("pasted-token", store.saved.last().password)
            assertEquals("http://192.168.0.183:4096/", store.saved.last().baseUrl)
            assertEquals(listOf("http://192.168.0.183:4096/"), store.saved.last().serverUrls)
            assertTrue(viewModel.uiState.value.usesQrLayout)
        }

    @Test
    fun submit_pastedConnectLink_transportFailure_restoresConnect() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val redeem = RecordingPairingCodeRedeeming()
            val viewModel =
                viewModel(
                    store = store,
                    probe =
                        ConnectionProbing {
                            throw ServerProbeError.Transport(IllegalStateException("down"))
                        },
                    redeem = redeem,
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.onBaseUrlChanged("http://192.168.0.183:4096/auth/connect/XJdLmg9llko9nmn3jN2XLw")
            var saved = false
            viewModel.submit { saved = true }
            advanceUntilIdle()
            assertFalse(saved)
            assertTrue(redeem.calls.isEmpty())
            assertTrue(store.saved.isEmpty())
            val state = viewModel.uiState.value
            assertFalse(state.usesQrLayout)
            assertTrue(state.serverUrls.isEmpty())
            assertTrue(state.showsPrimaryAction)
            assertTrue(state.primaryEnabled)
            assertEquals("Connect", state.primaryActionLabel)
            assertEquals("http://192.168.0.183:4096/", state.baseUrl)
            assertEquals("opencode", state.username)
            assertEquals("Unable to reach the server", state.snackbar?.text)
            assertEquals(OpenCodeSnackbarVariant.Error, state.snackbar?.variant)

            viewModel.consumeSnackbar()
            viewModel.submit { saved = true }
            advanceUntilIdle()
            assertFalse(saved)
            assertTrue(store.saved.isEmpty())
            assertTrue(viewModel.uiState.value.showsPrimaryAction)
            assertEquals("Connect", viewModel.uiState.value.primaryActionLabel)
            assertEquals(
                "Unable to reach the server",
                viewModel.uiState.value.snackbar?.text,
            )
        }

    @Test
    fun submit_pairingCode_typedPassword_savesReplacementWithoutSecondRedeem() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val redeem = RecordingPairingCodeRedeeming()
            val viewModel =
                viewModel(
                    store = store,
                    probe = ConnectionProbing { ServerProtocol.V2 },
                    redeem = redeem,
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.applyPairingQR(
                """{"code":"abc_DEF-123","urls":["http://192.168.1.2:49374","http://100.64.0.3:49374"]}""",
            )
            advanceUntilIdle()
            viewModel.onPasswordChanged("user-secret")
            var saved = false
            viewModel.submit { saved = true }
            advanceUntilIdle()
            assertTrue(saved)
            assertEquals(1, redeem.calls.size)
            assertEquals("user-secret", store.saved.last().password)
        }

    @Test
    fun applyPairingQR_invalid_showsSnackbar() {
        val viewModel = viewModel(probe = neverProbe())
        viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
        viewModel.applyPairingQR("nope")
        assertEquals(
            "This isn't an OpenCode 2 pairing QR",
            viewModel.uiState.value.snackbar
                ?.text,
        )
        assertEquals("", viewModel.uiState.value.baseUrl)
    }

    @Test
    fun pairingQRCameraDenied_showsSnackbar() {
        val viewModel = viewModel(probe = neverProbe())
        viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
        viewModel.pairingQRCameraDenied()
        assertEquals(
            "Allow camera access to scan the pairing QR",
            viewModel.uiState.value.snackbar
                ?.text,
        )
    }

    @Test
    fun pairingQRCameraUnavailable_showsSnackbar() {
        val viewModel = viewModel(probe = neverProbe())
        viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
        viewModel.pairingQRCameraUnavailable()
        assertEquals(
            "Camera isn't available on this device",
            viewModel.uiState.value.snackbar
                ?.text,
        )
    }

    @Test
    fun submit_passwordSegment_emptyPassword_probesAndSavesWithoutRedeem() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val redeem = RecordingPairingCodeRedeeming()
            val probed = mutableListOf<String>()
            val viewModel =
                viewModel(
                    store = store,
                    probe =
                        ConnectionProbing { connection ->
                            probed += connection.baseUrl
                            ServerProtocol.V1
                        },
                    redeem = redeem,
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.onBaseUrlChanged("http://10.0.0.1:4096/")
            viewModel.onUsernameChanged("")
            viewModel.onPasswordChanged("")
            viewModel.onCredentialSegmentChanged(ConnectionCredentialSegment.Password)
            var saved = false
            viewModel.submit { saved = true }
            advanceUntilIdle()
            assertTrue(saved)
            assertTrue(redeem.calls.isEmpty())
            assertEquals(listOf("http://10.0.0.1:4096/"), probed)
            assertEquals("opencode", store.saved.last().username)
            assertEquals("", store.saved.last().password)
            assertTrue(store.saved.last().serverUrls.isEmpty())
        }

    @Test
    fun submit_pairingCodeSegment_redeemsOnceAndSavesToken() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val redeem = RecordingPairingCodeRedeeming(token = "typed-token")
            val viewModel =
                viewModel(
                    store = store,
                    probe = unauthorizedUntilPassword(),
                    redeem = redeem,
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.onBaseUrlChanged("http://10.0.0.8:4096/")
            viewModel.onCredentialSegmentChanged(ConnectionCredentialSegment.PairingCode)
            viewModel.onPairingCodeChanged("once-code")
            var saved = false
            viewModel.submit { saved = true }
            advanceUntilIdle()
            assertTrue(saved)
            assertEquals(listOf("http://10.0.0.8:4096/" to "once-code"), redeem.calls)
            assertEquals("typed-token", store.saved.last().password)
            assertEquals("http://10.0.0.8:4096/", store.saved.last().baseUrl)
            assertTrue(store.saved.last().serverUrls.isEmpty())
        }

    @Test
    fun submit_pairingCodeSegment_customUsername_savesAndProbesAsOpencode() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val redeem = RecordingPairingCodeRedeeming(token = "typed-token")
            val probed = mutableListOf<Pair<String, String?>>()
            val viewModel =
                viewModel(
                    store = store,
                    probe =
                        ConnectionProbing { connection ->
                            probed += connection.username to connection.password
                            if (connection.password.isNullOrEmpty()) {
                                throw ServerProbeError.Http(401, "Unauthorized")
                            }
                            ServerProtocol.V2
                        },
                    redeem = redeem,
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.onUsernameChanged("alice")
            viewModel.onCredentialSegmentChanged(ConnectionCredentialSegment.PairingCode)
            viewModel.onBaseUrlChanged("http://10.0.0.8:4096/")
            viewModel.onPairingCodeChanged("once-code")
            var saved = false
            viewModel.submit { saved = true }
            advanceUntilIdle()
            assertTrue(saved)
            assertEquals(listOf("http://10.0.0.8:4096/" to "once-code"), redeem.calls)
            assertEquals("opencode", store.saved.last().username)
            val authenticated = probed.filter { !it.second.isNullOrEmpty() }
            assertEquals(1, authenticated.size)
            assertTrue(probed.all { it.first == "opencode" })
        }

    @Test
    fun submit_pairingCodeSegment_blankCode_showsSnackbarWithoutRedeem() =
        runTest(dispatcher) {
            val redeem = RecordingPairingCodeRedeeming()
            val viewModel =
                viewModel(
                    probe = neverProbe(),
                    redeem = redeem,
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.onBaseUrlChanged("http://10.0.0.8:4096/")
            viewModel.onCredentialSegmentChanged(ConnectionCredentialSegment.PairingCode)
            viewModel.onPairingCodeChanged("   ")
            var saved = false
            viewModel.submit { saved = true }
            advanceUntilIdle()
            assertFalse(saved)
            assertTrue(redeem.calls.isEmpty())
            assertEquals(
                "Enter a pairing code",
                viewModel.uiState.value.snackbar?.text,
            )
        }

    @Test
    fun submit_pairingCodeSegment_blankHost_showsEnterHost() =
        runTest(dispatcher) {
            val redeem = RecordingPairingCodeRedeeming()
            val viewModel =
                viewModel(
                    probe = neverProbe(),
                    redeem = redeem,
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.onCredentialSegmentChanged(ConnectionCredentialSegment.PairingCode)
            viewModel.onPairingCodeChanged("once-code")
            var saved = false
            viewModel.submit { saved = true }
            advanceUntilIdle()
            assertFalse(saved)
            assertTrue(redeem.calls.isEmpty())
            assertEquals("Enter a host", viewModel.uiState.value.snackbar?.text)
        }

    @Test
    fun submit_passwordSegment_afterPairingCode_doesNotRedeem() =
        runTest(dispatcher) {
            val store = RecordingConnectionProfileStore()
            val redeem = RecordingPairingCodeRedeeming()
            val viewModel =
                viewModel(
                    store = store,
                    probe = ConnectionProbing { ServerProtocol.V2 },
                    redeem = redeem,
                )
            viewModel.configure(initialProfile = null, mode = ConnectionSetupMode.Add)
            viewModel.onBaseUrlChanged("http://10.0.0.8:4096/")
            viewModel.onPasswordChanged("secret")
            viewModel.onCredentialSegmentChanged(ConnectionCredentialSegment.PairingCode)
            viewModel.onPairingCodeChanged("leftover-code")
            viewModel.onCredentialSegmentChanged(ConnectionCredentialSegment.Password)
            assertEquals("secret", viewModel.uiState.value.password)
            assertEquals("leftover-code", viewModel.uiState.value.pairingCode)
            var saved = false
            viewModel.submit { saved = true }
            advanceUntilIdle()
            assertTrue(saved)
            assertTrue(redeem.calls.isEmpty())
            assertEquals("secret", store.saved.last().password)
            assertEquals("leftover-code", viewModel.uiState.value.pairingCode)
        }

    private fun neverProbe(): ConnectionProbing = ConnectionProbing { error("probe should not run") }

    private fun unauthorizedUntilPassword(): ConnectionProbing =
        ConnectionProbing { connection ->
            if (connection.password.isNullOrEmpty()) {
                throw ServerProbeError.Http(401, "Unauthorized")
            }
            ServerProtocol.V2
        }

    private fun viewModel(
        store: RecordingConnectionProfileStore = RecordingConnectionProfileStore(),
        probe: ConnectionProbing,
        redeem: PairingCodeRedeeming = PairingCodeRedeeming { _, _ -> error("redeem should not run") },
    ): ConnectionSetupViewModel =
        ConnectionSetupViewModel(
            connectionProbeService = probe,
            connectionSessionCoordinator = coordinator(store),
            pairingCodeRedeeming = redeem,
        )

    private fun coordinator(store: ConnectionProfileStore): ConnectionSessionCoordinator {
        val activeConnectionManager = ActiveConnectionManager(store)
        return testConnectionSessionCoordinator(
            activeConnectionManager = activeConnectionManager,
            connectionProfileStore = store,
            secureCredentialStore = NoOpSecureCredentialStore(),
            sessionModelSelectionStore = NoOpSessionModelSelectionStore(),
            composerDraftStore = InMemoryComposerDraftStore(),
            httpClient = OpencodeHttpClient(json, activeConnectionManager),
            sseHttpClient = OpencodeSseHttpClient(json, activeConnectionManager),
            eventStream = FakeForegroundEventStream(),
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

private class RecordingPairingCodeRedeeming(
    private val token: String = "pairing-token",
    private val failure: PairingCodeRedeemException? = null,
) : PairingCodeRedeeming {
    val calls = mutableListOf<Pair<String, String>>()

    override suspend fun redeem(
        baseUrl: String,
        code: String,
    ): String {
        calls += baseUrl to code
        failure?.let { throw it }
        return token
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
