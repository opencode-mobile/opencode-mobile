package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.domain.event.SessionMovedUpdate
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeWorktreeCopy
import dev.opencode.mobile.domain.session.ServerErrorMessage
import dev.opencode.mobile.ui.worktree.NewWorktreeAvailability
import dev.opencode.mobile.ui.worktree.WorktreeCreateFormState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SessionChatWorktreeMoveCopy {
    const val MENU_TITLE = "Move to worktree…"
    const val SHEET_TITLE = "Move to worktree"
    const val NEEDS_V2 = "Needs an opencode v2 server"
    const val TURN_ON_WORKTREES = "Turn on Worktrees in Settings"
    const val NO_OTHER_WORKTREES = "This project has no other worktrees"

    fun movesAfterTurn(worktreeTitle: String): String = "Moves to $worktreeTitle after this turn."

    fun failed(message: String): String = "Couldn't move the session: $message"
}

/** Stable test tags of Move to worktree (UI tests and real-app evidence). */
object SessionChatWorktreeMoveTags {
    const val MENU_ENTRY = "sessionChatMoveToWorktree"

    fun row(title: String): String = "moveToWorktreeRow:$title"
}

/** Whether Session Chat's "Move to worktree…" entry works, and why not. */
data class SessionChatWorktreeMoveAvailability(
    val enabled: Boolean,
    val subtitle: String? = null,
) {
    companion object {
        /**
         * Checks in order; the first that fails explains the disabled entry. A project with one
         * checkout still opens the sheet while its "New worktree…" row can create one.
         */
        fun resolve(
            supportsSessionMove: Boolean,
            worktreesEnabled: Boolean,
            sessionCreated: Boolean,
            worktreeCount: Int,
            canCreateWorktree: Boolean = false,
        ): SessionChatWorktreeMoveAvailability =
            when {
                !supportsSessionMove -> SessionChatWorktreeMoveAvailability(false, SessionChatWorktreeMoveCopy.NEEDS_V2)
                !worktreesEnabled -> SessionChatWorktreeMoveAvailability(false, SessionChatWorktreeMoveCopy.TURN_ON_WORKTREES)
                !sessionCreated -> SessionChatWorktreeMoveAvailability(false)
                worktreeCount < 2 && !canCreateWorktree ->
                    SessionChatWorktreeMoveAvailability(false, SessionChatWorktreeMoveCopy.NO_OTHER_WORKTREES)
                else -> SessionChatWorktreeMoveAvailability(true)
            }
    }
}

/** One worktree in the Move to worktree sheet; the session's current one is checked and inert. */
data class SessionChatWorktreeMoveRow(
    val directory: String,
    val title: String,
    val subtitle: String,
    val isCurrent: Boolean,
) {
    companion object {
        /** The project's worktrees in Home order (main checkout first). */
        fun rows(
            project: HomeProject?,
            currentDirectory: String?,
        ): List<SessionChatWorktreeMoveRow> {
            project ?: return emptyList()
            val current = currentDirectory?.trim()?.takeIf { it.isNotEmpty() }?.let(ServerPathPolicy::canonical)
            return project.worktrees.map { worktree ->
                SessionChatWorktreeMoveRow(
                    directory = worktree.directory,
                    title = HomeWorktreeCopy.worktreeTitle(worktree),
                    subtitle = HomeWorktreeCopy.worktreeSubtitle(worktree),
                    isCurrent = worktree.directory == current,
                )
            }
        }

        /** A checkout made from the Move sheet; titled by its branch, else its folder. */
        fun created(
            directory: String,
            title: String,
        ): SessionChatWorktreeMoveRow =
            SessionChatWorktreeMoveRow(
                directory = directory,
                title = title,
                subtitle = ServerPathPolicy.name(directory).ifBlank { directory },
                isCurrent = false,
            )
    }
}

/** What Session Chat shows for Move to worktree. */
data class SessionChatWorktreeMoveUiState(
    val availability: SessionChatWorktreeMoveAvailability = SessionChatWorktreeMoveAvailability(false),
    val sheetVisible: Boolean = false,
    val rows: List<SessionChatWorktreeMoveRow> = emptyList(),
    /** The destination whose request is in flight; rows stay inert meanwhile. */
    val submittingDirectory: String? = null,
    /** The "New worktree…" row after the worktrees. */
    val newWorktree: NewWorktreeAvailability = NewWorktreeAvailability(false),
    /** The New worktree form shown in the sheet in place of the list; null shows the list. */
    val form: WorktreeCreateFormState? = null,
)

data class SessionChatWorktreeMoveRegionState(
    val sheetVisible: Boolean = false,
    val submittingDirectory: String? = null,
    /** "Moves to … after this turn." after a move the server parks until the turn ends. */
    val notice: String? = null,
    val errorMessage: String? = null,
)

/**
 * Session Chat region for "Move to worktree…": the sheet, the v2 move request, and the
 * `session.moved` signal that the session runs somewhere else now.
 */
class SessionChatWorktreeMove(
    /** The session repository's `moveSession`. */
    private val moveSession: suspend (sessionId: String, directory: String) -> Unit,
    private val scope: CoroutineScope,
) {
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _state = MutableStateFlow(SessionChatWorktreeMoveRegionState())
    val state: StateFlow<SessionChatWorktreeMoveRegionState> = _state.asStateFlow()

    private var moveJob: Job? = null

    /** A new session or directory starts with nothing pending. */
    fun reset() {
        moveJob?.cancel()
        moveJob = null
        _state.value = SessionChatWorktreeMoveRegionState()
    }

    fun openSheet() {
        _state.update { it.copy(sheetVisible = true) }
    }

    fun dismissSheet() {
        _state.update { it.copy(sheetVisible = false) }
    }

    /**
     * Asks the server to move [sessionId] to [destination]. A busy session moves when its turn
     * ends, so [isBusy] adds the notice saying so.
     */
    fun move(
        sessionId: String,
        destination: SessionChatWorktreeMoveRow,
        isBusy: Boolean,
    ) {
        if (destination.isCurrent || _state.value.submittingDirectory != null) return
        _state.update { it.copy(submittingDirectory = destination.directory, errorMessage = null, notice = null) }
        moveJob =
            scope.launch {
                try {
                    withContext(ioDispatcher) { moveSession(sessionId, destination.directory) }
                    _state.update {
                        it.copy(
                            sheetVisible = false,
                            submittingDirectory = null,
                            notice = if (isBusy) SessionChatWorktreeMoveCopy.movesAfterTurn(destination.title) else null,
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    val message = ServerErrorMessage.from(error) ?: error.message?.takeIf { it.isNotBlank() } ?: "Unknown error"
                    _state.update {
                        it.copy(
                            sheetVisible = false,
                            submittingDirectory = null,
                            errorMessage = SessionChatWorktreeMoveCopy.failed(message),
                        )
                    }
                }
            }
    }

    /**
     * A `session.moved` for [boundSessionId]: returns the directory Session Chat now runs in, or
     * null when the event is for another session or names [boundDirectory] already. A move that
     * lands closes the sheet and clears the "after this turn" notice.
     */
    fun onSessionMoved(
        moved: SessionMovedUpdate,
        boundSessionId: String?,
        boundDirectory: String?,
    ): String? {
        if (moved.sessionId != boundSessionId) return null
        val destination = ServerPathPolicy.canonical(moved.directory)
        if (boundDirectory != null && ServerPathPolicy.canonical(boundDirectory) == destination) return null
        _state.update { it.copy(sheetVisible = false, notice = null) }
        return moved.directory
    }

    fun clearMessages() {
        _state.update { it.copy(notice = null, errorMessage = null) }
    }
}
