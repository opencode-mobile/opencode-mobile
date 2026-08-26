package dev.opencode.mobile.ui.sessionchat.region

import android.util.Log
import dev.opencode.mobile.domain.command.ClientSlashActionCatalog
import dev.opencode.mobile.domain.command.ClientSlashMessageRef
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.model.NewSessionModelSelectionResolver
import dev.opencode.mobile.domain.session.OptimisticSessionCreate
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.ui.sessionchat.ChildSessionNavigationTarget
import dev.opencode.mobile.ui.sessionchat.SessionChatCommandsRegionState
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionCompactChrome
import dev.opencode.mobile.ui.sessionchat.SessionCompactionPresence
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
    private var lastDomainMessages: List<Message> = emptyList()
    private var completedCompactionIdsAtStart: Set<String> = emptySet()

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
        lastDomainMessages = emptyList()
        completedCompactionIdsAtStart = emptySet()
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

    fun observeLoadedMessages(messages: List<Message>) {
        lastDomainMessages = messages
        if (!_state.value.isCompacting) return
        val ids = SessionCompactionPresence.completedCompactionIds(messages)
        if (ids.any { it !in completedCompactionIdsAtStart }) {
            logI("compact completed via timeline")
            _state.update { it.copy(isCompacting = false) }
        }
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
        _state.update {
            it.copy(
                pendingSessionReplace =
                    ChildSessionNavigationTarget(
                        sessionId = OptimisticSessionCreate.PLACEHOLDER_SESSION_ID,
                        sessionTitle = "New Session",
                        worktree = worktree,
                        optimisticNavigationInstanceId = java.util.UUID.randomUUID().toString(),
                    ),
            )
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
        val boundary = currentRevertMessageId
        val target =
            lastMessages
                .filter { boundary == null || it.id < boundary }
                .lastOrNull(::isRevertibleUserMessage)
                ?: return
        revert(
            target = target,
            isBusy = isBusy,
            restoreComposerText = restoreComposerText,
            operation = "undo",
            failureMessage = "Couldn't undo last message.",
        )
    }

    fun revertMessage(
        id: String,
        isBusy: Boolean,
        restoreComposerText: (String) -> Unit,
    ) {
        val target = lastMessages.firstOrNull { it.id == id && isRevertibleUserMessage(it) } ?: return
        revert(
            target = target,
            isBusy = isBusy,
            restoreComposerText = restoreComposerText,
            operation = "revert",
            failureMessage = "Couldn't revert message.",
        )
    }

    fun runRedo(
        isBusy: Boolean,
        clearComposerRestore: () -> Unit,
    ) {
        dismissCommands()
        unrevert(
            isBusy = isBusy,
            clearComposerRestore = clearComposerRestore,
            operation = "redo",
            failureMessage = "Couldn't redo.",
        )
    }

    fun cancelRevert(
        isBusy: Boolean,
        clearComposerRestore: () -> Unit,
    ) {
        unrevert(
            isBusy = isBusy,
            clearComposerRestore = clearComposerRestore,
            operation = "cancel-revert",
            failureMessage = "Couldn't cancel revert.",
        )
    }

    fun runCompact(
        providerId: String,
        modelId: String,
        beforeContextFraction: Float?,
        awaitAfterContextFraction: suspend () -> Float?,
    ) {
        dismissCommands()
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        completedCompactionIdsAtStart = SessionCompactionPresence.completedCompactionIds(lastDomainMessages)
        _state.update {
            it.copy(
                isCompacting = true,
                actionErrorMessage = null,
                actionSuccessMessage = null,
            )
        }
        scope.launch(ioDispatcher) {
            runCatching { sessionRepository.summarizeSession(sessionId, providerId, modelId, worktree) }
                .onSuccess {
                    logI("compact success session=$sessionId")
                    // Clear Compacting… before post-success refresh so a slow timeline
                    // rebuild cannot leave the chrome stuck (iOS MainActor freeze twin).
                    _state.update { it.copy(isCompacting = false) }
                    val after = runCatching { awaitAfterContextFraction() }.getOrNull()
                    _state.update {
                        it.copy(
                            actionSuccessMessage =
                                SessionCompactChrome.successMessage(
                                    beforeFraction = beforeContextFraction,
                                    afterFraction = after,
                                ),
                        )
                    }
                }.onFailure { error ->
                    logW(
                        "compact failed session=$sessionId error=${error.message ?: error::class.java.simpleName}",
                    )
                    _state.update {
                        it.copy(
                            isCompacting = false,
                            actionErrorMessage = SessionCompactChrome.FAILURE,
                            actionSuccessMessage = null,
                        )
                    }
                }
        }
    }

    private fun isRevertibleUserMessage(message: SessionChatMessageItem): Boolean =
        message.role == MessageRole.User &&
            !message.isPending &&
            !message.isStreaming &&
            message.text.isNotBlank()

    private fun revert(
        target: SessionChatMessageItem,
        isBusy: Boolean,
        restoreComposerText: (String) -> Unit,
        operation: String,
        failureMessage: String,
    ) {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        scope.launch(ioDispatcher) {
            if (isBusy) runCatching { sessionRepository.abortSession(sessionId, directory = worktree) }
            runCatching { sessionRepository.revertSession(sessionId, target.id, null, worktree) }
                .onSuccess {
                    logI("$operation success session=$sessionId message=${target.id}")
                    setRevertBoundary(target.id)
                    refreshRevertNow(sessionId, worktree)
                    restoreComposerText(target.text)
                }.onFailure { error ->
                    logW("$operation failed session=$sessionId error=${error.message}")
                    _state.update { it.copy(actionErrorMessage = failureMessage) }
                }
        }
    }

    private fun unrevert(
        isBusy: Boolean,
        clearComposerRestore: () -> Unit,
        operation: String,
        failureMessage: String,
    ) {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        if (currentRevertMessageId == null) return
        scope.launch(ioDispatcher) {
            if (isBusy) runCatching { sessionRepository.abortSession(sessionId, directory = worktree) }
            runCatching { sessionRepository.unrevertSession(sessionId, worktree) }
                .onSuccess {
                    logI("$operation success session=$sessionId")
                    setRevertBoundary(null)
                    refreshRevertNow(sessionId, worktree)
                    clearComposerRestore()
                }.onFailure { error ->
                    logW("$operation failed session=$sessionId error=${error.message}")
                    _state.update { it.copy(actionErrorMessage = failureMessage) }
                }
        }
    }

    private fun setRevertBoundary(messageId: String?) {
        currentRevertMessageId = messageId
        _state.update { it.copy(hasPendingRevert = messageId != null) }
        updateVisibility(lastMessages, lastHasSelectedComposerModel)
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
                setRevertBoundary(session.revert?.messageId)
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
