package dev.opencode.mobile.ui.sessionchat.region

import android.util.Log
import dev.opencode.mobile.domain.command.ClientSlashActionCatalog
import dev.opencode.mobile.domain.command.ClientSlashMessageRef
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.model.NewSessionModelSelectionResolver
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.ui.sessionchat.ChildSessionNavigationTarget
import dev.opencode.mobile.ui.sessionchat.SessionChatCommandsRegionState
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Client slash actions (Commands sheet): new / fork / undo / redo / compact.
 * These are local session behaviors, never routed through [dev.opencode.mobile.domain.command.CommandRepository].
 */
class SessionChatCommands(
    private val sessionRepository: SessionRepository,
    private val newSessionModelSelectionResolver: NewSessionModelSelectionResolver,
    private val connectionSessionCoordinator: ConnectionSessionCoordinator,
    private val scope: CoroutineScope,
) {
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _state = MutableStateFlow(SessionChatCommandsRegionState())
    val state: StateFlow<SessionChatCommandsRegionState> = _state.asStateFlow()

    private var boundSessionId: String? = null
    private var boundWorktree: String? = null
    private var currentRevertMessageId: String? = null
    private var forkedTarget: ChildSessionNavigationTarget? = null
    private var lastMessages: List<SessionChatMessageItem> = emptyList()
    private var lastHasSelectedComposerModel: Boolean = false

    fun bind(
        sessionId: String,
        worktree: String,
    ) {
        if (boundSessionId == sessionId && boundWorktree == worktree) return
        boundSessionId = sessionId
        boundWorktree = worktree
        currentRevertMessageId = null
        forkedTarget = null
        lastMessages = emptyList()
        lastHasSelectedComposerModel = false
        _state.value = SessionChatCommandsRegionState()
        refreshRevert()
    }

    fun updateVisibility(
        messages: List<SessionChatMessageItem>,
        hasSelectedComposerModel: Boolean,
    ) {
        lastMessages = messages
        lastHasSelectedComposerModel = hasSelectedComposerModel
        val refs =
            messages.map { message ->
                ClientSlashMessageRef(
                    id = message.id,
                    isUser = message.role == MessageRole.User,
                    isPending = message.isPending,
                    isStreaming = message.isStreaming,
                    hasNonEmptyText = message.text.isNotBlank(),
                )
            }
        val input = ClientSlashActionCatalog.visibilityInput(refs, currentRevertMessageId, hasSelectedComposerModel)
        _state.update { it.copy(visibleActions = ClientSlashActionCatalog.visibleActions(input)) }
    }

    fun openCommands() {
        _state.update { it.copy(commandsSheetVisible = true) }
    }

    fun dismissCommands() {
        _state.update { it.copy(commandsSheetVisible = false) }
    }

    fun clearActionFeedback() {
        _state.update { it.copy(actionErrorMessage = null, actionSuccessMessage = null) }
    }

    fun consumePendingSessionReplace() {
        _state.update { it.copy(pendingSessionReplace = null) }
    }

    fun runNew() {
        dismissCommands()
        val worktree = boundWorktree ?: return
        _state.update { it.copy(actionErrorMessage = null, actionSuccessMessage = null) }
        scope.launch(ioDispatcher) {
            runCatching {
                val profile = connectionSessionCoordinator.currentProfile()
                val selection = profile?.let { newSessionModelSelectionResolver.resolve(it, worktree) }
                val session = sessionRepository.createSession(directory = worktree)
                if (profile != null && selection != null) {
                    newSessionModelSelectionResolver.seedSession(profile, worktree, session.id, selection)
                }
                session
            }.onSuccess { session ->
                logI("new session created id=${session.id}")
                _state.update {
                    it.copy(
                        pendingSessionReplace =
                            ChildSessionNavigationTarget(
                                sessionId = session.id,
                                sessionTitle = session.title,
                                worktree = worktree,
                            ),
                    )
                }
            }.onFailure { error ->
                logW("new session failed error=${error.message}")
                _state.update { it.copy(actionErrorMessage = "Couldn't start a new session.") }
            }
        }
    }

    fun openForkDialog(currentTitle: String) {
        dismissCommands()
        _state.update {
            it.copy(
                forkDialogVisible = true,
                forkDraftTitle = currentTitle,
                forkSubmitting = false,
                forkErrorMessage = null,
            )
        }
    }

    fun updateForkDraft(text: String) {
        _state.update { it.copy(forkDraftTitle = text, forkErrorMessage = null) }
    }

    fun cancelForkDialog() {
        _state.update { it.copy(forkDialogVisible = false, forkSubmitting = false, forkErrorMessage = null) }
    }

    fun confirmFork() {
        if (_state.value.forkSubmitting) return
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        val title = _state.value.forkDraftTitle.trim()
        if (title.isEmpty()) {
            _state.update { it.copy(forkErrorMessage = "Enter a session title") }
            return
        }
        _state.update { it.copy(forkSubmitting = true, forkErrorMessage = null) }
        scope.launch(ioDispatcher) {
            runCatching {
                val forked = sessionRepository.forkSession(id = sessionId, atMessageId = null, directory = worktree)
                sessionRepository.updateSession(id = forked.id, title = title, directory = worktree)
            }.onSuccess { updated ->
                logI("fork success sourceSession=$sessionId forkedSession=${updated.id}")
                forkedTarget =
                    ChildSessionNavigationTarget(
                        sessionId = updated.id,
                        sessionTitle = updated.title,
                        worktree = worktree,
                    )
                _state.update {
                    it.copy(
                        forkDialogVisible = false,
                        forkSubmitting = false,
                        forkResultVisible = true,
                        forkedSessionTitle = updated.title,
                    )
                }
            }.onFailure { error ->
                logW("fork failed sourceSession=$sessionId error=${error.message}")
                _state.update {
                    it.copy(
                        forkSubmitting = false,
                        forkErrorMessage = error.message ?: "Couldn't fork session.",
                    )
                }
            }
        }
    }

    fun chooseForkOpen() {
        val target = forkedTarget
        forkedTarget = null
        _state.update { it.copy(forkResultVisible = false, pendingSessionReplace = target) }
    }

    fun chooseForkStay() {
        forkedTarget = null
        _state.update { it.copy(forkResultVisible = false) }
    }

    fun runUndo(
        isBusy: Boolean,
        restoreComposerText: (String) -> Unit,
    ) {
        dismissCommands()
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        val boundary = currentRevertMessageId
        val target =
            lastMessages
                .filter { boundary == null || it.id < boundary }
                .lastOrNull { it.role == MessageRole.User && !it.isPending && !it.isStreaming && it.text.isNotBlank() }
                ?: return
        scope.launch(ioDispatcher) {
            if (isBusy) runCatching { sessionRepository.abortSession(sessionId, directory = worktree) }
            runCatching { sessionRepository.revertSession(sessionId, target.id, null, worktree) }
                .onSuccess {
                    logI("undo success session=$sessionId message=${target.id}")
                    refreshRevertNow(sessionId, worktree)
                    restoreComposerText(target.text)
                }.onFailure { error ->
                    logW("undo failed session=$sessionId error=${error.message}")
                    _state.update { it.copy(actionErrorMessage = "Couldn't undo last message.") }
                }
        }
    }

    fun runRedo(
        isBusy: Boolean,
        clearComposerRestore: () -> Unit,
    ) {
        dismissCommands()
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        if (currentRevertMessageId == null) return
        scope.launch(ioDispatcher) {
            if (isBusy) runCatching { sessionRepository.abortSession(sessionId, directory = worktree) }
            runCatching { sessionRepository.unrevertSession(sessionId, worktree) }
                .onSuccess {
                    logI("redo success session=$sessionId")
                    refreshRevertNow(sessionId, worktree)
                    clearComposerRestore()
                }.onFailure { error ->
                    logW("redo failed session=$sessionId error=${error.message}")
                    _state.update { it.copy(actionErrorMessage = "Couldn't redo.") }
                }
        }
    }

    fun runCompact(
        providerId: String,
        modelId: String,
        onSuccess: () -> Unit,
    ) {
        dismissCommands()
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        scope.launch(ioDispatcher) {
            runCatching { sessionRepository.summarizeSession(sessionId, providerId, modelId, worktree) }
                .onSuccess {
                    logI("compact success session=$sessionId")
                    _state.update { it.copy(actionSuccessMessage = "Session compacted.") }
                    onSuccess()
                }.onFailure { error ->
                    logW("compact failed session=$sessionId error=${error.message}")
                    _state.update {
                        it.copy(
                            actionErrorMessage = "Couldn't compact session context.",
                            actionSuccessMessage = null,
                        )
                    }
                }
        }
    }

    private fun refreshRevert() {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        scope.launch(ioDispatcher) { refreshRevertNow(sessionId, worktree) }
    }

    private suspend fun refreshRevertNow(
        sessionId: String,
        worktree: String,
    ) {
        runCatching { sessionRepository.getSession(sessionId, worktree) }
            .onSuccess { session ->
                if (boundSessionId != sessionId || boundWorktree != worktree) return@onSuccess
                currentRevertMessageId = session.revert?.messageId
                updateVisibility(lastMessages, lastHasSelectedComposerModel)
            }
    }

    private fun logI(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logW(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    companion object {
        private const val TAG = "SessionChatCommands"
    }
}
