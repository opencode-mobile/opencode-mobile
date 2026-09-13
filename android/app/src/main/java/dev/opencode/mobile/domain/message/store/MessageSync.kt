package dev.opencode.mobile.domain.message

import android.os.Looper
import android.util.Log
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MessageSync(
    listMessages: MessageListFetcher? = null,
    listMessageTail: MessageTailFetcher? = null,
    listOlderMessages: MessageOlderFetcher? = null,
    listSplitTurnMessages: MessageOlderFetcher? = null,
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

    private val observationCache = ConcurrentHashMap<MessageSessionKey, SharedFlow<MessageListUpdate>>()
    private val lastIdentities = ConcurrentHashMap<String, MessageFrameIdentity>()
    private val lastPublishedFrames = ConcurrentHashMap<String, List<Message>>()
    private val healthByKey = ConcurrentHashMap<MessageSessionKey, MessageSyncHealth>()
    private val catchUpSlots = MutableStateFlow<Set<String>>(emptySet())
    private val messageCacheHandles = ConcurrentHashMap<MessageSessionKey, SessionMessageCacheHandle>()

    /** Store slots that painted from cache and still need network confirmation. */
    private val awaitingCacheSyncConfirm = ConcurrentHashMap.newKeySet<String>()

    private val observationGeneration = AtomicInteger(0)
    private val terminalSessionKeys = ConcurrentHashMap.newKeySet<MessageSessionKey>()
    private val lastMessageEventAtMs = ConcurrentHashMap<String, Long>()
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
        return observationCache.getOrPut(key) {
            buildMessageFlow(key)
                .shareIn(
                    scope = liveStoreScope.coroutineScope,
                    started = SharingStarted.WhileSubscribed(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS),
                    replay = 1,
                )
        }
    }

    suspend fun refreshMessages(
        sessionId: String,
        directory: String?,
        reason: String = StoreRefreshReason.PULL_TO_REFRESH,
    ): List<Message> {
        val key = sessionKey(sessionId, directory)
        val projection = transcriptKey(key)
        syncCoordinator.invalidate(projection)
        syncCoordinator.ensureFresh(projection)
        return engine.messages(key.storeSlot)
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

    fun invalidate(
        sessionId: String,
        directory: String?,
        reason: String = StoreRefreshReason.PULL_TO_REFRESH,
    ) {
        syncCoordinator.invalidate(transcriptKey(sessionKey(sessionId, directory)))
    }

    fun clearCache() {
        observationGeneration.incrementAndGet()
        observationCache.clear()
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

    fun observeCatchUpChrome(): Flow<Boolean> =
        catchUpSlots.map { it.isNotEmpty() }.distinctUntilChanged()

    fun observeCatchUpChrome(
        sessionId: String,
        directory: String?,
    ): Flow<Boolean> {
        val key = sessionKey(sessionId, directory)
        val projection = transcriptKey(key)
        val slot = key.storeSlot
        return kotlinx.coroutines.flow.combine(
            syncCoordinator.observe(projection),
            catchUpSlots,
        ) { state, slots ->
            state != ProjectionState.Fresh || slot in slots
        }.distinctUntilChanged()
    }

    private fun setCatchUpSlot(
        key: MessageSessionKey,
        catchingUp: Boolean,
    ) {
        catchUpSlots.update { current ->
            if (catchingUp) current + key.storeSlot else current - key.storeSlot
        }
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

    private fun buildMessageFlow(key: MessageSessionKey): Flow<MessageListUpdate> =
        merge(
            incrementalMessageFlow(key),
            extraPublishFlow(key.storeSlot),
        )

    private fun extraPublishFlow(storeSlot: String): kotlinx.coroutines.flow.MutableSharedFlow<MessageListUpdate> = extraPublishes.getOrPut(storeSlot) {
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

    private fun messageCacheHandle(key: MessageSessionKey): SessionMessageCacheHandle = messageCacheHandles.computeIfAbsent(key) {
            sessionCache.openMessages(sessionId = key.sessionId, worktree = key.directory)
        }

    private fun closeMessageCacheHandle(key: MessageSessionKey) {
        messageCacheHandles.remove(key)?.close()
    }

    private fun incrementalMessageFlow(key: MessageSessionKey): Flow<MessageListUpdate> =
        channelFlow {
            val startedGeneration = observationGeneration.get()
            noteMessageActivity(key.storeSlot)
            fun observationStillCurrent(): Boolean =
                observationGeneration.get() == startedGeneration && key !in terminalSessionKeys

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
            val overflowed = java.util.concurrent.atomic.AtomicBoolean(false)

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
                    setCatchUpSlot(key, false)
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
                                stillCurrent = { syncCoordinator.profileGeneration == generation },
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
                                stillCurrent = { observationStillCurrent() },
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
                }
            syncCoordinator.visible(projection, true)

            val healthWatcher =
                launch {
                    syncCoordinator.observe(projection).collect { state ->
                        healthByKey[key] =
                            when (state) {
                                ProjectionState.Fresh -> MessageSyncHealth.Fresh
                                ProjectionState.Stale -> MessageSyncHealth.Stale
                                ProjectionState.Syncing -> MessageSyncHealth.Syncing
                            }
                        setCatchUpSlot(
                            key,
                            state != ProjectionState.Fresh || storeSlot in awaitingCacheSyncConfirm,
                        )
                    }
                }

            val producer =
                launch {
                    eventStream.events.collect { event ->
                        assertOffMainThread()
                        if (!isActive || !observationStillCurrent()) return@collect
                        if (!MessageEventRouter.shouldStageForMessageSync(event)) return@collect
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
                when (event.type) {
                    "message.updated",
                    "message.part.updated",
                    "message.part.delta",
                    "message.removed",
                    "message.part.removed",
                    "session.idle",
                    -> noteMessageActivity(storeSlot)
                }
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
                        if (SessionTranscriptAuthority.lastAssistantNeedsAuthority(recovered)) {
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
                val seeded = cacheHandle.seed()
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

    private suspend fun fetchMessages(
        sessionId: String,
        directory: String?,
        reason: String,
    ): List<Message> = fetchTail(sessionId, directory, reason).messages

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
            logI(
                "fullReload session=${key.sessionId} reason=$reason count=${local.size} ms=$elapsedMs tailIdentity=match",
            )
            MessagePipelineProbe.emit(MessagePipelineProbe.Marker.ReducerCommitted)
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

    private fun noteMessageActivity(storeSlot: String) {
        lastMessageEventAtMs[storeSlot] = nowMs()
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
