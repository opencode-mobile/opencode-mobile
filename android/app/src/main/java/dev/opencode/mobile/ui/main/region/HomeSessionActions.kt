package dev.opencode.mobile.ui.main.region

import dev.opencode.mobile.domain.command.ClientSlashAction
import dev.opencode.mobile.domain.command.ClientSlashActionCatalog
import dev.opencode.mobile.domain.engine.SessionCapabilities
import dev.opencode.mobile.domain.command.ClientSlashMessageRef
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.ui.main.HomeSessionActionTarget
import dev.opencode.mobile.ui.main.HomeSessionActionsUiState
import dev.opencode.mobile.ui.main.SessionOpenTarget
import dev.opencode.mobile.ui.main.suggestedHomeForkTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Long-press session actions on home: sheet, rename / fork dialogs, archive, delete.
 */
class HomeSessionActions(
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val scope: CoroutineScope,
    private val onLifecycleError: (String) -> Unit,
    private val capabilities: () -> SessionCapabilities = { SessionCapabilities.OpenCode },
) {
    private val _state = MutableStateFlow(HomeSessionActionsUiState())
    val state: StateFlow<HomeSessionActionsUiState> = _state.asStateFlow()

    private var forkGateJob: Job? = null
    private var forkedOpenTarget: SessionOpenTarget? = null

    fun begin(
        sessionId: String,
        title: String,
        worktree: String,
        canArchive: Boolean,
    ) {
        forkGateJob?.cancel()
        forkedOpenTarget = null
        _state.value =
            HomeSessionActionsUiState(
                target =
                    HomeSessionActionTarget(
                        sessionId = sessionId,
                        title = title,
                        worktree = worktree,
                    ),
                sheetVisible = true,
                canArchive = canArchive,
            )
        forkGateJob = scope.launch { resolveForkAvailability(sessionId, worktree) }
    }

    fun dismissSheet() {
        _state.update { it.copy(sheetVisible = false) }
        if (!_state.value.hasOpenDialog) {
            clear()
        }
    }

    fun chooseRename() {
        val target = _state.value.target ?: return
        _state.update {
            it.copy(
                sheetVisible = false,
                renameVisible = true,
                renameDraft = target.title,
                renameSubmitting = false,
                renameError = null,
            )
        }
    }

    fun updateRenameDraft(text: String) {
        _state.update { it.copy(renameDraft = text, renameError = null) }
    }

    fun cancelRename() {
        if (_state.value.renameSubmitting) return
        clear()
    }

    fun confirmRename(onOutcome: (String) -> Unit) {
        val snapshot = _state.value
        val target = snapshot.target ?: return
        if (snapshot.renameSubmitting) return
        val trimmed = snapshot.renameDraft.trim()
        if (trimmed.isEmpty()) {
            onOutcome(OUTCOME_BLOCKED)
            _state.update { it.copy(renameError = EMPTY_TITLE_ERROR) }
            return
        }
        if (trimmed == target.title) {
            onOutcome(OUTCOME_BLOCKED)
            clear()
            return
        }
        _state.update { it.copy(renameSubmitting = true, renameError = null) }
        logI("rename submit session=${target.sessionId}")
        scope.launch {
            runCatching {
                sessionRepository.updateSession(
                    id = target.sessionId,
                    title = trimmed,
                    directory = target.worktree,
                )
            }.onSuccess {
                logI("rename success session=${target.sessionId}")
                onOutcome(OUTCOME_SUCCESS)
                clear(target.sessionId)
            }.onFailure { error ->
                logW("rename failed session=${target.sessionId} message=${error.message}")
                onOutcome(OUTCOME_FAILURE)
                _state.update { current ->
                    if (current.target?.sessionId != target.sessionId) {
                        current
                    } else {
                        current.copy(renameSubmitting = false, renameError = mutationErrorMessage(error))
                    }
                }
            }
        }
    }

    fun chooseFork() {
        val snapshot = _state.value
        val target = snapshot.target ?: return
        if (!snapshot.canFork) return
        _state.update {
            it.copy(
                sheetVisible = false,
                forkVisible = true,
                forkDraft = suggestedHomeForkTitle(target.title),
                forkSubmitting = false,
                forkError = null,
            )
        }
    }

    fun updateForkDraft(text: String) {
        _state.update { it.copy(forkDraft = text, forkError = null) }
    }

    fun cancelFork() {
        if (_state.value.forkSubmitting) return
        clear()
    }

    fun confirmFork(onOutcome: (String) -> Unit) {
        val snapshot = _state.value
        val target = snapshot.target ?: return
        if (snapshot.forkSubmitting) return
        val title = snapshot.forkDraft.trim()
        if (title.isEmpty()) {
            onOutcome(OUTCOME_BLOCKED)
            _state.update { it.copy(forkError = EMPTY_TITLE_ERROR) }
            return
        }
        _state.update { it.copy(forkSubmitting = true, forkError = null) }
        logI("fork submit session=${target.sessionId}")
        scope.launch {
            runCatching {
                val forked =
                    sessionRepository.forkSession(
                        id = target.sessionId,
                        atMessageId = null,
                        directory = target.worktree,
                    )
                sessionRepository.updateSession(
                    id = forked.id,
                    title = title,
                    directory = target.worktree,
                )
            }.onSuccess { forked ->
                logI("fork success session=${target.sessionId} forkedSession=${forked.id}")
                onOutcome(OUTCOME_SUCCESS)
                forkedOpenTarget =
                    SessionOpenTarget(
                        sessionId = forked.id,
                        title = forked.title,
                        worktree = target.worktree,
                    )
                _state.update { current ->
                    if (current.target?.sessionId != target.sessionId) {
                        current
                    } else {
                        current.copy(
                            forkVisible = false,
                            forkSubmitting = false,
                            forkResultVisible = true,
                            forkedTitle = forked.title,
                        )
                    }
                }
            }.onFailure { error ->
                logW("fork failed session=${target.sessionId} message=${error.message}")
                onOutcome(OUTCOME_FAILURE)
                _state.update { current ->
                    if (current.target?.sessionId != target.sessionId) {
                        current
                    } else {
                        current.copy(forkSubmitting = false, forkError = mutationErrorMessage(error))
                    }
                }
            }
        }
    }

    fun takeForkedOpenTarget(): SessionOpenTarget? {
        val target = forkedOpenTarget
        forkedOpenTarget = null
        clear()
        return target
    }

    fun clearForkedOpenTarget() {
        forkedOpenTarget = null
        clear()
    }

    fun chooseArchive() {
        val snapshot = _state.value
        val target = snapshot.target ?: return
        if (!snapshot.canArchive) return
        if (snapshot.lifecycleInFlight) return
        _state.update { it.copy(sheetVisible = false, lifecycleInFlight = true) }
        logI("archive start session=${target.sessionId}")
        scope.launch {
            runCatching { sessionRepository.archiveSession(id = target.sessionId, directory = target.worktree) }
                .onSuccess {
                    logI("archive success session=${target.sessionId}")
                    clear(target.sessionId)
                }.onFailure { error ->
                    logW("archive failed session=${target.sessionId} message=${error.message}")
                    clear(target.sessionId)
                    onLifecycleError(ARCHIVE_ERROR)
                }
        }
    }

    fun chooseDelete() {
        if (_state.value.target == null) return
        _state.update { it.copy(sheetVisible = false, deleteConfirmVisible = true) }
    }

    fun dismissDeleteConfirm() {
        if (_state.value.lifecycleInFlight) return
        clear()
    }

    fun confirmDelete() {
        val snapshot = _state.value
        val target = snapshot.target ?: return
        if (snapshot.lifecycleInFlight) return
        _state.update { it.copy(deleteConfirmVisible = false, lifecycleInFlight = true) }
        logI("delete start session=${target.sessionId}")
        scope.launch {
            runCatching { sessionRepository.deleteSession(id = target.sessionId, directory = target.worktree) }
                .onSuccess { deleted ->
                    clear(target.sessionId)
                    if (deleted) {
                        logI("delete success session=${target.sessionId}")
                    } else {
                        logW("delete rejected session=${target.sessionId}")
                        onLifecycleError(DELETE_ERROR)
                    }
                }.onFailure { error ->
                    logW("delete failed session=${target.sessionId} message=${error.message}")
                    clear(target.sessionId)
                    onLifecycleError(DELETE_ERROR)
                }
        }
    }

    /**
     * Fork is hidden until a message fetch proves the session has at least one visible user
     * message, matching the Commands sheet rule. A failed fetch leaves Fork hidden. Home keeps
     * this to one request per long-press and so passes no revert boundary (see parity gaps).
     */
    private suspend fun resolveForkAvailability(
        sessionId: String,
        worktree: String,
    ) {
        val messages =
            runCatching { messageRepository.listMessages(sessionId = sessionId, directory = worktree) }
                .getOrNull() ?: return
        val refs =
            messages.map { message ->
                ClientSlashMessageRef(
                    id = message.id,
                    isUser = message.role == MessageRole.User,
                    isPending = false,
                    isStreaming = false,
                    hasNonEmptyText = message.text.isNotBlank(),
                )
            }
        val visibility =
            ClientSlashActionCatalog.visibilityInput(
                messages = refs,
                revertMessageId = null,
                hasSelectedComposerModel = false,
            )
        _state.update { current ->
            if (current.target?.sessionId != sessionId) {
                current
            } else {
                current.copy(
                    canFork =
                        ClientSlashActionCatalog
                            .visibleActions(visibility, capabilities())
                            .contains(ClientSlashAction.Fork),
                )
            }
        }
    }

    /** Resets the whole action surface; [sessionId] guards against a stale in-flight action. */
    private fun clear(sessionId: String? = null) {
        forkGateJob?.cancel()
        forkGateJob = null
        _state.update { current ->
            if (sessionId != null && current.target?.sessionId != sessionId) {
                current
            } else {
                HomeSessionActionsUiState()
            }
        }
    }

    private fun mutationErrorMessage(throwable: Throwable): String =
        when (throwable) {
            is SessionError.Http -> throwable.message ?: "Request failed"
            is SessionError.Transport -> "Unable to reach the server"
            is SessionError.Decoding -> "Unable to read server response"
            else -> throwable.message ?: "Something went wrong"
        }

    private fun logI(message: String) {
        DiagnosticLog.info(DiagnosticLogArea.Chat, message)
    }

    private fun logW(message: String) {
        DiagnosticLog.warning(DiagnosticLogArea.Chat, message)
    }

    private companion object {
        const val EMPTY_TITLE_ERROR = "Enter a session title"
        const val ARCHIVE_ERROR = "Couldn't archive session."
        const val DELETE_ERROR = "Couldn't delete session."
        const val OUTCOME_BLOCKED = "blocked"
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_FAILURE = "failure"
    }
}
