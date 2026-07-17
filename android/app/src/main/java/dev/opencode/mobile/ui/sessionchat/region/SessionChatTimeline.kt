package dev.opencode.mobile.ui.sessionchat.region

import android.util.Log
import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.DisplayMessageListUpdate
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageError
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.ui.sessionchat.AttachmentPresentationMapper
import dev.opencode.mobile.ui.sessionchat.LatestModelHint
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlocks
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineRegionState
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SessionChatTimeline(
    private val messageRepository: MessageRepository,
    private val scope: CoroutineScope,
) {
    var ioDispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

    private val _state = MutableStateFlow(SessionChatTimelineRegionState())
    val state: StateFlow<SessionChatTimelineRegionState> = _state.asStateFlow()

    private var boundSessionId: String? = null
    private var boundWorktree: String? = null
    private var observeJob: Job? = null
    private var transformJob: Job? = null
    private var mergePendingWhileSending = false
    private var pendingOptimisticRows: List<SessionChatMessageItem> = emptyList()
    private var pendingBaselineSignatures: Map<String, String> = emptyMap()
    private var lastLoadedMessages: List<Message> = emptyList()

    fun bind(
        sessionId: String,
        worktree: String,
    ) {
        if (boundSessionId == sessionId && boundWorktree == worktree) return
        observeJob?.cancel()
        transformJob?.cancel()
        boundSessionId = sessionId
        boundWorktree = worktree
        mergePendingWhileSending = false
        pendingOptimisticRows = emptyList()
        pendingBaselineSignatures = emptyMap()
        lastLoadedMessages = emptyList()
        _state.value = SessionChatTimelineRegionState()
        _state.update {
            it.copy(timeline = SessionChatTimelineState.Loading, errorMessage = null)
        }
        logI("bind session=$sessionId worktree=$worktree")
        startLiveObservation(sessionId, worktree)
    }

    fun loadMessages() {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        scope.launch {
            if (!isCurrentBinding(sessionId, worktree)) return@launch
            logI("loadMessages retry session=$sessionId worktree=$worktree")
            _state.update {
                it.copy(timeline = SessionChatTimelineState.Loading, errorMessage = null)
            }
            runCatching {
                withContext(ioDispatcher) {
                    messageRepository.refreshDisplayMessages(sessionId, worktree)
                }
            }.onSuccess { displayMessages ->
                logI("loadMessages fetched session=$sessionId count=${displayMessages.size}")
                applyLoadedDisplayMessages(
                    displayMessages = displayMessages,
                    sourceSessionId = sessionId,
                    sourceWorktree = worktree,
                    source = "timeline-retry",
                )
            }.onFailure { throwable ->
                logE("loadMessages failed session=$sessionId", throwable)
                if (isCurrentBinding(sessionId, worktree)) {
                    applyLoadError(throwable)
                }
            }
        }
    }

    fun refreshMessages() {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        scope.launch {
            if (!isCurrentBinding(sessionId, worktree)) return@launch
            _state.update { it.copy(isRefreshing = true, errorMessage = null) }
            runCatching {
                withContext(ioDispatcher) {
                    messageRepository.refreshDisplayMessages(sessionId, worktree)
                }
            }.onSuccess { displayMessages ->
                applyLoadedDisplayMessages(
                    displayMessages = displayMessages,
                    sourceSessionId = sessionId,
                    sourceWorktree = worktree,
                    isRefreshing = false,
                    source = "refresh",
                )
            }.onFailure { throwable ->
                if (isCurrentBinding(sessionId, worktree)) {
                    _state.update {
                        it.copy(
                            isRefreshing = false,
                            errorMessage = errorMessage(throwable),
                        )
                    }
                }
            }
        }
    }

    fun reloadAfterSend(
        onComplete: () -> Unit,
        onReloadFailed: () -> Unit,
    ) {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        scope.launch {
            if (!isCurrentBinding(sessionId, worktree)) return@launch
            runCatching {
                withContext(ioDispatcher) {
                    messageRepository.refreshDisplayMessages(sessionId, worktree)
                }
            }.onSuccess { displayMessages ->
                applyLoadedDisplayMessages(
                    displayMessages = displayMessages,
                    sourceSessionId = sessionId,
                    sourceWorktree = worktree,
                    source = "send-prompt",
                    onPublished = onComplete,
                )
            }.onFailure {
                onReloadFailed()
            }
        }
    }

    fun beginOptimisticSend(enrichedRows: List<SessionChatMessageItem>) {
        mergePendingWhileSending = true
        pendingOptimisticRows = enrichedRows
        pendingBaselineSignatures =
            _state.value.messages
                .filterNot { it.isPending }
                .associate { it.id to it.reconciliationSignature() }
        _state.update {
            it.copy(
                messages = it.messages + enrichedRows,
                timeline = SessionChatTimelineState.Ready,
                errorMessage = null,
            )
        }
    }

    fun failOptimisticSend(
        optimisticId: String,
        error: String,
    ) {
        mergePendingWhileSending = false
        pendingOptimisticRows = emptyList()
        pendingBaselineSignatures = emptyMap()
        _state.update {
            it.copy(
                messages = it.messages.filterNot { item -> item.id == optimisticId },
                errorMessage = error,
            )
        }
    }

    fun clearSendingState() {
        mergePendingWhileSending = false
        pendingOptimisticRows = emptyList()
        pendingBaselineSignatures = emptyMap()
    }

    fun latestModelHint(): LatestModelHint? = latestModelHintFromMessages(lastLoadedMessages)

    fun modelSelectionHistoryHints(): dev.opencode.mobile.ui.sessionchat.ModelSelectionHistoryHints {
        val sorted =
            lastLoadedMessages.sortedWith(
                compareByDescending<Message> { it.sentAtEpochMs ?: Long.MIN_VALUE }.thenByDescending { it.id },
            )
        return dev.opencode.mobile.ui.sessionchat.ModelSelectionHistoryHints(
            user = latestModelHintFromMessages(sorted, MessageRole.User),
            assistant = latestModelHintFromMessages(sorted, MessageRole.Assistant),
        )
    }

    internal fun toMessageItem(
        message: Message,
        previous: SessionChatMessageItem? = null,
    ): SessionChatMessageItem? = toMessageItem(DisplayMessage(message), previous)

    internal fun toMessageItem(
        display: DisplayMessage,
        previous: SessionChatMessageItem? = null,
    ): SessionChatMessageItem? {
        val message = display.message
        val blocks = SessionChatTimelineBlocks.build(role = message.role, parts = message.parts)
        val resolvedBlocks =
            when {
                blocks.isNotEmpty() -> blocks
                message.text.isNotBlank() -> listOf(SessionChatTimelineBlock.Text(message.text))
                else -> emptyList()
            }
        val attachments = blocks.mapNotNull { (it as? SessionChatTimelineBlock.Attachment)?.attachment }
        if (resolvedBlocks.isEmpty() && attachments.isEmpty()) return null

        val isStreaming = display.isStreaming
        val renderedMarkdown =
            display.renderedMarkdown ?: previous?.renderedMarkdown.takeIf {
                isStreaming && (previous?.isStreaming == true || previous?.text == message.text)
            }

        return SessionChatMessageItem(
            id = message.id,
            role = message.role,
            text = message.text,
            metadataLabel = metadataLabel(message),
            isStreaming = isStreaming,
            renderedMarkdown = renderedMarkdown,
            blocks = resolvedBlocks,
            attachments = attachments,
            modelProviderId = message.modelProviderId,
            modelId = message.modelId,
            contextInputTokens = message.contextInputTokens,
        )
    }

    fun latestModelHintFromMessages(messages: List<Message>): LatestModelHint? {
        val sorted =
            messages.sortedWith(
                compareByDescending<Message> { it.sentAtEpochMs ?: Long.MIN_VALUE }.thenByDescending { it.id },
            )
        return latestModelHintFromMessages(sorted, MessageRole.User)
            ?: latestModelHintFromMessages(sorted, MessageRole.Assistant)
    }

    private fun latestModelHintFromMessages(
        sortedMessages: List<Message>,
        role: MessageRole,
    ): LatestModelHint? =
        sortedMessages
            .firstOrNull { it.role == role && it.modelProviderId != null && it.modelId != null }
            ?.let { message ->
                LatestModelHint(
                    providerId = message.modelProviderId.orEmpty(),
                    modelId = message.modelId.orEmpty(),
                    variant = message.variant,
                    sentAtEpochMs = message.sentAtEpochMs,
                    source =
                        if (role == MessageRole.User) {
                            dev.opencode.mobile.ui.sessionchat.ModelSelectionHistorySource.User
                        } else {
                            dev.opencode.mobile.ui.sessionchat.ModelSelectionHistorySource.Assistant
                        },
                )
            }

    private fun startLiveObservation(
        sessionId: String,
        worktree: String,
    ) {
        observeJob =
            scope.launch {
                messageRepository.observeDisplayMessages(sessionId, worktree).collect { update ->
                    if (!isCurrentBinding(sessionId, worktree)) return@collect
                    when (update) {
                        is DisplayMessageListUpdate.LoadFailed -> {
                            logE("live display messages failed session=$sessionId", update.error)
                            applyLoadError(update.error)
                        }
                        is DisplayMessageListUpdate.Messages -> {
                            logD("live display messages session=$sessionId count=${update.messages.size}")
                            applyLoadedDisplayMessages(
                                displayMessages = update.messages,
                                sourceSessionId = sessionId,
                                sourceWorktree = worktree,
                                fromQuietRefresh = true,
                                source = "display-pipeline",
                            )
                        }
                    }
                }
            }
    }

    private fun applyLoadedDisplayMessages(
        displayMessages: List<DisplayMessage>,
        sourceSessionId: String,
        sourceWorktree: String,
        isRefreshing: Boolean = false,
        fromQuietRefresh: Boolean = false,
        source: String,
        onPublished: (() -> Unit)? = null,
    ) {
        if (!isCurrentBinding(sourceSessionId, sourceWorktree)) return
        lastLoadedMessages = displayMessages.map { it.message }
        logI(
            "applyLoadedDisplayMessages start session=$sourceSessionId source=$source " +
                "count=${displayMessages.size} quietRefresh=$fromQuietRefresh refreshing=$isRefreshing",
        )
        transformJob?.cancel()
        transformJob =
            scope.launch {
                if (!isCurrentBinding(sourceSessionId, sourceWorktree)) return@launch
                val stateSnapshot = _state.value
                val previousById = stateSnapshot.messages.associateBy { it.id }
                val structuralStartedAt = System.nanoTime()
                val structural = withContext(ioDispatcher) { timelineMessages(displayMessages, previousById) }
                val structuralMs = (System.nanoTime() - structuralStartedAt) / 1_000_000
                val merged =
                    if (fromQuietRefresh && mergePendingWhileSending) {
                        mergeWithPending(structural, pendingOptimisticRows.ifEmpty { stateSnapshot.messages.filter { it.isPending } })
                    } else {
                        structural
                    }
                if (!isCurrentBinding(sourceSessionId, sourceWorktree)) return@launch
                val timeline =
                    when {
                        merged.isEmpty() -> SessionChatTimelineState.Empty
                        else -> SessionChatTimelineState.Ready
                    }
                val resolvedTimeline =
                    if (fromQuietRefresh && stateSnapshot.timeline == SessionChatTimelineState.Error && merged.isEmpty()) {
                        SessionChatTimelineState.Error
                    } else {
                        timeline
                    }
                logI(
                    "applyLoadedDisplayMessages ready session=$sourceSessionId source=$source " +
                        "timeline=$timeline messages=${merged.size} structuralMs=$structuralMs",
                )
                _state.update { state ->
                    state.copy(
                        timeline = resolvedTimeline,
                        messages = merged,
                        isRefreshing = isRefreshing,
                        errorMessage =
                            if (fromQuietRefresh) {
                                if (merged.isNotEmpty() && state.timeline == SessionChatTimelineState.Error) {
                                    null
                                } else {
                                    state.errorMessage
                                }
                            } else {
                                null
                            },
                    )
                }
                if (!fromQuietRefresh) {
                    mergePendingWhileSending = false
                    pendingOptimisticRows = emptyList()
                    pendingBaselineSignatures = emptyMap()
                }
                onPublished?.invoke()
            }
    }

    private fun applyLoadError(throwable: Throwable) {
        mergePendingWhileSending = false
        pendingOptimisticRows = emptyList()
        pendingBaselineSignatures = emptyMap()
        _state.update {
            it.copy(
                timeline = SessionChatTimelineState.Error,
                messages = emptyList(),
                errorMessage = errorMessage(throwable),
            )
        }
    }

    private fun isCurrentBinding(
        sessionId: String,
        worktree: String,
    ): Boolean = boundSessionId == sessionId && boundWorktree == worktree

    private fun mergeWithPending(
        serverItems: List<SessionChatMessageItem>,
        pendingItems: List<SessionChatMessageItem>,
    ): List<SessionChatMessageItem> {
        if (pendingItems.isEmpty()) return serverItems
        val pendingToKeep =
            pendingItems.filter { pending ->
                !serverItems.any { server ->
                    pendingBaselineSignatures[server.id] != server.reconciliationSignature() &&
                        server.matchesOptimistic(pending)
                }
            }
        return serverItems + pendingToKeep
    }

    private fun SessionChatMessageItem.matchesOptimistic(pending: SessionChatMessageItem): Boolean =
        role == MessageRole.User &&
            text == pending.text &&
            attachments.size == pending.attachments.size &&
            attachments.zip(pending.attachments).all { (server, optimistic) ->
                server.id == optimistic.id || server.signature == optimistic.signature
            }

    private fun SessionChatMessageItem.reconciliationSignature(): String =
        buildString {
            append(role.name)
            append('\u0000')
            append(text)
            attachments.forEach { attachment ->
                append('\u0000')
                append(attachment.id)
                append('\u0000')
                append(attachment.signature)
            }
        }

    private fun timelineMessages(
        displayMessages: List<DisplayMessage>,
        previousById: Map<String, SessionChatMessageItem>,
    ): List<SessionChatMessageItem> =
        displayMessages
            .withIndex()
            .sortedWith { left, right ->
                val leftTime = left.value.message.sentAtEpochMs
                val rightTime = right.value.message.sentAtEpochMs
                when {
                    leftTime != null && rightTime != null && leftTime != rightTime -> leftTime.compareTo(rightTime)
                    leftTime != null && rightTime != null ->
                        left.value.message.id
                            .compareTo(right.value.message.id)
                    else -> left.index.compareTo(right.index)
                }
            }.mapNotNull { toMessageItem(it.value, previousById[it.value.id]) }

    private fun metadataLabel(message: Message): String {
        val timeLabel = message.sentAtEpochMs?.let(::formatSentTime)
        return listOfNotNull(message.mode, message.model, message.variant, timeLabel).joinToString(" · ")
    }

    private fun formatSentTime(epochMs: Long): String =
        Instant
            .ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(SentTimeFormatter)

    private fun errorMessage(throwable: Throwable): String =
        when (throwable) {
            is MessageError.Http -> throwable.message ?: "Request failed"
            is MessageError.Transport -> "Unable to reach the server"
            is MessageError.Decoding -> "Unable to read server response"
            else -> throwable.message ?: "Something went wrong"
        }

    private fun logI(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logD(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun logE(
        message: String,
        throwable: Throwable,
    ) {
        runCatching { Log.e(TAG, message, throwable) }
    }

    companion object {
        private const val TAG = "SessionChatTimeline"
        private val SentTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    }
}
