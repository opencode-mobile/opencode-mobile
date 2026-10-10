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

data class TerminalCatalogProject(
    val worktree: String,
    val title: String,
)

data class TerminalsCatalogUiState(
    val rows: List<TerminalCatalogRow> = emptyList(),
    val isLoading: Boolean = true,
    val pendingClose: TerminalCatalogRow? = null,
    val projects: List<TerminalCatalogProject> = emptyList(),
    val isProjectPickerVisible: Boolean = false,
    val isCreating: Boolean = false,
    val createError: String? = null,
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
                val connection = activeConnectionManager.current()
                if (connection == null) {
                    _uiState.update { it.copy(isLoading = false, projects = emptyList()) }
                    return@launch
                }
                val worktrees = connectionProfileStore.addedWorktrees(connection.baseUrl)
                val running =
                    runCatching { terminalRepository.listRunning(worktrees) }
                        .getOrDefault(emptyList())
                val projects =
                    worktrees.map { worktree ->
                        TerminalCatalogProject(
                            worktree = worktree,
                            title = ProjectDomainAdapter.displayNameFromWorktree(worktree),
                        )
                    }
                _uiState.update {
                    it.copy(
                        rows = running.map { running -> running.toRow() },
                        projects = projects,
                        isLoading = false,
                    )
                }
            }
        }

        fun showProjectPicker() {
            _uiState.update { it.copy(isProjectPickerVisible = true) }
        }

        fun dismissProjectPicker() {
            _uiState.update { it.copy(isProjectPickerVisible = false, createError = null) }
        }

        suspend fun createShell(worktree: String): TerminalCatalogRow? {
            val state = _uiState.value
            if (state.isCreating) return null
            val project = state.projects.firstOrNull { it.worktree == worktree } ?: return null
            _uiState.update { it.copy(isCreating = true, createError = null) }
            return runCatching {
                terminalRepository.create(directory = worktree, cwd = worktree)
            }.fold(
                onSuccess = { session ->
                    val row =
                        TerminalCatalogRow(
                            id = session.id,
                            title = catalogTitle(session),
                            projectTitle = project.title,
                            worktree = worktree,
                        )
                    _uiState.update { current ->
                        current.copy(
                            rows = listOf(row) + current.rows,
                            isProjectPickerVisible = false,
                            isCreating = false,
                            createError = null,
                        )
                    }
                    row
                },
                onFailure = {
                    _uiState.update { it.copy(isCreating = false, createError = "Could not start a shell.") }
                    null
                },
            )
        }

        fun requestClose(row: TerminalCatalogRow) {
            _uiState.update { it.copy(pendingClose = row) }
        }

        fun dismissClose() {
            _uiState.update { it.copy(pendingClose = null) }
        }

        fun confirmClose() {
            val pending = _uiState.value.pendingClose ?: return
            _uiState.update { it.copy(pendingClose = null) }
            viewModelScope.launch {
                runCatching { terminalRepository.remove(pending.id, pending.worktree) }
                    .onSuccess {
                        _uiState.update { state ->
                            state.copy(
                                rows =
                                    state.rows.filterNot { row ->
                                        row.id == pending.id && row.worktree == pending.worktree
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
