package dev.opencode.mobile.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.terminal.RunningTerminal
import dev.opencode.mobile.domain.terminal.TerminalRepository
import dev.opencode.mobile.domain.terminal.TerminalSession
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TerminalCatalogRow(
    val id: String,
    val title: String,
    val projectTitle: String,
    val worktree: String,
)

data class TerminalsCatalogUiState(
    val rows: List<TerminalCatalogRow> = emptyList(),
)

@HiltViewModel
class TerminalsCatalogViewModel
    @Inject
    constructor(
        private val terminalRepository: TerminalRepository,
        private val activeConnectionManager: ActiveConnectionManager,
        private val connectionProfileStore: ConnectionProfileStore,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(TerminalsCatalogUiState())
        val uiState: StateFlow<TerminalsCatalogUiState> = _uiState.asStateFlow()

        fun appear() {
            viewModelScope.launch {
                val connection = activeConnectionManager.current() ?: return@launch
                val worktrees = connectionProfileStore.addedWorktrees(connection.baseUrl)
                val running =
                    runCatching { terminalRepository.listRunning(worktrees) }
                        .getOrDefault(emptyList())
                _uiState.value = TerminalsCatalogUiState(rows = running.map { it.toRow() })
            }
        }

        fun close(
            id: String,
            directory: String,
        ) {
            viewModelScope.launch {
                runCatching { terminalRepository.remove(id, directory) }
                    .onSuccess {
                        _uiState.update { state ->
                            state.copy(
                                rows =
                                    state.rows.filterNot { row ->
                                        row.id == id && row.worktree == directory
                                    },
                            )
                        }
                    }
            }
        }

        private fun RunningTerminal.toRow(): TerminalCatalogRow =
            TerminalCatalogRow(
                id = session.id,
                title = catalogTitle(session),
                projectTitle = ProjectDomainAdapter.displayNameFromWorktree(directory),
                worktree = directory,
            )
    }

internal fun catalogTitle(session: TerminalSession): String {
    val titled = session.title.trim()
    if (titled.isNotEmpty()) return titled
    val commandName = session.command.substringAfterLast('/').trim()
    if (commandName.isNotEmpty()) return commandName
    return "zsh"
}
