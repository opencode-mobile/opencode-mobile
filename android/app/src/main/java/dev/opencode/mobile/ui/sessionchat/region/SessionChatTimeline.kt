package dev.opencode.mobile.ui.sessionchat.region

import android.util.Log
import dev.opencode.mobile.domain.command.ServerCommand
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.message.BlockDecorationCache
import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.DisplayMessageListUpdate
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageError
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ThoughtPartChrome
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.ui.sessionchat.ContentRevision
import dev.opencode.mobile.ui.sessionchat.DisplayFrame
import dev.opencode.mobile.ui.sessionchat.DisplayMessageBody
import dev.opencode.mobile.ui.sessionchat.LatestAgentHint
import dev.opencode.mobile.ui.sessionchat.LatestModelHint
import dev.opencode.mobile.ui.sessionchat.OptimisticInputs
import dev.opencode.mobile.ui.sessionchat.SessionChatMessageItem
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlocks
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineRegionState
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineState
import dev.opencode.mobile.ui.sessionchat.TimelineApply
import dev.opencode.mobile.ui.sessionchat.TimelineDisplayOrder
import dev.opencode.mobile.ui.sessionchat.TimelineFrame
import dev.opencode.mobile.ui.sessionchat.TimelineProjector
import dev.opencode.mobile.ui.sessionchat.TimelineRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SessionChatTimeline(
    private val messageRepository: MessageRepository,
    private val scope: CoroutineScope,
) {
    var ioDispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

    @Volatile
    var serverCommands: List<ServerCommand> = emptyList()

    @Volatile
    var collapseConsecutiveToolCalls: Boolean = false

    fun updateServerCommands(commands: List<ServerCommand>) {
        if (commands == serverCommands) return
        val wasEmpty = serverCommands.isEmpty()
        serverCommands = commands
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        if (wasEmpty && commands.isNotEmpty() && lastLoadedDisplayMessages.isNotEmpty()) {
            applyLoadedDisplayMessages(
                displayMessages = lastLoadedDisplayMessages,
                sourceSessionId = sessionId,
                sourceWorktree = worktree,
                fromQuietRefresh = true,
                source = "commands-ready",
                messagesFromCache = _state.value.messagesFromCache,
            )
        }
    }

    fun updateCollapseConsecutiveToolCalls(enabled: Boolean) {
        if (enabled == collapseConsecutiveToolCalls) return
        collapseConsecutiveToolCalls = enabled
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        if (lastLoadedDisplayMessages.isNotEmpty()) {
            applyLoadedDisplayMessages(
                displayMessages = lastLoadedDisplayMessages,
                sourceSessionId = sessionId,
                sourceWorktree = worktree,
                fromQuietRefresh = true,
                source = "collapse-pref",
                messagesFromCache = _state.value.messagesFromCache,
            )
        }
    }

    private val _state = MutableStateFlow(SessionChatTimelineRegionState())
    val state: StateFlow<SessionChatTimelineRegionState> = _state.asStateFlow()

    private var boundSessionId: String? = null
    private var boundWorktree: String? = null
    private var observeJob: Job? = null
    private var transformJob: Job? = null
    private val pendingRequiredDisplayApplies = ArrayDeque<PendingDisplayApply>()
    private var pendingLiveDisplayApply: PendingDisplayApply? = null
    private var mergePendingWhileSending = false
    private var pendingOptimisticRows: List<SessionChatMessageItem> = emptyList()
    private var pendingBaselineSignatures: Map<String, String> = emptyMap()
    private var lastLoadedMessages: List<Message> = emptyList()
    private var lastLoadedDisplayMessages: List<DisplayMessage> = emptyList()
    private var lastTimelineFrame: TimelineFrame? = null
    private var nextFrameRevision: Long = 1L

    /** After network confirms the cache, ignore late isFromCache publishes for Syncing. */
    private var networkCacheSyncConfirmed = false
    private var catchUpSyncing = false

    fun bindCreating(worktree: String) {
        observeJob?.cancel()
        transformJob?.cancel()
        pendingRequiredDisplayApplies.clear()
        pendingLiveDisplayApply = null
        boundSessionId = null
        boundWorktree = worktree
        mergePendingWhileSending = false
        pendingOptimisticRows = emptyList()
        pendingBaselineSignatures = emptyMap()
        lastLoadedMessages = emptyList()
        lastLoadedDisplayMessages = emptyList()
        lastTimelineFrame = null
        nextFrameRevision = 1L
        networkCacheSyncConfirmed = false
        catchUpSyncing = false
        BlockDecorationCache.clear()
        _state.value = SessionChatTimelineRegionState()
        _state.update {
            it.copy(timeline = SessionChatTimelineState.Creating, errorMessage = null)
        }
        logI("bind creating worktree=$worktree")
    }

    fun showCreateFailed(message: String) {
        _state.update {
            it.copy(timeline = SessionChatTimelineState.Error, errorMessage = message)
        }
        logI("create failed message=$message")
    }

    fun bind(
        sessionId: String,
        worktree: String,
    ) {
        if (boundSessionId == sessionId && boundWorktree == worktree) return
        observeJob?.cancel()
        transformJob?.cancel()
        pendingRequiredDisplayApplies.clear()
        pendingLiveDisplayApply = null
        boundSessionId = sessionId
        boundWorktree = worktree
        mergePendingWhileSending = false
        pendingOptimisticRows = emptyList()
        pendingBaselineSignatures = emptyMap()
        lastLoadedMessages = emptyList()
        lastLoadedDisplayMessages = emptyList()
        lastTimelineFrame = null
        nextFrameRevision = 1L
        networkCacheSyncConfirmed = false
        catchUpSyncing = false
        BlockDecorationCache.clear()
        _state.value = SessionChatTimelineRegionState()
        _state.update {
            it.copy(timeline = SessionChatTimelineState.Loading, errorMessage = null)
        }
        logI("bind session=$sessionId worktree=$worktree")
        startLiveObservation(sessionId, worktree)
    }

    fun restartLiveObservation() {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
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
        scope.launch { refreshMessagesSuspending() }
    }

    fun loadOlder() {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        val current = _state.value
        if (!current.hasOlder || current.isLoadingOlder) return
        _state.update { it.copy(isLoadingOlder = true) }
        scope.launch {
            if (!isCurrentBinding(sessionId, worktree)) return@launch
            runCatching {
                withContext(ioDispatcher) {
                    messageRepository.loadOlderMessages(sessionId, worktree)
                }
            }.onSuccess { result ->
                if (!isCurrentBinding(sessionId, worktree)) return@launch
                _state.update { it.copy(hasOlder = result.hasOlder, isLoadingOlder = false) }
                if (result.addedCount > 0) {
                    val displayMessages =
                        lastLoadedDisplayMessages
                            .associateBy { it.id }
                            .let { previous ->
                                result.messages.map { message ->
                                    previous[message.id] ?: DisplayMessage(message)
                                }
                            }
                    applyLoadedDisplayMessages(
                        displayMessages = displayMessages,
                        sourceSessionId = sessionId,
                        sourceWorktree = worktree,
                        fromQuietRefresh = false,
                        source = "load-older",
                        hasOlder = result.hasOlder,
                    )
                }
            }.onFailure {
                if (isCurrentBinding(sessionId, worktree)) {
                    _state.update { it.copy(isLoadingOlder = false) }
                }
            }
        }
    }

    suspend fun refreshMessagesSuspending() {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        if (!isCurrentBinding(sessionId, worktree)) return
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
                .associate { item ->
                    item.id to TimelineProjector.reconciliationSignature(TimelineProjector.rowFromMessageItem(item))
                }
        val pendingRows = enrichedRows.map(TimelineProjector::rowFromMessageItem)
        val previous = lastTimelineFrame
        val frame =
            TimelineFrame(
                frameRevision = nextFrameRevision++,
                rows = (previous?.rows.orEmpty()) + pendingRows,
                insertedRowIDs = pendingRows.map { it.id }.toSet(),
                changedRowIDs = emptySet(),
                removedRowIDs = emptySet(),
                layoutChangedRowIDs = emptySet(),
            )
        lastTimelineFrame = frame
        _state.update {
            it.copy(
                messages = it.messages + enrichedRows,
                timelineFrame = frame,
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
        val previous = lastTimelineFrame
        val frame =
            previous?.let { prior ->
                val rows = prior.rows.filterNot { it.id == optimisticId }
                TimelineFrame(
                    frameRevision = nextFrameRevision++,
                    rows = rows,
                    insertedRowIDs = emptySet(),
                    changedRowIDs = emptySet(),
                    removedRowIDs = setOf(optimisticId).intersect(prior.rows.map { it.id }.toSet()),
                    layoutChangedRowIDs = emptySet(),
                )
            }
        lastTimelineFrame = frame
        _state.update {
            it.copy(
                messages = it.messages.filterNot { item -> item.id == optimisticId },
                timelineFrame = frame,
                errorMessage = error,
            )
        }
    }

    fun clearSendingState() {
        mergePendingWhileSending = false
        pendingOptimisticRows = emptyList()
        pendingBaselineSignatures = emptyMap()
    }

    fun clearReadyError() {
        _state.update { current ->
            if (current.timeline != SessionChatTimelineState.Ready) {
                current
            } else {
                current.copy(errorMessage = null)
            }
        }
    }

    fun latestModelHint(): LatestModelHint? = latestModelHintFromMessages(lastLoadedMessages)

    fun loadedMessages(): List<Message> = lastLoadedMessages

    fun modelSelectionHistoryHints(): dev.opencode.mobile.ui.sessionchat.ModelSelectionHistoryHints {
        val sorted =
            lastLoadedMessages.sortedWith(
                compareByDescending<Message> { it.sentAtEpochMs ?: Long.MIN_VALUE }.thenByDescending { it.id },
            )
        return dev.opencode.mobile.ui.sessionchat.ModelSelectionHistoryHints(
            user = latestModelHintFromMessages(sorted, MessageRole.User),
            assistant = latestModelHintFromMessages(sorted, MessageRole.Assistant),
            userAgent = latestAgentHintFromMessages(sorted, MessageRole.User),
            assistantAgent = latestAgentHintFromMessages(sorted, MessageRole.Assistant),
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
        val body = displayMessageBody(display, previous) ?: return null
        val previousFrame =
            previous?.let { item ->
                TimelineFrame(
                    frameRevision = 0L,
                    rows = listOf(TimelineProjector.rowFromMessageItem(item)),
                    insertedRowIDs = emptySet(),
                    changedRowIDs = emptySet(),
                    removedRowIDs = emptySet(),
                    layoutChangedRowIDs = emptySet(),
                )
            }
        val projected =
            TimelineProjector.project(
                frame =
                    DisplayFrame(
                        sessionKey = boundSessionId ?: display.message.id,
                        frameRevision = 0L,
                        sourceRevision = 0L,
                        messages = listOf(body),
                    ),
                previous = previousFrame,
            )
        return projected.rows.singleOrNull()?.let(TimelineProjector::toMessageItem)
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

    private fun latestAgentHintFromMessages(
        sortedMessages: List<Message>,
        role: MessageRole,
    ): LatestAgentHint? =
        sortedMessages
            .firstOrNull { it.role == role && !it.mode.isNullOrBlank() }
            ?.let { message ->
                LatestAgentHint(
                    mode = message.mode.orEmpty(),
                    sentAtEpochMs = message.sentAtEpochMs,
                )
            }

    private fun startLiveObservation(
        sessionId: String,
        worktree: String,
    ) {
        observeJob?.cancel()
        observeJob =
            scope.launch {
                launch {
                    messageRepository.observeCatchUpChrome(sessionId, worktree).collect { catchingUp ->
                        if (!isCurrentBinding(sessionId, worktree)) return@collect
                        catchUpSyncing = catchingUp
                        publishSyncingMessages()
                    }
                }
                messageRepository
                    .observeDisplayMessages(sessionId, worktree)
                    // Do not conflate: CacheSyncConfirmed must not be dropped when a late
                    // cache-generation Messages publish arrives while apply is busy.
                    // Live Messages still coalesce via pendingLiveDisplayApply.
                    .collect { update ->
                        if (!isCurrentBinding(sessionId, worktree)) return@collect
                        when (update) {
                            is DisplayMessageListUpdate.LoadFailed -> {
                                logE("live display messages failed session=$sessionId", update.error)
                                applyLoadError(update.error)
                            }
                            is DisplayMessageListUpdate.CacheSyncConfirmed -> {
                                logD("live display cache sync confirmed session=$sessionId")
                                confirmCacheSync(hasOlder = update.hasOlder)
                            }
                            is DisplayMessageListUpdate.Messages -> {
                                logD("live display messages session=$sessionId count=${update.messages.size}")
                                applyLoadedDisplayMessages(
                                    displayMessages = update.messages,
                                    sourceSessionId = sessionId,
                                    sourceWorktree = worktree,
                                    fromQuietRefresh = true,
                                    source = "display-pipeline",
                                    messagesFromCache = update.isFromCache,
                                    hasOlder = update.hasOlder,
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
        messagesFromCache: Boolean = false,
        hasOlder: Boolean? = null,
        onPublished: (() -> Unit)? = null,
    ) {
        if (!isCurrentBinding(sourceSessionId, sourceWorktree)) return
        logI(
            "applyLoadedDisplayMessages start session=$sourceSessionId source=$source " +
                "count=${displayMessages.size} quietRefresh=$fromQuietRefresh refreshing=$isRefreshing",
        )
        val pending =
            PendingDisplayApply(
                displayMessages = displayMessages,
                sourceSessionId = sourceSessionId,
                sourceWorktree = sourceWorktree,
                isRefreshing = isRefreshing,
                fromQuietRefresh = fromQuietRefresh,
                source = source,
                messagesFromCache = messagesFromCache,
                hasOlder = hasOlder,
                onPublished = onPublished,
            )
        if (fromQuietRefresh && onPublished == null) {
            // Live frames are visual snapshots: keep only the latest.
            pendingLiveDisplayApply = pending
        } else {
            // Explicit loads/refreshes and post-send completion carry state
            // transitions or callbacks that must never be discarded.
            // A live snapshot already waiting here was submitted before this
            // required apply and must not repaint older state afterward.
            pendingLiveDisplayApply = null
            pendingRequiredDisplayApplies.addLast(pending)
        }
        if (transformJob?.isActive == true) return
        transformJob =
            scope.launch {
                do {
                    while (true) {
                        val pending =
                            if (pendingRequiredDisplayApplies.isNotEmpty()) {
                                pendingRequiredDisplayApplies.removeFirst()
                            } else {
                                pendingLiveDisplayApply?.also { pendingLiveDisplayApply = null }
                            } ?: break
                        publishDisplayMessages(pending)
                    }
                    // Cover the race where a newer snapshot arrives after the inner
                    // loop observes null but before this job goes inactive.
                } while (pendingRequiredDisplayApplies.isNotEmpty() || pendingLiveDisplayApply != null)
            }
    }

    private data class PendingDisplayApply(
        val displayMessages: List<DisplayMessage>,
        val sourceSessionId: String,
        val sourceWorktree: String,
        val isRefreshing: Boolean,
        val fromQuietRefresh: Boolean,
        val source: String,
        val messagesFromCache: Boolean,
        val hasOlder: Boolean?,
        val onPublished: (() -> Unit)?,
    )

    private suspend fun publishDisplayMessages(pending: PendingDisplayApply) {
        val displayMessages = pending.displayMessages
        val sourceSessionId = pending.sourceSessionId
        val sourceWorktree = pending.sourceWorktree
        val isRefreshing = pending.isRefreshing
        val fromQuietRefresh = pending.fromQuietRefresh
        val source = pending.source
        val onPublished = pending.onPublished

        if (!isCurrentBinding(sourceSessionId, sourceWorktree)) return
        lastLoadedDisplayMessages = displayMessages
        lastLoadedMessages = displayMessages.map { it.message }
        val stateSnapshot = _state.value
        val previousById = stateSnapshot.messages.associateBy { it.id }
        val previousFrame = lastTimelineFrame
        val sessionKey = sourceSessionId
        val optimisticInputs =
            if (mergePendingWhileSending) {
                val pendingItems =
                    pendingOptimisticRows.ifEmpty { stateSnapshot.messages.filter { it.isPending } }
                OptimisticInputs(
                    pendingRows = pendingItems.map(TimelineProjector::rowFromMessageItem),
                    baselineSignatures = pendingBaselineSignatures,
                )
            } else {
                OptimisticInputs()
            }
        val frameRevision = nextFrameRevision++
        val structuralStartedAt = System.nanoTime()
        val projected =
            withContext(ioDispatcher) {
                val bodies =
                    SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
                        sortedDisplayMessages(displayMessages).mapNotNull { display ->
                            displayMessageBody(display, previousById[display.id])
                        },
                        collapseConsecutiveToolCalls,
                    )
                TimelineProjector.project(
                    frame =
                        DisplayFrame(
                            sessionKey = sessionKey,
                            frameRevision = frameRevision,
                            sourceRevision = 0L,
                            messages = bodies,
                        ),
                    optimisticInputs = optimisticInputs,
                    previous = previousFrame,
                )
            }
        val structuralMs = (System.nanoTime() - structuralStartedAt) / 1_000_000
        if (!isCurrentBinding(sourceSessionId, sourceWorktree)) return
        val isRequired = !(fromQuietRefresh && onPublished == null)
        val stamped = projected.withRequired(isRequired)
        val apply = TimelineApply.classify(stamped)
        lastTimelineFrame = stamped
        val previousMessageCount = previousById.size
        val merged = TimelineProjector.mergeMessageItems(stamped, previousById)
        val rebuiltCount =
            merged.count { item ->
                val previous = previousById[item.id]
                previous == null || previous !== item
            }
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
                "timeline=$timeline messages=${merged.size} rebuilt=$rebuiltCount " +
                "previous=$previousMessageCount structuralMs=$structuralMs " +
                "kinds=${TimelineApply.kindsLabel(apply.kinds)} required=$isRequired " +
                "inserted=${stamped.insertedRowIDs.size} changed=${stamped.changedRowIDs.size} " +
                "layoutChanged=${stamped.layoutChangedRowIDs.size} " +
                "decorate=${stamped.decorateRowIDs.size} grewBottom=${stamped.grewBottomRowIDs.size} " +
                "layoutAffectingRev=${stamped.layoutAffectingRevision()} " +
                "growBottomRev=${stamped.growBottomRevision()} " +
                ThoughtPartChrome.summarize(
                    messages = lastLoadedMessages,
                    paintedCount = merged.size,
                ),
        )
        _state.update { state ->
            state.copy(
                timeline = resolvedTimeline,
                messages = merged,
                timelineFrame = stamped,
                isRefreshing = isRefreshing,
                messagesFromCache = pending.messagesFromCache && !networkCacheSyncConfirmed,
                isSyncingMessages =
                    (pending.messagesFromCache && !networkCacheSyncConfirmed) || catchUpSyncing,
                hasOlder = pending.hasOlder ?: state.hasOlder,
                isLoadingOlder = if (pending.hasOlder == false) false else state.isLoadingOlder,
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
        clearOptimisticIfReconciled(stamped.rows)
        onPublished?.invoke()
    }

    private fun confirmCacheSync(hasOlder: Boolean) {
        networkCacheSyncConfirmed = true
        _state.update {
            it.copy(
                messagesFromCache = false,
                isSyncingMessages = catchUpSyncing,
                hasOlder = hasOlder,
            )
        }
    }

    private fun publishSyncingMessages() {
        _state.update { state ->
            val fromCache = state.messagesFromCache && !networkCacheSyncConfirmed
            val next = fromCache || catchUpSyncing
            if (state.isSyncingMessages == next) state else state.copy(isSyncingMessages = next)
        }
    }

    private fun applyLoadError(throwable: Throwable) {
        mergePendingWhileSending = false
        pendingOptimisticRows = emptyList()
        pendingBaselineSignatures = emptyMap()
        // Retain the last good timeline when one exists; only clear on cold failure.
        _state.update { current ->
            val retainLastGood = current.messages.isNotEmpty()
            if (!retainLastGood) {
                lastTimelineFrame = null
            }
            current.copy(
                timeline = SessionChatTimelineState.Error,
                messages = if (retainLastGood) current.messages else emptyList(),
                timelineFrame = if (retainLastGood) current.timelineFrame else null,
                errorMessage = errorMessage(throwable),
            )
        }
    }

    private fun clearOptimisticIfReconciled(rows: List<TimelineRow>) {
        if (!mergePendingWhileSending) return
        val pendingIds = pendingOptimisticRows.map { it.id }.toSet()
        val stillPending =
            if (pendingIds.isNotEmpty()) {
                rows.any { it.id in pendingIds }
            } else {
                rows.any { it.isPending }
            }
        if (stillPending) return
        mergePendingWhileSending = false
        pendingOptimisticRows = emptyList()
        pendingBaselineSignatures = emptyMap()
    }

    private fun isCurrentBinding(
        sessionId: String,
        worktree: String,
    ): Boolean = boundSessionId == sessionId && boundWorktree == worktree

    private fun sortedDisplayMessages(displayMessages: List<DisplayMessage>): List<DisplayMessage> =
        TimelineDisplayOrder.ordered(displayMessages)

    private fun displayMessageBody(
        display: DisplayMessage,
        previous: SessionChatMessageItem?,
    ): DisplayMessageBody? {
        val message = display.message
        val resolvedBlocks =
            SessionChatTimelineBlocks.ensureLiveRow(
                message = message,
                isStreaming = display.isStreaming,
                blocks =
                    SessionChatTimelineBlocks.collapseCommandInvocations(
                        role = message.role,
                        blocks =
                            SessionChatTimelineBlocks.buildOrFallback(
                                role = message.role,
                                parts = message.parts,
                                messageId = message.id,
                                fallbackText = message.text,
                                collapseConsecutiveToolCalls = collapseConsecutiveToolCalls,
                            ),
                        commands = serverCommands,
                    ),
            )
        if (resolvedBlocks.isEmpty()) return null

        val sessionKey = boundSessionId ?: message.id
        val isStreaming = display.isStreaming
        val blocksWithCache =
            resolvedBlocks.map { block ->
                if (block !is SessionChatTimelineBlock.Text) {
                    block
                } else {
                    val revision = ContentRevision.of(block.text)
                    val cached =
                        BlockDecorationCache.get(
                            sessionKey = sessionKey,
                            blockID = block.blockID,
                            textHash = revision.textHash,
                            textLength = revision.textLength,
                            isStreaming = isStreaming,
                        )
                    if (cached != null && block.renderedMarkdown == null) {
                        block.copy(renderedMarkdown = cached)
                    } else {
                        block
                    }
                }
            }

        val renderedMarkdown =
            matchingMarkdown(
                candidate =
                    display.renderedMarkdown ?: previous?.renderedMarkdown.takeIf {
                        display.isStreaming && (previous?.isStreaming == true || previous?.text == message.text)
                    },
                message = message,
                blocks = blocksWithCache,
            )
        val blocks = attachMarkdownBridge(blocksWithCache, renderedMarkdown, message)
        return DisplayMessageBody(message = message, blocks = blocks)
    }

    /**
     * Temporary Phase 5 bridge: message-scoped markdown lands on the sole text
     * block when per-block cache has not already decorated it.
     *
     * Skip when slash-command collapse rewrote the sole text away from
     * [Message.text] so the expanded template document is not pasted onto
     * `[/name] args`. Streaming may keep a stale previous document whose hash
     * no longer matches growing text — that remains intentional.
     */
    private fun attachMarkdownBridge(
        blocks: List<SessionChatTimelineBlock>,
        renderedMarkdown: MarkdownDocument?,
        message: Message,
    ): List<SessionChatTimelineBlock> {
        if (renderedMarkdown == null) return blocks
        val textBlockIndexes =
            blocks.mapIndexedNotNull { index, block ->
                index.takeIf { block is SessionChatTimelineBlock.Text }
            }
        if (textBlockIndexes.size != 1) return blocks
        val textIndex = textBlockIndexes.single()
        val textBlock = blocks[textIndex] as SessionChatTimelineBlock.Text
        if (textBlock.renderedMarkdown != null) return blocks
        if (isCollapsedAwayFromMessageText(message, textBlock.text)) return blocks
        return blocks.toMutableList().also {
            it[textIndex] = textBlock.copy(renderedMarkdown = renderedMarkdown)
        }
    }

    private fun matchingMarkdown(
        candidate: MarkdownDocument?,
        message: Message,
        blocks: List<SessionChatTimelineBlock>,
    ): MarkdownDocument? {
        if (candidate == null) return null
        val soleText =
            blocks.filterIsInstance<SessionChatTimelineBlock.Text>().singleOrNull()
                ?: return candidate
        return candidate.takeUnless { isCollapsedAwayFromMessageText(message, soleText.text) }
    }

    private fun isCollapsedAwayFromMessageText(
        message: Message,
        soleText: String,
    ): Boolean = message.role == MessageRole.User && soleText != message.text


    private fun errorMessage(throwable: Throwable): String =
        when (throwable) {
            is MessageError.Http -> throwable.message ?: "Request failed"
            is MessageError.Transport -> "Unable to reach the server"
            is MessageError.Decoding -> "Unable to read server response"
            else -> throwable.message ?: "Something went wrong"
        }

    private fun logI(message: String) {
        DiagnosticLog.info(DiagnosticLogArea.Chat, message)
    }

    private fun logD(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun logE(
        message: String,
        throwable: Throwable,
    ) {
        DiagnosticLog.error(DiagnosticLogArea.Chat, message, throwable)
    }

    companion object {
        private const val TAG = "SessionChatTimeline"
    }
}
