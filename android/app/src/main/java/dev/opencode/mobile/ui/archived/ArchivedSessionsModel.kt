package dev.opencode.mobile.ui.archived

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeScope
import dev.opencode.mobile.domain.session.ArchivedSessionList
import dev.opencode.mobile.domain.session.ArchivedSessionPeriod
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.time.RelativeTimeFormatting
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.ui.components.OpenCodeSnackbarVariant
import dev.opencode.mobile.ui.components.SessionListSession
import dev.opencode.mobile.ui.components.SessionListStatus
import dev.opencode.mobile.ui.components.visibleForFilter
import dev.opencode.mobile.ui.main.ProjectSectionUi
import dev.opencode.mobile.ui.main.homeSessionListSessions
import dev.opencode.mobile.ui.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One archived session as the list shows it: Home's row, captioned with when it was archived. */
data class ArchivedSessionRow(
    val row: SessionListSession,
    val archivedAtEpochMs: Long,
) {
    val id: String get() = row.id
    val title: String get() = row.title

    /** Directory the session runs in, for requests and for opening it. */
    val directory: String get() = row.directory
}

data class ArchivedSessionSection(
    val period: ArchivedSessionPeriod,
    val rows: List<ArchivedSessionRow>,
)

sealed interface ArchivedSessionsContent {
    data object Loading : ArchivedSessionsContent

    data class Failed(
        val message: UiText,
    ) : ArchivedSessionsContent

    /** [canShowAllProjects]: the scope is narrower than All and other projects have archived sessions. */
    data class Loaded(
        val sections: List<ArchivedSessionSection>,
        val canShowAllProjects: Boolean,
    ) : ArchivedSessionsContent {
        val isEmpty: Boolean get() = sections.isEmpty()
    }
}

/** A snackbar; [undoSessionId] puts an Undo on it that archives that session again. */
data class ArchivedSessionsSnackbar(
    val text: UiText,
    val variant: OpenCodeSnackbarVariant = OpenCodeSnackbarVariant.Default,
    val undoSessionId: String? = null,
    val id: Long = System.nanoTime(),
)

data class ArchivedSessionsUiState(
    val scopeTitle: UiText = UiText.resource(R.string.home_all_projects),
    val content: ArchivedSessionsContent = ArchivedSessionsContent.Loading,
    /** The row whose actions sheet or delete confirmation is up. */
    val actionTarget: ArchivedSessionRow? = null,
    val sheetVisible: Boolean = false,
    val deleteConfirmVisible: Boolean = false,
    val snackbar: ArchivedSessionsSnackbar? = null,
)

/**
 * Archived sessions of one Home scope: lists them grouped by when they were archived, and
 * unarchives (with Undo), opens and deletes them. Home's projects name each row; a live
 * archive, unarchive or delete anywhere refreshes the list.
 */
class ArchivedSessionsModel(
    private val sessionRepository: SessionRepository,
    private val sessionDomainAdapter: SessionDomainAdapter,
    private val projects: StateFlow<List<HomeProject>>,
    private val events: Flow<OpencodeEvent>,
    private val scope: CoroutineScope,
    private val worktreesEnabled: () -> Boolean = { true },
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(ArchivedSessionsUiState())
    val state: StateFlow<ArchivedSessionsUiState> = _state.asStateFlow()

    private var scopeId: String = HomeScope.ALL_ID
    private var bound = false

    /** Every Home project's archived sessions, by project key, as last loaded. */
    private var archivedByProject: Map<String, List<Session>> = emptyMap()

    /** Sessions with an unarchive or delete in flight; a refresh must not bring their rows back. */
    private val hiddenIds = mutableSetOf<String>()

    /** Sessions shown again by Undo before the server has archived them. */
    private val restoredRows = mutableMapOf<String, Session>()
    /** Sessions the last unarchive snackbars can archive again. */
    private val undoable = mutableMapOf<String, Session>()
    private var loaded = false
    private var loadJob: Job? = null
    private var refreshJob: Job? = null

    /** Shows the archived sessions of Home scope [scopeId]. Later calls are ignored. */
    fun bind(scopeId: String) {
        if (bound) return
        bound = true
        this.scopeId = scopeId
        publish()
        load()
        scope.launch {
            events.collect { event ->
                if (event.type in REFRESH_EVENT_TYPES) scheduleRefresh()
            }
        }
    }

    fun retry() {
        _state.update { it.copy(content = ArchivedSessionsContent.Loading) }
        load()
    }

    /** Empty state's "Show all projects". */
    fun showAllProjects() {
        scopeId = HomeScope.ALL_ID
        publish()
    }

    fun openActions(row: ArchivedSessionRow) {
        _state.update { it.copy(actionTarget = row, sheetVisible = true, deleteConfirmVisible = false) }
    }

    fun dismissActions() {
        _state.update { it.copy(sheetVisible = false, actionTarget = if (it.deleteConfirmVisible) it.actionTarget else null) }
    }

    /** The sheet's Open: the caller navigates to the returned row's session. */
    fun chooseOpen(): ArchivedSessionRow? {
        val target = _state.value.actionTarget
        _state.update { it.copy(sheetVisible = false, actionTarget = null) }
        return target
    }

    fun chooseUnarchive() {
        val target = _state.value.actionTarget ?: return
        _state.update { it.copy(sheetVisible = false, actionTarget = null) }
        unarchive(target.id)
    }

    fun chooseDelete() {
        if (_state.value.actionTarget == null) return
        _state.update { it.copy(sheetVisible = false, deleteConfirmVisible = true) }
    }

    fun dismissDeleteConfirm() {
        _state.update { it.copy(deleteConfirmVisible = false, actionTarget = null) }
    }

    fun confirmDelete() {
        val target = _state.value.actionTarget ?: return
        _state.update { it.copy(deleteConfirmVisible = false, actionTarget = null) }
        val session = sessionFor(target.id) ?: return
        hide(session.id)
        logI("delete start session=${session.id}")
        scope.launch {
            val deleted =
                try {
                    sessionRepository.deleteSession(id = session.id, directory = target.directory)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logW("delete failed session=${session.id} message=${error.message}")
                    false
                }
            if (deleted) {
                logI("delete success session=${session.id}")
                drop(session.id)
            } else {
                unhide(session.id)
                showSnackbar(ArchivedSessionsSnackbar(DELETE_ERROR, OpenCodeSnackbarVariant.Error))
            }
        }
    }

    /** The row's Unarchive: the row leaves at once and a snackbar offers Undo. */
    fun unarchive(sessionId: String) {
        val session = sessionFor(sessionId) ?: return
        if (sessionId in hiddenIds) return
        val directory = directoryOf(session)
        hide(sessionId)
        logI("unarchive start session=$sessionId")
        scope.launch {
            try {
                sessionRepository.unarchiveSession(id = sessionId, directory = directory)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logW("unarchive failed session=$sessionId message=${error.message}")
                unhide(sessionId)
                showSnackbar(ArchivedSessionsSnackbar(UNARCHIVE_ERROR, OpenCodeSnackbarVariant.Error))
                return@launch
            }
            logI("unarchive success session=$sessionId")
            drop(sessionId)
            showSnackbar(
                ArchivedSessionsSnackbar(
                    text = UiText.resource(R.string.archived_sessions_unarchived, session.title),
                    undoSessionId = sessionId,
                ),
            )
            undoable[sessionId] = session
        }
    }

    /** The snackbar's Undo: archives the session again and puts its row back. */
    fun undoUnarchive(sessionId: String) {
        val session = undoable.remove(sessionId) ?: return
        restoredRows[sessionId] = session.copy(archivedAtEpochMs = nowEpochMs())
        publish()
        logI("undo unarchive session=$sessionId")
        scope.launch {
            try {
                sessionRepository.archiveSession(id = sessionId, directory = directoryOf(session))
                scheduleRefresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logW("undo unarchive failed session=$sessionId message=${error.message}")
                restoredRows.remove(sessionId)
                publish()
                showSnackbar(ArchivedSessionsSnackbar(ARCHIVE_ERROR, OpenCodeSnackbarVariant.Error))
            }
        }
    }

    fun consumeSnackbar() {
        _state.update { it.copy(snackbar = null) }
    }

    private fun load() {
        loadJob?.cancel()
        loadJob =
            scope.launch {
                val homeProjects = projects.value
                val result =
                    try {
                        coroutineScope {
                            homeProjects
                                .map { project ->
                                    async { project.key to ArchivedSessionList.load(sessionRepository, project.memberPaths) }
                                }.awaitAll()
                                .toMap()
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        logW("load failed message=${error.message}")
                        if (_state.value.content !is ArchivedSessionsContent.Loaded) {
                            _state.update { it.copy(content = ArchivedSessionsContent.Failed(LOAD_ERROR)) }
                        }
                        return@launch
                    }
                archivedByProject = result
                this@ArchivedSessionsModel.loaded = true
                // The server now has every row Undo put back.
                restoredRows.keys.removeAll { id -> result.values.any { list -> list.any { it.id == id } } }
                publish()
            }
    }

    private fun scheduleRefresh() {
        refreshJob?.cancel()
        refreshJob =
            scope.launch {
                delay(REFRESH_DEBOUNCE_MS)
                load()
            }
    }

    private fun hide(sessionId: String) {
        hiddenIds += sessionId
        publish()
    }

    private fun unhide(sessionId: String) {
        hiddenIds -= sessionId
        publish()
    }

    /** The server no longer lists [sessionId] as archived. */
    private fun drop(sessionId: String) {
        hiddenIds -= sessionId
        restoredRows.remove(sessionId)
        archivedByProject = archivedByProject.mapValues { (_, list) -> list.filterNot { it.id == sessionId } }
        publish()
    }

    private fun showSnackbar(snackbar: ArchivedSessionsSnackbar) {
        _state.update { it.copy(snackbar = snackbar) }
    }

    private fun sessionFor(sessionId: String): Session? =
        restoredRows[sessionId] ?: archivedByProject.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == sessionId } }

    private fun directoryOf(session: Session): String =
        session.directory?.takeIf { it.isNotBlank() }
            ?: projects.value.firstOrNull { project -> archivedByProject[project.key].orEmpty().any { it.id == session.id } }?.primaryPath
            ?: ""

    private fun publish() {
        val homeProjects = projects.value
        val worktrees = worktreesEnabled()
        val resolved = HomeScope.resolve(scopeId, homeProjects, worktrees) ?: HomeScope.All
        val resolvedId = HomeScope.id(resolved)
        val now = nowEpochMs()
        val restoredByProject =
            restoredRows.values.groupBy { session ->
                homeProjects.firstOrNull { project -> session.directory?.let(project::contains) == true }?.key
            }
        val sessionsByProject =
            homeProjects.associate { project ->
                val listed = archivedByProject[project.key].orEmpty()
                val restored = restoredByProject[project.key].orEmpty().filter { r -> listed.none { it.id == r.id } }
                project.key to ArchivedSessionList.sorted(listed + restored).filterNot { it.id in hiddenIds }
            }
        val archivedAt = sessionsByProject.values.flatten().associate { it.id to (it.archivedAtEpochMs ?: 0L) }
        val sections =
            homeProjects.map { project ->
                ProjectSectionUi(
                    worktree = project.primaryPath,
                    displayName = project.displayName,
                    pathSubtitle = project.primaryPath,
                    letter = letterCharacterForProject(project.displayName),
                    letterAccent = letterAccentForWorktree(project.primaryPath),
                    allSessions = sessionsByProject[project.key].orEmpty().map(sessionDomainAdapter::toSessionSummary),
                )
            }
        val allRows =
            homeSessionListSessions(homeProjects, sections, worktreesEnabled = worktrees)
                .map { row ->
                    val at = archivedAt[row.id] ?: 0L
                    ArchivedSessionRow(
                        row = row.copy(status = SessionListStatus.Idle, caption = RelativeTimeFormatting.format(at, now)),
                        archivedAtEpochMs = at,
                    )
                }.sortedWith(compareByDescending<ArchivedSessionRow> { it.archivedAtEpochMs }.thenBy { it.id })
        val visibleIds = allRows.map { it.row }.visibleForFilter(resolvedId).mapTo(mutableSetOf()) { it.id }
        val visible = allRows.filter { it.id in visibleIds }
        _state.update { current ->
            current.copy(
                scopeTitle = scopeTitle(resolved, homeProjects),
                content =
                    if (!loaded) {
                        current.content
                    } else {
                        ArchivedSessionsContent.Loaded(
                            sections =
                                ArchivedSessionPeriod.entries.mapNotNull { period ->
                                    visible
                                        .filter { ArchivedSessionList.period(it.archivedAtEpochMs, now) == period }
                                        .takeIf { it.isNotEmpty() }
                                        ?.let { ArchivedSessionSection(period, it) }
                                },
                            canShowAllProjects = resolved != HomeScope.All && allRows.isNotEmpty(),
                        )
                    },
            )
        }
    }

    private fun scopeTitle(
        scope: HomeScope,
        homeProjects: List<HomeProject>,
    ): UiText =
        when (scope) {
            HomeScope.All -> UiText.resource(R.string.home_all_projects)
            is HomeScope.Project -> UiText.verbatim(homeProjects.firstOrNull { it.key == scope.key }?.displayName.orEmpty())
            is HomeScope.Worktree -> {
                val project = homeProjects.firstOrNull { it.key == scope.projectKey }
                val worktree = project?.worktree(scope.directory)
                UiText.verbatim(listOfNotNull(project?.displayName, worktree?.folderName).joinToString(" · "))
            }
        }

    private fun logI(message: String) {
        DiagnosticLog.info(DiagnosticLogArea.Session, "archived $message")
    }

    private fun logW(message: String) {
        DiagnosticLog.warning(DiagnosticLogArea.Session, "archived $message")
    }

    companion object {
        /** Live events after which the archived list may have changed. */
        val REFRESH_EVENT_TYPES = setOf("session.archived", "session.unarchived", "session.deleted")
        const val REFRESH_DEBOUNCE_MS = 300L
        private val LOAD_ERROR = UiText.resource(R.string.archived_sessions_load_failed)
        private val UNARCHIVE_ERROR = UiText.resource(R.string.archived_sessions_unarchive_failed)
        private val ARCHIVE_ERROR = UiText.resource(R.string.home_session_error_archive_failed)
        private val DELETE_ERROR = UiText.resource(R.string.home_session_error_delete_failed)
    }
}
