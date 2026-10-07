package dev.opencode.mobile.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.data.local.ServerConnectionDefaults
import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.connection.DuplicateConnectionProfileException
import dev.opencode.mobile.domain.connection.PairingCodeRedeemException
import dev.opencode.mobile.domain.connection.PairingCodeRedeeming
import dev.opencode.mobile.domain.connection.PairingQRCredentials
import dev.opencode.mobile.domain.connection.PairingQRError
import dev.opencode.mobile.domain.connection.PairingQRParseResult
import dev.opencode.mobile.domain.connection.PairingQRPayload
import dev.opencode.mobile.domain.connection.ProbeStatus
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.connection.toProbeStatus
import dev.opencode.mobile.domain.connection.userMessage
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.network.ServerConnection
import dev.opencode.mobile.ui.components.OpenCodeSnackbarMessage
import dev.opencode.mobile.ui.components.OpenCodeSnackbarVariant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ConnectionSetupState {
    Idle,
    Connecting,
    Saving,
}

enum class ConnectionSetupMode {
    First,
    Add,
    Edit,
}

enum class ConnectionCredentialSegment {
    Password,
    PairingCode,
}

private enum class ConnectFailureOrigin {
    Manual,
    ScannedQr,
}

data class ConnectionSetupUiState(
    val name: String = "",
    val baseUrl: String = "",
    val username: String = ServerConnectionDefaults.USERNAME,
    val password: String = "",
    val state: ConnectionSetupState = ConnectionSetupState.Idle,
    val mode: ConnectionSetupMode = ConnectionSetupMode.First,
    val profileId: String = "",
    val connectionUnchanged: Boolean = false,
    val credentialSegment: ConnectionCredentialSegment = ConnectionCredentialSegment.Password,
    val pairingCode: String = "",
    val requestPasswordFocus: Boolean = false,
    val snackbar: OpenCodeSnackbarMessage? = null,
    val serverUrls: List<String> = emptyList(),
    val addressProbeFinished: Boolean = false,
    val unreachableUrls: Set<String> = emptySet(),
    val chosenUrl: String? = null,
    val qrPairingReady: Boolean = false,
    /** Camera scan still pairing. A saved address list is not a scan. */
    val qrScanPending: Boolean = false,
) {
    val usesQrLayout: Boolean
        get() = serverUrls.isNotEmpty()

    val screenTitle: String
        get() =
            when (mode) {
                ConnectionSetupMode.First -> "Connect to server"
                ConnectionSetupMode.Add -> "Add server"
                ConnectionSetupMode.Edit -> "Edit server"
            }

    val primaryActionLabel: String
        get() =
            when {
                state == ConnectionSetupState.Connecting ->
                    if (mode == ConnectionSetupMode.Edit) "Saving…" else "Connecting…"
                state == ConnectionSetupState.Saving -> "Saving…"
                mode == ConnectionSetupMode.Edit || qrPairingReady -> "Save"
                else -> "Connect"
            }

    /** A camera scan hides Save until pairing succeeds. Editing a saved QR profile, and a failed paste, keep the button. */
    val showsPrimaryAction: Boolean
        get() = !qrScanPending || isBusy || qrPairingReady

    val canRemove: Boolean
        get() = mode == ConnectionSetupMode.Edit && profileId.isNotBlank()

    /** Rename-only edit can save without a probe. Create/dirty always use primary. */
    val canSubmitWithoutProbe: Boolean
        get() = connectionUnchanged

    val isBusy: Boolean
        get() = state == ConnectionSetupState.Connecting || state == ConnectionSetupState.Saving

    val primaryEnabled: Boolean
        get() = !isBusy
}

@HiltViewModel
class ConnectionSetupViewModel
    @Inject
    constructor(
        private val connectionProbeService: ConnectionProbing,
        private val connectionSessionCoordinator: ConnectionSessionCoordinator,
        private val pairingCodeRedeeming: PairingCodeRedeeming,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ConnectionSetupUiState())
        val uiState: StateFlow<ConnectionSetupUiState> = _uiState.asStateFlow()

        // Field edits fire on every keystroke; report only the first edit per field
        // per screen appearance so analytics stays a signal of engagement, not volume.
        private val reportedFieldEdits = mutableSetOf<String>()
        private var isEditingExistingProfile = false
        private var detectedProtocol: ServerProtocol? = null
        private var savedBaseUrl = ""
        private var savedUsername = ""
        private var savedPassword = ""
        private var savedProtocol: ServerProtocol? = null
        private var pendingPairingCode: String? = null
        private var scannedQrPassword: String = ""
        private var addressProbeGeneration = 0
        /** Paste applied an address list that this session has not saved yet. */
        private var pastedConnectLinkUnconfirmed = false

        fun configure(
            initialProfile: ConnectionProfile?,
            mode: ConnectionSetupMode = if (initialProfile == null) ConnectionSetupMode.First else ConnectionSetupMode.Edit,
        ) {
            reportedFieldEdits.clear()
            pendingPairingCode = null
            scannedQrPassword = ""
            pastedConnectLinkUnconfirmed = false
            addressProbeGeneration += 1
            isEditingExistingProfile = initialProfile != null && mode == ConnectionSetupMode.Edit
            detectedProtocol = initialProfile?.serverProtocol
            if (initialProfile == null || mode != ConnectionSetupMode.Edit) {
                savedBaseUrl = ""
                savedUsername = ""
                savedPassword = ""
                savedProtocol = null
                _uiState.value = ConnectionSetupUiState(mode = mode)
            } else {
                savedBaseUrl = initialProfile.baseUrl
                savedUsername = initialProfile.username
                savedPassword = initialProfile.password
                savedProtocol = initialProfile.serverProtocol
                _uiState.value =
                    ConnectionSetupUiState(
                        name = initialProfile.name,
                        baseUrl = initialProfile.baseUrl,
                        username = initialProfile.username,
                        password = initialProfile.password,
                        mode = ConnectionSetupMode.Edit,
                        profileId = initialProfile.id,
                        connectionUnchanged = true,
                        serverUrls = initialProfile.serverUrls,
                    )
            }
            if (_uiState.value.serverUrls.isNotEmpty()) {
                startAddressProbe()
            }
        }

        fun configureFromCurrentProfile() {
            configure(connectionSessionCoordinator.currentProfile(), ConnectionSetupMode.Edit)
        }

        fun onNameChanged(value: String) {
            val current = _uiState.value
            _uiState.value = current.copy(name = value)
        }

        fun onBaseUrlChanged(value: String) {
            reportFieldEdit(ProductAnalytics.Action.CONNECTION_EDIT_BASE_URL)
            if (_uiState.value.serverUrls.isNotEmpty()) {
                addressProbeGeneration += 1
                pendingPairingCode = null
                pastedConnectLinkUnconfirmed = false
                _uiState.value =
                    _uiState.value.copy(
                        serverUrls = emptyList(),
                        addressProbeFinished = false,
                        unreachableUrls = emptySet(),
                        chosenUrl = null,
                        qrPairingReady = false,
                        qrScanPending = false,
                        credentialSegment = ConnectionCredentialSegment.Password,
                    )
            }
            updateFields(baseUrl = value)
        }

        fun onUsernameChanged(value: String) {
            reportFieldEdit(ProductAnalytics.Action.CONNECTION_EDIT_USERNAME)
            updateFields(username = value)
        }

        fun onPasswordChanged(value: String) {
            reportFieldEdit(ProductAnalytics.Action.CONNECTION_EDIT_PASSWORD)
            if (value.isNotEmpty()) {
                pendingPairingCode = null
            }
            updateFields(password = value)
        }

        fun onCredentialSegmentChanged(segment: ConnectionCredentialSegment) {
            _uiState.value = _uiState.value.copy(credentialSegment = segment)
        }

        fun onPairingCodeChanged(value: String) {
            _uiState.value = _uiState.value.copy(pairingCode = value)
        }

        fun consumePasswordFocusRequest() {
            if (_uiState.value.requestPasswordFocus) {
                _uiState.value = _uiState.value.copy(requestPasswordFocus = false)
            }
        }

        fun consumeSnackbar() {
            if (_uiState.value.snackbar != null) {
                _uiState.value = _uiState.value.copy(snackbar = null)
            }
        }

        fun applyPairingQR(raw: String) {
            when (val result = PairingQRPayload.parse(raw)) {
                is PairingQRParseResult.Ready -> {
                    logAction(
                        ProductAnalytics.Action.CONNECTION_PAIRING_QR_SCAN,
                        mapOf(ProductAnalytics.Param.OUTCOME to ProductAnalytics.Outcome.SUCCESS),
                    )
                    applyPairingCredentials(result.credentials, scheduleProbe = false)
                    pastedConnectLinkUnconfirmed = false
                    _uiState.value = _uiState.value.copy(qrScanPending = true)
                    pairScannedQr()
                }
                is PairingQRParseResult.Failed -> {
                    logAction(
                        ProductAnalytics.Action.CONNECTION_PAIRING_QR_SCAN,
                        mapOf(ProductAnalytics.Param.OUTCOME to ProductAnalytics.Outcome.FAILURE),
                    )
                    showError(pairingErrorText(result.error))
                }
            }
        }

        fun pairingQRCameraDenied() {
            showError("Allow camera access to scan the pairing QR")
        }

        fun pairingQRCameraUnavailable() {
            showError("Camera isn't available on this device")
        }

        private fun reportFieldEdit(actionId: String) {
            if (!reportedFieldEdits.add(actionId)) return
            logAction(actionId)
        }

        private fun logAction(
            actionId: String,
            parameters: Map<String, String> = emptyMap(),
        ) {
            ProductAnalytics.logUserAction(
                screenId = ProductAnalytics.Screen.CONNECTION_SETUP,
                actionId = actionId,
                parameters = parameters,
            )
        }

        private fun updateFields(
            name: String = _uiState.value.name,
            baseUrl: String = _uiState.value.baseUrl,
            username: String = _uiState.value.username,
            password: String = _uiState.value.password,
        ) {
            val current = _uiState.value
            val unchanged = isConnectionUnchanged(baseUrl, username, password)
            if (current.state == ConnectionSetupState.Idle || current.state == ConnectionSetupState.Connecting) {
                detectedProtocol = if (unchanged) savedProtocol else null
            }
            _uiState.value =
                current.copy(
                    name = name,
                    baseUrl = baseUrl,
                    username = username,
                    password = password,
                    connectionUnchanged = unchanged,
                )
        }

        /**
         * Primary CTA: rename-only Save skips probe; otherwise probe then save (Connect / dirty Save).
         */
        fun submit(onSaved: () -> Unit) {
            if (_uiState.value.isBusy) return
            if (_uiState.value.qrScanPending && !_uiState.value.qrPairingReady) return
            if (_uiState.value.qrPairingReady) {
                persist(onSaved)
                return
            }
            if (hostIsBlank(_uiState.value.baseUrl)) {
                showError("Enter a host")
                return
            }
            if (PairingQRPayload.isConnectLink(_uiState.value.baseUrl)) {
                if (!applySubmittedConnectLink()) return
                val redeeming = pendingPairingCode != null && _uiState.value.password.isEmpty()
                if (_uiState.value.canSubmitWithoutProbe && !redeeming) {
                    persist(onSaved)
                    return
                }
                probeThenSave(onSaved)
                return
            }
            when (_uiState.value.credentialSegment) {
                ConnectionCredentialSegment.Password -> {
                    if (_uiState.value.canSubmitWithoutProbe) {
                        persist(onSaved)
                        return
                    }
                    probeThenSave(onSaved, redeemPendingCode = false)
                }
                ConnectionCredentialSegment.PairingCode -> {
                    val code = _uiState.value.pairingCode.trim()
                    if (code.isEmpty()) {
                        showError("Enter a pairing code")
                        return
                    }
                    // Typed codes redeem for user opencode. The username field is hidden here.
                    updateFields(username = ServerConnectionDefaults.USERNAME)
                    redeemTypedPairingCode(code, onSaved)
                }
            }
        }

        private fun probeThenSave(
            onSaved: () -> Unit,
            redeemPendingCode: Boolean = true,
            origin: ConnectFailureOrigin = ConnectFailureOrigin.Manual,
        ) {
            logAction(ProductAnalytics.Action.CONNECTION_TEST)
            val generation = ++addressProbeGeneration
            viewModelScope.launch {
                if (generation != addressProbeGeneration) return@launch
                _uiState.value =
                    _uiState.value.copy(
                        state = ConnectionSetupState.Connecting,
                        snackbar = null,
                        addressProbeFinished = false,
                        unreachableUrls = emptySet(),
                        chosenUrl = null,
                    )
                val pendingCode = pendingPairingCode
                if (redeemPendingCode && pendingCode != null && _uiState.value.password.isEmpty()) {
                    redeemAnsweredHost(pendingCode, generation, onSaved, origin = origin)
                    return@launch
                }
                val summary = probeAddresses(urlsToProbe())
                if (generation != addressProbeGeneration) return@launch
                publishAddressProbe(summary)
                val winner = summary.winner
                val winnerOutcome = winner?.let { summary.outcomeFor(it) }
                if (winnerOutcome is ProbeOutcome.Reachable) {
                    detectedProtocol = winnerOutcome.protocol
                    persistAfterProbe(onSaved)
                    return@launch
                }
                detectedProtocol = null
                if (winnerOutcome is ProbeOutcome.Failed) {
                    handleProbeFailure(winnerOutcome.error, origin)
                    return@launch
                }
                val failed = summary.outcomes.mapNotNull { (it as? ProbeOutcome.Failed)?.error }
                val authError = failed.firstOrNull { it.toProbeStatus() == ProbeStatus.AuthFailed }
                handleProbeFailure(
                    authError
                        ?: failed.lastOrNull()
                        ?: ServerProbeError.Transport(IllegalStateException("Unable to reach the server")),
                    origin,
                )
            }
        }

        private fun redeemTypedPairingCode(
            code: String,
            onSaved: () -> Unit,
        ) {
            logAction(ProductAnalytics.Action.CONNECTION_TEST)
            val generation = ++addressProbeGeneration
            viewModelScope.launch {
                if (generation != addressProbeGeneration) return@launch
                _uiState.value =
                    _uiState.value.copy(
                        state = ConnectionSetupState.Connecting,
                        snackbar = null,
                        addressProbeFinished = false,
                        unreachableUrls = emptySet(),
                        chosenUrl = null,
                    )
                redeemAnsweredHost(
                    code,
                    generation,
                    onSaved,
                    persistOnSuccess = true,
                    origin = ConnectFailureOrigin.Manual,
                )
            }
        }

        private fun applyPairingCredentials(
            credentials: PairingQRCredentials,
            scheduleProbe: Boolean = true,
        ) {
            scannedQrPassword = credentials.password
            pendingPairingCode = credentials.code
            updateFields(
                baseUrl = credentials.baseUrl,
                username = credentials.username,
                password = credentials.password,
            )
            _uiState.value =
                _uiState.value.copy(
                    serverUrls = ConnectionProfileIdentity.normalizeServerUrls(credentials.urls),
                    snackbar = null,
                    addressProbeFinished = false,
                    unreachableUrls = emptySet(),
                    chosenUrl = null,
                    qrPairingReady = false,
                )
            if (scheduleProbe) startAddressProbe()
        }

        private fun pairScannedQr() {
            val generation = ++addressProbeGeneration
            viewModelScope.launch {
                if (generation != addressProbeGeneration) return@launch
                _uiState.value =
                    _uiState.value.copy(
                        state = ConnectionSetupState.Connecting,
                        snackbar = null,
                        qrPairingReady = false,
                    )
                val pendingCode = pendingPairingCode
                if (pendingCode != null && _uiState.value.password.isEmpty()) {
                    redeemAnsweredHost(
                        pendingCode,
                        generation,
                        onSaved = {},
                        persistOnSuccess = false,
                        origin = ConnectFailureOrigin.ScannedQr,
                    )
                    return@launch
                }
                val summary = probeAddresses(urlsToProbe())
                if (generation != addressProbeGeneration) return@launch
                publishAddressProbe(summary)
                val winnerOutcome = summary.winner?.let { summary.outcomeFor(it) }
                if (winnerOutcome is ProbeOutcome.Reachable) {
                    detectedProtocol = winnerOutcome.protocol
                    _uiState.value =
                        _uiState.value.copy(
                            state = ConnectionSetupState.Idle,
                            qrPairingReady = true,
                            qrScanPending = false,
                        )
                    return@launch
                }
                detectedProtocol = null
                if (winnerOutcome is ProbeOutcome.Failed) {
                    handleProbeFailure(winnerOutcome.error, ConnectFailureOrigin.ScannedQr)
                    return@launch
                }
                val failed = summary.outcomes.mapNotNull { (it as? ProbeOutcome.Failed)?.error }
                val authError = failed.firstOrNull { it.toProbeStatus() == ProbeStatus.AuthFailed }
                handleProbeFailure(
                    authError
                        ?: failed.lastOrNull()
                        ?: ServerProbeError.Transport(IllegalStateException("Unable to reach the server")),
                    ConnectFailureOrigin.ScannedQr,
                )
            }
        }

        private fun applySubmittedConnectLink(): Boolean {
            when (val result = PairingQRPayload.parse(_uiState.value.baseUrl.trim())) {
                is PairingQRParseResult.Ready -> {
                    applyPairingCredentials(result.credentials)
                    pastedConnectLinkUnconfirmed = true
                    return true
                }
                is PairingQRParseResult.Failed -> {
                    showError(pairingErrorText(result.error))
                    return false
                }
            }
        }

        private fun pairingErrorText(error: PairingQRError): String =
            when (error) {
                PairingQRError.InvalidPayload -> "This isn't an OpenCode 2 pairing QR"
                PairingQRError.NoReachableUrl -> "That QR only has a localhost address"
            }

        private suspend fun redeemAnsweredHost(
            code: String,
            generation: Int,
            onSaved: () -> Unit,
            persistOnSuccess: Boolean = true,
            origin: ConnectFailureOrigin = ConnectFailureOrigin.Manual,
        ) {
            val summary = probeAddresses(urlsToProbe())
            if (generation != addressProbeGeneration) return
            publishAddressProbe(summary)
            val winner = summary.winner
            if (winner == null) {
                detectedProtocol = null
                handleProbeFailure(
                    ServerProbeError.Transport(IllegalStateException("Unable to reach the server")),
                    origin,
                )
                return
            }
            val token =
                try {
                    pairingCodeRedeeming.redeem(winner, code)
                } catch (error: PairingCodeRedeemException) {
                    detectedProtocol = null
                    when (error) {
                        is PairingCodeRedeemException.Expired ->
                            if (origin == ConnectFailureOrigin.ScannedQr) {
                                presentScannedQrFailure()
                            } else {
                                dropUnconfirmedPastedLayout()
                                _uiState.value =
                                    _uiState.value.copy(
                                        state = ConnectionSetupState.Idle,
                                        snackbar =
                                            OpenCodeSnackbarMessage(
                                                text = "This pairing link expired or was already used",
                                                variant = OpenCodeSnackbarVariant.Error,
                                            ),
                                    )
                            }
                        is PairingCodeRedeemException.Unreachable ->
                            handleProbeFailure(
                                ServerProbeError.Transport(
                                    error.cause ?: IllegalStateException("Unable to reach the server"),
                                ),
                                origin,
                            )
                    }
                    return
                }
            if (generation != addressProbeGeneration) return
            _uiState.value = _uiState.value.copy(password = token, baseUrl = winner)
            when (val authed = probeOutcome(winner)) {
                is ProbeOutcome.Reachable -> {
                    detectedProtocol = authed.protocol
                    if (persistOnSuccess) {
                        persistAfterProbe(onSaved)
                    } else {
                        pendingPairingCode = null
                        _uiState.value =
                            _uiState.value.copy(
                                state = ConnectionSetupState.Idle,
                                qrPairingReady = true,
                                qrScanPending = false,
                            )
                    }
                }
                is ProbeOutcome.Failed -> {
                    detectedProtocol = null
                    handleProbeFailure(authed.error, origin)
                }
            }
        }

        private fun answeredHost(outcome: ProbeOutcome): String? =
            when (outcome) {
                is ProbeOutcome.Reachable -> outcome.url
                is ProbeOutcome.Failed -> {
                    val http = outcome.error as? ServerProbeError.Http
                    if (http != null && (http.statusCode == 401 || http.statusCode == 403)) {
                        outcome.url
                    } else {
                        null
                    }
                }
            }

        private suspend fun probeOutcome(url: String): ProbeOutcome =
            try {
                ProbeOutcome.Reachable(url, connectionProbeService.probe(draftConnection(url)))
            } catch (error: ServerProbeError) {
                ProbeOutcome.Failed(url, error)
            } catch (error: Throwable) {
                ProbeOutcome.Failed(url, ServerProbeError.Transport(error))
            }

        private sealed interface ProbeOutcome {
            val url: String

            data class Reachable(
                override val url: String,
                val protocol: ServerProtocol,
            ) : ProbeOutcome

            data class Failed(
                override val url: String,
                val error: ServerProbeError,
            ) : ProbeOutcome
        }

        private data class AddressProbeSummary(
            val outcomes: List<ProbeOutcome>,
            val answered: List<String>,
            val winner: String?,
        ) {
            val unreachable: Set<String>
                get() {
                    val answeredSet = answered.map(ConnectionProfileIdentity::normalizeBaseUrl).toSet()
                    return outcomes
                        .map { ConnectionProfileIdentity.normalizeBaseUrl(it.url) }
                        .filter { it !in answeredSet }
                        .toSet()
                }

            fun outcomeFor(url: String): ProbeOutcome? {
                val normalized = ConnectionProfileIdentity.normalizeBaseUrl(url)
                return outcomes.firstOrNull { ConnectionProfileIdentity.normalizeBaseUrl(it.url) == normalized }
            }
        }

        private fun urlsToProbe(): List<String> {
            val saved = ConnectionProfileIdentity.normalizeServerUrls(_uiState.value.serverUrls)
            if (saved.isNotEmpty()) return saved
            return listOf(ConnectionProfileIdentity.normalizeBaseUrl(_uiState.value.baseUrl))
        }

        private fun startAddressProbe() {
            val urls = ConnectionProfileIdentity.normalizeServerUrls(_uiState.value.serverUrls)
            if (urls.isEmpty()) return
            val generation = ++addressProbeGeneration
            _uiState.value =
                _uiState.value.copy(
                    addressProbeFinished = false,
                    unreachableUrls = emptySet(),
                    chosenUrl = null,
                )
            viewModelScope.launch {
                if (generation != addressProbeGeneration) return@launch
                val summary = probeAddresses(urls)
                if (generation != addressProbeGeneration) return@launch
                if (_uiState.value.serverUrls.isEmpty()) return@launch
                publishAddressProbe(summary)
            }
        }

        private suspend fun probeAddresses(urls: List<String>): AddressProbeSummary {
            val outcomes =
                coroutineScope {
                    urls
                        .map { url -> async { probeOutcome(url) } }
                        .awaitAll()
                }
            val answered = outcomes.mapNotNull(::answeredHost)
            return AddressProbeSummary(
                outcomes = outcomes,
                answered = answered,
                winner = PairingQRPayload.pickReachableUrl(answered),
            )
        }

        private fun publishAddressProbe(summary: AddressProbeSummary) {
            val current = _uiState.value
            val winner = summary.winner?.let(ConnectionProfileIdentity::normalizeBaseUrl)
            if (current.serverUrls.isEmpty()) {
                if (winner != null && winner != ConnectionProfileIdentity.normalizeBaseUrl(current.baseUrl)) {
                    _uiState.value =
                        current.copy(
                            baseUrl = winner,
                            connectionUnchanged = isConnectionUnchanged(winner, current.username, current.password),
                        )
                }
                return
            }
            val baseUrl = winner ?: current.baseUrl
            _uiState.value =
                current.copy(
                    baseUrl = baseUrl,
                    addressProbeFinished = true,
                    unreachableUrls = summary.unreachable,
                    chosenUrl = winner,
                    connectionUnchanged = isConnectionUnchanged(baseUrl, current.username, current.password),
                )
        }

        private fun isConnectionUnchanged(
            baseUrl: String,
            username: String,
            password: String,
        ): Boolean =
            isEditingExistingProfile &&
                ConnectionProfileIdentity.sameConnectionIdentity(
                    baseUrl,
                    username,
                    savedBaseUrl,
                    savedUsername,
                ) &&
                password == savedPassword

        private fun dropUnconfirmedPastedLayout() {
            if (!pastedConnectLinkUnconfirmed) return
            pastedConnectLinkUnconfirmed = false
            pendingPairingCode = null
            _uiState.value =
                _uiState.value.copy(
                    serverUrls = emptyList(),
                    addressProbeFinished = false,
                    unreachableUrls = emptySet(),
                    chosenUrl = null,
                    qrPairingReady = false,
                    qrScanPending = false,
                )
        }

        private fun handleProbeFailure(
            error: ServerProbeError,
            origin: ConnectFailureOrigin = ConnectFailureOrigin.Manual,
        ) {
            if (origin == ConnectFailureOrigin.ScannedQr) {
                presentScannedQrFailure()
                return
            }
            dropUnconfirmedPastedLayout()
            val authFailed = error.toProbeStatus() == ProbeStatus.AuthFailed
            val focusPassword =
                authFailed &&
                    _uiState.value.credentialSegment == ConnectionCredentialSegment.Password &&
                    !_uiState.value.usesQrLayout
            _uiState.value =
                _uiState.value.copy(
                    state = ConnectionSetupState.Idle,
                    requestPasswordFocus = focusPassword,
                    snackbar =
                        OpenCodeSnackbarMessage(
                            text = error.userMessage(),
                            variant = OpenCodeSnackbarVariant.Error,
                        ),
                )
        }

        private fun presentScannedQrFailure() {
            pendingPairingCode = null
            pastedConnectLinkUnconfirmed = false
            val current = _uiState.value
            val password = scannedQrPassword
            _uiState.value =
                current.copy(
                    state = ConnectionSetupState.Idle,
                    password = password,
                    serverUrls = emptyList(),
                    addressProbeFinished = false,
                    unreachableUrls = emptySet(),
                    chosenUrl = null,
                    qrPairingReady = false,
                    qrScanPending = false,
                    credentialSegment = ConnectionCredentialSegment.Password,
                    requestPasswordFocus = false,
                    connectionUnchanged = isConnectionUnchanged(current.baseUrl, current.username, password),
                    snackbar =
                        OpenCodeSnackbarMessage(
                            text = QrScanFailureMessage,
                            variant = OpenCodeSnackbarVariant.Error,
                        ),
                )
        }

        private fun persist(onSaved: () -> Unit) {
            pastedConnectLinkUnconfirmed = false
            logAction(
                actionId = ProductAnalytics.Action.CONNECTION_SAVE,
                parameters = mapOf(ProductAnalytics.Param.IS_EDIT to isEditingExistingProfile.toString()),
            )
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(state = ConnectionSetupState.Saving, snackbar = null)
                try {
                    connectionSessionCoordinator.saveProfile(draftProfile(), detectedProtocol)
                    _uiState.value = _uiState.value.copy(state = ConnectionSetupState.Idle)
                    onSaved()
                } catch (_: DuplicateConnectionProfileException) {
                    _uiState.value =
                        _uiState.value.copy(
                            state = ConnectionSetupState.Idle,
                            snackbar =
                                OpenCodeSnackbarMessage(
                                    text = "A server with this host and username is already saved",
                                    variant = OpenCodeSnackbarVariant.Error,
                                ),
                        )
                }
            }
        }

        private suspend fun persistAfterProbe(onSaved: () -> Unit) {
            pastedConnectLinkUnconfirmed = false
            logAction(
                actionId = ProductAnalytics.Action.CONNECTION_SAVE,
                parameters = mapOf(ProductAnalytics.Param.IS_EDIT to isEditingExistingProfile.toString()),
            )
            _uiState.value = _uiState.value.copy(state = ConnectionSetupState.Saving)
            try {
                connectionSessionCoordinator.saveProfile(draftProfile(), detectedProtocol)
                _uiState.value = _uiState.value.copy(state = ConnectionSetupState.Idle)
                onSaved()
            } catch (_: DuplicateConnectionProfileException) {
                _uiState.value =
                    _uiState.value.copy(
                        state = ConnectionSetupState.Idle,
                        snackbar =
                            OpenCodeSnackbarMessage(
                                text = "A server with this host and username is already saved",
                                variant = OpenCodeSnackbarVariant.Error,
                            ),
                    )
            }
        }

        private fun showError(text: String) {
            _uiState.value =
                _uiState.value.copy(
                    snackbar =
                        OpenCodeSnackbarMessage(
                            text = text,
                            variant = OpenCodeSnackbarVariant.Error,
                        ),
                )
        }

        fun removeConnection(onRemoved: () -> Unit) {
            val id = _uiState.value.profileId
            if (id.isBlank()) return
            viewModelScope.launch {
                connectionSessionCoordinator.removeProfile(id)
                onRemoved()
            }
        }

        private fun draftProfile(): ConnectionProfile =
            ConnectionProfile(
                baseUrl = _uiState.value.baseUrl,
                username = ConnectionProfileIdentity.normalizeUsername(_uiState.value.username),
                password = _uiState.value.password,
                serverProtocol =
                    detectedProtocol
                        ?: savedProtocol.takeIf { _uiState.value.connectionUnchanged },
                id = _uiState.value.profileId,
                name = _uiState.value.name,
                serverUrls = ConnectionProfileIdentity.normalizeServerUrls(_uiState.value.serverUrls),
            )

        private fun draftConnection(baseUrl: String = _uiState.value.baseUrl): ServerConnection =
            ServerConnection.fromProfile(draftProfile().copy(baseUrl = baseUrl))

        companion object {
            const val QrScanFailureMessage =
                "QR didn't connect. OpenCode 2's pairing API is still changing, so a scan can fail " +
                    "while this app is working. Enter the host and connect. Password is optional. " +
                    "A pairing code works too."

            fun hostIsBlank(baseUrl: String): Boolean {
                val trimmed = baseUrl.trim()
                if (trimmed.isEmpty()) return true
                // Scheme-only values from the address input (e.g. "http://")
                val withoutScheme =
                    trimmed
                        .removePrefix("https://")
                        .removePrefix("http://")
                        .trim('/')
                return withoutScheme.isEmpty()
            }
        }
    }
