package dev.opencode.mobile.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.mobile.data.DataRepository
import dev.opencode.mobile.data.SessionSummary
import dev.opencode.mobile.ui.main.MainScreenUiState.Success
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MainScreenViewModel(dataRepository: DataRepository) : ViewModel() {
  val uiState: StateFlow<MainScreenUiState> =
    dataRepository.sessions
      .map<List<SessionSummary>, MainScreenUiState> { sessions ->
        Success(
          sessions = sessions,
          serverBaseUrl = dataRepository.serverBaseUrl,
          selectedProjectName = dataRepository.selectedProjectName,
        )
      }
      .catch { emit(MainScreenUiState.Error(it)) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainScreenUiState.Loading)
}

sealed interface MainScreenUiState {
  object Loading : MainScreenUiState

  data class Error(val throwable: Throwable) : MainScreenUiState

  data class Success(
    val sessions: List<SessionSummary>,
    val serverBaseUrl: String,
    val selectedProjectName: String,
  ) : MainScreenUiState
}
