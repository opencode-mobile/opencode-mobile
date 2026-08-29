package dev.opencode.mobile.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.terminal.TerminalRepository
import dev.opencode.mobile.domain.terminal.TerminalSessionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

sealed interface TerminalUiState {
    data object Connecting : TerminalUiState

    data object Live : TerminalUiState

    data object ConnectFailed : TerminalUiState

    data object Exited : TerminalUiState
}

@HiltViewModel
class TerminalViewModel
    @Inject
    constructor(
        private val terminalRepository: TerminalRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<TerminalUiState>(TerminalUiState.Connecting)
        val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

        private var worktree: String? = null
        private var connectJob: Job? = null

        fun appear(worktree: String) {
            this.worktree = worktree
            connect()
        }

        fun retry() {
            connect()
        }

        fun disappear() {
            connectJob?.cancel()
        }

        private fun connect() {
            val directory = worktree ?: return
            connectJob?.cancel()
            _uiState.value = TerminalUiState.Connecting
            connectJob =
                viewModelScope.launch {
                    try {
                        val session = terminalRepository.ensureRunning(directory)
                        _uiState.value =
                            if (session.status == TerminalSessionStatus.Running) {
                                TerminalUiState.Live
                            } else {
                                TerminalUiState.Exited
                            }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        _uiState.value = TerminalUiState.ConnectFailed
                    }
                }
        }
    }
