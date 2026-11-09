package dev.opencode.mobile.ui.worktrees

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeWorktree
import dev.opencode.mobile.domain.home.HomeWorktreeRequest
import dev.opencode.mobile.domain.home.HomeWorktreesFeed
import dev.opencode.mobile.domain.home.HomeWorktreesSnapshot
import dev.opencode.mobile.domain.home.worktreePathKey
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.worktree.RemoveWorktreeRequest
import dev.opencode.mobile.domain.worktree.WorktreeRemoveError
import dev.opencode.mobile.domain.worktree.WorktreeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One worktree row: never the main checkout. */
data class WorktreeRowUi(
    val projectKey: String,
    val directory: String,
    /** Branch, else folder name. */
    val name: String,
    val isRunning: Boolean,
    /** Uncommitted changes the server reported; null until known or when it could not tell. */
    val changeCount: Int?,
    val isRemoving: Boolean,
)

/** A project and its worktrees, under its main checkout. */
data class WorktreesGroupUi(
    val projectKey: String,
    val displayName: String,
    /** Added path that colours the project badge. */
    val primaryPath: String,
    val mainCheckout: String,
    val worktrees: List<WorktreeRowUi>,
)

/** Bottom sheet of a worktree's actions. */
data class WorktreeActionsUi(
    val projectKey: String,
    val directory: String,
    val name: String,
    val projectName: String,
    /** Home shows worktrees, so its scope picker can select this one. */
    val canShowOnHome: Boolean,
)

sealed interface WorktreeRemoveDialogUi {
    val name: String

    /**
     * Remove, or Remove anyway when uncommitted work may be lost: [changeCount] changes,
     * [changesUnknown] (the server refused without force and gave no count), or
     * [changesUnchecked] (the change count could not be read, so the screen cannot say).
     */
    data class Confirm(
        val directory: String,
        override val name: String,
        val changeCount: Int?,
        val changesUnknown: Boolean,
        /** Home lists a session here; the dialog says sessions stay, without a count (#981). */
        val hasSessions: Boolean,
        val changesUnchecked: Boolean = false,
    ) : WorktreeRemoveDialogUi {
        /** Known to lose changes, so a v2 remove forces. */
        val losesChanges: Boolean
            get() = changesUnknown || (changeCount ?: 0) > 0

        /** Remove anyway rather than Remove. */
        val warnsAboutChanges: Boolean
            get() = losesChanges || changesUnchecked
    }

    /** A session runs in the worktree: stop it first. */
    data class Blocked(
        override val name: String,
        val sessionId: String,
        val sessionTitle: String,
        val sessionDirectory: String,
    ) : WorktreeRemoveDialogUi
}

sealed interface WorktreesSnackbarUi {
    val id: Long

    data class Removed(
        val name: String,
        override val id: Long = System.nanoTime(),
    ) : WorktreesSnackbarUi

    /** [message] is the server's own words; null when the server could not be reached. */
    data class Failed(
        val name: String,
        val directory: String,
        val message: String?,
        override val id: Long = System.nanoTime(),
    ) : WorktreesSnackbarUi
}

data class WorktreesUiState(
    val groups: List<WorktreesGroupUi> = emptyList(),
    val actions: WorktreeActionsUi? = null,
    val dialog: WorktreeRemoveDialogUi? = null,
    val snackbar: WorktreesSnackbarUi? = null,
) {
    val worktreeCount: Int
        get() = groups.sumOf { it.worktrees.size }

    val projectCount: Int
        get() = groups.size
}

sealed interface WorktreesEvent {
    /** Back to Home, which already has the request. */
    data object Close : WorktreesEvent

    data class OpenSession(
        val sessionId: String,
        val title: String,
        val directory: String,
    ) : WorktreesEvent
}

/**
 * Worktrees screen: every worktree of Home's projects, its sessions and uncommitted changes, and
 * removing one. Projects and sessions come from Home through [HomeWorktreesFeed]; Show sessions,
 * New session and a finished remove go back to Home the same way.
 */
@HiltViewModel
class WorktreesViewModel
    @Inject
    constructor(
        private val feed: HomeWorktreesFeed,
        private val worktreeRepository: WorktreeRepository,
        private val sessionRepository: SessionRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(WorktreesUiState())
        val state: StateFlow<WorktreesUiState> = _state.asStateFlow()

        private val _events = Channel<WorktreesEvent>(Channel.BUFFERED)
        val events: Flow<WorktreesEvent> = _events.receiveAsFlow()

        /** Home shows worktrees; false hides Show sessions on Home. */
        var homeShowsWorktrees: Boolean = true

        private var snapshot = HomeWorktreesSnapshot()
        private val changeCounts = mutableMapOf<String, Int?>()
        private val changesRequested = mutableSetOf<String>()
        private val removing = mutableSetOf<String>()

        /** Removed here; hidden until Home's list no longer has them. */
        private val removed = mutableSetOf<String>()

        init {
            viewModelScope.launch {
                feed.snapshot.collect { next ->
                    if (next.projects.isEmpty()) {
                        // Another server or a disconnect: its counts do not apply.
                        changeCounts.clear()
                        changesRequested.clear()
                    }
                    snapshot = next
                    loadChangeCounts()
                    publish()
                }
            }
        }

        fun openActions(directory: String) {
            val (project, worktree) = find(directory) ?: return
            _state.update {
                it.copy(
                    actions =
                        WorktreeActionsUi(
                            projectKey = project.key,
                            directory = worktree.directory,
                            name = worktree.folderName,
                            projectName = project.displayName,
                            canShowOnHome = homeShowsWorktrees,
                        ),
                )
            }
        }

        fun dismissActions() {
            _state.update { it.copy(actions = null) }
        }

        fun showOnHome(directory: String) {
            val (project, worktree) = find(directory) ?: return
            dismissActions()
            feed.send(HomeWorktreeRequest.ShowSessions(project.key, worktree.directory))
            _events.trySend(WorktreesEvent.Close)
        }

        fun newSessionHere(directory: String) {
            val (_, worktree) = find(directory) ?: return
            dismissActions()
            feed.send(HomeWorktreeRequest.NewSession(worktree.directory))
            _events.trySend(WorktreesEvent.Close)
        }

        /** Remove worktree…: a running session blocks it; otherwise confirm. */
        fun requestRemove(directory: String) {
            val (_, worktree) = find(directory) ?: return
            dismissActions()
            val sessions = snapshot.sessionsIn(worktree.directory)
            val name = worktree.folderName
            val running = sessions.firstOrNull { it.isRunning }
            val dialog =
                if (running != null) {
                    WorktreeRemoveDialogUi.Blocked(name, running.id, running.title, running.directory)
                } else {
                    WorktreeRemoveDialogUi.Confirm(
                        directory = worktree.directory,
                        name = name,
                        changeCount = changeCounts[worktree.directory],
                        changesUnknown = false,
                        hasSessions = sessions.isNotEmpty(),
                        // Still loading or failed. v1 removes with force, so say it rather than act clean.
                        changesUnchecked = changeCounts[worktree.directory] == null,
                    )
                }
            _state.update { it.copy(dialog = dialog) }
        }

        fun dismissDialog() {
            _state.update { it.copy(dialog = null) }
        }

        fun confirmRemove() {
            val dialog = _state.value.dialog as? WorktreeRemoveDialogUi.Confirm ?: return
            _state.update { it.copy(dialog = null) }
            remove(dialog.directory, force = dialog.losesChanges)
        }

        fun showRunningSession() {
            val dialog = _state.value.dialog as? WorktreeRemoveDialogUi.Blocked ?: return
            _state.update { it.copy(dialog = null) }
            _events.trySend(WorktreesEvent.OpenSession(dialog.sessionId, dialog.sessionTitle, dialog.sessionDirectory))
        }

        /** Retry from the failure snackbar: the same remove, after the same confirmation. */
        fun retry(directory: String) {
            _state.update { it.copy(snackbar = null) }
            requestRemove(directory)
        }

        fun snackbarShown(id: Long) {
            _state.update { if (it.snackbar?.id == id) it.copy(snackbar = null) else it }
        }

        private fun remove(
            directory: String,
            force: Boolean,
        ) {
            val (project, worktree) = find(directory) ?: return
            if (!removing.add(worktree.directory)) return
            val name = worktree.folderName
            publish()
            viewModelScope.launch {
                val outcome =
                    try {
                        worktreeRepository.remove(
                            RemoveWorktreeRequest(
                                projectId = project.projectId,
                                projectDirectory = project.mainCheckoutDirectory,
                                directory = worktree.directory,
                                force = force,
                            ),
                        )
                        null
                    } catch (cancellation: CancellationException) {
                        removing.remove(worktree.directory)
                        throw cancellation
                    } catch (error: WorktreeRemoveError) {
                        error
                    }
                removing.remove(worktree.directory)
                when (outcome) {
                    null -> {
                        removed += worktree.directory
                        feed.send(HomeWorktreeRequest.Removed(project.key, worktree.directory))
                        _state.update { it.copy(snackbar = WorktreesSnackbarUi.Removed(name)) }
                    }
                    is WorktreeRemoveError.ForceRequired -> {
                        // The count was unknown or stale: say changes will be lost, then force.
                        val knownChanges = changeCounts[worktree.directory]?.takeIf { count -> count > 0 }
                        changeCounts[worktree.directory] = null
                        _state.update {
                            it.copy(
                                dialog =
                                    WorktreeRemoveDialogUi.Confirm(
                                        directory = worktree.directory,
                                        name = name,
                                        changeCount = knownChanges,
                                        changesUnknown = true,
                                        hasSessions = snapshot.sessionsIn(worktree.directory).isNotEmpty(),
                                    ),
                            )
                        }
                    }
                    else -> {
                        DiagnosticLog.warning(DiagnosticLogArea.Session, "worktree remove failed: ${outcome.message}")
                        val message = outcome.message.takeUnless { outcome is WorktreeRemoveError.Unreachable }
                        _state.update {
                            it.copy(snackbar = WorktreesSnackbarUi.Failed(name, worktree.directory, message))
                        }
                    }
                }
                publish()
            }
        }

        private fun loadChangeCounts() {
            val directories =
                snapshot.projects
                    .flatMap { project -> snapshot.removableWorktrees(project) }
                    .map { it.directory }
                    .filter { changesRequested.add(it) }
            for (directory in directories) {
                viewModelScope.launch {
                    val count =
                        try {
                            sessionRepository.getVcsStatus(directory).size
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            null
                        }
                    changeCounts[directory] = count
                    publish()
                }
            }
        }

        private fun find(directory: String): Pair<HomeProject, HomeWorktree>? {
            val key = worktreePathKey(directory)
            for (project in snapshot.projects) {
                val worktree = snapshot.removableWorktrees(project).firstOrNull { worktreePathKey(it.directory) == key } ?: continue
                return project to worktree
            }
            return null
        }

        private fun publish() {
            // Home lists the project again after a remove; until then the row stays hidden here.
            removed.retainAll { directory -> find(directory) != null }
            val groups =
                snapshot.projects.filter { it.isRepository }.map { project ->
                    WorktreesGroupUi(
                        projectKey = project.key,
                        displayName = project.displayName,
                        primaryPath = project.primaryPath,
                        mainCheckout = project.mainCheckoutDirectory,
                        worktrees =
                            snapshot
                                .removableWorktrees(project)
                                .filter { it.directory !in removed }
                                .map { worktree ->
                                    val sessions = snapshot.sessionsIn(worktree.directory)
                                    WorktreeRowUi(
                                        projectKey = project.key,
                                        directory = worktree.directory,
                                        name = worktree.folderName,
                                        isRunning = sessions.any { it.isRunning },
                                        changeCount = changeCounts[worktree.directory],
                                        isRemoving = worktree.directory in removing,
                                    )
                                },
                    )
                }
            _state.update { it.copy(groups = groups) }
        }
    }
