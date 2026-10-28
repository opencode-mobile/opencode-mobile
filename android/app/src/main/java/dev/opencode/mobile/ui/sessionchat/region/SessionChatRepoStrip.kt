package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.VcsFileStatus
import dev.opencode.mobile.domain.session.VcsInfo
import dev.opencode.mobile.ui.sessionchat.SessionChatRepoStripRegionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Session Chat region for the repo strip: current branch plus uncommitted working-tree changes.
 *
 * Fetches `GET vcs` and `GET vcs/status` for the bound worktree on bind, after each turn
 * completes, and on a `vcs.branch.updated` event. A new session starts expanded when the tree
 * is dirty; an active session starts collapsed. The user toggle wins over both defaults.
 */
class SessionChatRepoStrip(
    private val sessionRepository: SessionRepository,
    private val foregroundEventStream: ForegroundEventStream? = null,
    private val scope: CoroutineScope,
) {
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _state = MutableStateFlow(SessionChatRepoStripRegionState())
    val state: StateFlow<SessionChatRepoStripRegionState> = _state.asStateFlow()

    private var boundWorktree: String? = null
    private var expandWhenDirty = false
    private var userToggled = false
    private var lastTurnActive: Boolean? = null
    private var refreshJob: Job? = null
    private var eventJob: Job? = null

    /**
     * Binds the strip to [worktree]. [startExpanded] sets the first-load default: a new session
     * shows the file list right away; an active session shows only the summary row.
     * Rebinding the same worktree keeps state and disclosure.
     */
    fun bind(
        worktree: String,
        startExpanded: Boolean,
    ) {
        if (boundWorktree == worktree) return
        boundWorktree = worktree
        expandWhenDirty = startExpanded
        userToggled = false
        lastTurnActive = null
        _state.value = SessionChatRepoStripRegionState()
        refresh()
        eventJob?.cancel()
        eventJob =
            foregroundEventStream?.let { stream ->
                scope.launch {
                    stream.events
                        .filter { it.type == BRANCH_UPDATED_EVENT }
                        .collect { refresh() }
                }
            }
    }

    /**
     * The session moved to [worktree]. Shows [knownBranch] (what Home last saw there) until the
     * server answers, so the pill switches at once instead of blanking.
     */
    fun moveTo(
        worktree: String,
        knownBranch: String?,
    ) {
        if (boundWorktree == worktree) return
        boundWorktree = worktree
        expandWhenDirty = false
        userToggled = false
        _state.value =
            if (knownBranch.isNullOrBlank()) {
                SessionChatRepoStripRegionState()
            } else {
                SessionChatRepoStripRegionState(isAvailable = true, branch = knownBranch)
            }
        refresh()
    }

    fun toggle() {
        val current = _state.value
        if (current.files.isEmpty()) return
        userToggled = true
        _state.value = current.copy(isExpanded = !current.isExpanded)
    }

    /** Refetches when a turn goes from active to idle so the strip reflects the agent's edits. */
    fun updateTurnActive(isTurnActive: Boolean) {
        val previous = lastTurnActive
        lastTurnActive = isTurnActive
        if (previous == true && !isTurnActive) refresh()
    }

    fun refresh() {
        val worktree = boundWorktree ?: return
        refreshJob?.cancel()
        refreshJob =
            scope.launch {
                val info =
                    runCatching { withContext(ioDispatcher) { sessionRepository.getVcsInfo(worktree) } }
                        .getOrNull()
                if (boundWorktree != worktree) return@launch
                // A cold v2 directory answers without a provider first; keep the branch shown.
                if (info != null && !info.isSettled && _state.value.isAvailable) return@launch
                val branch = info?.currentBranch
                if (info == null || branch == null) {
                    _state.value = SessionChatRepoStripRegionState()
                    return@launch
                }
                val files =
                    runCatching { withContext(ioDispatcher) { sessionRepository.getVcsStatus(worktree) } }
                        .getOrElse { error ->
                            // Servers without the status route still show the branch row.
                            if (error is SessionError.Http && error.statusCode == 404) emptyList() else return@launch
                        }
                if (boundWorktree != worktree) return@launch
                _state.value = resolved(info, files)
            }
    }

    private fun resolved(
        info: VcsInfo,
        files: List<VcsFileStatus>,
    ): SessionChatRepoStripRegionState {
        val previous = _state.value
        val isExpanded =
            when {
                files.isEmpty() -> false
                userToggled -> previous.isExpanded
                !previous.isAvailable -> expandWhenDirty
                else -> previous.isExpanded
            }
        return SessionChatRepoStripRegionState(
            isAvailable = true,
            branch = requireNotNull(info.currentBranch),
            defaultBranch = info.defaultBranch,
            files = files,
            isExpanded = isExpanded,
        )
    }

    companion object {
        const val BRANCH_UPDATED_EVENT = "vcs.branch.updated"
    }
}
