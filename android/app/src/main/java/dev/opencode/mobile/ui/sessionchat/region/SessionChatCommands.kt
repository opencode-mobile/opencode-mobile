package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.domain.command.ClientSlashActionCatalog
import dev.opencode.mobile.domain.command.ClientSlashMessageRef
import dev.opencode.mobile.domain.engine.SessionCapabilities
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.mcp.McpRepository
import dev.opencode.mobile.domain.mcp.McpServerCatalog
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.model.NewSessionModelSelectionResolver
import dev.opencode.mobile.domain.session.CompactionStart
import dev.opencode.mobile.domain.session.OptimisticSessionCreate
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.ui.sessionchat.ChildSessionNavigationTarget
import dev.opencode.mobile.ui.sessionchat.PendingTerminalOpen
import dev.opencode.mobile.ui.sessionchat.SessionChatCommandsRegionState
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock
import dev.opencode.mobile.ui.sessionchat.SessionCompactChrome
import dev.opencode.mobile.ui.sessionchat.SessionCompactionPresence
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

/**
 * Client slash actions (Commands sheet): new / fork / undo / redo / compact.
 * These are local session behaviors, never routed through [dev.opencode.mobile.domain.command.CommandRepository].
 */
class SessionChatCommands(
    private val sessionRepository: SessionRepository,
    private val newSessionModelSelectionResolver: NewSessionModelSelectionResolver,
    private val connectionSessionCoordinator: ConnectionSessionCoordinator,
    private val mcpRepository: McpRepository,
    private val scope: CoroutineScope,
) {
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    var capabilities: SessionCapabilities = SessionCapabilities.OpenCode

    private var mcpJob: Job? = null

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
    private var lastIsSessionBusy: Boolean = false

    /**
     * The compaction this screen started, tracked from the moment [runCompact] runs so live
     * timeline updates that land before `POST /compact` returns are not lost.
     */
    private var pendingCompaction: PendingCompaction? = null

    private class PendingCompaction(
        val beforeContextFraction: Float?,
        val awaitAfterContextFraction: suspend () -> Float?,
    ) {
        /** `POST /compact` returned `Enqueued` (v2): the live compaction item decides success. */
        var enqueued: Boolean = false
        var sawBusy: Boolean = false

        /** Set when the timeline completed the compaction before the POST returned: whether it had a summary. */
        var finishedEarlyWithSummary: Boolean? = null
    }

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
        lastIsSessionBusy = false
        pendingCompaction = null
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
        _state.update { it.copy(visibleActions = ClientSlashActionCatalog.visibleActions(input, capabilities)) }
    }

    fun observeLoadedMessages(
        messages: List<Message>,
        isSessionBusy: Boolean = false,
    ) {
        lastDomainMessages = messages
        lastIsSessionBusy = isSessionBusy
        val pending = pendingCompaction ?: return
        // Already finished in the timeline; waiting for the POST to say v1 or v2.
        if (pending.finishedEarlyWithSummary != null) return
        if (isSessionBusy) pending.sawBusy = true
        val finished =
            SessionCompactionPresence
                .completedCompactions(messages)
                .filter { it.key !in completedCompactionIdsAtStart }
        if (finished.isNotEmpty()) {
            logI("compact completed via timeline")
            _state.update { it.copy(isCompacting = false) }
            // v2 `compaction.ended` carries the summary; `compaction.failed` does not
            // (its error reaches the session error chrome).
            val hasSummary = finished.values.any { !it.isNullOrBlank() }
            if (pending.enqueued) {
                pendingCompaction = null
                if (hasSummary) postLiveCompactionSuccess(pending)
            } else {
                pending.finishedEarlyWithSummary = hasSummary
            }
            return
        }
        if (pending.enqueued &&
            pending.sawBusy &&
            !isSessionBusy &&
            !SessionCompactionPresence.isActive(messages, isSessionBusy = true)
        ) {
            // The session went idle without a completed compaction (failed or cancelled).
            logI("compact ended without a summary")
            pendingCompaction = null
            _state.update { it.copy(isCompacting = false) }
        }
    }

    private fun postLiveCompactionSuccess(pending: PendingCompaction) {
        scope.launch { showCompactionSuccess(pending.beforeContextFraction, pending.awaitAfterContextFraction) }
    }

    private suspend fun showCompactionSuccess(
        beforeContextFraction: Float?,
        awaitAfterContextFraction: suspend () -> Float?,
    ) {
        val after = withContext(ioDispatcher) { runCatching { awaitAfterContextFraction() }.getOrNull() }
        _state.update {
            it.copy(
                actionSuccessMessage =
                    SessionCompactChrome.successMessage(
                        beforeFraction = beforeContextFraction,
                        afterFraction = after,
                    ),
            )
        }
    }

    fun openCommands() {
        _state.update { it.copy(commandsSheetVisible = true, mcpPageVisible = false) }
        refreshMcpServers()
    }

    fun dismissCommands() {
        _state.update { it.copy(commandsSheetVisible = false, mcpPageVisible = false) }
    }

    /** Second page of the Commands sheet: read-only MCP server status. */
    fun openMcpServers() {
        _state.update { it.copy(mcpPageVisible = true) }
        refreshMcpServers()
    }

    fun closeMcpServers() {
        _state.update { it.copy(mcpPageVisible = false) }
    }

    fun refreshMcpServers() {
        val worktree = boundWorktree
        mcpJob?.cancel()
        _state.update { it.copy(mcpLoading = true) }
        mcpJob =
            scope.launch(ioDispatcher) {
                runCatching { mcpRepository.listServers(worktree) }
                    .onSuccess { servers ->
                        _state.update {
                            it.copy(
                                mcpServers = servers,
                                mcpSummary = McpServerCatalog.summary(servers),
                                mcpLoading = false,
                                mcpErrorMessage = null,
                            )
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        logW("mcp servers load failed: ${error.message}")
                        _state.update {
                            it.copy(
                                mcpLoading = false,
                                mcpErrorMessage = "Couldn't load MCP servers. Check the connection and try again.",
                            )
                        }
                    }
            }
    }

    fun clearActionFeedback() {
        _state.update { it.copy(actionErrorMessage = null, actionSuccessMessage = null) }
    }

    fun reportActionError(message: String) {
        _state.update { it.copy(actionErrorMessage = message) }
    }

    fun consumePendingSessionReplace() {
        _state.update { it.copy(pendingSessionReplace = null) }
    }

    fun consumePendingTerminalOpen() {
        _state.update { it.copy(pendingTerminalOpen = null) }
    }

    fun consumePendingFilesOpen() {
        _state.update { it.copy(pendingFilesOpen = null) }
    }

    fun runFiles() {
        dismissCommands()
        val worktree = boundWorktree ?: return
        _state.update { it.copy(pendingFilesOpen = worktree) }
    }

    fun runTerminal(projectTitle: String) {
        dismissCommands()
        val worktree = boundWorktree ?: return
        _state.update {
            it.copy(
                pendingTerminalOpen =
                    PendingTerminalOpen(
                        worktree = worktree,
                        projectTitle = projectTitle,
                    ),
            )
        }
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
                        optimisticNavigationInstanceId =
                            java.util.UUID
                                .randomUUID()
                                .toString(),
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

    fun consumePendingRevert() {
        if (currentRevertMessageId == null) return
        setRevertBoundary(null)
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
        val pending = PendingCompaction(beforeContextFraction, awaitAfterContextFraction)
        pendingCompaction = pending
        _state.update {
            it.copy(
                isCompacting = true,
                actionErrorMessage = null,
                actionSuccessMessage = null,
            )
        }
        scope.launch {
            withContext(ioDispatcher) {
                runCatching { sessionRepository.compactSession(sessionId, providerId, modelId, worktree) }
            }.onSuccess { start ->
                // Rebound or superseded by another compaction while the POST was in flight.
                if (pendingCompaction !== pending) return@onSuccess
                if (start == CompactionStart.Enqueued) {
                    // v2 returns at enqueue time: stay Compacting… until the live
                    // compaction item completes (observeLoadedMessages).
                    logI("compact enqueued session=$sessionId")
                    pending.enqueued = true
                    val finishedEarlyWithSummary = pending.finishedEarlyWithSummary
                    if (finishedEarlyWithSummary != null) {
                        pendingCompaction = null
                        if (finishedEarlyWithSummary) {
                            showCompactionSuccess(beforeContextFraction, awaitAfterContextFraction)
                        }
                        return@onSuccess
                    }
                    observeLoadedMessages(lastDomainMessages, lastIsSessionBusy)
                    return@onSuccess
                }
                logI("compact success session=$sessionId")
                pendingCompaction = null
                // Clear Compacting… before post-success refresh so a slow timeline
                // rebuild cannot leave the chrome stuck (iOS MainActor freeze twin).
                _state.update { it.copy(isCompacting = false) }
                showCompactionSuccess(beforeContextFraction, awaitAfterContextFraction)
            }.onFailure { error ->
                if (pendingCompaction !== pending) return@onFailure
                logW(
                    "compact failed session=$sessionId error=${error.message ?: error::class.java.simpleName}",
                )
                pendingCompaction = null
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
            isBeforeRevertBoundary(message.id) &&
            hasRevertibleContent(message)

    private fun isBeforeRevertBoundary(id: String): Boolean {
        val boundary = currentRevertMessageId ?: return true
        return id < boundary
    }

    private fun hasRevertibleContent(message: SessionChatMessageItem): Boolean =
        message.text.isNotBlank() ||
            message.attachments.isNotEmpty() ||
            message.blocks.any { it is SessionChatTimelineBlock.Attachment }

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
        DiagnosticLog.info(DiagnosticLogArea.Chat, message)
    }

    private fun logW(message: String) {
        DiagnosticLog.warning(DiagnosticLogArea.Chat, message)
    }
}
