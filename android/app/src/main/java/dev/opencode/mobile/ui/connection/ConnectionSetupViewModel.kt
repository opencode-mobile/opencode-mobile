package dev.opencode.mobile.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.data.local.ServerConnectionDefaults
import dev.opencode.mobile.domain.connection.ConnectionProbeService
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.connection.userMessage
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.network.ServerConnection
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ConnectionSetupState {
  Idle,
  Testing,
  TestSucceeded,
  TestFailed,
  Saving,
}

data class ConnectionSetupUiState(
  val baseUrl: String = ServerConnectionDefaults.DEFAULT_BASE_URL,
  val username: String = ServerConnectionDefaults.USERNAME,
  val password: String = "",
  val state: ConnectionSetupState = ConnectionSetupState.Idle,
  val testResultMessage: String? = null,
) {
  val canSave: Boolean
    get() = state == ConnectionSetupState.TestSucceeded

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

  fun configure(initialProfile: ConnectionProfile?) {
    _uiState.value =
      if (initialProfile == null) {
        ConnectionSetupUiState()
      } else {
        ConnectionSetupUiState(
          baseUrl = initialProfile.baseUrl,
          username = initialProfile.username,
          password = initialProfile.password,
        )
      }
  }

  fun onBaseUrlChanged(value: String) {
    updateFields(baseUrl = value)
  }

  fun onUsernameChanged(value: String) {
    updateFields(username = value)
  }

  fun onPasswordChanged(value: String) {
    updateFields(password = value)
  }

  private fun updateFields(
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
    _uiState.value =
      current.copy(
        baseUrl = baseUrl,
        username = username,
        password = password,
        state = resetState,
        testResultMessage = null,
      )
  }

  fun testConnection() {
    viewModelScope.launch {
      _uiState.value = _uiState.value.copy(state = ConnectionSetupState.Testing, testResultMessage = null)
      try {
        connectionProbeService.probe(draftConnection())
        _uiState.value =
          _uiState.value.copy(
            state = ConnectionSetupState.TestSucceeded,
            testResultMessage = "Server reachable",
          )
      } catch (error: ServerProbeError) {
        _uiState.value =
          _uiState.value.copy(
            state = ConnectionSetupState.TestFailed,
            testResultMessage = error.userMessage(),
          )
      } catch (error: Throwable) {
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
    viewModelScope.launch {
      _uiState.value = _uiState.value.copy(state = ConnectionSetupState.Saving)
      connectionSessionCoordinator.saveProfile(draftProfile())
      _uiState.value = _uiState.value.copy(state = ConnectionSetupState.TestSucceeded)
      onSaved()
    }
  }

  private fun draftProfile(): ConnectionProfile =
    ConnectionProfile(
      baseUrl = _uiState.value.baseUrl,
      username = _uiState.value.username,
      password = _uiState.value.password,
    )

  private fun draftConnection(): ServerConnection = ServerConnection.fromProfile(draftProfile())
}
