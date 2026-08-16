package dev.opencode.mobile.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.data.local.ServerConnectionDefaults
import dev.opencode.mobile.domain.connection.ConnectionProbeService
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.connection.DuplicateConnectionProfileException
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.connection.userMessage
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.network.ServerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ConnectionSetupState {
    Idle,
    Testing,
    TestSucceeded,
    TestFailed,
    Saving,
}

enum class ConnectionSetupMode {
    First,
    Add,
    Edit,
}

data class ConnectionSetupUiState(
    val name: String = "",
    val baseUrl: String = ServerConnectionDefaults.DEFAULT_BASE_URL,
    val username: String = ServerConnectionDefaults.USERNAME,
    val password: String = "",
    val state: ConnectionSetupState = ConnectionSetupState.Idle,
    val testResultMessage: String? = null,
    val mode: ConnectionSetupMode = ConnectionSetupMode.First,
    val profileId: String = "",
    val connectionUnchanged: Boolean = false,
) {
    val screenTitle: String
        get() =
            when (mode) {
                ConnectionSetupMode.First -> "Connect to server"
                ConnectionSetupMode.Add -> "Add server"
                ConnectionSetupMode.Edit -> "Edit server"
            }

    val canRemove: Boolean
        get() = mode == ConnectionSetupMode.Edit && profileId.isNotBlank()
    val canSave: Boolean
        get() = state == ConnectionSetupState.TestSucceeded || connectionUnchanged

    val isTesting: Boolean
        get() = state == ConnectionSetupState.Testing

    val isSaving: Boolean
        get() = state == ConnectionSetupState.Saving
}

@HiltViewModel
class ConnectionSetupViewModel
    @Inject
    constructor(
        private val connectionProbeService: ConnectionProbeService,
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
            val resetState =
                when (current.state) {
                    ConnectionSetupState.TestSucceeded, ConnectionSetupState.TestFailed -> ConnectionSetupState.Idle
                    else -> current.state
                }
            val unchanged =
                isEditingExistingProfile &&
                    ConnectionProfileIdentity.sameConnectionIdentity(
                        baseUrl,
                        username,
                        savedBaseUrl,
                        savedUsername,
                    ) &&
                    password == savedPassword
            if (resetState == ConnectionSetupState.Idle) {
                detectedProtocol = if (unchanged) savedProtocol else null
            }
            _uiState.value =
                current.copy(
                    name = name,
                    baseUrl = baseUrl,
                    username = username,
                    password = password,
                    state = resetState,
                    testResultMessage = null,
                    connectionUnchanged = unchanged,
                )
        }

        fun testConnection() {
            logAction(ProductAnalytics.Action.CONNECTION_TEST)
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(state = ConnectionSetupState.Testing, testResultMessage = null)
                try {
                    val protocol = connectionProbeService.probe(draftConnection())
                    detectedProtocol = protocol
                    _uiState.value =
                        _uiState.value.copy(
                            state = ConnectionSetupState.TestSucceeded,
                            testResultMessage = protocol.reachableMessage,
                        )
                } catch (error: ServerProbeError) {
                    detectedProtocol = null
                    _uiState.value =
                        _uiState.value.copy(
                            state = ConnectionSetupState.TestFailed,
                            testResultMessage = error.userMessage(),
                        )
                } catch (error: Throwable) {
                    detectedProtocol = null
                    _uiState.value =
                        _uiState.value.copy(
                            state = ConnectionSetupState.TestFailed,
                            testResultMessage = error.message ?: "Unable to reach the server",
                        )
                }
            }
        }

        fun saveConnection(onSaved: () -> Unit) {
            if (!_uiState.value.canSave) return
            logAction(
                actionId = ProductAnalytics.Action.CONNECTION_SAVE,
                parameters = mapOf(ProductAnalytics.Param.IS_EDIT to isEditingExistingProfile.toString()),
            )
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(state = ConnectionSetupState.Saving)
                try {
                    connectionSessionCoordinator.saveProfile(draftProfile(), detectedProtocol)
                    _uiState.value = _uiState.value.copy(state = ConnectionSetupState.TestSucceeded)
                    onSaved()
                } catch (error: DuplicateConnectionProfileException) {
                    _uiState.value =
                        _uiState.value.copy(
                            state = ConnectionSetupState.TestFailed,
                            testResultMessage = "A server with this host and username is already saved",
                        )
                }
            }
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
    }
