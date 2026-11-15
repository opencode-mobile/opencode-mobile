package dev.opencode.mobile.domain.message

import android.os.Looper
import android.util.Log
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.event.ResumeTiming
import dev.opencode.mobile.domain.event.StoreRefreshReason
import dev.opencode.mobile.domain.live.LiveStoreRefreshPolicy
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.domain.sessioncache.NoOpSessionCache
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.sessioncache.SessionMessageCacheHandle
import dev.opencode.mobile.domain.sync.ProjectionKey
import dev.opencode.mobile.domain.sync.ProjectionState
import dev.opencode.mobile.domain.sync.SyncCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis

typealias MessageListFetcher = suspend (sessionId: String, directory: String?) -> List<Message>
typealias MessageTailFetcher = suspend (sessionId: String, directory: String?) -> MessagePage
typealias MessageOlderFetcher = suspend (sessionId: String, directory: String?, cursor: String) -> MessagePage
typealias MessageFetcher = suspend (sessionId: String, directory: String?, messageId: String) -> Message

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MessageSync(
    listMessages: MessageListFetcher? = null,
    listMessageTail: MessageTailFetcher? = null,
    listOlderMessages: MessageOlderFetcher? = null,
    listSplitTurnMessages: MessageOlderFetcher? = null,
    private val getMessage: MessageFetcher? = null,
    private val engine: SessionMessageEngine,
    private val eventStream: ForegroundEventStream,
    private val liveStoreScope: LiveStoreScope,
    private val syncCoordinator: SyncCoordinator,
    private val eventStagingCapacity: Int = MESSAGE_EVENT_STAGING_CAPACITY,
    private val eventStagingByteCapacity: Int = MESSAGE_EVENT_STAGING_BYTE_CAPACITY,
    private val connectionProfileIdProvider: () -> String = { "local" },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sessionCache: SessionCache = NoOpSessionCache,
) {
    private val listTail: MessageTailFetcher =
        listMessageTail
            ?: { sessionId, directory ->
                val messages =
                    requireNotNull(listMessages) { "listMessages or listMessageTail required" }(
                        sessionId,
                        directory,
                    )
                MessagePage(messages = messages, olderCursor = null, hasOlder = false)
            }
    private val listOlder: MessageOlderFetcher =
        listOlderMessages
            ?: { _, _, _ -> MessagePage(messages = emptyList(), olderCursor = null, hasOlder = false) }
    private val listSplitTurn: MessageOlderFetcher = listSplitTurnMessages ?: listOlder

    private val retentionLock = Any()
    private val observations = ConcurrentHashMap<MessageSessionKey, Observation>()
    private val releasesInFlight = ConcurrentHashMap<MessageSessionKey, Job>()
    private var closedCounter = 0L
    private val lastIdentities = ConcurrentHashMap<String, MessageFrameIdentity>()
    private val lastPublishedFrames = ConcurrentHashMap<String, List<Message>>()
    private val healthByKey = ConcurrentHashMap<MessageSessionKey, MessageSyncHealth>()
    private val catchUpSlots = MutableStateFlow<Set<String>>(emptySet())
    private val messageCacheHandles = ConcurrentHashMap<MessageSessionKey, SessionMessageCacheHandle>()

    /** Store slots that painted from cache and still need network confirmation. */
    private val awaitingCacheSyncConfirm = ConcurrentHashMap.newKeySet<String>()

    /** Live send/pull `refreshMessages` hops. Coordinator still goes stale→syncing; chrome stays off (OPE-253). */
    private val quietRefreshDepth = ConcurrentHashMap<String, Int>()

    private val observationGeneration = AtomicInteger(0)
    private val lastPublishedProfileId = AtomicReference<String?>(null)
    private val restAttempts = CopyOnWriteArrayList<String>()
    private val historyWindows = ConcurrentHashMap<String, HistoryWindow>()
    private val extraPublishes = ConcurrentHashMap<String, kotlinx.coroutines.flow.MutableSharedFlow<MessageListUpdate>>()
    private val olderLoadsInFlight = ConcurrentHashMap.newKeySet<String>()

    fun observeMessages(
        sessionId: String,
        directory: String?,
    ): Flow<MessageListUpdate> {
        val key = sessionKey(sessionId, directory)
        return flow {
            val observation = acquireObservation(key)
            try {
                val shared = observation.shared
                // replay=1 may be CacheSyncConfirmed or a Messages frame from before
                // loadOlderMessages. Emit the current transcript first, then drop the
                // shared replay so it cannot regress hasOlder or the painted list.
                // A transcript still being released is not painted: it loads like a first open.
                val snapshot = if (observation.afterRelease == null) replayPublishedSnapshot(key) else null
                if (snapshot != null) {
                    emit(snapshot)
                    var skipStaleReplay = shared.replayCache.isNotEmpty()
                    shared.collect { update ->
                        if (skipStaleReplay) {
                            skipStaleReplay = false
                            return@collect
                        }
                        emit(update)
                    }
                } else {
                    emitAll(shared)
                }
            } finally {
                releaseObserver(observation)
            }
        }
    }

    /**
     * Releases every session nothing observes, ignoring the grace period and the warm cap.
     * Called on memory pressure; open sessions are untouched.
     */
    fun releaseClosedSessions() {
        synchronized(retentionLock) {
            observations.values.filter { it.observers == 0 }.forEach { releaseLocked(it) }
        }
    }

    /** True while [sessionId] holds an in-memory transcript or its observation. */
    fun retainsTranscript(
        sessionId: String,
        directory: String?,
    ): Boolean {
        val key = sessionKey(sessionId, directory)
        val slot = key.storeSlot
        return observations.containsKey(key) ||
            lastPublishedFrames.containsKey(slot) ||
            historyWindows.containsKey(slot) ||
            engine.retains(slot)
    }

    /**
     * One session's shared observation. It runs in its own scope so releasing the session
     * stops the share coroutine and frees its replay buffer.
     */
    private inner class Observation(
        val key: MessageSessionKey,
        /** Release of an earlier observation of the same session; the upstream waits for it. */
        val afterRelease: Job?,
    ) {
        val scope: CoroutineScope =
            liveStoreScope.coroutineScope.coroutineContext.let { parent ->
                CoroutineScope(parent + SupervisorJob(parent[Job]))
            }
        val shared: SharedFlow<MessageListUpdate> =
            flow {
                afterRelease?.join()
                emitAll(buildMessageFlow(key))
            }.shareIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS),
                replay = 1,
            )

        /** Guarded by [retentionLock]. */
        var observers = 0

        /** Order in which the last observer stopped; guarded by [retentionLock]. */
        var closedOrder = 0L

        /** Guarded by [retentionLock]. */
        var graceJob: Job? = null
    }

    private fun acquireObservation(key: MessageSessionKey): Observation =
        synchronized(retentionLock) {
            val observation =
                observations.getOrPut(key) {
                    Observation(key, afterRelease = releasesInFlight[key]?.takeIf { it.isActive })
                }
            observation.observers += 1
            observation.graceJob?.cancel()
            observation.graceJob = null
            observation
        }

    private fun releaseObserver(observation: Observation) {
        synchronized(retentionLock) {
            if (observations[observation.key] !== observation) return
            observation.observers -= 1
            if (observation.observers > 0) return
            observation.closedOrder = ++closedCounter
            observation.graceJob =
                liveStoreScope.coroutineScope.launch {
                    delay(TranscriptRetentionPolicy.CLOSED_SESSION_GRACE_MS)
                    synchronized(retentionLock) {
                        if (observations[observation.key] === observation && observation.observers == 0) {
                            releaseLocked(observation)
                        }
                    }
                }
            observations.values
                .filter { it.observers == 0 }
                .sortedByDescending { it.closedOrder }
                .drop(TranscriptRetentionPolicy.MAX_WARM_CLOSED_SESSIONS)
                .forEach { releaseLocked(it) }
        }
    }

    /** Stops [observation]'s share coroutine, then drops everything held for its session. */
    private fun releaseLocked(observation: Observation) {
        val key = observation.key
        observations.remove(key, observation)
        observation.graceJob?.cancel()
        observation.graceJob = null
        val generation = observationGeneration.get()
        val release =
            liveStoreScope.coroutineScope.launch {
                observation.scope.coroutineContext[Job]?.cancelAndJoin()
                if (observationGeneration.get() == generation) dropSession(key)
            }
        releasesInFlight[key] = release
        release.invokeOnCompletion { releasesInFlight.remove(key, release) }
    }

    private fun dropSession(key: MessageSessionKey) {
        val storeSlot = key.storeSlot
        lastIdentities.remove(storeSlot)
        lastPublishedFrames.remove(storeSlot)
        historyWindows.remove(storeSlot)
        extraPublishes.remove(storeSlot)
        awaitingCacheSyncConfirm.remove(storeSlot)
        healthByKey.remove(key)
        setCatchUpSlot(key, false)
        closeMessageCacheHandle(key)
        engine.release(storeSlot)
    }

    suspend fun refreshMessages(
        sessionId: String,
        directory: String?,
        reason: String = StoreRefreshReason.PULL_TO_REFRESH,
    ): List<Message> {
        val key = sessionKey(sessionId, directory)
        val projection = transcriptKey(key)
        val storeSlot = key.storeSlot
        quietRefreshDepth.merge(storeSlot, 1) { current, increment -> current + increment }
        try {
            syncCoordinator.invalidate(projection)
            syncCoordinator.ensureFresh(projection)
            return engine.messages(storeSlot)
        } finally {
            // After the window closes, a failed refresh is re-published by the coordinator retry as
            // Syncing, and the caller receives the error.
            quietRefreshDepth.compute(storeSlot) { _, current ->
                val next = (current ?: 0) - 1
                if (next > 0) next else null
            }
        }
    }

    /** Production REST attempt reasons, recorded before the fetch succeeds or throws. */
    fun recordedRestAttempts(): List<String> = restAttempts.toList()

    suspend fun loadOlderMessages(
        sessionId: String,
        directory: String?,
    ): OlderLoadResult {
        val key = sessionKey(sessionId, directory)
        val storeSlot = key.storeSlot
        if (!olderLoadsInFlight.add(storeSlot)) {
            val current = engine.messages(storeSlot)
            return OlderLoadResult(current, historyWindow(storeSlot).hasOlder, addedCount = 0)
        }
        try {
            val window = historyWindow(storeSlot)
            if (!window.hasOlder || window.olderCursor.isNullOrBlank()) {
                return OlderLoadResult(engine.messages(storeSlot), hasOlder = false, addedCount = 0)
            }
            restAttempts += "load-older"
            val page = listOlder(key.sessionId, key.directory.ifEmpty { null }, window.olderCursor)
            val beforeIds = engine.messages(storeSlot).map { it.id }.toSet()
            var applied = false
            while (!applied) {
                applied = engine.prependOlderIfRevision(storeSlot, page.messages, engine.revision(storeSlot))
            }
            val after = engine.messages(storeSlot)
            val added = after.count { it.id !in beforeIds }
            rememberHistoryWindow(storeSlot, page)
            rememberPublishedFrame(storeSlot, after)
            return OlderLoadResult(after, historyWindow(storeSlot).hasOlder, addedCount = added)
        } finally {
            olderLoadsInFlight.remove(storeSlot)
        }
    }

    /**
     * The full picture of image [imageId] (a tool image or an image file part) in message
     * [messageId]: from the store when it already holds the data URL, otherwise by fetching
     * that one message, which also hydrates the store and republishes the timeline. Throws
     * when the image cannot be loaded.
     */
    suspend fun fullImage(
        sessionId: String,
        directory: String?,
        messageId: String,
        imageId: String,
    ): MessagePart.File {
        val key = sessionKey(sessionId, directory)
        val storeSlot = key.storeSlot
        engine.message(storeSlot, messageId)?.imageWithData(imageId)?.let { return it }
        val fetch = getMessage ?: throw IllegalStateException("single-message fetch unavailable")
        restAttempts += "full-image"
        val message = fetch(key.sessionId, key.directory.ifEmpty { null }, messageId)
        val image = message.imageWithData(imageId) ?: throw NoSuchElementException("image not in message")
        if (engine.hydrateMedia(storeSlot, listOf(message))) {
            val merged = engine.messages(storeSlot)
            rememberPublishedFrame(storeSlot, merged)
            messageCacheHandles[key]?.note(merged)
            extraPublishFlow(storeSlot).tryEmit(
                MessageListUpdate.Messages(
                    messages = merged,
                    isFromCache = storeSlot in awaitingCacheSyncConfirm,
                    hasOlder = historyWindow(storeSlot).hasOlder,
                ),
            )
        }
        return image
    }

    private fun Message.imageWithData(imageId: String): MessagePart.File? {
        val image =
            parts.firstNotNullOfOrNull { part ->
                when (part) {
                    is MessagePart.Tool -> part.images.firstOrNull { it.id == imageId }
                    is MessagePart.File -> part.takeIf { it.id == imageId }
                    else -> null
                }
            }
        return image?.takeIf { it.hasFullData }
    }

    fun invalidate(
        sessionId: String,
        directory: String?,
        reason: String = StoreRefreshReason.PULL_TO_REFRESH,
    ) {
        syncCoordinator.invalidate(transcriptKey(sessionKey(sessionId, directory)))
    }

    fun clearCache() {
        observationGeneration.incrementAndGet()
        val stale =
            synchronized(retentionLock) {
                observations.values.toList().also { observations.clear() }
            }
        stale.forEach { observation ->
            observation.graceJob?.cancel()
            observation.scope.cancel()
        }
        lastIdentities.clear()
        lastPublishedFrames.clear()
        healthByKey.clear()
        catchUpSlots.value = emptySet()
        awaitingCacheSyncConfirm.clear()
        lastPublishedProfileId.set(null)
        historyWindows.clear()
        extraPublishes.clear()
        olderLoadsInFlight.clear()
        messageCacheHandles.keys.toList().forEach { closeMessageCacheHandle(it) }
        engine.clear()
        syncCoordinator.invalidate(null)
    }

    /** Last observation that published a transcript frame. */
    fun publishedProfileId(): String? = lastPublishedProfileId.get()

    fun syncHealth(
        sessionId: String,
        directory: String?,
    ): MessageSyncHealth = healthByKey[sessionKey(sessionId, directory)] ?: MessageSyncHealth.Fresh

    fun observeCatchUpChrome(): Flow<Boolean> = catchUpSlots.map { it.isNotEmpty() }.distinctUntilChanged()

    fun observeCatchUpChrome(
        sessionId: String,
        directory: String?,
    ): Flow<Boolean> {
        val slot = sessionKey(sessionId, directory).storeSlot
        // Slots only. Coordinator stale during a live `refreshMessages` is not catch-up chrome.
        return catchUpSlots.map { slot in it }.distinctUntilChanged()
    }

    private fun setCatchUpSlot(
        key: MessageSessionKey,
        catchingUp: Boolean,
    ) {
        catchUpSlots.update { current ->
            if (catchingUp) current + key.storeSlot else current - key.storeSlot
        }
    }

    /** Reconnect and cache-confirm raise Syncing. A live send/pull refresh stays quiet (OPE-253). */
    private fun applyCoordinatorHealth(
        key: MessageSessionKey,
        storeSlot: String,
        state: ProjectionState,
    ) {
        val awaitingCache = storeSlot in awaitingCacheSyncConfirm
        if ((quietRefreshDepth[storeSlot] ?: 0) > 0 &&
            state != ProjectionState.Fresh &&
            !awaitingCache
        ) {
            return
        }
        healthByKey[key] =
            when (state) {
                ProjectionState.Fresh -> MessageSyncHealth.Fresh
                ProjectionState.Stale -> MessageSyncHealth.Stale
                ProjectionState.Syncing -> MessageSyncHealth.Syncing
            }
        setCatchUpSlot(
            key,
            state != ProjectionState.Fresh || awaitingCache,
        )
    }

    private fun sessionKey(
        sessionId: String,
        directory: String?,
    ): MessageSessionKey =
        MessageSessionKey.of(
            connectionProfileId = connectionProfileIdProvider(),
            directory = directory,
            sessionId = sessionId,
        )

    private fun transcriptKey(key: MessageSessionKey) = ProjectionKey.Transcript(key.directory, key.sessionId)

    private fun replayPublishedSnapshot(key: MessageSessionKey): MessageListUpdate.Messages? {
        val storeSlot = key.storeSlot
        val published = lastPublishedFrames[storeSlot] ?: return null
        return MessageListUpdate.Messages(
            messages = published,
            isFromCache = storeSlot in awaitingCacheSyncConfirm,
            hasOlder = historyWindow(storeSlot).hasOlder,
        )
    }

    private fun buildMessageFlow(key: MessageSessionKey): Flow<MessageListUpdate> =
        merge(
            incrementalMessageFlow(key),
            extraPublishFlow(key.storeSlot),
        )

    private fun extraPublishFlow(storeSlot: String): kotlinx.coroutines.flow.MutableSharedFlow<MessageListUpdate> =
        extraPublishes.getOrPut(storeSlot) {
            kotlinx.coroutines.flow.MutableSharedFlow(extraBufferCapacity = 8)
        }

    private data class HistoryWindow(
        val olderCursor: String? = null,
        val hasOlder: Boolean = false,
    )

    private fun historyWindow(storeSlot: String): HistoryWindow = historyWindows[storeSlot] ?: HistoryWindow()

    private fun rememberHistoryWindow(
        storeSlot: String,
        page: MessagePage,
        preserveOlderThanTail: Boolean = false,
    ) {
        val current = historyWindows[storeSlot]
        val local = engine.messages(storeSlot)
        val noOverlapReset =
            page.messages.isNotEmpty() &&
                !MessageWindowMerge.hasOverlap(local, page.messages)
        if (
            preserveOlderThanTail &&
            !noOverlapReset &&
            current != null &&
            current.hasOlder &&
            !current.olderCursor.isNullOrBlank()
        ) {
            return
        }
        historyWindows[storeSlot] =
            HistoryWindow(
                olderCursor = page.olderCursor,
                hasOlder = page.hasOlder,
            )
    }

    private fun messageCacheHandle(key: MessageSessionKey): SessionMessageCacheHandle =
        messageCacheHandles.computeIfAbsent(key) {
            sessionCache.openMessages(sessionId = key.sessionId, worktree = key.directory)
        }

    private fun closeMessageCacheHandle(key: MessageSessionKey) {
        messageCacheHandles.remove(key)?.close()
    }

    private fun incrementalMessageFlow(key: MessageSessionKey): Flow<MessageListUpdate> =
        channelFlow {
            val startedGeneration = observationGeneration.get()

            fun observationStillCurrent(): Boolean = observationGeneration.get() == startedGeneration

            // A stopped or released observation must not commit a late REST page: the loader
            // runs in the coordinator's scope and can outlive this one by a suspension point.
            fun mayCommit(): Boolean = observationStillCurrent() && isActive

            val staging = Channel<StagedItem>(capacity = eventStagingCapacity)
            val queued = AtomicInteger(0)
            val queuedBytes = AtomicInteger(0)
            val highWater = AtomicInteger(0)
            val highWaterBytes = AtomicInteger(0)
            val storeSlot = key.storeSlot
            val directory = key.directory.ifEmpty { null }
            val cacheHandle = messageCacheHandle(key)
            val projection = transcriptKey(key)
            val extra = extraPublishFlow(storeSlot)
            val overflowed =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)
            val authorityWindowOpen =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)
            val idleRacedAuthority =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)

            suspend fun emitMessages(
                messages: List<Message>,
                isFromCache: Boolean = false,
                hasOlder: Boolean = historyWindow(storeSlot).hasOlder,
            ) {
                if (isFromCache) {
                    awaitingCacheSyncConfirm.add(storeSlot)
                    setCatchUpSlot(key, true)
                    ResumeTiming.markCachePainted(nowMs())
                } else {
                    awaitingCacheSyncConfirm.remove(storeSlot)
                    cacheHandle.note(messages)
                    // applyCoordinatorHealth owns the slot so a live SSE apply
                    // cannot hide reconnect chrome while the projection is stale.
                }
                lastPublishedProfileId.set(key.connectionProfileId)
                send(
                    MessageListUpdate.Messages(
                        messages = messages,
                        isFromCache = isFromCache,
                        hasOlder = hasOlder,
                    ),
                )
            }

            fun noteQueued(cost: Int) {
                val depth = queued.incrementAndGet()
                val bytes = queuedBytes.addAndGet(cost)
                highWater.updateAndGet { current -> maxOf(current, depth) }
                highWaterBytes.updateAndGet { current -> maxOf(current, bytes) }
            }

            fun noteDequeued(cost: Int) {
                queued.updateAndGet { current -> maxOf(0, current - 1) }
                queuedBytes.updateAndGet { current -> maxOf(0, current - cost) }
            }

            var handleEventFn: (suspend (OpencodeEvent) -> Unit)? = null

            suspend fun applyQueuedIncrementalEvents() {
                val leftover = ArrayDeque<StagedItem.Event>()
                while (true) {
                    when (val item = staging.tryReceive().getOrNull() ?: break) {
                        is StagedItem.Event -> {
                            if (MessageEventRouter.isIncrementalTranscriptEvent(item.event)) {
                                noteDequeued(item.byteCost)
                                handleEventFn?.invoke(item.event)
                            } else {
                                leftover.add(item)
                            }
                        }
                        else -> noteDequeued(0)
                    }
                }
                leftover.forEach { staging.send(it) }
            }

            fun assertOffMainThread() {
                val main = runCatching { Looper.getMainLooper() }.getOrNull() ?: return
                check(Looper.myLooper() != main) {
                    "MessageSync ingress/reduction must not run on the main thread"
                }
            }

            suspend fun confirmCacheSyncIfAwaiting() {
                if (!awaitingCacheSyncConfirm.remove(storeSlot)) return
                setCatchUpSlot(key, false)
                send(MessageListUpdate.CacheSyncConfirmed(hasOlder = historyWindow(storeSlot).hasOlder))
            }

            suspend fun requestCatchUp() {
                if (!observationStillCurrent()) return
                syncCoordinator.invalidate(projection)
                syncCoordinator.ensureFresh(projection)
            }

            val registration =
                syncCoordinator.register(projection) {
                    authorityWindowOpen.set(true)
                    try {
                        val generation = syncCoordinator.profileGeneration
                        applyQueuedIncrementalEvents()
                        val previous = lastIdentities[storeSlot]
                        val previousFrame = lastPublishedFrames[storeSlot]
                        when (
                            val result =
                                fullReloadResult(
                                    key,
                                    reason = "transcript",
                                    mergeExisting = true,
                                    beforeCommit = {
                                        if (syncCoordinator.profileGeneration == generation) {
                                            applyQueuedIncrementalEvents()
                                        }
                                    },
                                    stillCurrent = { syncCoordinator.profileGeneration == generation && mayCommit() },
                                )
                        ) {
                            is ReloadResult.Accepted,
                            is ReloadResult.Discarded,
                            -> {
                                if (syncCoordinator.profileGeneration != generation) return@register
                                if (overflowed.getAndSet(false)) {
                                    throw IllegalStateException("event staging overflow")
                                }
                                val messages =
                                    when (result) {
                                        is ReloadResult.Accepted -> result.messages
                                        is ReloadResult.Discarded -> result.messages
                                        is ReloadResult.Failure -> error("unreachable")
                                    }
                                if (result is ReloadResult.Accepted && result.confirmedWithoutPublish) {
                                    confirmCacheSyncIfAwaiting()
                                } else if (
                                    previous != frameIdentity(storeSlot, messages) &&
                                    messages != previousFrame
                                ) {
                                    // Merge bumps the engine revision even when the tail is byte-identical;
                                    // only repaint when the frame itself changed (matches iOS).
                                    emitMessages(messages)
                                } else {
                                    confirmCacheSyncIfAwaiting()
                                }
                                backfillSplitTurnIfNeeded(
                                    key,
                                    stillCurrent = { mayCommit() },
                                    emitMessages = { frame -> emitMessages(frame, isFromCache = false) },
                                )
                                healthByKey[key] = MessageSyncHealth.Fresh
                            }
                            is ReloadResult.Failure -> {
                                healthByKey[key] = MessageSyncHealth.Failed(result.error)
                                if (!lastPublishedFrames.containsKey(storeSlot)) {
                                    extra.tryEmit(MessageListUpdate.LoadFailed(result.error))
                                }
                                if (isMissingSessionError(result.error)) {
                                    return@register
                                }
                                throw result.error
                            }
                        }
                    } finally {
                        authorityWindowOpen.set(false)
                    }
                }
            syncCoordinator.visible(projection, true)

            val healthWatcher =
                launch {
                    syncCoordinator.observe(projection).collect { state ->
                        applyCoordinatorHealth(key, storeSlot, state)
                    }
                }

            val producer =
                launch {
                    eventStream.events.collect { event ->
                        assertOffMainThread()
                        if (!isActive || !observationStillCurrent()) return@collect
                        if (!MessageEventRouter.shouldStageForMessageSync(event)) return@collect
                        if (event.type == "session.idle" &&
                            event.sessionId == key.sessionId &&
                            authorityWindowOpen.get()
                        ) {
                            idleRacedAuthority.set(true)
                        }
                        val cost = MessageEventRouter.estimatedByteCost(event)
                        if (queued.get() >= eventStagingCapacity ||
                            queuedBytes.get() + cost > eventStagingByteCapacity
                        ) {
                            logW(
                                "eventStagingOverflow session=${key.sessionId} capacity=$eventStagingCapacity " +
                                    "byteCapacity=$eventStagingByteCapacity highWater=${highWater.get()} " +
                                    "highWaterBytes=${highWaterBytes.get()}",
                            )
                            staging.trySend(StagedItem.Overflow)
                            overflowed.set(true)
                            return@collect
                        }
                        val result = staging.trySend(StagedItem.Event(event, cost))
                        if (result.isSuccess) {
                            noteQueued(cost)
                        } else if (!result.isClosed) {
                            staging.trySend(StagedItem.Overflow)
                            overflowed.set(true)
                        }
                    }
                }

            suspend fun handleEvent(event: OpencodeEvent) {
                assertOffMainThread()
                if (!observationStillCurrent()) return
                if (event.sessionId != key.sessionId) return
                MessagePipelineProbe.emit(MessagePipelineProbe.Marker.EventAccepted)

                var applyResult: MessageStoreApplyResult? = null
                when (event.type) {
                    "message.updated" -> {
                        val infoJson = event.rawProperties?.get("info")?.jsonObject ?: return
                        applyResult = engine.apply(storeSlot, MessagePatch.InfoUpdated(infoJson))
                    }
                    "message.part.updated" -> {
                        if (requiresFullReloadForRestSeededPartUpdate(event.rawProperties, storeSlot)) {
                            requestCatchUp()
                            return
                        }
                        val partJson = event.rawProperties?.get("part")?.jsonObject ?: return
                        val delta =
                            event.rawProperties
                                ?.get("delta")
                                ?.jsonPrimitive
                                ?.contentOrNull
                        applyResult = engine.apply(storeSlot, MessagePatch.PartUpdated(partJson, delta))
                    }
                    "message.part.delta" -> {
                        val props = event.rawProperties ?: return
                        val field = props["field"]?.jsonPrimitive?.contentOrNull
                        if (field != "text" && field != "reasoning" && field != "input") return
                        val messageId = props["messageID"]?.jsonPrimitive?.contentOrNull ?: return
                        val partId = props["partID"]?.jsonPrimitive?.contentOrNull ?: return
                        val delta = props["delta"]?.jsonPrimitive?.contentOrNull ?: return
                        applyResult = engine.apply(storeSlot, MessagePatch.PartDelta(messageId, partId, delta))
                    }
                    "message.removed" -> {
                        val messageId =
                            event.rawProperties
                                ?.get("messageID")
                                ?.jsonPrimitive
                                ?.contentOrNull ?: return
                        applyResult = engine.apply(storeSlot, MessagePatch.Removed(messageId))
                    }
                    "session.revert.committed" -> {
                        val messageId =
                            event.rawProperties
                                ?.get("messageID")
                                ?.jsonPrimitive
                                ?.contentOrNull ?: return
                        applyResult = engine.apply(storeSlot, MessagePatch.RemovedFrom(messageId))
                        if (applyResult == MessageStoreApplyResult.NoChange) {
                            // The revert point is outside the loaded window: let REST settle it.
                            requestCatchUp()
                            return
                        }
                    }
                    EventDomainAdapter.MESSAGE_CONTEXT_EVENT -> {
                        val patch = MessageContextPatch.from(event.rawProperties ?: return) ?: return
                        engine.apply(storeSlot, patch)
                        return
                    }
                    "message.part.removed" -> {
                        if (requiresFullReloadForRestSeededPartRemoval(event.rawProperties, storeSlot)) {
                            requestCatchUp()
                            return
                        }
                        val messageId =
                            event.rawProperties
                                ?.get("messageID")
                                ?.jsonPrimitive
                                ?.contentOrNull ?: return
                        val partId =
                            event.rawProperties
                                ?.get("partID")
                                ?.jsonPrimitive
                                ?.contentOrNull ?: return
                        applyResult = engine.apply(storeSlot, MessagePatch.PartRemoved(messageId, partId))
                    }
                    "session.idle" -> {
                        engine.finishStreamingText(storeSlot)
                        val recovered = engine.messages(storeSlot)
                        if (markChanged(storeSlot, recovered)) {
                            logI(
                                "observeMessages emit session=${key.sessionId} count=${recovered.size} " +
                                    "source=session.idle",
                            )
                            emitMessages(recovered)
                        }
                        if (SessionTranscriptAuthority.lastAssistantNeedsAuthority(recovered) ||
                            idleRacedAuthority.getAndSet(false)
                        ) {
                            requestCatchUp()
                        }
                        return
                    }
                    else -> return
                }

                val changeSet =
                    when (val result = applyResult) {
                        is MessageStoreApplyResult.Changed -> result.changeSet
                        else -> return
                    }
                if (!engine.shouldPublishLive(changeSet)) return
                MessagePipelineProbe.emit(MessagePipelineProbe.Marker.ReducerCommitted)
                val frame =
                    engine.frameAfterChange(
                        storeSlot,
                        changeSet,
                        lastPublishedFrames[storeSlot],
                    )
                rememberPublishedFrame(storeSlot, frame, changeSet.sessionRevision)
                logD(
                    "observeMessages emit session=${key.sessionId} count=${frame.size} " +
                        "source=sse-incremental type=${event.type} revision=${changeSet.sessionRevision}",
                )
                emitMessages(frame)
                MessagePipelineProbe.emit(MessagePipelineProbe.Marker.DisplayFramePublished)
            }

            handleEventFn = { event -> handleEvent(event) }

            logI("observeMessages subscribe session=${key.sessionId} directory=${directory ?: "-"}")
            try {
                val seeded = seedOrNull(cacheHandle, key.sessionId)
                if (seeded != null) {
                    val seedRevision = engine.revision(storeSlot)
                    engine.replaceAllIfRevision(storeSlot, seeded, seedRevision)
                    val seededMerged = engine.messages(storeSlot)
                    rememberPublishedFrame(storeSlot, seededMerged)
                    logI(
                        "observeMessages emit session=${key.sessionId} count=${seededMerged.size} " +
                            "source=cache-seed",
                    )
                    emitMessages(seededMerged, isFromCache = true)
                    MessagePipelineProbe.emit(MessagePipelineProbe.Marker.DisplayFramePublished)
                }

                syncCoordinator.ensureFresh(projection)

                for (item in staging) {
                    if (!observationStillCurrent()) break
                    when (item) {
                        is StagedItem.Overflow -> requestCatchUp()
                        is StagedItem.Event -> {
                            noteDequeued(item.byteCost)
                            handleEvent(item.event)
                        }
                    }
                }
            } finally {
                healthWatcher.cancel()
                producer.cancel()
                staging.close()
                syncCoordinator.visible(projection, false)
                syncCoordinator.unregister(registration)
                if (observationStillCurrent()) {
                    closeMessageCacheHandle(key)
                    healthByKey.remove(key)
                    setCatchUpSlot(key, false)
                }
            }
        }

    /**
     * The cache seed, or null when it fails: a seed is only a first paint, so its failure must
     * never abort the subscription before [SyncCoordinator.ensureFresh] loads the session (#1033).
     * The handle's frame has unwound by the catch, so an OutOfMemoryError's allocations are free.
     */
    private suspend fun seedOrNull(
        cacheHandle: SessionMessageCacheHandle,
        sessionId: String,
    ): List<Message>? {
        val failure: Throwable =
            try {
                return cacheHandle.seed()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                exception
            } catch (outOfMemory: OutOfMemoryError) {
                outOfMemory
            } catch (overflow: StackOverflowError) {
                overflow
            }
        logW("observeMessages cache seed failed session=$sessionId: ${failure::class.simpleName}")
        return null
    }

    private sealed interface StagedItem {
        data class Event(
            val event: OpencodeEvent,
            val byteCost: Int,
        ) : StagedItem

        data object Overflow : StagedItem
    }

    private sealed interface ReloadResult {
        data class Accepted(
            val messages: List<Message>,
            val confirmedWithoutPublish: Boolean = false,
        ) : ReloadResult

        data class Discarded(
            val messages: List<Message>,
        ) : ReloadResult

        data class Failure(
            val error: Throwable,
        ) : ReloadResult
    }

    private suspend fun fetchTail(
        sessionId: String,
        directory: String?,
        reason: String,
    ): MessagePage {
        restAttempts += reason
        return listTail(sessionId, directory)
    }

    private suspend fun backfillSplitTurnIfNeeded(
        key: MessageSessionKey,
        stillCurrent: () -> Boolean = { true },
        emitMessages: suspend (List<Message>) -> Unit,
    ) {
        val storeSlot = key.storeSlot
        val directory = key.directory.ifEmpty { null }
        var extra = 0
        while (extra < MessageHistoryWindow.SPLIT_TURN_MAX_PAGES) {
            if (!stillCurrent()) return
            val local = engine.messages(storeSlot)
            if (!beginsMidTurn(local)) return
            val window = historyWindow(storeSlot)
            if (!window.hasOlder || window.olderCursor.isNullOrBlank()) return
            restAttempts += "split-turn"
            val older =
                runCatching { listSplitTurn(key.sessionId, directory, window.olderCursor) }
                    .getOrElse {
                        logW("splitTurnBackfill failed session=${key.sessionId}")
                        return
                    }
            if (older.messages.isEmpty()) return
            var applied = false
            while (!applied) {
                applied = engine.prependOlderIfRevision(storeSlot, older.messages, engine.revision(storeSlot))
            }
            rememberHistoryWindow(storeSlot, older)
            val merged = engine.messages(storeSlot)
            rememberPublishedFrame(storeSlot, merged)
            logI("splitTurnBackfill session=${key.sessionId} extra=${extra + 1} count=${merged.size}")
            emitMessages(merged)
            extra += 1
        }
    }

    private suspend fun fullReloadResult(
        key: MessageSessionKey,
        reason: String,
        mergeExisting: Boolean = false,
        beforeCommit: suspend () -> Unit = {},
        stillCurrent: () -> Boolean = { true },
    ): ReloadResult {
        var page: MessagePage? = null
        var failure: Throwable? = null
        val storeSlot = key.storeSlot
        val directory = key.directory.ifEmpty { null }
        val expectedRevision = engine.revision(storeSlot)
        val elapsedMs =
            measureTimeMillis {
                runCatching { fetchTail(key.sessionId, directory, reason) }
                    .onSuccess { page = it }
                    .onFailure { failure = it }
            }
        beforeCommit()
        if (!stillCurrent()) {
            return ReloadResult.Discarded(engine.messages(storeSlot))
        }
        val loaded = page
        if (loaded == null) {
            logW("fullReload failed session=${key.sessionId} reason=$reason ms=$elapsedMs")
            return ReloadResult.Failure(failure ?: IllegalStateException("Message load failed"))
        }
        rememberHistoryWindow(
            storeSlot,
            loaded,
            preserveOlderThanTail = engine.messages(storeSlot).isNotEmpty(),
        )
        val local = engine.messages(storeSlot)
        if (
            awaitingCacheSyncConfirm.contains(storeSlot) &&
            local.matchesTailIdentity(loaded.messages)
        ) {
            // The cached copy stays, but the cache never keeps tool images or file data URLs:
            // fill them in from the page just fetched, and publish only when that changed a part.
            val hydrated = engine.hydrateMedia(storeSlot, loaded.messages)
            logI(
                "fullReload session=${key.sessionId} reason=$reason count=${local.size} ms=$elapsedMs " +
                    "tailIdentity=match hydrated=$hydrated",
            )
            MessagePipelineProbe.emit(MessagePipelineProbe.Marker.ReducerCommitted)
            if (hydrated) {
                val merged = engine.messages(storeSlot)
                rememberPublishedFrame(storeSlot, merged)
                return ReloadResult.Accepted(merged)
            }
            return ReloadResult.Accepted(local, confirmedWithoutPublish = true)
        }
        val acceptRemote =
            engine.revision(storeSlot) == expectedRevision &&
                MessageWindowMerge.isTailAcceptable(remote = loaded.messages, local = local)
        val applied =
            acceptRemote &&
                if (mergeExisting) {
                    engine.mergeTailAuthoritativeIfRevision(storeSlot, loaded.messages, expectedRevision)
                } else {
                    engine.applyTailIfRevision(storeSlot, loaded.messages, expectedRevision)
                }
        val merged = engine.messages(storeSlot)
        rememberPublishedFrame(storeSlot, merged)
        logI(
            "fullReload session=${key.sessionId} reason=$reason count=${merged.size} ms=$elapsedMs",
        )
        MessagePipelineProbe.emit(MessagePipelineProbe.Marker.ReducerCommitted)
        return if (applied) {
            ReloadResult.Accepted(merged)
        } else {
            ReloadResult.Discarded(merged)
        }
    }

    private fun requiresFullReloadForRestSeededPartUpdate(
        rawProperties: JsonObject?,
        storeSlot: String,
    ): Boolean {
        val messageId =
            rawProperties
                ?.get("part")
                ?.jsonObject
                ?.get("messageID")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: return false
        return engine.isRestSeededMessage(storeSlot, messageId)
    }

    private fun requiresFullReloadForRestSeededPartRemoval(
        rawProperties: JsonObject?,
        storeSlot: String,
    ): Boolean {
        val messageId = rawProperties?.get("messageID")?.jsonPrimitive?.contentOrNull ?: return false
        return engine.isRestSeededMessage(storeSlot, messageId)
    }

    private fun frameIdentity(
        storeSlot: String,
        messages: List<Message>,
        revision: Long? = null,
    ): MessageFrameIdentity = messages.frameIdentity(revision ?: engine.revision(storeSlot))

    private fun rememberPublishedFrame(
        storeSlot: String,
        messages: List<Message>,
        revision: Long? = null,
    ) {
        lastPublishedFrames[storeSlot] = messages
        lastIdentities[storeSlot] = frameIdentity(storeSlot, messages, revision)
    }

    private fun markChanged(
        storeSlot: String,
        messages: List<Message>,
    ): Boolean {
        val identity = frameIdentity(storeSlot, messages)
        if (identity == lastIdentities[storeSlot]) return false
        rememberPublishedFrame(storeSlot, messages)
        return true
    }

    private fun logI(message: String) {
        DiagnosticLog.info(DiagnosticLogArea.Chat, message)
    }

    private fun logD(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun logW(message: String) {
        DiagnosticLog.warning(DiagnosticLogArea.Chat, message)
    }

    private companion object {
        private const val TAG = "MessageSync"

        fun isMissingSessionError(error: Throwable): Boolean {
            var current: Throwable? = error
            while (current != null) {
                val text = current.message.orEmpty()
                if (
                    text.contains("NotFoundError") ||
                    text.contains("Session not found", ignoreCase = true)
                ) {
                    return true
                }
                current = current.cause
            }
            return false
        }
    }
}
