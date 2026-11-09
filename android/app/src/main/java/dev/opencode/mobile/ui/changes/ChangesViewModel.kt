package dev.opencode.mobile.ui.changes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.connection.ServerCapabilities
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.isSessionRunEnd
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.VcsDiffMode
import dev.opencode.mobile.domain.session.VcsInfo
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.ui.components.UnifiedDiffContextMode
import dev.opencode.mobile.ui.components.UnifiedDiffDocument
import dev.opencode.mobile.ui.components.UnifiedDiffParser
import dev.opencode.mobile.ui.sessionchat.LastTurnReviewChangesStore
import dev.opencode.mobile.ui.sessionchat.ReviewChangesSummary
import dev.opencode.mobile.ui.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
enum class ChangeSource {
    WorkingTree,
    LastTurn,
    ;

    val displayName: UiText
        get() =
            UiText.resource(
                when (this) {
                    WorkingTree -> R.string.changes_source_working_tree_name
                    LastTurn -> R.string.changes_source_last_turn_name
                },
            )

    val emptyTitle: UiText
        get() =
            UiText.resource(
                when (this) {
                    WorkingTree -> R.string.changes_source_working_tree_empty_title
                    LastTurn -> R.string.changes_source_last_turn_empty_title
                },
            )

    val emptyDetail: UiText
        get() =
            UiText.resource(
                when (this) {
                    WorkingTree -> R.string.changes_source_working_tree_empty_message
                    LastTurn -> R.string.changes_source_last_turn_empty_message
                },
            )
}

sealed interface ChangesPhase {
    data object Loading : ChangesPhase

    data object NoTurn : ChangesPhase

    data object NoChanges : ChangesPhase

    data class Content(
        val changeSet: ChangesContentState,
    ) : ChangesPhase

    /** [message] is usually the server's error, verbatim; the screen shows a fallback when it is blank. */
    data class Error(
        val message: UiText,
    ) : ChangesPhase
}

data class ChangesUiState(
    val phase: ChangesPhase = ChangesPhase.Loading,
    val selectedSource: ChangeSource = ChangeSource.LastTurn,
    val availableSources: List<ChangeSource> = listOf(ChangeSource.LastTurn),
    val isRefreshing: Boolean = false,
    val refreshError: UiText? = null,
    /** Set while the selected source is empty but the other source has files. */
    val otherSourceHint: ChangesOtherSourceHint? = null,
)

/** Points from an empty source to the other one, which has [fileCount] changed files. */
data class ChangesOtherSourceHint(
    val source: ChangeSource,
    val fileCount: Int,
) {
    val message: UiText
        get() =
            when (source) {
                ChangeSource.WorkingTree -> UiText.plural(R.plurals.changes_other_source_working_tree, fileCount)
                ChangeSource.LastTurn -> UiText.plural(R.plurals.changes_other_source_last_turn, fileCount)
            }

    val actionTitle: UiText get() = UiText.resource(R.string.changes_other_source_show, source.displayName)
}

data class ChangesContentState(
    val files: List<FileDiff>,
    val filter: String = "",
) {
    val filteredFiles: List<FileDiff>
        get() {
            val query = filter.trim()
            return if (query.isEmpty()) files else files.filter { it.path.contains(query, ignoreCase = true) }
        }

    val fileGroups: List<ChangedFileGroup>
        get() = groupChangedFiles(filteredFiles, files)

    val additions: Int get() = filteredFiles.sumOf(FileDiff::additions)
    val deletions: Int get() = filteredFiles.sumOf(FileDiff::deletions)
    val summary: UiText get() = UiText.plural(R.plurals.changes_summary_changed_files, filteredFiles.size)

    val aggregateAccessibilityLabel: UiText
        get() =
            UiText.resource(
                R.string.changes_accessibility_summary,
                summary,
                additionsLabel(additions),
                deletionsLabel(deletions),
            )

    fun fileAccessibilityLabel(file: FileDiff): UiText {
        val position = filteredFiles.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        return UiText.resource(
            R.string.changes_accessibility_file,
            UiText.verbatim(file.path),
            file.status.displayName(),
            position + 1,
            filteredFiles.size,
            additionsLabel(file.additions),
            deletionsLabel(file.deletions),
        )
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
    private val lastTurnReviewChangesStore: LastTurnReviewChangesStore = LastTurnReviewChangesStore(),
    private val foregroundEventStream: ForegroundEventStream? = null,
    private val vcsSettleDelayMs: Long = VCS_SETTLE_DELAY_MS,
) : ViewModel() {
    @Inject
    constructor(
        repository: SessionRepository,
        activeConnectionManager: ActiveConnectionManager,
        lastTurnReviewChangesStore: LastTurnReviewChangesStore,
        foregroundEventStream: ForegroundEventStream,
    ) : this(
        repository,
        { activeConnectionManager.currentCapabilities() },
        lastTurnReviewChangesStore,
        foregroundEventStream,
    )

    constructor(
        repository: SessionRepository,
        capabilities: ServerCapabilities = ServerCapabilities.forProtocol(ServerProtocol.V1),
    ) : this(repository, { capabilities })

    constructor(
        repository: SessionRepository,
        lastTurnReviewChangesStore: LastTurnReviewChangesStore,
    ) : this(repository, { ServerCapabilities.forProtocol(ServerProtocol.V1) }, lastTurnReviewChangesStore)

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

    init {
        foregroundEventStream?.let { stream ->
            viewModelScope.launch {
                stream.events.collect { event ->
                    if (event.isSessionRunEnd) onSessionIdle(event.sessionId)
                }
            }
        }
    }

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

    /**
     * Neither protocol pushes a diff for the finished turn, so Last turn refetches when its
     * session goes idle. A turn still running compares against the working copy until then.
     */
    private fun onSessionIdle(sessionId: String?) {
        val target = target ?: return
        if (sessionId != target.sessionId || target.messageId == null) return
        if (_uiState.value.selectedSource != ChangeSource.LastTurn) return
        refresh()
    }

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
                    publishLastTurnSummary(target, selectedSource, sortedFiles, failed = false)
                    val phase =
                        when {
                            selectedSource == ChangeSource.LastTurn && target.messageId == null -> ChangesPhase.NoTurn
                            sortedFiles.isEmpty() -> ChangesPhase.NoChanges
                            else -> ChangesPhase.Content(ChangesContentState(sortedFiles, filter))
                        }
                    _uiState.value =
                        ChangesUiState(
                            phase = phase,
                            selectedSource = selectedSource,
                            availableSources = availableSources,
                        )
                    if (phase == ChangesPhase.NoChanges) publishOtherSourceHint(target, selectedSource)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    DiagnosticLog.warning(
                        DiagnosticLogArea.Chat,
                        "load source=${activeSource.logValue()} session=${target.sessionId} directory=${target.worktree} files=0 result=failure durationMs=${System.currentTimeMillis() - startedAt}",
                        error,
                    )
                    if (activeSource == ChangeSource.LastTurn) {
                        publishLastTurnSummary(target, ChangeSource.LastTurn, emptyList(), failed = true)
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
            if (settledVcsInfo(worktree).supportsGit) {
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

    /**
     * A v2 server answers without a branch for a moment after it loads (or reloads) a project.
     * Ask again a few times before treating the folder as having no Git, so Git changes stays
     * available. A folder that is not a Git repository never settles and ends up without it.
     */
    private suspend fun settledVcsInfo(worktree: String): VcsInfo {
        var info = repository.getVcsInfo(worktree)
        repeat(VCS_SETTLE_RETRIES) {
            if (info.isSettled || info.supportsGit) return info
            delay(vcsSettleDelayMs)
            info = repository.getVcsInfo(worktree)
        }
        return info
    }

    /**
     * When the selected source is empty, counts the other source's files so the empty state can
     * point there. Lookup failures leave the plain empty state.
     */
    private suspend fun publishOtherSourceHint(
        target: Target,
        emptySource: ChangeSource,
    ) {
        val other = availableSources.firstOrNull { it != emptySource } ?: return
        val count =
            try {
                when (other) {
                    ChangeSource.WorkingTree -> repository.getVcsStatus(target.worktree).size
                    ChangeSource.LastTurn ->
                        target.messageId?.let { repository.getSessionDiff(target.sessionId, it, target.worktree).size } ?: 0
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                DiagnosticLog.info(DiagnosticLogArea.Chat, "hint source=${other.logValue()} session=${target.sessionId} result=failure")
                return
            }
        if (count <= 0) return
        val current = _uiState.value
        if (current.phase != ChangesPhase.NoChanges || current.selectedSource != emptySource) return
        _uiState.value = current.copy(otherSourceHint = ChangesOtherSourceHint(other, count))
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

    private fun publishLastTurnSummary(
        target: Target,
        source: ChangeSource,
        files: List<FileDiff>,
        failed: Boolean,
    ) {
        if (source != ChangeSource.LastTurn) return
        val messageId = target.messageId ?: return
        lastTurnReviewChangesStore.publish(
            sessionId = target.sessionId,
            messageId = messageId,
            summary = if (failed) null else ReviewChangesSummary.from(files, messageId),
        )
    }

    private fun logLoad(
        source: ChangeSource,
        target: Target,
        fileCount: Int,
        result: String,
        startedAt: Long,
    ) {
        DiagnosticLog.info(
            DiagnosticLogArea.Chat,
            "load source=${source.logValue()} session=${target.sessionId} directory=${target.worktree} files=$fileCount result=$result durationMs=${System.currentTimeMillis() - startedAt}",
        )
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
        /** A cold project settles in well under a second; give it about 1.2 s. */
        const val VCS_SETTLE_RETRIES = 4
        const val VCS_SETTLE_DELAY_MS = 300L
    }
}

/** Copy for a last-turn diff the server will not build because the turn moved folders. */
object TurnDiffUnavailable {
    const val MESSAGE = "This turn moved folders, so its changes can't be shown together."
    const val SERVER_MARKER = "Turn range spans a location change"

    fun userFacing(error: Throwable): String? {
        val http = error as? SessionError.Http ?: return null
        val text = listOfNotNull(http.message, http.body).joinToString("\n")
        return MESSAGE.takeIf { text.contains(SERVER_MARKER) }
    }
}

private fun ChangeSource.logValue() = name.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()

private fun Throwable.isUnsupportedVcs() = this is SessionError.Http && statusCode == 404

/** The moved-folders text, the server's own message verbatim, or a fallback when it has none. */
private fun Throwable.userMessage(): UiText {
    if (TurnDiffUnavailable.userFacing(this) != null) return UiText.resource(R.string.changes_error_turn_moved_folders)
    val serverMessage =
        when (this) {
            is SessionError.Transport -> cause.message
            else -> message
        }
    return serverMessage?.let(UiText::verbatim) ?: UiText.resource(R.string.changes_error_request_failed)
}

private fun FileDiffStatus.displayName(): UiText =
    UiText.resource(
        when (this) {
            FileDiffStatus.Added -> R.string.changes_file_status_added
            FileDiffStatus.Deleted -> R.string.changes_file_status_deleted
            FileDiffStatus.Modified -> R.string.changes_file_status_modified
        },
    )

private fun additionsLabel(count: Int): UiText = UiText.plural(R.plurals.changes_count_additions, count)

private fun deletionsLabel(count: Int): UiText = UiText.plural(R.plurals.changes_count_deletions, count)
