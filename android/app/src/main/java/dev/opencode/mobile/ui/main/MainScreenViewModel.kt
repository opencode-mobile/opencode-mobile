package dev.opencode.mobile.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.network.ServerConnection
import dev.opencode.mobile.ui.main.MainScreenUiState.Success
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class MainScreenViewModel
@Inject
constructor(
  private val sessionRepository: SessionRepository,
  private val sessionDomainAdapter: SessionDomainAdapter,
  private val serverConnection: ServerConnection,
) : ViewModel() {
  private val _uiState = MutableStateFlow<MainScreenUiState>(MainScreenUiState.Loading)
  val uiState: StateFlow<MainScreenUiState> = _uiState.asStateFlow()

  init {
    loadSessions()
  }

  fun loadSessions() {
    viewModelScope.launch {
      _uiState.value = MainScreenUiState.Loading
      try {
        val sessions =
          sessionRepository.listSessions().map(sessionDomainAdapter::toSessionSummary)
        _uiState.value =
          Success(
            sessions = sessions,
            serverBaseUrl = serverConnection.baseUrl,
            selectedProjectName = SELECTED_PROJECT_NAME,
          )
      } catch (throwable: Throwable) {
        _uiState.value = MainScreenUiState.Error(throwable)
      }
    }
  }

  private companion object {
    const val SELECTED_PROJECT_NAME = "opencode-mobile"
  }
}

sealed interface MainScreenUiState {
  data object Loading : MainScreenUiState

  data class Error(val throwable: Throwable) : MainScreenUiState

  data class Success(
    val sessions: List<SessionSummary>,
    val serverBaseUrl: String,
    val selectedProjectName: String,
  ) : MainScreenUiState
}
