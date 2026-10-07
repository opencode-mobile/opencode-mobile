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

data class ConnectionSetupUiState(
    val name: String = "",
    val baseUrl: String = "",
    val username: String = ServerConnectionDefaults.USERNAME,
    val password: String = "",
    val state: ConnectionSetupState = ConnectionSetupState.Idle,
    val mode: ConnectionSetupMode = ConnectionSetupMode.First,
    val profileId: String = "",
    val connectionUnchanged: Boolean = false,
    val credentialsExpanded: Boolean = false,
    val requestPasswordFocus: Boolean = false,
    val snackbar: OpenCodeSnackbarMessage? = null,
    val serverUrls: List<String> = emptyList(),
    val addressProbeFinished: Boolean = false,
    val unreachableUrls: Set<String> = emptySet(),
    val chosenUrl: String? = null,
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
                mode == ConnectionSetupMode.Edit -> "Save"
                else -> "Connect"
            }

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
        private var addressProbeGeneration = 0

        fun configure(
            initialProfile: ConnectionProfile?,
            mode: ConnectionSetupMode = if (initialProfile == null) ConnectionSetupMode.First else ConnectionSetupMode.Edit,
        ) {
            reportedFieldEdits.clear()
            pendingPairingCode = null
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
                _uiState.value =
                    _uiState.value.copy(
                        serverUrls = emptyList(),
                        addressProbeFinished = false,
                        unreachableUrls = emptySet(),
                        chosenUrl = null,
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

        fun onCredentialsExpandedChanged(expanded: Boolean) {
            _uiState.value = _uiState.value.copy(credentialsExpanded = expanded)
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
                    applyPairingCredentials(result.credentials)
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
            val hostBlank = hostIsBlank(_uiState.value.baseUrl)
            if (hostBlank) {
                showError("Enter a host")
                return
            }
            if (PairingQRPayload.isConnectLink(_uiState.value.baseUrl)) {
                if (!applySubmittedConnectLink()) return
            }
            val redeeming = pendingPairingCode != null && _uiState.value.password.isEmpty()
            if (_uiState.value.canSubmitWithoutProbe && !redeeming) {
                persist(onSaved)
                return
            }
            probeThenSave(onSaved)
        }

        private fun probeThenSave(onSaved: () -> Unit) {
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
                if (pendingCode != null && _uiState.value.password.isEmpty()) {
                    redeemAnsweredHost(pendingCode, generation, onSaved)
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
                    handleProbeFailure(winnerOutcome.error)
                    return@launch
                }
                val failed = summary.outcomes.mapNotNull { (it as? ProbeOutcome.Failed)?.error }
                val authError = failed.firstOrNull { it.toProbeStatus() == ProbeStatus.AuthFailed }
                handleProbeFailure(
                    authError
                        ?: failed.lastOrNull()
                        ?: ServerProbeError.Transport(IllegalStateException("Unable to reach the server")),
                )
            }
        }

        private fun applyPairingCredentials(credentials: PairingQRCredentials) {
            pendingPairingCode = credentials.code
            updateFields(
                baseUrl = credentials.baseUrl,
                username = credentials.username,
                password = credentials.password,
            )
            _uiState.value =
                _uiState.value.copy(
                    serverUrls = ConnectionProfileIdentity.normalizeServerUrls(credentials.urls),
                    credentialsExpanded = false,
                    snackbar = null,
                    addressProbeFinished = false,
                    unreachableUrls = emptySet(),
                    chosenUrl = null,
                )
            startAddressProbe()
        }

        private fun applySubmittedConnectLink(): Boolean {
            when (val result = PairingQRPayload.parse(_uiState.value.baseUrl.trim())) {
                is PairingQRParseResult.Ready -> {
                    applyPairingCredentials(result.credentials)
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
        ) {
            val summary = probeAddresses(urlsToProbe())
            if (generation != addressProbeGeneration) return
            publishAddressProbe(summary)
            val winner = summary.winner
            if (winner == null) {
                detectedProtocol = null
                handleProbeFailure(
                    ServerProbeError.Transport(IllegalStateException("Unable to reach the server")),
                )
                return
            }
            val token =
                try {
                    pairingCodeRedeeming.redeem(winner, code)
                } catch (error: PairingCodeRedeemException) {
                    detectedProtocol = null
                    when (error) {
                        is PairingCodeRedeemException.Expired -> {
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
                            )
                    }
                    return
                }
            if (generation != addressProbeGeneration) return
            _uiState.value = _uiState.value.copy(password = token, baseUrl = winner)
            when (val authed = probeOutcome(winner)) {
                is ProbeOutcome.Reachable -> {
                    detectedProtocol = authed.protocol
                    persistAfterProbe(onSaved)
                }
                is ProbeOutcome.Failed -> {
                    detectedProtocol = null
                    handleProbeFailure(authed.error)
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

        private fun handleProbeFailure(error: ServerProbeError) {
            val authFailed = error.toProbeStatus() == ProbeStatus.AuthFailed
            _uiState.value =
                _uiState.value.copy(
                    state = ConnectionSetupState.Idle,
                    credentialsExpanded = if (authFailed) true else _uiState.value.credentialsExpanded,
                    requestPasswordFocus = authFailed,
                    snackbar =
                        OpenCodeSnackbarMessage(
                            text = error.userMessage(),
                            variant = OpenCodeSnackbarVariant.Error,
                        ),
                )
        }

        private fun persist(onSaved: () -> Unit) {
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
                username = _uiState.value.username,
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
