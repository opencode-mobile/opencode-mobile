package dev.opencode.mobile.ui.sessionchat.region

import android.util.Log
import dev.opencode.mobile.domain.command.ServerCommand
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.message.BlockDecorationCache
import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.DisplayMessageListUpdate
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageError
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ThoughtPartChrome
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.markdown.MarkdownDocument
import dev.opencode.mobile.ui.sessionchat.ContentRevision
import dev.opencode.mobile.ui.sessionchat.DisplayFrame
import dev.opencode.mobile.ui.sessionchat.DisplayMessageBody
import dev.opencode.mobile.ui.sessionchat.LatestAgentHint
import dev.opencode.mobile.ui.sessionchat.LatestModelHint
import dev.opencode.mobile.ui.sessionchat.OptimisticInputs
import dev.opencode.mobile.ui.sessionchat.SessionChatChildSessionCandidate
import dev.opencode.mobile.ui.sessionchat.SessionChatChildSessionLinker
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SessionChatTimeline(
    private val messageRepository: MessageRepository,
    private val scope: CoroutineScope,
    private val listChildSessions: (suspend (String) -> List<Session>)? = null,
) {
    var ioDispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

    /**
     * Wait before looking up child sessions, so a live `tool.progress` can link
     * a just-started subagent first. Doubles before each retry of a failed lookup.
     */
    var childSessionLookupDelayMs: Long = 1_500L

    /** Children of this session that subagent parts without a child id link to (#648). */
    @Volatile
    var childSessions: List<SessionChatChildSessionCandidate> = emptyList()
        private set

    /** Unlinked subagent parts already covered by a child-session lookup. */
    private var childSessionLookupPartIds: Set<String> = emptySet()
    private var childSessionLookupJob: Job? = null

    @Volatile
    var serverCommands: List<ServerCommand> = emptyList()

    @Volatile
    var collapseConsecutiveToolCalls: Boolean = false

    @Volatile
    var chipTimeline: Boolean = false

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

    fun updateChipTimeline(enabled: Boolean) {
        if (enabled == chipTimeline) return
        chipTimeline = enabled
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        if (lastLoadedDisplayMessages.isNotEmpty()) {
            applyLoadedDisplayMessages(
                displayMessages = lastLoadedDisplayMessages,
                sourceSessionId = sessionId,
                sourceWorktree = worktree,
                fromQuietRefresh = true,
                source = "chip-pref",
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
    private val pendingPromptPatches = mutableMapOf<String, PendingPromptPatch>()
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
        resetChildSessionLookup()
        pendingPromptPatches.clear()
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
        resetChildSessionLookup()
        pendingPromptPatches.clear()
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
                    messageRepository.loadOlderDisplayMessages(sessionId, worktree)
                }
            }.onSuccess { result ->
                if (!isCurrentBinding(sessionId, worktree)) return@launch
                _state.update { it.copy(hasOlder = result.hasOlder, isLoadingOlder = false) }
                if (result.addedCount > 0) {
                    applyLoadedDisplayMessages(
                        displayMessages = result.messages,
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
            TimelineProjector.reconciliationSignaturesById(lastLoadedServerRows())
        val displayedItems = TimelineProjector.displayedOptimisticSendItems(enrichedRows)
        val displayedRows = displayedItems.map(TimelineProjector::rowFromMessageItem)
        val previous = lastTimelineFrame
        val hadPriorRows = _state.value.messages.isNotEmpty() || !previous?.rows.isNullOrEmpty()
        val markReady =
            TimelineProjector.shouldMarkTimelineReadyAfterOptimisticSend(
                displayedItems = displayedItems,
                hadPriorDisplayedRows = hadPriorRows,
            )
        if (displayedRows.isEmpty()) {
            val frame =
                if (hadPriorRows) {
                    previous?.copy(
                        frameRevision = nextFrameRevision++,
                        insertedRowIDs = emptySet(),
                        changedRowIDs = emptySet(),
                        removedRowIDs = emptySet(),
                        layoutChangedRowIDs = emptySet(),
                        grewBottomRowIDs = emptySet(),
                        decorateRowIDs = emptySet(),
                    )
                } else {
                    previous
                }
            if (frame != null) {
                lastTimelineFrame = frame
            }
            _state.update {
                it.copy(
                    timeline =
                        if (markReady) {
                            SessionChatTimelineState.Ready
                        } else {
                            it.timeline
                        },
                    timelineFrame = frame ?: it.timelineFrame,
                    errorMessage = null,
                )
            }
            return
        }
        val frame =
            TimelineFrame(
                frameRevision = nextFrameRevision++,
                rows = (previous?.rows.orEmpty()) + displayedRows,
                insertedRowIDs = displayedRows.map { it.id }.toSet(),
                changedRowIDs = emptySet(),
                removedRowIDs = emptySet(),
                layoutChangedRowIDs = emptySet(),
            )
        lastTimelineFrame = frame
        _state.update {
            it.copy(
                messages = it.messages + displayedItems,
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

    fun pendingOptimisticItems(): List<SessionChatMessageItem> =
        pendingOptimisticRows.ifEmpty { _state.value.messages.filter { it.isPending } }

    internal fun pendingPromptPatchCount(): Int = pendingPromptPatches.size

    fun applyOptimisticPendingPrompt(
        messageId: String,
        delivery: FollowUpDelivery? = null,
        removed: Boolean = false,
    ) {
        val sessionId = boundSessionId ?: return
        val worktree = boundWorktree ?: return
        pendingPromptPatches[messageId] = PendingPromptPatch(delivery = delivery, removed = removed)
        val overlaid =
            applyPendingPromptOverlay(
                lastLoadedDisplayMessages,
                expireAgainstAuthoritative = false,
            )
        lastLoadedDisplayMessages = overlaid
        lastLoadedMessages = overlaid.map { it.message }
        applyLoadedDisplayMessages(
            displayMessages = overlaid,
            sourceSessionId = sessionId,
            sourceWorktree = worktree,
            source = "pending-prompt",
        )
    }

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
            // Pending-prompt overlay re-applies must not drop a newer live
            // snapshot; the overlay is applied onto that list at publish.
            if (source != SOURCE_PENDING_PROMPT) {
                pendingLiveDisplayApply = null
            }
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
        val displayMessages =
            applyPendingPromptOverlay(
                pending.displayMessages,
                expireAgainstAuthoritative = shouldExpirePendingPromptPatches(pending.source),
            )
        val sourceSessionId = pending.sourceSessionId
        val sourceWorktree = pending.sourceWorktree
        val isRefreshing = pending.isRefreshing
        val fromQuietRefresh = pending.fromQuietRefresh
        val source = pending.source
        val onPublished = pending.onPublished

        if (!isCurrentBinding(sourceSessionId, sourceWorktree)) return
        lastLoadedDisplayMessages = displayMessages
        lastLoadedMessages = displayMessages.map { it.message }
        scheduleChildSessionLookup(sourceSessionId, sourceWorktree)
        val children = childSessions
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
                        linkChildSessions(sortedDisplayMessages(displayMessages), children).mapNotNull { display ->
                            displayMessageBody(display, previousById[display.id])
                        },
                        collapseConsecutiveToolCalls,
                        chipTimeline,
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
        clearOptimisticIfReconciled()
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

    /**
     * Hidden pending-prompt rows leave the displayed frame before this runs, so
     * reconciliation cannot use displayed ids. An optimistic send is done when
     * every pending item has a new server echo relative to the send-time
     * baseline — the same match [TimelineProjector] uses to drop the optimistic
     * row, often under a different id.
     */
    private fun clearOptimisticIfReconciled() {
        if (!mergePendingWhileSending) return
        val pendingItems = pendingOptimisticItems()
        if (pendingItems.isEmpty()) {
            mergePendingWhileSending = false
            pendingOptimisticRows = emptyList()
            pendingBaselineSignatures = emptyMap()
            return
        }
        val stillUnacked =
            TimelineProjector
                .unmatchedPending(
                    pendingRows = pendingItems.map(TimelineProjector::rowFromMessageItem),
                    serverRows = lastLoadedServerRows(),
                    baselineSignatures = pendingBaselineSignatures,
                ).isNotEmpty()
        if (stillUnacked) return
        mergePendingWhileSending = false
        pendingOptimisticRows = emptyList()
        pendingBaselineSignatures = emptyMap()
    }

    private fun lastLoadedServerRows(): List<TimelineRow> =
        TimelineProjector.projectServerRows(
            lastLoadedDisplayMessages.mapNotNull { display ->
                displayMessageBody(display, previous = null)
            },
        )

    private data class PendingPromptPatch(
        val delivery: FollowUpDelivery?,
        val removed: Boolean,
    )

    private fun shouldExpirePendingPromptPatches(source: String): Boolean =
        source != SOURCE_PENDING_PROMPT &&
            source != "commands-ready" &&
            source != "collapse-pref" &&
            source != "chip-pref" &&
            source != SOURCE_CHILD_SESSIONS

    private fun applyPendingPromptOverlay(
        incoming: List<DisplayMessage>,
        expireAgainstAuthoritative: Boolean,
    ): List<DisplayMessage> {
        if (pendingPromptPatches.isEmpty()) return incoming
        if (expireAgainstAuthoritative) {
            expirePendingPromptPatches(incoming)
        }
        if (pendingPromptPatches.isEmpty()) return incoming
        return incoming.mapNotNull { display ->
            val patch = pendingPromptPatches[display.message.id] ?: return@mapNotNull display
            if (patch.removed) {
                null
            } else {
                display.copy(
                    message =
                        display.message.copy(
                            isWaitingForPickup = patch.delivery != null,
                            followUpDelivery = patch.delivery,
                        ),
                )
            }
        }
    }

    private fun expirePendingPromptPatches(authoritative: List<DisplayMessage>) {
        if (pendingPromptPatches.isEmpty()) return
        val byId = authoritative.associateBy { it.message.id }
        val iterator = pendingPromptPatches.iterator()
        while (iterator.hasNext()) {
            val (id, patch) = iterator.next()
            val incoming = byId[id]?.message
            if (patch.removed) {
                if (incoming == null) {
                    iterator.remove()
                }
            } else if (incoming == null || !incoming.isWaitingForPickup) {
                iterator.remove()
            } else if (
                incoming.followUpDelivery == patch.delivery &&
                incoming.isWaitingForPickup == (patch.delivery != null)
            ) {
                iterator.remove()
            }
        }
    }

    private fun isCurrentBinding(
        sessionId: String,
        worktree: String,
    ): Boolean = boundSessionId == sessionId && boundWorktree == worktree

    private fun sortedDisplayMessages(displayMessages: List<DisplayMessage>): List<DisplayMessage> =
        TimelineDisplayOrder.ordered(displayMessages)

    private fun linkChildSessions(
        displayMessages: List<DisplayMessage>,
        children: List<SessionChatChildSessionCandidate>,
    ): List<DisplayMessage> {
        val linked = SessionChatChildSessionLinker.link(displayMessages.map { it.message }, children)
        return displayMessages.zip(linked) { display, message ->
            if (message === display.message) display else display.copy(message = message)
        }
    }

    private fun resetChildSessionLookup() {
        childSessionLookupJob?.cancel()
        childSessionLookupJob = null
        childSessionLookupPartIds = emptySet()
        childSessions = emptyList()
    }

    /**
     * Looks up this session's children when a subagent part has no child id
     * (history reloads drop the one-shot `tool.progress` id), then re-projects
     * so its row or chip can open the child. Each unlinked part is looked up
     * once; a later reload with a new unlinked part looks up again. A failed
     * fetch retries with backoff.
     */
    private fun scheduleChildSessionLookup(
        sessionId: String,
        worktree: String,
    ) {
        val listChildSessions = listChildSessions ?: return
        if (childSessionLookupJob?.isActive == true) return
        if (!hasNewUnlinkedSubagent()) return
        childSessionLookupJob =
            scope.launch {
                var delayMs = childSessionLookupDelayMs
                for (attempt in 1..CHILD_SESSION_LOOKUP_ATTEMPTS) {
                    delay(delayMs)
                    if (!isCurrentBinding(sessionId, worktree)) return@launch
                    val retry = lookUpChildSessions(listChildSessions, sessionId, worktree, attempt)
                    if (!retry) return@launch
                    delayMs *= 2
                }
            }
    }

    /** Returns true when the fetch failed and should be retried. */
    private suspend fun lookUpChildSessions(
        listChildSessions: suspend (String) -> List<Session>,
        sessionId: String,
        worktree: String,
        attempt: Int,
    ): Boolean {
        val unlinked = SessionChatChildSessionLinker.unlinkedPartIds(lastLoadedMessages)
        if (childSessionLookupPartIds.containsAll(unlinked)) return false
        val sessions =
            try {
                withContext(ioDispatcher) { listChildSessions(sessionId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                logE("childSessions lookup failed session=$sessionId attempt=$attempt", throwable)
                return true
            }
        if (!isCurrentBinding(sessionId, worktree)) return false
        childSessionLookupPartIds = childSessionLookupPartIds + unlinked
        val candidates =
            sessions
                .filterNot { it.isArchived }
                .map { SessionChatChildSessionCandidate(it.id, it.title, it.createdAtEpochMs) }
        logI("childSessions lookup session=$sessionId unlinked=${unlinked.size} children=${candidates.size}")
        if (candidates == childSessions) return false
        childSessions = candidates
        if (lastLoadedDisplayMessages.isNotEmpty()) {
            applyLoadedDisplayMessages(
                displayMessages = lastLoadedDisplayMessages,
                sourceSessionId = sessionId,
                sourceWorktree = worktree,
                fromQuietRefresh = true,
                source = SOURCE_CHILD_SESSIONS,
                messagesFromCache = _state.value.messagesFromCache,
            )
        }
        return false
    }

    private fun hasNewUnlinkedSubagent(): Boolean =
        !childSessionLookupPartIds.containsAll(SessionChatChildSessionLinker.unlinkedPartIds(lastLoadedMessages))

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
                                chipTimeline = chipTimeline,
                            ),
                        commands = serverCommands,
                    ),
            )
        if (resolvedBlocks.isEmpty() && message.failure == null) return null

        val sessionKey = boundSessionId ?: message.id
        val isStreaming = display.isStreaming
        val blocksWithCache =
            resolvedBlocks.map { block ->
                // Per-block cache is keyed by the merged-text id; a step
                // paragraph looks up the id its text block would have carried.
                val cacheBlockID =
                    when (block) {
                        is SessionChatTimelineBlock.Text -> block.blockID
                        is SessionChatTimelineBlock.StepParagraph -> block.textBlockID
                        else -> null
                    }
                val text = block.markdownText
                if (cacheBlockID == null || text == null || block.decoratedMarkdown != null) {
                    block
                } else {
                    val revision = ContentRevision.of(text)
                    val cached =
                        BlockDecorationCache.get(
                            sessionKey = sessionKey,
                            blockID = cacheBlockID,
                            textHash = revision.textHash,
                            textLength = revision.textLength,
                            isStreaming = isStreaming,
                        )
                    if (cached != null) block.withRenderedMarkdown(cached) else block
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
                index.takeIf { block.markdownText != null }
            }
        if (textBlockIndexes.size != 1) return blocks
        val textIndex = textBlockIndexes.single()
        val textBlock = blocks[textIndex]
        if (textBlock.decoratedMarkdown != null) return blocks
        if (isCollapsedAwayFromMessageText(message, textBlock.markdownText.orEmpty())) return blocks
        return blocks.toMutableList().also {
            it[textIndex] = textBlock.withRenderedMarkdown(renderedMarkdown)
        }
    }

    private fun matchingMarkdown(
        candidate: MarkdownDocument?,
        message: Message,
        blocks: List<SessionChatTimelineBlock>,
    ): MarkdownDocument? {
        if (candidate == null) return null
        val soleText =
            blocks.mapNotNull { it.markdownText }.singleOrNull()
                ?: return candidate
        return candidate.takeUnless { isCollapsedAwayFromMessageText(message, soleText) }
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
        private const val SOURCE_PENDING_PROMPT = "pending-prompt"
        private const val SOURCE_CHILD_SESSIONS = "child-sessions"
        private const val CHILD_SESSION_LOOKUP_ATTEMPTS = 3
    }
}
