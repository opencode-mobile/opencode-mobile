package dev.opencode.mobile.ui.changes

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.domain.session.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ChangesUiState {
    data object Loading : ChangesUiState

    data object NoTurn : ChangesUiState

    data object NoChanges : ChangesUiState

    data class Content(
        val changeSet: ChangesContentState,
    ) : ChangesUiState

    data class Error(
        val message: String,
    ) : ChangesUiState
}

data class ChangesContentState(
    val files: List<FileDiff>,
    val selectedPath: String,
    val filter: String = "",
) {
    val filteredFiles: List<FileDiff>
        get() {
            val query = filter.trim()
            return if (query.isEmpty()) files else files.filter { it.path.contains(query, ignoreCase = true) }
        }

    val selectedFile: FileDiff
        get() = files.first { it.path == selectedPath }

    val additions: Int
        get() = files.sumOf(FileDiff::additions)

    val deletions: Int
        get() = files.sumOf(FileDiff::deletions)

    val aggregateAccessibilityLabel: String
        get() =
            listOf(
                countLabel(files.size, "changed file", "changed files"),
                countLabel(additions, "addition", "additions"),
                countLabel(deletions, "deletion", "deletions"),
            ).joinToString()

    val selectedFileSummary: String
        get() = "${selectedFile.status.displayName()} · ${selectedPosition + 1} of ${positionFiles.size}"

    val selectedFileAccessibilityLabel: String
        get() = fileAccessibilityLabel(selectedFile)

    val canSelectPrevious: Boolean
        get() = filteredSelectedIndex > 0

    val canSelectNext: Boolean
        get() = filteredSelectedIndex >= 0 && filteredSelectedIndex < filteredFiles.lastIndex

    fun filePickerSummary(file: FileDiff) = "${file.status.displayName()} · +${file.additions} −${file.deletions}"

    fun fileAccessibilityLabel(file: FileDiff): String {
        val position = positionFiles.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        return listOf(
            file.path,
            file.status.displayName(),
            "file ${position + 1} of ${positionFiles.size}",
            countLabel(file.additions, "addition", "additions"),
            countLabel(file.deletions, "deletion", "deletions"),
        ).joinToString()
    }

    private val filteredSelectedIndex: Int
        get() = filteredFiles.indexOfFirst { it.path == selectedPath }

    private val positionFiles: List<FileDiff>
        get() = if (filteredSelectedIndex >= 0) filteredFiles else files

    private val selectedPosition: Int
        get() = positionFiles.indexOfFirst { it.path == selectedPath }.coerceAtLeast(0)
}

@HiltViewModel
class ChangesViewModel
    @Inject
    constructor(
        private val repository: SessionRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<ChangesUiState>(ChangesUiState.Loading)
        val uiState: StateFlow<ChangesUiState> = _uiState.asStateFlow()

        private var target: Target? = null

        fun bind(
            sessionId: String,
            worktree: String,
            messageId: String?,
        ) {
            val next = Target(sessionId, worktree, messageId)
            if (target == next) return
            target = next
            load()
        }

        fun retry() = load()

        fun updateFilter(filter: String) =
            updateContent { current ->
                val filtered = current.copy(filter = filter).filteredFiles
                current.copy(
                    filter = filter,
                    selectedPath =
                        if (filtered.any { it.path == current.selectedPath }) {
                            current.selectedPath
                        } else {
                            filtered.firstOrNull()?.path ?: current.selectedPath
                        },
                )
            }

        fun clearFilter() = updateFilter("")

        fun selectFile(path: String) =
            updateContent { current ->
                if (current.filteredFiles.any { it.path == path }) current.copy(selectedPath = path) else current
            }

        fun selectPreviousFile() = moveSelection(-1)

        fun selectNextFile() = moveSelection(1)

        private fun load() {
            val target = target ?: return
            val messageId = target.messageId
            if (messageId == null) {
                _uiState.value = ChangesUiState.NoTurn
                return
            }
            _uiState.value = ChangesUiState.Loading
            viewModelScope.launch {
                val startedAt = System.currentTimeMillis()
                try {
                    val files = repository.getSessionDiff(target.sessionId, messageId, target.worktree)
                    val sortedFiles = files.sortedBy(FileDiff::path)
                    val first = sortedFiles.firstOrNull()
                    runCatching {
                        Log.i(
                            TAG,
                            "load source=last-turn session=${target.sessionId} directory=${target.worktree} files=${files.size} result=success durationMs=${System.currentTimeMillis() - startedAt}",
                        )
                    }
                    _uiState.value =
                        when {
                            first == null -> ChangesUiState.NoChanges
                            else -> ChangesUiState.Content(ChangesContentState(sortedFiles, first.path))
                        }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    runCatching {
                        Log.w(
                            TAG,
                            "load source=last-turn session=${target.sessionId} directory=${target.worktree} files=0 result=failure durationMs=${System.currentTimeMillis() - startedAt}",
                            error,
                        )
                    }
                    _uiState.value = ChangesUiState.Error(error.message ?: "Request failed")
                }
            }
        }

        private fun moveSelection(offset: Int) =
            updateContent { current ->
                val index = current.filteredFiles.indexOfFirst { it.path == current.selectedPath }
                val next = current.filteredFiles.getOrNull(index + offset)
                if (next == null) current else current.copy(selectedPath = next.path)
            }

        private inline fun updateContent(transform: (ChangesContentState) -> ChangesContentState) {
            val current = _uiState.value as? ChangesUiState.Content ?: return
            _uiState.value = current.copy(changeSet = transform(current.changeSet))
        }

        private data class Target(
            val sessionId: String,
            val worktree: String,
            val messageId: String?,
        )

        private companion object {
            const val TAG = "Changes"
        }
    }

private fun FileDiffStatus.displayName() = name.replaceFirstChar(Char::uppercase)

private fun countLabel(
    count: Int,
    singular: String,
    plural: String,
) = "$count ${if (count == 1) singular else plural}"
