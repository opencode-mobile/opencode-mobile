package dev.opencode.mobile.domain.message

import android.os.Looper
import android.util.Log
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.live.LiveStoreRefreshPolicy
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.domain.sessioncache.NoOpSessionCache
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.sessioncache.SessionMessageCacheHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis

typealias MessageListFetcher = suspend (sessionId: String, directory: String?) -> List<Message>

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MessageSync(
    private val listMessages: MessageListFetcher,
    private val engine: SessionMessageEngine,
    private val eventStream: ForegroundEventStream,
    private val liveStoreScope: LiveStoreScope,
    private val pollIntervalMs: Long = LiveStoreRefreshPolicy.WORKING_POLL_INTERVAL_MS,
    private val eventStagingCapacity: Int = MESSAGE_EVENT_STAGING_CAPACITY,
    private val eventStagingByteCapacity: Int = MESSAGE_EVENT_STAGING_BYTE_CAPACITY,
    private val connectionProfileIdProvider: () -> String = { "local" },
    private val recoveryDelay: suspend (Long) -> Unit = { ms -> delay(ms) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val connectionEpoch: ConnectionEpoch = ConnectionEpoch.shared,
    private val sessionCache: SessionCache = NoOpSessionCache,
) {
    private val observationCache = ConcurrentHashMap<MessageSessionKey, SharedFlow<MessageListUpdate>>()
    private val lastFingerprints = ConcurrentHashMap<String, String>()
    private val lastPublishedFrames = ConcurrentHashMap<String, List<Message>>()
    private val lastPublishedRevisions = ConcurrentHashMap<String, Long>()
    private val healthByKey = ConcurrentHashMap<MessageSessionKey, MessageSyncHealth>()
    private val messageCacheHandles = ConcurrentHashMap<MessageSessionKey, SessionMessageCacheHandle>()
    /** Store slots that painted from cache and still need network confirmation. */
    private val awaitingCacheSyncConfirm = ConcurrentHashMap.newKeySet<String>()
    private val observationGeneration = AtomicInteger(0)
    private val terminalSessionKeys = ConcurrentHashMap.newKeySet<MessageSessionKey>()
    private val lastMessageEventAtMs = ConcurrentHashMap<String, Long>()

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
    ): List<Message> = fullReloadOrThrow(sessionKey(sessionId, directory), reason = "refresh")

    fun clearCache() {
        observationGeneration.incrementAndGet()
        observationCache.keys.forEach { terminalSessionKeys.add(it) }
        observationCache.clear()
        lastFingerprints.clear()
        lastPublishedFrames.clear()
        lastPublishedRevisions.clear()
        healthByKey.clear()
        awaitingCacheSyncConfirm.clear()
        messageCacheHandles.keys.toList().forEach { closeMessageCacheHandle(it) }
        engine.clear()
    }

    /** Test/observability seam for the recovery/fault matrix. */
    fun syncHealth(
        sessionId: String,
        directory: String?,
    ): MessageSyncHealth = healthByKey[sessionKey(sessionId, directory)] ?: MessageSyncHealth.Healthy

    private fun sessionKey(
        sessionId: String,
        directory: String?,
    ): MessageSessionKey =
        MessageSessionKey.of(
            connectionProfileId = connectionProfileIdProvider(),
            directory = directory,
            sessionId = sessionId,
        )

    private fun buildMessageFlow(key: MessageSessionKey): Flow<MessageListUpdate> =
        merge(
            incrementalMessageFlow(key),
            timelinePollFlow(key),
        )

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
            noteMessageActivity(key.storeSlot)
            fun observationStillCurrent(): Boolean =
                observationGeneration.get() == startedGeneration && key !in terminalSessionKeys
            // Adopted forward on stream.reconnected / server.connected so a
            // ConnectionEpoch bump from SSE start/reconnect does not latch
            // Recovering on a stale observation generation.
            val observationEpoch = AtomicLong(connectionEpoch.current())
            val staging = Channel<StagedItem>(capacity = eventStagingCapacity)
            val health = AtomicReference<MessageSyncHealth>(MessageSyncHealth.Healthy)
            val authorityWindowOpen = AtomicBoolean(false)
            val racedDuringAuthority = AtomicBoolean(false)
            val pendingRecoveryWhileIdle = AtomicBoolean(false)
            val queued = AtomicInteger(0)
            val queuedBytes = AtomicInteger(0)
            val highWater = AtomicInteger(0)
            val highWaterBytes = AtomicInteger(0)
            var serverConnectedReloadJob: Job? = null
            val storeSlot = key.storeSlot
            val directory = key.directory.ifEmpty { null }
            val cacheHandle = messageCacheHandle(key)
            // One authoritative reconnect catch-up per ConnectionEpoch (OPE-165).
            val lastReconnectCatchUpEpoch = AtomicLong(Long.MIN_VALUE)
            val resumeCatchUpRequestedEpoch = AtomicLong(Long.MIN_VALUE)
            val seenConnected = AtomicBoolean(false)

            fun claimReconnectCatchUp(): Boolean {
                val epoch = connectionEpoch.current()
                while (true) {
                    val previous = lastReconnectCatchUpEpoch.get()
                    if (previous == epoch) return false
                    if (lastReconnectCatchUpEpoch.compareAndSet(previous, epoch)) return true
                }
            }

            suspend fun emitMessages(
                messages: List<Message>,
                isFromCache: Boolean = false,
            ) {
                if (isFromCache) {
                    awaitingCacheSyncConfirm.add(storeSlot)
                } else {
                    awaitingCacheSyncConfirm.remove(storeSlot)
                    cacheHandle.note(messages)
                }
                send(MessageListUpdate.Messages(messages = messages, isFromCache = isFromCache))
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

            fun drainStaging() {
                while (true) {
                    val item = staging.tryReceive().getOrNull() ?: break
                    if (item is StagedItem.Event) {
                        noteDequeued(item.byteCost)
                    } else {
                        noteDequeued(0)
                    }
                }
                queued.set(0)
                queuedBytes.set(0)
            }

            fun publishHealth(next: MessageSyncHealth) {
                health.set(next)
                healthByKey[key] = next
            }

            fun requiresRecovery(): Boolean = health.get().requiresAuthoritativeRecovery

            fun isRelevant(event: OpencodeEvent): Boolean =
                when (event.type) {
                    "stream.reconnected", "server.connected" -> true
                    else -> event.sessionId == key.sessionId
                }

            fun noteRelevantWhileRecovering() {
                if (authorityWindowOpen.get()) {
                    racedDuringAuthority.set(true)
                } else {
                    pendingRecoveryWhileIdle.set(true)
                    if (health.get() !is MessageSyncHealth.Recovering) {
                        publishHealth(MessageSyncHealth.Recovering(MessageSyncRecoveryReason.RacedAuthority))
                    }
                    staging.trySend(StagedItem.RecoveryRequired)
                }
            }

            fun enterRecovering(
                reason: MessageSyncRecoveryReason,
                wakeConsumer: Boolean = true,
            ) {
                publishHealth(MessageSyncHealth.Recovering(reason))
                drainStaging()
                MessagePipelineProbe.emit(MessagePipelineProbe.Marker.RecoveryStarted)
                if (wakeConsumer) {
                    staging.trySend(StagedItem.Overflow)
                }
            }

            fun beginAuthorityWindow() {
                authorityWindowOpen.set(true)
                racedDuringAuthority.set(false)
                MessagePipelineProbe.emit(MessagePipelineProbe.Marker.AuthorityWindowOpened)
            }

            fun completeAuthorityWindow(reloadSucceeded: Boolean): Boolean {
                authorityWindowOpen.set(false)
                val raced = racedDuringAuthority.getAndSet(false)
                MessagePipelineProbe.emit(MessagePipelineProbe.Marker.AuthorityWindowClosed)
                if (!reloadSucceeded) {
                    publishHealth(MessageSyncHealth.Recovering(MessageSyncRecoveryReason.ReloadFailed))
                    MessagePipelineProbe.emit(MessagePipelineProbe.Marker.RecoveryRetry)
                    return false
                }
                if (raced) {
                    publishHealth(MessageSyncHealth.Recovering(MessageSyncRecoveryReason.RacedAuthority))
                    MessagePipelineProbe.emit(MessagePipelineProbe.Marker.RecoveryRetry)
                    return false
                }
                publishHealth(MessageSyncHealth.Healthy)
                pendingRecoveryWhileIdle.set(false)
                MessagePipelineProbe.emit(MessagePipelineProbe.Marker.RecoveryHealthy)
                return true
            }

            fun markOverflow() {
                if (requiresRecovery()) return
                logW(
                    "eventStagingOverflow session=${key.sessionId} capacity=$eventStagingCapacity " +
                        "byteCapacity=$eventStagingByteCapacity highWater=${highWater.get()} " +
                        "highWaterBytes=${highWaterBytes.get()}",
                )
                enterRecovering(MessageSyncRecoveryReason.Overflow)
            }

            fun assertOffMainThread() {
                val main = runCatching { Looper.getMainLooper() }.getOrNull() ?: return
                check(Looper.myLooper() != main) {
                    "MessageSync ingress/reduction must not run on the main thread"
                }
            }

            fun isCurrentEpoch(): Boolean = connectionEpoch.current() == observationEpoch.get()

            fun adoptCurrentEpoch() {
                observationEpoch.set(connectionEpoch.current())
            }

            fun requestResumeCatchUp(): Boolean {
                if (!seenConnected.get()) return false
                val needsCatchUp =
                    health.get() is MessageSyncHealth.Stale || !isCurrentEpoch()
                if (!needsCatchUp) return false
                val epoch = connectionEpoch.current()
                while (true) {
                    val previous = resumeCatchUpRequestedEpoch.get()
                    if (previous == epoch) return false
                    if (resumeCatchUpRequestedEpoch.compareAndSet(previous, epoch)) {
                        staging.trySend(StagedItem.TransportResumed)
                        return true
                    }
                }
            }

            val producer =
                launch {
                    eventStream.events.collect { event ->
                        assertOffMainThread()
                        if (!isActive || !observationStillCurrent()) return@collect
                        if (!MessageEventRouter.shouldStageForMessageSync(event)) return@collect
                        if (requiresRecovery()) {
                            if (isRelevant(event)) noteRelevantWhileRecovering()
                            return@collect
                        }
                        val cost = MessageEventRouter.estimatedByteCost(event)
                        if (queued.get() >= eventStagingCapacity ||
                            queuedBytes.get() + cost > eventStagingByteCapacity
                        ) {
                            markOverflow()
                            return@collect
                        }
                        val result =
                            staging.trySend(
                                StagedItem.Event(event, observationEpoch.get(), cost),
                            )
                        if (result.isSuccess) {
                            noteQueued(cost)
                        } else if (!result.isClosed) {
                            markOverflow()
                        }
                    }
                }

            val connectionWatcher =
                launch {
                    eventStream.connectionState.collect { state ->
                        when (state) {
                            EventStreamConnectionState.Connected -> {
                                // Cold resume after stop() reaches Connected/Live without
                                // stream.reconnected. If server.connected is missed, still
                                // adopt the bumped epoch and fullReload — otherwise a
                                // latched streaming turn blocks the poll watchdog forever.
                                val shouldRecover =
                                    seenConnected.get() &&
                                        (
                                            health.get() is MessageSyncHealth.Stale ||
                                                !isCurrentEpoch()
                                        )
                                seenConnected.set(true)
                                if (shouldRecover) {
                                    requestResumeCatchUp()
                                }
                            }
                            EventStreamConnectionState.Disconnected,
                            EventStreamConnectionState.Reconnecting,
                            EventStreamConnectionState.Idle,
                            -> {
                                if (!seenConnected.get()) return@collect
                                if (lastPublishedFrames[storeSlot] == null) return@collect
                                // Unlatch streaming so the poll watchdog can catch up even
                                // before Connected triggers TransportResumed recovery.
                                engine.finishStreamingText(storeSlot)
                                if (!isCurrentEpoch()) return@collect
                                if (!health.get().isHealthy) return@collect
                                publishHealth(
                                    MessageSyncHealth.Stale(MessageSyncRecoveryReason.TransportUnavailable),
                                )
                            }
                            EventStreamConnectionState.Connecting -> {
                                // OPE-165: catch up as soon as cold resume occupies the stream
                                // (Connecting after stop cleared wasConnected), before live.
                                requestResumeCatchUp()
                            }
                        }
                    }
                }

            suspend fun performClosedLoopRecovery(reason: MessageSyncRecoveryReason) {
                if (!observationStillCurrent() || !isCurrentEpoch()) {
                    // SSE start/reconnect bumps ConnectionEpoch under an in-flight
                    // observation. Never leave Recovering latched when we cannot
                    // complete authority for this generation.
                    if (requiresRecovery()) {
                        publishHealth(MessageSyncHealth.Healthy)
                    }
                    return
                }
                if (!requiresRecovery()) {
                    enterRecovering(reason)
                }
                var backoffMs = MessageSyncRecoveryPolicy.INITIAL_BACKOFF_MS
                while (isActive && isCurrentEpoch() && observationStillCurrent()) {
                    drainStaging()
                    beginAuthorityWindow()
                    when (val result = fullReloadResult(key, reason = "overflow-recovery")) {
                        is ReloadResult.Success -> {
                            val becameHealthy = completeAuthorityWindow(reloadSucceeded = true)
                            logI(
                                "observeMessages emit session=${key.sessionId} count=${result.messages.size} " +
                                    "source=overflow-recovery highWater=${highWater.get()}",
                            )
                            if (isCurrentEpoch()) {
                                rememberPublishedFrame(storeSlot, result.messages)
                                emitMessages(result.messages)
                                MessagePipelineProbe.emit(MessagePipelineProbe.Marker.DisplayFramePublished)
                            }
                            if (becameHealthy) return
                        }
                        is ReloadResult.Failure -> {
                            completeAuthorityWindow(reloadSucceeded = false)
                            if (isMissingSessionError(result.error)) {
                                terminalSessionKeys.add(key)
                                logW(
                                    "fullReload missing session=${key.sessionId} reason=overflow-recovery " +
                                        "stopping recovery",
                                )
                                return
                            }
                            // Retain last good frame; do not emit LoadFailed.
                        }
                    }
                    MessagePipelineProbe.emit(MessagePipelineProbe.Marker.RecoveryRetry)
                    recoveryDelay(backoffMs)
                    backoffMs = minOf(backoffMs * 2, MessageSyncRecoveryPolicy.MAX_BACKOFF_MS)
                }
            }

            suspend fun handleEvent(event: OpencodeEvent) {
                assertOffMainThread()
                if (!observationStillCurrent()) return
                if (event.type == "stream.reconnected") {
                    serverConnectedReloadJob?.cancel()
                    serverConnectedReloadJob = null
                    if (!claimReconnectCatchUp()) return
                    adoptCurrentEpoch()
                    enterRecovering(MessageSyncRecoveryReason.Reconnect, wakeConsumer = false)
                    performClosedLoopRecovery(MessageSyncRecoveryReason.Reconnect)
                    return
                }
                if (event.type == "server.connected") {
                    serverConnectedReloadJob?.cancel()
                    serverConnectedReloadJob =
                        launch {
                            delay(LiveStoreRefreshPolicy.MESSAGE_DEBOUNCE_MS)
                            if (!observationStillCurrent() || !claimReconnectCatchUp()) return@launch
                            adoptCurrentEpoch()
                            enterRecovering(MessageSyncRecoveryReason.Reconnect, wakeConsumer = false)
                            performClosedLoopRecovery(MessageSyncRecoveryReason.Reconnect)
                        }
                    return
                }

                if (!isCurrentEpoch()) return
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
                            beginAuthorityWindow()
                            when (val result = fullReloadResult(key, reason = "message.part.updated-rest-seeded")) {
                                is ReloadResult.Success -> {
                                    val healthy = completeAuthorityWindow(reloadSucceeded = true)
                                    rememberPublishedFrame(storeSlot, result.messages)
                                    logI(
                                        "observeMessages emit session=${key.sessionId} count=${result.messages.size} " +
                                            "source=message.part.updated-rest-seeded",
                                    )
                                    emitMessages(result.messages)
                                    if (!healthy) {
                                        performClosedLoopRecovery(MessageSyncRecoveryReason.RacedAuthority)
                                    }
                                }
                                is ReloadResult.Failure -> {
                                    completeAuthorityWindow(reloadSucceeded = false)
                                    performClosedLoopRecovery(MessageSyncRecoveryReason.ReloadFailed)
                                }
                            }
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
                        if (field != "text" && field != "reasoning") return
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
                            beginAuthorityWindow()
                            when (val result = fullReloadResult(key, reason = "message.part.removed-rest-seeded")) {
                                is ReloadResult.Success -> {
                                    val healthy = completeAuthorityWindow(reloadSucceeded = true)
                                    rememberPublishedFrame(storeSlot, result.messages)
                                    logI(
                                        "observeMessages emit session=${key.sessionId} count=${result.messages.size} " +
                                            "source=message.part.removed-rest-seeded",
                                    )
                                    emitMessages(result.messages)
                                    if (!healthy) {
                                        performClosedLoopRecovery(MessageSyncRecoveryReason.RacedAuthority)
                                    }
                                }
                                is ReloadResult.Failure -> {
                                    completeAuthorityWindow(reloadSucceeded = false)
                                    performClosedLoopRecovery(MessageSyncRecoveryReason.ReloadFailed)
                                }
                            }
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
                        when (val result = fullReloadResult(key, reason = "session.idle")) {
                            is ReloadResult.Success -> {
                                rememberPublishedFrame(storeSlot, result.messages)
                                logI(
                                    "observeMessages emit session=${key.sessionId} count=${result.messages.size} " +
                                        "source=session.idle",
                                )
                                emitMessages(result.messages)
                                publishHealth(MessageSyncHealth.Healthy)
                            }
                            is ReloadResult.Failure -> {
                                engine.finishStreamingText(storeSlot)
                                val recovered = engine.messages(storeSlot)
                                if (markChanged(storeSlot, recovered)) {
                                    rememberPublishedFrame(storeSlot, recovered)
                                    logW("session.idle reload failed; resumed polling session=${key.sessionId}")
                                    emitMessages(recovered)
                                }
                            }
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

                val expectedRevision = engine.revision(storeSlot)
                var loaded: List<Message>? = null
                var failure: Throwable? = null
                val elapsedMs =
                    measureTimeMillis {
                        runCatching { listMessages(key.sessionId, directory) }
                            .onSuccess { loaded = it }
                            .onFailure { failure = it }
                    }
                if (requiresRecovery()) {
                    logW(
                        "eventStagingRejectStaleAuthority session=${key.sessionId} " +
                            "highWater=${highWater.get()} ms=$elapsedMs",
                    )
                    performClosedLoopRecovery(MessageSyncRecoveryReason.Overflow)
                } else {
                    val messages = loaded
                    if (messages == null) {
                        if (lastPublishedFrames.containsKey(storeSlot)) {
                            publishHealth(MessageSyncHealth.Recovering(MessageSyncRecoveryReason.ReloadFailed))
                            performClosedLoopRecovery(MessageSyncRecoveryReason.ReloadFailed)
                        } else {
                            logW("fullReload failed session=${key.sessionId} reason=initial-subscribe ms=$elapsedMs")
                            send(
                                MessageListUpdate.LoadFailed(
                                    failure ?: IllegalStateException("Message load failed"),
                                ),
                            )
                        }
                    } else {
                        val previousFingerprint = lastFingerprints[storeSlot]
                        engine.replaceAllIfRevision(storeSlot, messages, expectedRevision)
                        val merged = engine.messages(storeSlot)
                        rememberPublishedFrame(storeSlot, merged)
                        publishHealth(MessageSyncHealth.Healthy)
                        logI(
                            "fullReload session=${key.sessionId} reason=initial-subscribe " +
                                "count=${merged.size} ms=$elapsedMs",
                        )
                        // Skip identical REST republish after cache seed (or warm
                        // replay) so DisplayMessagePipeline does not cancel
                        // HistoryPlainFirst enrich and flash a second timeline.
                        if (previousFingerprint != messageFingerprint(merged)) {
                            logI(
                                "observeMessages emit session=${key.sessionId} count=${merged.size} " +
                                    "source=initial-subscribe",
                            )
                            emitMessages(merged, isFromCache = false)
                            MessagePipelineProbe.emit(MessagePipelineProbe.Marker.DisplayFramePublished)
                        } else {
                            cacheHandle.note(merged)
                            awaitingCacheSyncConfirm.remove(storeSlot)
                            logI(
                                "observeMessages skip session=${key.sessionId} count=${merged.size} " +
                                    "source=initial-subscribe fingerprint-unchanged",
                            )
                            send(MessageListUpdate.CacheSyncConfirmed)
                        }
                    }
                }

                for (item in staging) {
                    if (!observationStillCurrent()) break
                    when (item) {
                        is StagedItem.Overflow, is StagedItem.RecoveryRequired -> {
                            val reason =
                                (health.get() as? MessageSyncHealth.Recovering)?.reason
                                    ?: MessageSyncRecoveryReason.Overflow
                            performClosedLoopRecovery(reason)
                        }
                        is StagedItem.TransportResumed -> {
                            serverConnectedReloadJob?.cancel()
                            serverConnectedReloadJob = null
                            if (!claimReconnectCatchUp()) continue
                            adoptCurrentEpoch()
                            enterRecovering(MessageSyncRecoveryReason.Reconnect, wakeConsumer = false)
                            performClosedLoopRecovery(MessageSyncRecoveryReason.Reconnect)
                        }
                        is StagedItem.Event -> {
                            noteDequeued(item.byteCost)
                            if (item.epoch != observationEpoch.get()) {
                                // Drop stale-epoch work.
                            } else if (requiresRecovery() || pendingRecoveryWhileIdle.getAndSet(false)) {
                                val reason =
                                    (health.get() as? MessageSyncHealth.Recovering)?.reason
                                        ?: MessageSyncRecoveryReason.Overflow
                                performClosedLoopRecovery(reason)
                            } else {
                                handleEvent(item.event)
                            }
                        }
                    }
                }
            } finally {
                serverConnectedReloadJob?.cancel()
                producer.cancel()
                connectionWatcher.cancel()
                staging.close()
                closeMessageCacheHandle(key)
            }
        }

    private sealed interface StagedItem {
        data class Event(
            val event: OpencodeEvent,
            val epoch: Long,
            val byteCost: Int,
        ) : StagedItem

        data object Overflow : StagedItem

        data object RecoveryRequired : StagedItem

        /** SSE returned to Connected after Idle/Disconnect without a reconnect event. */
        data object TransportResumed : StagedItem
    }

    private fun timelinePollFlow(key: MessageSessionKey): Flow<MessageListUpdate> =
        channelFlow {
            val startedGeneration = observationGeneration.get()
            val storeSlot = key.storeSlot
            val directory = key.directory.ifEmpty { null }
            while (isActive) {
                delay(pollIntervalMs)
                if (observationGeneration.get() != startedGeneration || key in terminalSessionKeys) return@channelFlow
                if (healthByKey[key]?.requiresAuthoritativeRecovery == true) continue
                // Polling REST mid-stream publishes a behind snapshot and looks like the
                // assistant message clearing then reprinting from the start.
                val streaming = engine.hasStreamingText(storeSlot)
                if (streaming && !isStreamingStalled(storeSlot)) continue
                val expectedRevision = engine.revision(storeSlot)
                val directory = key.directory.ifEmpty { null }
                val load = runCatching { listMessages(key.sessionId, directory) }
                if (load.isFailure) {
                    val error = load.exceptionOrNull()
                    if (error != null && isMissingSessionError(error)) {
                        terminalSessionKeys.add(key)
                        return@channelFlow
                    }
                    continue
                }
                val messages = load.getOrThrow()
                if (streaming) {
                    val local = engine.messages(storeSlot)
                    if (!MessageSnapshotAuthority.isAheadOrEqual(remote = messages, local = local)) {
                        continue
                    }
                    logI("streaming stall catch-up session=${key.sessionId}")
                }
                val fp = messageFingerprint(messages)
                if (fp != lastFingerprints[storeSlot]) {
                    if (!engine.replaceAllIfRevision(storeSlot, messages, expectedRevision)) continue
                    val merged = engine.messages(storeSlot)
                    noteMessageActivity(storeSlot)
                    rememberPublishedFrame(storeSlot, merged)
                    logD(
                        "observeMessages emit session=${key.sessionId} count=${merged.size} " +
                            "source=poll fingerprint-changed",
                    )
                    messageCacheHandle(key).note(merged)
                    awaitingCacheSyncConfirm.remove(storeSlot)
                    send(MessageListUpdate.Messages(messages = merged, isFromCache = false))
                } else if (awaitingCacheSyncConfirm.remove(storeSlot)) {
                    // Initial REST may still be hung after reconnect; poll already
                    // agrees with the cache — clear Syncing without a second paint.
                    messageCacheHandle(key).note(messages)
                    logI(
                        "observeMessages skip session=${key.sessionId} count=${messages.size} " +
                            "source=poll fingerprint-unchanged",
                    )
                    send(MessageListUpdate.CacheSyncConfirmed)
                }
            }
        }

    private sealed interface ReloadResult {
        data class Success(
            val messages: List<Message>,
        ) : ReloadResult

        data class Failure(
            val error: Throwable,
        ) : ReloadResult
    }

    private suspend fun fullReloadOrThrow(
        key: MessageSessionKey,
        reason: String,
    ): List<Message> {
        var messages: List<Message>? = null
        val storeSlot = key.storeSlot
        val directory = key.directory.ifEmpty { null }
        val expectedRevision = engine.revision(storeSlot)
        val elapsedMs =
            measureTimeMillis {
                messages = listMessages(key.sessionId, directory)
            }
        engine.replaceAllIfRevision(storeSlot, messages!!, expectedRevision)
        val merged = engine.messages(storeSlot)
        rememberPublishedFrame(storeSlot, merged)
        logI(
            "fullReload session=${key.sessionId} reason=$reason count=${merged.size} ms=$elapsedMs",
        )
        return merged
    }

    private suspend fun fullReloadResult(
        key: MessageSessionKey,
        reason: String,
    ): ReloadResult {
        var messages: List<Message>? = null
        var failure: Throwable? = null
        val storeSlot = key.storeSlot
        val directory = key.directory.ifEmpty { null }
        val expectedRevision = engine.revision(storeSlot)
        val elapsedMs =
            measureTimeMillis {
                runCatching { listMessages(key.sessionId, directory) }
                    .onSuccess { messages = it }
                    .onFailure { failure = it }
            }
        val loaded = messages
        if (loaded == null) {
            logW("fullReload failed session=${key.sessionId} reason=$reason ms=$elapsedMs")
            return ReloadResult.Failure(failure ?: IllegalStateException("Message load failed"))
        }
        engine.replaceAllIfRevision(storeSlot, loaded, expectedRevision)
        val merged = engine.messages(storeSlot)
        rememberPublishedFrame(storeSlot, merged)
        logI(
            "fullReload session=${key.sessionId} reason=$reason count=${merged.size} ms=$elapsedMs",
        )
        MessagePipelineProbe.emit(MessagePipelineProbe.Marker.ReducerCommitted)
        return ReloadResult.Success(merged)
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

    private fun messageFingerprint(messages: List<Message>): String =
        messages.joinToString("|") { message ->
            val parts =
                message.parts.joinToString(",") { part ->
                    when (part) {
                        is MessagePart.Text -> "text:${part.text.length}:${part.text.hashCode()}:${part.isStreaming}"
                        is MessagePart.Tool ->
                            "tool:${part.callId}:${part.status}:${part.output?.hashCode()}:${part.error?.hashCode()}"
                        is MessagePart.File ->
                            "file:${part.id}:${part.filename}:${part.mime}:" +
                                "${part.dataUrl.length}:${part.dataUrl.hashCode()}"
                        is MessagePart.Compaction -> "compaction:${part.id}:${part.auto}:${part.completed}"
                        is MessagePart.Reasoning ->
                            "reasoning:${part.text.length}:${part.text.hashCode()}:${part.isStreaming}"
                    }
                }
            "${message.id}:${message.text.length}:${message.text.hashCode()}:$parts"
        }

    private fun rememberPublishedFrame(
        storeSlot: String,
        messages: List<Message>,
        revision: Long? = null,
    ) {
        lastPublishedFrames[storeSlot] = messages
        lastPublishedRevisions[storeSlot] = revision ?: engine.revision(storeSlot)
        lastFingerprints[storeSlot] = messageFingerprint(messages)
    }

    private fun markChanged(
        storeSlot: String,
        messages: List<Message>,
    ): Boolean {
        val fp = messageFingerprint(messages)
        if (fp == lastFingerprints[storeSlot]) return false
        rememberPublishedFrame(storeSlot, messages)
        return true
    }

    private fun noteMessageActivity(storeSlot: String) {
        lastMessageEventAtMs[storeSlot] = nowMs()
    }

    private fun isStreamingStalled(storeSlot: String): Boolean {
        val last = lastMessageEventAtMs[storeSlot] ?: return true
        return nowMs() - last >= pollIntervalMs
    }

    private fun logI(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logD(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun logW(message: String) {
        runCatching { Log.w(TAG, message) }
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
