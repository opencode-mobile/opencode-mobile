package dev.opencode.mobile.ui.changes

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.ui.components.UnifiedDiffContextMode
import dev.opencode.mobile.ui.components.UnifiedDiffDocument
import dev.opencode.mobile.ui.components.UnifiedDiffParser
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
    val contextMode: UnifiedDiffContextMode = UnifiedDiffContextMode.Collapsed,
    val reviewedPaths: Set<String> = emptySet(),
    val selectedHunkId: Int? = null,
) {
    val filteredFiles: List<FileDiff>
        get() {
            val query = filter.trim()
            return if (query.isEmpty()) files else files.filter { it.path.contains(query, ignoreCase = true) }
        }

    val selectedFile: FileDiff
        get() = files.first { it.path == selectedPath }

    val selectedDocument: UnifiedDiffDocument
        get() = UnifiedDiffParser.parse(selectedFile.patch.orEmpty())

    val selectedFileHasPreview: Boolean
        get() = selectedDocument.hunks.isNotEmpty()

    val selectedFileReviewed: Boolean
        get() = selectedPath in reviewedPaths

    val selectedHunkPositionLabel: String
        get() =
            selectedHunkIndex.takeIf { it >= 0 }?.let { "Hunk ${it + 1} of ${selectedDocument.hunks.size}" }
                ?: "No hunks"

    val canSelectPreviousHunk: Boolean
        get() = selectedHunkIndex > 0

    val canSelectNextHunk: Boolean
        get() = selectedHunkIndex >= 0 && selectedHunkIndex < selectedDocument.hunks.lastIndex

    val additions: Int get() = files.sumOf(FileDiff::additions)

    val deletions: Int get() = files.sumOf(FileDiff::deletions)

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

    fun filePickerSummary(file: FileDiff): String {
        val reviewed = if (file.path in reviewedPaths) "Reviewed · " else ""
        return "$reviewed${file.status.displayName()} · +${file.additions} −${file.deletions}"
    }

    fun fileAccessibilityLabel(file: FileDiff): String {
        val position = positionFiles.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        return listOf(
            file.path,
            if (file.path in reviewedPaths) "reviewed ${file.status.displayName()}" else file.status.displayName(),
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

    private val selectedHunkIndex: Int
        get() = selectedDocument.hunks.indexOfFirst { it.id == selectedHunkId }

    fun resettingViewerControls(): ChangesContentState =
        copy(
            contextMode = UnifiedDiffContextMode.Collapsed,
            reviewedPaths = emptySet(),
            selectedHunkId = selectedDocument.hunks.firstOrNull()?.id,
        )
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

        fun refresh() = load()

        fun updateFilter(filter: String) =
            updateContent { current ->
                val filtered = current.copy(filter = filter).filteredFiles
                val nextPath =
                    if (filtered.any { it.path == current.selectedPath }) {
                        current.selectedPath
                    } else {
                        filtered.firstOrNull()?.path ?: current.selectedPath
                    }
                current.selectPath(nextPath).copy(
                    filter = filter,
                )
            }

        fun clearFilter() = updateFilter("")

        fun selectFile(path: String) =
            updateContent { current ->
                if (current.filteredFiles.any { it.path == path }) current.selectPath(path) else current
            }

        fun selectPreviousFile() = moveSelection(-1)

        fun selectNextFile() = moveSelection(1)

        fun selectPreviousHunk() = moveHunk(-1)

        fun selectNextHunk() = moveHunk(1)

        fun toggleContextMode() =
            updateContent { current ->
                current.copy(
                    contextMode =
                        if (current.contextMode == UnifiedDiffContextMode.Collapsed) {
                            UnifiedDiffContextMode.Expanded
                        } else {
                            UnifiedDiffContextMode.Collapsed
                        },
                )
            }

        fun toggleSelectedFileReviewed() =
            updateContent { current ->
                val reviewed = current.reviewedPaths.toMutableSet()
                if (!reviewed.add(current.selectedPath)) reviewed.remove(current.selectedPath)
                current.copy(reviewedPaths = reviewed)
            }

        fun prepareForSourceSwitch() = updateContent(ChangesContentState::resettingViewerControls)

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
                            else -> {
                                val document = UnifiedDiffParser.parse(first.patch.orEmpty())
                                ChangesUiState.Content(
                                    ChangesContentState(sortedFiles, first.path, selectedHunkId = document.hunks.firstOrNull()?.id),
                                )
                            }
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
                if (next == null) current else current.selectPath(next.path)
            }

        private fun moveHunk(offset: Int) =
            updateContent { current ->
                val hunks = current.selectedDocument.hunks
                val index = hunks.indexOfFirst { it.id == current.selectedHunkId }
                val next = hunks.getOrNull(index + offset)
                if (next == null) current else current.copy(selectedHunkId = next.id)
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

private fun ChangesContentState.selectPath(path: String): ChangesContentState {
    val file = files.first { it.path == path }
    val firstHunk =
        UnifiedDiffParser
            .parse(file.patch.orEmpty())
            .hunks
            .firstOrNull()
            ?.id
    return copy(selectedPath = path, selectedHunkId = firstHunk)
}

private fun FileDiffStatus.displayName() = name.replaceFirstChar(Char::uppercase)

private fun countLabel(
    count: Int,
    singular: String,
    plural: String,
) = "$count ${if (count == 1) singular else plural}"
