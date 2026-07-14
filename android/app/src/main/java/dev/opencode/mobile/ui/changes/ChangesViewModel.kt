package dev.opencode.mobile.ui.changes

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.VcsDiffMode
import dev.opencode.mobile.ui.components.UnifiedDiffContextMode
import dev.opencode.mobile.ui.components.UnifiedDiffDocument
import dev.opencode.mobile.ui.components.UnifiedDiffParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
enum class ChangeSource(
    val displayName: String,
    val emptyTitle: String,
    val emptyDetail: String,
) {
    LastTurn("Last turn", "No changes", "The latest completed turn did not change any files."),
    WorkingTree("Working tree", "No Working tree changes", "The working tree has no uncommitted changes."),
    MainBranch("Main branch", "No Main branch changes", "The current branch has no changes relative to the default branch."),
}

sealed interface ChangesPhase {
    data object Loading : ChangesPhase

    data object NoTurn : ChangesPhase

    data object NoChanges : ChangesPhase

    data class Content(
        val changeSet: ChangesContentState,
    ) : ChangesPhase

    data class Error(
        val message: String,
    ) : ChangesPhase
}

data class ChangesUiState(
    val phase: ChangesPhase = ChangesPhase.Loading,
    val selectedSource: ChangeSource = ChangeSource.LastTurn,
    val availableSources: List<ChangeSource> = listOf(ChangeSource.LastTurn),
    val isRefreshing: Boolean = false,
)

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
        private val _uiState = MutableStateFlow(ChangesUiState())
        val uiState: StateFlow<ChangesUiState> = _uiState.asStateFlow()

        private var target: Target? = null
        private var availableSources = listOf(ChangeSource.LastTurn)
        private var loadJob: Job? = null

        fun bind(
            sessionId: String,
            worktree: String,
            messageId: String?,
            initialSource: ChangeSource = ChangeSource.LastTurn,
        ) {
            val next = Target(sessionId, worktree, messageId, initialSource)
            if (target == next) return
            target = next
            availableSources = listOf(ChangeSource.LastTurn)
            _uiState.value = ChangesUiState(selectedSource = initialSource)
            load(refreshAvailability = true)
        }

        fun retry() = load(refreshAvailability = true)

        fun refresh() = load(refreshAvailability = true, preserveContent = true)

        fun selectSource(source: ChangeSource) {
            if (source == _uiState.value.selectedSource || source !in availableSources) return
            _uiState.value =
                ChangesUiState(
                    phase = ChangesPhase.Loading,
                    selectedSource = source,
                    availableSources = availableSources,
                )
            load(refreshAvailability = false)
        }

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

        private fun load(
            refreshAvailability: Boolean,
            preserveContent: Boolean = false,
        ) {
            val target = target ?: return
            val requestedSource = _uiState.value.selectedSource
            loadJob?.cancel()
            val preservedPhase = _uiState.value.phase.takeIf { preserveContent && it is ChangesPhase.Content }
            _uiState.value =
                ChangesUiState(
                    phase = preservedPhase ?: ChangesPhase.Loading,
                    selectedSource = requestedSource,
                    availableSources = availableSources,
                    isRefreshing = preservedPhase != null,
                )
            loadJob =
                viewModelScope.launch {
                    val startedAt = System.currentTimeMillis()
                    var activeSource = requestedSource
                    try {
                        if (refreshAvailability) availableSources = resolveAvailableSources(target.worktree)
                        var selectedSource = requestedSource.takeIf { it in availableSources } ?: ChangeSource.LastTurn
                        if (selectedSource != requestedSource) {
                            logLoad(
                                source = requestedSource,
                                target = target,
                                fileCount = 0,
                                result = "unsupported-fallback",
                                startedAt = startedAt,
                            )
                        }
                        activeSource = selectedSource
                        if (preservedPhase == null) {
                            _uiState.value = ChangesUiState(ChangesPhase.Loading, selectedSource, availableSources)
                        }
                        val files =
                            try {
                                loadFiles(target, selectedSource)
                            } catch (error: Throwable) {
                                if (selectedSource == ChangeSource.LastTurn || !error.isUnsupportedVcs()) throw error
                                logLoad(
                                    source = selectedSource,
                                    target = target,
                                    fileCount = 0,
                                    result = "unsupported-fallback",
                                    startedAt = startedAt,
                                )
                                availableSources = listOf(ChangeSource.LastTurn)
                                selectedSource = ChangeSource.LastTurn
                                activeSource = selectedSource
                                if (preservedPhase == null) {
                                    _uiState.value = ChangesUiState(ChangesPhase.Loading, selectedSource, availableSources)
                                }
                                loadFiles(target, selectedSource)
                            }
                        val sortedFiles = files.sortedBy(FileDiff::path)
                        val first = sortedFiles.firstOrNull()
                        logLoad(selectedSource, target, files.size, "success", startedAt)
                        _uiState.value =
                            ChangesUiState(
                                phase =
                                    when {
                                        selectedSource == ChangeSource.LastTurn && target.messageId == null -> ChangesPhase.NoTurn
                                        first == null -> ChangesPhase.NoChanges
                                        else -> {
                                            val document = UnifiedDiffParser.parse(first.patch.orEmpty())
                                            ChangesPhase.Content(
                                                ChangesContentState(
                                                    sortedFiles,
                                                    first.path,
                                                    selectedHunkId = document.hunks.firstOrNull()?.id,
                                                ),
                                            )
                                        }
                                    },
                                selectedSource = selectedSource,
                                availableSources = availableSources,
                            )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        runCatching {
                            Log.w(
                                TAG,
                                "load source=${activeSource.logValue()} session=${target.sessionId} directory=${target.worktree} files=0 result=failure durationMs=${System.currentTimeMillis() - startedAt}",
                                error,
                            )
                        }
                        _uiState.value =
                            ChangesUiState(
                                ChangesPhase.Error(error.userMessage()),
                                activeSource,
                                availableSources,
                            )
                    }
                }
        }

        private suspend fun resolveAvailableSources(worktree: String): List<ChangeSource> =
            try {
                val info = repository.getVcsInfo(worktree)
                buildList {
                    add(ChangeSource.LastTurn)
                    if (info.supportsGit) add(ChangeSource.WorkingTree)
                    if (info.hasDistinctMainBranch) add(ChangeSource.MainBranch)
                }
            } catch (error: Throwable) {
                if (error.isUnsupportedVcs()) listOf(ChangeSource.LastTurn) else throw error
            }

        private suspend fun loadFiles(
            target: Target,
            source: ChangeSource,
        ): List<FileDiff> =
            when (source) {
                ChangeSource.LastTurn ->
                    target.messageId?.let { repository.getSessionDiff(target.sessionId, it, target.worktree) }.orEmpty()
                ChangeSource.WorkingTree -> repository.getVcsDiff(VcsDiffMode.WorkingTree, target.worktree, context = 12)
                ChangeSource.MainBranch -> repository.getVcsDiff(VcsDiffMode.MainBranch, target.worktree, context = 12)
            }

        private fun logLoad(
            source: ChangeSource,
            target: Target,
            fileCount: Int,
            result: String,
            startedAt: Long,
        ) {
            runCatching {
                Log.i(
                    TAG,
                    "load source=${source.logValue()} session=${target.sessionId} directory=${target.worktree} files=$fileCount result=$result durationMs=${System.currentTimeMillis() - startedAt}",
                )
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
            val state = _uiState.value
            val current = state.phase as? ChangesPhase.Content ?: return
            _uiState.value = state.copy(phase = current.copy(changeSet = transform(current.changeSet)))
        }

        private data class Target(
            val sessionId: String,
            val worktree: String,
            val messageId: String?,
            val initialSource: ChangeSource,
        )

        private companion object {
            const val TAG = "Changes"
        }
    }

private fun ChangeSource.logValue() = name.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()

private fun Throwable.isUnsupportedVcs() = this is SessionError.Http && statusCode == 404

private fun Throwable.userMessage(): String =
    when (this) {
        is SessionError.Transport -> cause.message ?: "Request failed"
        else -> message ?: "Request failed"
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
