package dev.opencode.mobile.ui.changes

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.connection.ServerCapabilities
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.VcsDiffMode
import dev.opencode.mobile.network.ActiveConnectionManager
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
    WorkingTree("Git changes", "No Git changes", "The working tree has no uncommitted changes."),
    LastTurn("Last turn changes", "No changes", "The latest completed turn did not change any files."),
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
    val refreshError: String? = null,
)

data class ChangesContentState(
    val files: List<FileDiff>,
    val filter: String = "",
) {
    val filteredFiles: List<FileDiff>
        get() {
            val query = filter.trim()
            return if (query.isEmpty()) files else files.filter { it.path.contains(query, ignoreCase = true) }
        }

    val additions: Int get() = filteredFiles.sumOf(FileDiff::additions)
    val deletions: Int get() = filteredFiles.sumOf(FileDiff::deletions)
    val summary: String get() = countLabel(filteredFiles.size, "changed file", "changed files")

    val aggregateAccessibilityLabel: String
        get() =
            listOf(
                summary,
                countLabel(additions, "addition", "additions"),
                countLabel(deletions, "deletion", "deletions"),
            ).joinToString()

    fun fileAccessibilityLabel(file: FileDiff): String {
        val position = filteredFiles.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        return listOf(
            file.path,
            file.status.displayName(),
            "file ${position + 1} of ${filteredFiles.size}",
            countLabel(file.additions, "addition", "additions"),
            countLabel(file.deletions, "deletion", "deletions"),
        ).joinToString()
    }
}

data class FileViewerState(
    val file: FileDiff,
    val document: UnifiedDiffDocument = UnifiedDiffParser.parse(file.patch.orEmpty()),
    val contextMode: UnifiedDiffContextMode = UnifiedDiffContextMode.Collapsed,
    val selectedHunkId: Int? = document.hunks.firstOrNull()?.id,
) {
    val hasPreview: Boolean get() = document.hunks.isNotEmpty()
    val canExpandContext: Boolean
        get() = document.visibleRows(UnifiedDiffContextMode.Collapsed).size < document.rows.size
    private val selectedHunkIndex: Int get() = document.hunks.indexOfFirst { it.id == selectedHunkId }
    val canSelectPreviousHunk: Boolean get() = selectedHunkIndex > 0
    val canSelectNextHunk: Boolean get() = selectedHunkIndex >= 0 && selectedHunkIndex < document.hunks.lastIndex

    fun selectPreviousHunk() = moveHunk(-1)

    fun selectNextHunk() = moveHunk(1)

    fun toggleContextMode(): FileViewerState =
        if (!canExpandContext) {
            this
        } else {
            copy(
                contextMode =
                    if (contextMode == UnifiedDiffContextMode.Collapsed) {
                        UnifiedDiffContextMode.Expanded
                    } else {
                        UnifiedDiffContextMode.Collapsed
                    },
            )
        }

    private fun moveHunk(offset: Int): FileViewerState {
        val target = document.hunks.getOrNull(selectedHunkIndex + offset) ?: return this
        return copy(selectedHunkId = target.id)
    }
}

@HiltViewModel
class ChangesViewModel(
    private val repository: SessionRepository,
    private val capabilitiesProvider: () -> ServerCapabilities,
) : ViewModel() {
    @Inject
    constructor(
        repository: SessionRepository,
        activeConnectionManager: ActiveConnectionManager,
    ) : this(repository, { activeConnectionManager.currentCapabilities() })

    constructor(
        repository: SessionRepository,
        capabilities: ServerCapabilities = ServerCapabilities.forProtocol(ServerProtocol.V1),
    ) : this(repository, { capabilities })

    private val capabilities: ServerCapabilities
        get() = capabilitiesProvider()

    private val _uiState =
        MutableStateFlow(
            ChangesUiState(
                selectedSource = capabilitySelectedSource(ChangeSource.LastTurn),
                availableSources = capabilitySources(),
            ),
        )
    val uiState: StateFlow<ChangesUiState> = _uiState.asStateFlow()

    private var target: Target? = null
    private var availableSources = capabilitySources()
    private var filter = ""
    private var filterEditReported = false
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
        availableSources = capabilitySources()
        filter = ""
        filterEditReported = false
        _uiState.value =
            ChangesUiState(
                selectedSource = capabilitySelectedSource(initialSource),
                availableSources = availableSources,
            )
        load(refreshAvailability = true)
    }

    fun retry() = load(refreshAvailability = true)

    fun refresh() {
        load(refreshAvailability = true, preserveContent = true)
    }

    fun selectSource(source: ChangeSource) {
        if (source == _uiState.value.selectedSource || source !in availableSources) return
        logAction(
            actionId = ProductAnalytics.Action.CHANGES_SELECT_SOURCE,
            parameters = mapOf(ProductAnalytics.Param.CHANGE_SOURCE to source.logValue()),
        )
        _uiState.value =
            ChangesUiState(
                phase = ChangesPhase.Loading,
                selectedSource = source,
                availableSources = availableSources,
            )
        load(refreshAvailability = false)
    }

    fun updateFilter(value: String) {
        // Report that filtering happened once per filter session, never the query.
        // Clearing resets the flag instead of logging, so leaving Changes stays silent.
        if (value.isEmpty()) {
            filterEditReported = false
        } else if (!filterEditReported) {
            filterEditReported = true
            logAction(ProductAnalytics.Action.CHANGES_UPDATE_FILTER)
        }
        filter = value
        updateContent { it.copy(filter = value) }
    }

    fun clearFilter() = updateFilter("")

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
                    if (availableSources.isEmpty()) {
                        logLoad(requestedSource, target, 0, "empty", startedAt)
                        _uiState.value =
                            ChangesUiState(
                                phase = ChangesPhase.NoChanges,
                                selectedSource = requestedSource,
                                availableSources = availableSources,
                            )
                        return@launch
                    }
                    var selectedSource = preferredSource(requestedSource)
                    if (selectedSource != requestedSource) {
                        logLoad(requestedSource, target, 0, "unsupported-fallback", startedAt)
                    }
                    activeSource = selectedSource
                    if (preservedPhase == null) {
                        _uiState.value = ChangesUiState(ChangesPhase.Loading, selectedSource, availableSources)
                    }
                    val files =
                        try {
                            loadFiles(target, selectedSource)
                        } catch (error: Throwable) {
                            if (
                                selectedSource != ChangeSource.WorkingTree ||
                                !error.isUnsupportedVcs() ||
                                !capabilities.supportsSessionMessageDiff
                            ) {
                                throw error
                            }
                            logLoad(selectedSource, target, 0, "unsupported-fallback", startedAt)
                            availableSources = listOf(ChangeSource.LastTurn)
                            selectedSource = ChangeSource.LastTurn
                            activeSource = selectedSource
                            if (preservedPhase == null) {
                                _uiState.value = ChangesUiState(ChangesPhase.Loading, selectedSource, availableSources)
                            }
                            loadFiles(target, selectedSource)
                        }
                    val sortedFiles = files.sortedBy(FileDiff::path)
                    logLoad(selectedSource, target, sortedFiles.size, if (sortedFiles.isEmpty()) "empty" else "success", startedAt)
                    _uiState.value =
                        ChangesUiState(
                            phase =
                                when {
                                    selectedSource == ChangeSource.LastTurn && target.messageId == null -> ChangesPhase.NoTurn
                                    sortedFiles.isEmpty() -> ChangesPhase.NoChanges
                                    else -> ChangesPhase.Content(ChangesContentState(sortedFiles, filter))
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
                        if (preservedPhase != null) {
                            val restoredPhase =
                                (preservedPhase as ChangesPhase.Content).copy(
                                    changeSet = preservedPhase.changeSet.copy(filter = filter),
                                )
                            ChangesUiState(
                                phase = restoredPhase,
                                selectedSource = requestedSource,
                                availableSources = availableSources,
                                refreshError = error.userMessage(),
                            )
                        } else {
                            ChangesUiState(ChangesPhase.Error(error.userMessage()), activeSource, availableSources)
                        }
                }
            }
    }

    private fun capabilitySources(): List<ChangeSource> =
        if (capabilities.supportsSessionMessageDiff) {
            listOf(ChangeSource.LastTurn)
        } else {
            listOf(ChangeSource.WorkingTree)
        }

    private fun capabilitySelectedSource(requested: ChangeSource): ChangeSource =
        if (requested == ChangeSource.LastTurn && !capabilities.supportsSessionMessageDiff) {
            ChangeSource.WorkingTree
        } else {
            requested
        }

    private suspend fun resolveAvailableSources(worktree: String): List<ChangeSource> {
        val sources = mutableListOf<ChangeSource>()
        try {
            if (repository.getVcsInfo(worktree).supportsGit) {
                sources += ChangeSource.WorkingTree
            }
        } catch (error: Throwable) {
            if (!error.isUnsupportedVcs()) throw error
        }
        if (capabilities.supportsSessionMessageDiff) {
            sources += ChangeSource.LastTurn
        }
        return sources
    }

    private fun preferredSource(requested: ChangeSource): ChangeSource =
        requested.takeIf { it in availableSources }
            ?: ChangeSource.LastTurn.takeIf { it in availableSources }
            ?: availableSources.first()

    private suspend fun loadFiles(
        target: Target,
        source: ChangeSource,
    ): List<FileDiff> =
        when (source) {
            ChangeSource.LastTurn ->
                if (!capabilities.supportsSessionMessageDiff) {
                    emptyList()
                } else {
                    target.messageId?.let { repository.getSessionDiff(target.sessionId, it, target.worktree) }.orEmpty()
                }
            ChangeSource.WorkingTree -> repository.getVcsDiff(VcsDiffMode.WorkingTree, target.worktree, context = 12)
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

    private fun logAction(
        actionId: String,
        parameters: Map<String, String> = emptyMap(),
    ) {
        ProductAnalytics.logUserAction(
            screenId = ProductAnalytics.Screen.CHANGES,
            actionId = actionId,
            parameters = parameters,
        )
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

private fun FileDiffStatus.displayName() = name.replaceFirstChar(Char::uppercase)

private fun countLabel(
    count: Int,
    singular: String,
    plural: String,
) = "$count ${if (count == 1) singular else plural}"
