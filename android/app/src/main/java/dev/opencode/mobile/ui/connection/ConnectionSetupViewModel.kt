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
) {
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

        fun configure(
            initialProfile: ConnectionProfile?,
            mode: ConnectionSetupMode = if (initialProfile == null) ConnectionSetupMode.First else ConnectionSetupMode.Edit,
        ) {
            reportedFieldEdits.clear()
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
                    )
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
            updateFields(baseUrl = value)
        }

        fun onUsernameChanged(value: String) {
            reportFieldEdit(ProductAnalytics.Action.CONNECTION_EDIT_USERNAME)
            updateFields(username = value)
        }

        fun onPasswordChanged(value: String) {
            reportFieldEdit(ProductAnalytics.Action.CONNECTION_EDIT_PASSWORD)
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
                    val credentials = result.credentials
                    logAction(
                        ProductAnalytics.Action.CONNECTION_PAIRING_QR_SCAN,
                        mapOf(ProductAnalytics.Param.OUTCOME to ProductAnalytics.Outcome.SUCCESS),
                    )
                    updateFields(
                        baseUrl = credentials.baseUrl,
                        username = credentials.username,
                        password = credentials.password,
                    )
                    _uiState.value =
                        _uiState.value.copy(
                            credentialsExpanded = true,
                            snackbar = null,
                        )
                }
                is PairingQRParseResult.Failed -> {
                    logAction(
                        ProductAnalytics.Action.CONNECTION_PAIRING_QR_SCAN,
                        mapOf(ProductAnalytics.Param.OUTCOME to ProductAnalytics.Outcome.FAILURE),
                    )
                    val text =
                        when (result.error) {
                            PairingQRError.InvalidPayload -> "This isn't an OpenCode 2 pairing QR"
                            PairingQRError.NoReachableUrl -> "That QR only has a localhost address"
                        }
                    showError(text)
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
            val unchanged =
                isEditingExistingProfile &&
                    ConnectionProfileIdentity.sameConnectionIdentity(
                        baseUrl,
                        username,
                        savedBaseUrl,
                        savedUsername,
                    ) &&
                    password == savedPassword
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
            if (_uiState.value.canSubmitWithoutProbe) {
                persist(onSaved)
                return
            }
            probeThenSave(onSaved)
        }

        private fun probeThenSave(onSaved: () -> Unit) {
            logAction(ProductAnalytics.Action.CONNECTION_TEST)
            viewModelScope.launch {
                _uiState.value =
                    _uiState.value.copy(
                        state = ConnectionSetupState.Connecting,
                        snackbar = null,
                    )
                try {
                    val protocol = connectionProbeService.probe(draftConnection())
                    detectedProtocol = protocol
                    persistAfterProbe(onSaved)
                } catch (error: ServerProbeError) {
                    detectedProtocol = null
                    handleProbeFailure(error)
                } catch (error: Throwable) {
                    detectedProtocol = null
                    _uiState.value =
                        _uiState.value.copy(
                            state = ConnectionSetupState.Idle,
                            snackbar =
                                OpenCodeSnackbarMessage(
                                    text = error.message ?: "Unable to reach the server",
                                    variant = OpenCodeSnackbarVariant.Error,
                                ),
                        )
                }
            }
        }

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
            )

        private fun draftConnection(): ServerConnection = ServerConnection.fromProfile(draftProfile())

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
