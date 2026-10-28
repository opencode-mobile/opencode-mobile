package dev.opencode.mobile.ui.main.region

import dev.opencode.mobile.domain.command.ClientSlashAction
import dev.opencode.mobile.domain.command.ClientSlashActionCatalog
import dev.opencode.mobile.domain.engine.SessionCapabilities
import dev.opencode.mobile.domain.command.ClientSlashMessageRef
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.home.HomeSettleFeed
import dev.opencode.mobile.domain.home.SnoozeUnit
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionSettleBlockedException
import dev.opencode.mobile.ui.main.HomeSessionActionTarget
import dev.opencode.mobile.ui.main.HomeSessionActionsUiState
import dev.opencode.mobile.ui.main.HomeSessionLifecycle
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
    /** An archived or settled session came back; home reloads its sections. */
    private val onSessionRestored: () -> Unit = {},
) {
    private val _state = MutableStateFlow(HomeSessionActionsUiState())
    val state: StateFlow<HomeSessionActionsUiState> = _state.asStateFlow()

    private var forkCheckJob: Job? = null
    private var forkedOpenTarget: SessionOpenTarget? = null

    fun begin(
        sessionId: String,
        title: String,
        worktree: String,
        canArchive: Boolean,
        lifecycle: HomeSessionLifecycle = HomeSessionLifecycle.Active,
        showsSettle: Boolean = false,
        snoozeBlockReason: String? = null,
        presentSheet: Boolean = true,
    ) {
        forkCheckJob?.cancel()
        forkCheckJob = null
        forkedOpenTarget = null
        _state.value =
            HomeSessionActionsUiState(
                target =
                    HomeSessionActionTarget(
                        sessionId = sessionId,
                        title = title,
                        worktree = worktree,
                    ),
                sheetVisible = presentSheet,
                canArchive = canArchive && lifecycle == HomeSessionLifecycle.Active,
                lifecycle = lifecycle,
                showsSettle = showsSettle,
                snoozeBlockReason = snoozeBlockReason,
            )
    }

    fun chooseUnarchive() {
        val snapshot = _state.value
        val target = snapshot.target ?: return
        if (snapshot.lifecycle != HomeSessionLifecycle.Archived) return
        if (snapshot.lifecycleInFlight) return
        _state.update { it.copy(sheetVisible = false, lifecycleInFlight = true) }
        logI("unarchive start session=${target.sessionId}")
        scope.launch {
            runCatching { sessionRepository.unarchiveSession(id = target.sessionId, directory = target.worktree) }
                .onSuccess {
                    logI("unarchive success session=${target.sessionId}")
                    clear(target.sessionId)
                    onSessionRestored()
                }.onFailure { error ->
                    logW("unarchive failed session=${target.sessionId} message=${error.message}")
                    clear(target.sessionId)
                    onLifecycleError(UNARCHIVE_ERROR)
                }
        }
    }

    fun chooseUnsettle() {
        val snapshot = _state.value
        val target = snapshot.target ?: return
        if (snapshot.lifecycle != HomeSessionLifecycle.Settled) return
        if (snapshot.lifecycleInFlight) return
        _state.update { it.copy(sheetVisible = false, lifecycleInFlight = true) }
        logI("unsettle start session=${target.sessionId}")
        scope.launch {
            runCatching { sessionRepository.unsettleSession(id = target.sessionId, directory = target.worktree) }
                .onSuccess { restored ->
                    logI("unsettle ${if (restored) "success" else "rejected"} session=${target.sessionId}")
                    clear(target.sessionId)
                    if (restored) onSessionRestored() else onLifecycleError(UNSETTLE_ERROR)
                }.onFailure { error ->
                    logW("unsettle failed session=${target.sessionId} message=${error.message}")
                    clear(target.sessionId)
                    onLifecycleError(UNSETTLE_ERROR)
                }
        }
    }

    fun chooseSettle() {
        val snapshot = _state.value
        val target = snapshot.target ?: return
        if (snapshot.lifecycle != HomeSessionLifecycle.Active) return
        if (snapshot.lifecycleInFlight) return
        // Same reason as snooze: the server refuses to settle a running or waiting thread.
        if (snapshot.snoozeBlockReason != null) return
        _state.update { it.copy(sheetVisible = false, snoozeVisible = false, lifecycleInFlight = true) }
        logI("settle start session=${target.sessionId}")
        scope.launch {
            runCatching { sessionRepository.settleSession(id = target.sessionId, directory = target.worktree) }
                .onSuccess { restored ->
                    logI("settle ${if (restored) "success" else "rejected"} session=${target.sessionId}")
                    clear(target.sessionId)
                    if (restored) onSessionRestored() else onLifecycleError(SETTLE_ERROR)
                }.onFailure { error ->
                    logW("settle failed session=${target.sessionId} message=${error.message}")
                    clear(target.sessionId)
                    onLifecycleError(settleErrorMessage(error))
                }
        }
    }

    /** Hides the action sheet and keeps the target so the snooze sheet can commit. */
    fun openSnooze() {
        val snapshot = _state.value
        if (snapshot.target == null) return
        if (snapshot.lifecycle != HomeSessionLifecycle.Active) return
        if (snapshot.snoozeBlockReason != null) return
        _state.update { it.copy(sheetVisible = false, snoozeVisible = true, snoozeError = null) }
    }

    fun dismissSnooze() {
        if (_state.value.lifecycleInFlight) return
        clear()
    }

    fun updateSnoozeAmount(text: String) {
        _state.update { it.copy(snoozeAmount = text.filter(Char::isDigit).take(4), snoozeError = null) }
    }

    fun cycleSnoozeUnit() {
        _state.update { current ->
            val next =
                when (current.snoozeUnit) {
                    SnoozeUnit.Minutes -> SnoozeUnit.Hours
                    SnoozeUnit.Hours -> SnoozeUnit.Days
                    SnoozeUnit.Days -> SnoozeUnit.Minutes
                }
            current.copy(snoozeUnit = next, snoozeError = null)
        }
    }

    fun chooseSnoozePreset(untilEpochMs: Long) {
        snooze(untilEpochMs)
    }

    fun confirmCustomSnooze(nowEpochMs: Long = System.currentTimeMillis()) {
        val snapshot = _state.value
        val amount = snapshot.snoozeAmount.toIntOrNull()
        val until =
            amount?.let { HomeSettleFeed.snoozeUntilFromDuration(it, snapshot.snoozeUnit, nowEpochMs) }
        if (until == null) {
            _state.update { it.copy(snoozeError = "Enter a number of minutes, hours, or days.") }
            return
        }
        snooze(until)
    }

    fun chooseWake() {
        val snapshot = _state.value
        val target = snapshot.target ?: return
        if (snapshot.lifecycle != HomeSessionLifecycle.Snoozed) return
        if (snapshot.lifecycleInFlight) return
        _state.update { it.copy(sheetVisible = false, lifecycleInFlight = true) }
        logI("wake start session=${target.sessionId}")
        scope.launch {
            runCatching { sessionRepository.wakeSession(id = target.sessionId, directory = target.worktree) }
                .onSuccess { restored ->
                    logI("wake ${if (restored) "success" else "rejected"} session=${target.sessionId}")
                    clear(target.sessionId)
                    if (restored) onSessionRestored() else onLifecycleError(WAKE_ERROR)
                }.onFailure { error ->
                    logW("wake failed session=${target.sessionId} message=${error.message}")
                    clear(target.sessionId)
                    onLifecycleError(WAKE_ERROR)
                }
        }
    }

    private fun snooze(untilEpochMs: Long) {
        val snapshot = _state.value
        val target = snapshot.target ?: return
        if (snapshot.lifecycleInFlight) return
        if (snapshot.snoozeBlockReason != null) return
        _state.update { it.copy(sheetVisible = false, snoozeVisible = false, lifecycleInFlight = true) }
        val until = HomeSettleFeed.isoTimestamp(untilEpochMs)
        logI("snooze start session=${target.sessionId} until=$until")
        scope.launch {
            runCatching {
                sessionRepository.snoozeSession(
                    id = target.sessionId,
                    snoozedUntil = until,
                    directory = target.worktree,
                )
            }.onSuccess { restored ->
                logI("snooze ${if (restored) "success" else "rejected"} session=${target.sessionId}")
                clear(target.sessionId)
                if (restored) onSessionRestored() else onLifecycleError(SNOOZE_ERROR)
            }.onFailure { error ->
                logW("snooze failed session=${target.sessionId} message=${error.message}")
                clear(target.sessionId)
                onLifecycleError(SNOOZE_ERROR)
            }
        }
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
        val target = _state.value.target ?: return
        _state.update {
            it.copy(
                sheetVisible = false,
                forkVisible = true,
                forkDraft = suggestedHomeForkTitle(target.title),
                forkSubmitting = false,
                forkError = null,
                forkBlockedMessage = null,
            )
        }
        forkCheckJob?.cancel()
        forkCheckJob = scope.launch { resolveForkCheck(target.sessionId, target.worktree) }
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
        if (snapshot.forkBlockedMessage != null) {
            onOutcome(OUTCOME_BLOCKED)
            return
        }
        val title = snapshot.forkDraft.trim()
        if (title.isEmpty()) {
            onOutcome(OUTCOME_BLOCKED)
            _state.update { it.copy(forkError = EMPTY_TITLE_ERROR) }
            return
        }
        _state.update { it.copy(forkSubmitting = true, forkError = null) }
        logI("fork submit session=${target.sessionId}")
        val check = forkCheckJob
        scope.launch {
            // A quick confirm can beat the check started when the dialog opened.
            check?.join()
            val checked = _state.value
            if (checked.target?.sessionId != target.sessionId) return@launch
            if (checked.forkBlockedMessage != null) {
                onOutcome(OUTCOME_BLOCKED)
                _state.update { it.copy(forkSubmitting = false) }
                return@launch
            }
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
     * Fork needs at least one visible user message, matching the Commands sheet rule. The sheet
     * always lists Fork so it never reflows; this runs once the fork dialog opens and blocks the
     * dialog when there is nothing to fork. A failed fetch leaves the server to decide. Home
     * keeps this to one request per fork and so passes no revert boundary (see parity gaps).
     */
    private suspend fun resolveForkCheck(
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
        val canFork =
            ClientSlashActionCatalog
                .visibleActions(visibility, capabilities())
                .contains(ClientSlashAction.Fork)
        if (canFork) return
        _state.update { current ->
            if (current.target?.sessionId != sessionId || !current.forkVisible) {
                current
            } else {
                current.copy(forkBlockedMessage = NOTHING_TO_FORK_ERROR)
            }
        }
    }

    /** Resets the whole action surface; [sessionId] guards against a stale in-flight action. */
    private fun clear(sessionId: String? = null) {
        forkCheckJob?.cancel()
        forkCheckJob = null
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
        const val NOTHING_TO_FORK_ERROR = "Nothing to fork yet"
        const val ARCHIVE_ERROR = "Couldn't archive session."
        const val UNARCHIVE_ERROR = "Couldn't unarchive session."
        const val UNSETTLE_ERROR = "Couldn't unsettle session."
        const val SETTLE_ERROR = "Couldn't settle session."

        /** Says why when the engine refused because the thread is busy; generic otherwise. */
        fun settleErrorMessage(error: Throwable): String =
            if (error is SessionSettleBlockedException) SessionSettleBlockedException.USER_MESSAGE else SETTLE_ERROR
        const val SNOOZE_ERROR = "Couldn't snooze session."
        const val WAKE_ERROR = "Couldn't wake session."
        const val DELETE_ERROR = "Couldn't delete session."
        const val OUTCOME_BLOCKED = "blocked"
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_FAILURE = "failure"
    }
}
