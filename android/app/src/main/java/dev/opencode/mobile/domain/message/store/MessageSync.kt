package dev.opencode.mobile.domain.message

import android.os.Looper
import android.util.Log
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.GapAuthority
import dev.opencode.mobile.domain.event.LiveConnectionSnapshot
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.event.SilentWorldSignals
import dev.opencode.mobile.domain.event.StoreRefreshReason
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.event.asLiveConnection
import dev.opencode.mobile.domain.event.canCatchUp
import dev.opencode.mobile.domain.event.catchUpReason
import dev.opencode.mobile.domain.event.isReachable
import dev.opencode.mobile.domain.event.marksStale
import dev.opencode.mobile.domain.live.LiveStoreRefreshPolicy
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.domain.live.StoreInvalidationBus
import dev.opencode.mobile.domain.sessioncache.NoOpSessionCache
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.sessioncache.SessionMessageCacheHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    private val worldSignals: WorldSignals = SilentWorldSignals,
    private val pollIntervalMs: Long = LiveStoreRefreshPolicy.WORKING_POLL_INTERVAL_MS,
    private val eventStagingCapacity: Int = MESSAGE_EVENT_STAGING_CAPACITY,
    private val eventStagingByteCapacity: Int = MESSAGE_EVENT_STAGING_BYTE_CAPACITY,
    private val connectionProfileIdProvider: () -> String = { "local" },
    private val recoveryDelay: suspend (Long) -> Unit = { ms -> delay(ms) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sessionCache: SessionCache = NoOpSessionCache,
) {
    private val liveConnection = eventStream.asLiveConnection()
    private val observationCache = ConcurrentHashMap<MessageSessionKey, SharedFlow<MessageListUpdate>>()
    private val lastFingerprints = ConcurrentHashMap<String, String>()
    private val lastPublishedFrames = ConcurrentHashMap<String, List<Message>>()
    private val lastPublishedRevisions = ConcurrentHashMap<String, Long>()
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
    private val authorities = ConcurrentHashMap<MessageSessionKey, GapAuthority>()
    private val invalidationBus = StoreInvalidationBus()

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
        val authority = gapAuthority(key)
        authority.invalidate(liveConnection.snapshot.value)
        var begun = authority.captureBegin(liveConnection.snapshot.value)
        while (true) {
            when (val result = fullReloadResult(key, reason = reason)) {
                is ReloadResult.Accepted -> {
                    val newerUnpaid =
                        acknowledgeExactGeneration(
                            authority,
                            begun,
                            liveConnection.snapshot.value,
                        )
                    if (!newerUnpaid) {
                        healthByKey[key] = MessageSyncHealth.Healthy
                        return result.messages
                    }
                    begun = authority.tryBegin(liveConnection.snapshot.value)
                        ?: return result.messages
                }
                is ReloadResult.Discarded -> {
                    authority.fail(begun)
                    return result.messages
                }
                is ReloadResult.Failure -> {
                    authority.fail(begun)
                    healthByKey[key] = MessageSyncHealth.Recovering(MessageSyncRecoveryReason.ReloadFailed)
                    throw result.error
                }
            }
        }
    }

    /** Production REST attempt reasons, recorded before the fetch succeeds or throws. */
    fun recordedRestAttempts(): List<String> = restAttempts.toList()

    fun invalidate(
        sessionId: String,
        directory: String?,
        reason: String = StoreRefreshReason.PULL_TO_REFRESH,
    ) {
        invalidationBus.invalidate(
            key = sessionKey(sessionId, directory).storeSlot,
            reason = reason,
        )
    }

    fun clearCache() {
        observationGeneration.incrementAndGet()
        observationCache.clear()
        lastFingerprints.clear()
        lastPublishedFrames.clear()
        lastPublishedRevisions.clear()
        healthByKey.clear()
        catchUpSlots.value = emptySet()
        awaitingCacheSyncConfirm.clear()
        lastPublishedProfileId.set(null)
        authorities.clear()
        messageCacheHandles.keys.toList().forEach { closeMessageCacheHandle(it) }
        engine.clear()
    }

    /** Last observation that published a transcript frame. */
    fun publishedProfileId(): String? = lastPublishedProfileId.get()

    /** Test/observability seam for the recovery/fault matrix. */
    fun gapAuthority(
        sessionId: String,
        directory: String?,
    ): GapAuthority = gapAuthority(sessionKey(sessionId, directory))

    /** Test/observability seam for the recovery/fault matrix. */
    fun syncHealth(
        sessionId: String,
        directory: String?,
    ): MessageSyncHealth = healthByKey[sessionKey(sessionId, directory)] ?: MessageSyncHealth.Healthy

    fun observeCatchUpChrome(): Flow<Boolean> =
        catchUpSlots.map { it.isNotEmpty() }.distinctUntilChanged()

    fun observeCatchUpChrome(
        sessionId: String,
        directory: String?,
    ): Flow<Boolean> {
        val slot = sessionKey(sessionId, directory).storeSlot
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
            val staging = Channel<StagedItem>(capacity = eventStagingCapacity)
            val health = AtomicReference<MessageSyncHealth>(MessageSyncHealth.Healthy)
            val authorityWindowOpen = AtomicBoolean(false)
            val racedDuringAuthority = AtomicBoolean(false)
            val pendingRecoveryWhileIdle = AtomicBoolean(false)
            val queued = AtomicInteger(0)
            val queuedBytes = AtomicInteger(0)
            val highWater = AtomicInteger(0)
            val highWaterBytes = AtomicInteger(0)
            var snapshotWatcher: Job? = null
            var evaluateConsumer: Job? = null
            val storeSlot = key.storeSlot
            val directory = key.directory.ifEmpty { null }
            val cacheHandle = messageCacheHandle(key)
            val authority = gapAuthority(key)
            val recoveryGeneration = AtomicLong(0)
            val evaluateWake = Channel<String>(Channel.CONFLATED)

            fun currentSnapshot(): LiveConnectionSnapshot = liveConnection.snapshot.value

            fun invalidateIfCurrentObligationPaid() {
                val snapshot = currentSnapshot()
                if (authority.obligation(snapshot) <= authority.acknowledgedGeneration()) {
                    authority.invalidate(snapshot)
                }
            }

            fun wakeGapEvaluation(reason: String) {
                evaluateWake.trySend(reason)
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
                lastPublishedProfileId.set(key.connectionProfileId)
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

            fun publishHealth(next: MessageSyncHealth) {
                health.set(next)
                healthByKey[key] = next
                setCatchUpSlot(key, next.showsCatchUpChrome)
            }

            fun requiresRecovery(): Boolean = health.get().requiresAuthoritativeRecovery

            fun isAuthorityRace(event: OpencodeEvent): Boolean =
                when (event.type) {
                    "stream.reconnected", "server.connected", "sse.parse.failed" -> false
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

            fun completeAuthorityWindow(outcome: AuthorityWindowOutcome): Boolean {
                authorityWindowOpen.set(false)
                val raced = racedDuringAuthority.getAndSet(false)
                MessagePipelineProbe.emit(MessagePipelineProbe.Marker.AuthorityWindowClosed)
                return when (outcome) {
                    AuthorityWindowOutcome.Failed -> {
                        publishHealth(MessageSyncHealth.Recovering(MessageSyncRecoveryReason.ReloadFailed))
                        MessagePipelineProbe.emit(MessagePipelineProbe.Marker.RecoveryRetry)
                        false
                    }
                    AuthorityWindowOutcome.Discarded -> {
                        publishHealth(MessageSyncHealth.Recovering(MessageSyncRecoveryReason.RacedAuthority))
                        MessagePipelineProbe.emit(MessagePipelineProbe.Marker.RecoveryRetry)
                        false
                    }
                    AuthorityWindowOutcome.Accepted -> {
                        if (raced) {
                            publishHealth(MessageSyncHealth.Recovering(MessageSyncRecoveryReason.RacedAuthority))
                            MessagePipelineProbe.emit(MessagePipelineProbe.Marker.RecoveryRetry)
                            false
                        } else {
                            publishHealth(MessageSyncHealth.Healthy)
                            pendingRecoveryWhileIdle.set(false)
                            MessagePipelineProbe.emit(MessagePipelineProbe.Marker.RecoveryHealthy)
                            true
                        }
                    }
                }
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

            suspend fun confirmCacheSyncIfAwaiting() {
                if (!awaitingCacheSyncConfirm.remove(storeSlot)) return
                send(MessageListUpdate.CacheSyncConfirmed)
            }

            val producer =
                launch {
                    eventStream.events.collect { event ->
                        assertOffMainThread()
                        if (!isActive || !observationStillCurrent()) return@collect
                        if (!MessageEventRouter.shouldStageForMessageSync(event)) return@collect
                        if (requiresRecovery()) {
                            if (isAuthorityRace(event)) {
                                noteRelevantWhileRecovering()
                                if (MessageEventRouter.isIncrementalTranscriptEvent(event)) {
                                    handleEventFn?.invoke(event)
                                }
                            }
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
                                StagedItem.Event(event, cost),
                            )
                        if (result.isSuccess) {
                            noteQueued(cost)
                        } else if (!result.isClosed) {
                            markOverflow()
                        }
                    }
                }

            val worldWatcher =
                launch {
                    worldSignals.signals.collect { signal ->
                        when {
                            signal.marksStale() -> {
                                authority.invalidate(currentSnapshot())
                                if (lastPublishedFrames[storeSlot] == null) return@collect
                                if (!health.get().isHealthy) return@collect
                                publishHealth(
                                    MessageSyncHealth.Stale(MessageSyncRecoveryReason.TransportUnavailable),
                                )
                            }
                            else -> {
                                val reason = signal.catchUpReason() ?: return@collect
                                wakeGapEvaluation(reason)
                            }
                        }
                    }
                }

            val invalidationWatcher =
                launch {
                    invalidationBus.events.collect { invalidation ->
                        if (invalidation.key != null && invalidation.key != storeSlot) return@collect
                        authority.invalidate(currentSnapshot())
                        wakeGapEvaluation(invalidation.reason)
                    }
                }

            suspend fun performClosedLoopRecovery(
                reason: MessageSyncRecoveryReason,
                fetchReason: String = "overflow-recovery",
            ) {
                if (!observationStillCurrent()) {
                    authority.fail(recoveryGeneration.get())
                    return
                }
                if (!requiresRecovery()) {
                    enterRecovering(reason)
                }
                var acknowledged = false
                var backoffMs = MessageSyncRecoveryPolicy.INITIAL_BACKOFF_MS
                while (isActive && observationStillCurrent()) {
                    applyQueuedIncrementalEvents()
                    beginAuthorityWindow()
                    val fingerprintBefore = lastFingerprints[storeSlot]
                    when (
                        val result =
                            fullReloadResult(
                                key,
                                reason = fetchReason,
                                mergeExisting = true,
                                beforeCommit = { applyQueuedIncrementalEvents() },
                            )
                    ) {
                        is ReloadResult.Accepted -> {
                            val becameHealthy = completeAuthorityWindow(AuthorityWindowOutcome.Accepted)
                            if (observationStillCurrent()) {
                                rememberPublishedFrame(storeSlot, result.messages)
                                if (fingerprintBefore != messageFingerprint(result.messages)) {
                                    logI(
                                        "observeMessages emit session=${key.sessionId} count=${result.messages.size} " +
                                            "source=overflow-recovery highWater=${highWater.get()}",
                                    )
                                    emitMessages(result.messages)
                                    MessagePipelineProbe.emit(MessagePipelineProbe.Marker.DisplayFramePublished)
                                } else {
                                    logI(
                                        "observeMessages skip session=${key.sessionId} " +
                                            "source=overflow-recovery fingerprint-unchanged",
                                    )
                                    confirmCacheSyncIfAwaiting()
                                }
                            }
                            val newerUnpaid =
                                acknowledgeExactGeneration(
                                    authority,
                                    recoveryGeneration.get(),
                                    currentSnapshot(),
                                )
                            if (!newerUnpaid) {
                                if (!becameHealthy) {
                                    publishHealth(MessageSyncHealth.Healthy)
                                }
                                acknowledged = true
                                return
                            }
                            val next = authority.tryBegin(currentSnapshot())
                            if (next != null) {
                                recoveryGeneration.set(next)
                                continue
                            }
                        }
                        is ReloadResult.Discarded -> {
                            completeAuthorityWindow(AuthorityWindowOutcome.Discarded)
                        }
                        is ReloadResult.Failure -> {
                            completeAuthorityWindow(AuthorityWindowOutcome.Failed)
                            if (isMissingSessionError(result.error)) {
                                terminalSessionKeys.add(key)
                                publishHealth(MessageSyncHealth.Healthy)
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
                if (!acknowledged) {
                    authority.fail(recoveryGeneration.get())
                }
            }

            suspend fun reconcileGap(fetchReason: String) {
                if (!observationStillCurrent()) return
                val snapshot = currentSnapshot()
                if (!authority.needsReconcile(snapshot)) {
                    if (authority.isInFlight()) return
                    val current = health.get()
                    if (current is MessageSyncHealth.Recovering &&
                        current.reason == MessageSyncRecoveryReason.Reconnect
                    ) {
                        publishHealth(MessageSyncHealth.Healthy)
                        confirmCacheSyncIfAwaiting()
                        return
                    }
                    if (requiresRecovery()) return
                    logI("fullReload skip session=${key.sessionId} reason=$fetchReason")
                    confirmCacheSyncIfAwaiting()
                    return
                }
                if (!canCatchUp(worldSignals.occupancy.value, snapshot)) {
                    if (!snapshot.isReachable()) {
                        if (lastPublishedFrames[storeSlot] == null) return
                        engine.finishStreamingText(storeSlot)
                        if (health.get().isHealthy) {
                            publishHealth(MessageSyncHealth.Stale(MessageSyncRecoveryReason.TransportUnavailable))
                        }
                    }
                    return
                }
                val generation = authority.tryBegin(snapshot) ?: return
                recoveryGeneration.set(generation)
                enterRecovering(MessageSyncRecoveryReason.Reconnect, wakeConsumer = false)
                performClosedLoopRecovery(
                    MessageSyncRecoveryReason.Reconnect,
                    fetchReason = fetchReason,
                )
            }

            suspend fun handleEvent(event: OpencodeEvent) {
                assertOffMainThread()
                if (!observationStillCurrent()) return
                if (event.type == "sse.parse.failed") {
                    if (lastPublishedFrames[storeSlot] == null) return
                    if (requiresRecovery()) {
                        noteRelevantWhileRecovering()
                        return
                    }
                    authority.invalidate(currentSnapshot())
                    reconcileGap(StoreRefreshReason.STREAM_RECONNECTED)
                    return
                }
                if (event.type == "stream.reconnected") {
                    reconcileGap(StoreRefreshReason.STREAM_RECONNECTED)
                    return
                }
                if (event.type == "server.connected") {
                    reconcileGap(StoreRefreshReason.SERVER_CONNECTED)
                    return
                }

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
                                is ReloadResult.Accepted -> {
                                    val healthy = completeAuthorityWindow(AuthorityWindowOutcome.Accepted)
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
                                is ReloadResult.Discarded -> {
                                    completeAuthorityWindow(AuthorityWindowOutcome.Discarded)
                                    rememberPublishedFrame(storeSlot, result.messages)
                                    emitMessages(result.messages)
                                    performClosedLoopRecovery(MessageSyncRecoveryReason.RacedAuthority)
                                }
                                is ReloadResult.Failure -> {
                                    completeAuthorityWindow(AuthorityWindowOutcome.Failed)
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
                            beginAuthorityWindow()
                            when (val result = fullReloadResult(key, reason = "message.part.removed-rest-seeded")) {
                                is ReloadResult.Accepted -> {
                                    val healthy = completeAuthorityWindow(AuthorityWindowOutcome.Accepted)
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
                                is ReloadResult.Discarded -> {
                                    completeAuthorityWindow(AuthorityWindowOutcome.Discarded)
                                    rememberPublishedFrame(storeSlot, result.messages)
                                    emitMessages(result.messages)
                                    performClosedLoopRecovery(MessageSyncRecoveryReason.RacedAuthority)
                                }
                                is ReloadResult.Failure -> {
                                    completeAuthorityWindow(AuthorityWindowOutcome.Failed)
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
                        // TUI v2 only flips footer idle on execution.* — it does not
                        // REST-reload the transcript. Finalize local streaming parts.
                        engine.finishStreamingText(storeSlot)
                        val recovered = engine.messages(storeSlot)
                        if (markChanged(storeSlot, recovered)) {
                            logI(
                                "observeMessages emit session=${key.sessionId} count=${recovered.size} " +
                                    "source=session.idle",
                            )
                            emitMessages(recovered)
                        }
                        if (!SessionTranscriptAuthority.lastAssistantNeedsAuthority(recovered)) {
                            publishHealth(MessageSyncHealth.Healthy)
                            return
                        }
                        invalidateIfCurrentObligationPaid()
                        reconcileGap("session.idle")
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
                snapshotWatcher =
                    launch {
                        liveConnection.snapshot.collect {
                            if (!observationStillCurrent()) return@collect
                            wakeGapEvaluation(StoreRefreshReason.CONNECTION_SNAPSHOT)
                        }
                    }
                evaluateConsumer =
                    launch {
                        for (reason in evaluateWake) {
                            if (!observationStillCurrent()) break
                            reconcileGap(reason)
                        }
                    }
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
                val begun = authority.captureBegin(currentSnapshot())
                var loaded: List<Message>? = null
                var failure: Throwable? = null
                val elapsedMs =
                    measureTimeMillis {
                        runCatching { fetchMessages(key.sessionId, directory, StoreRefreshReason.INITIAL_SUBSCRIBE) }
                            .onSuccess { loaded = it }
                            .onFailure { failure = it }
                    }
                applyQueuedIncrementalEvents()
                if (requiresRecovery()) {
                    authority.fail(begun)
                    logW(
                        "eventStagingRejectStaleAuthority session=${key.sessionId} " +
                            "highWater=${highWater.get()} ms=$elapsedMs",
                    )
                    performClosedLoopRecovery(MessageSyncRecoveryReason.Overflow)
                } else {
                    val messages = loaded
                    if (messages == null) {
                        authority.fail(begun)
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
                        val local = engine.messages(storeSlot)
                        val acceptRemote =
                            engine.revision(storeSlot) == expectedRevision &&
                                MessageSnapshotAuthority.isAheadOrEqual(remote = messages, local = local)
                        if (acceptRemote) {
                            engine.replaceAllIfRevision(storeSlot, messages, expectedRevision)
                        }
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
                        if (acceptRemote) {
                            authority.acknowledge(begun)
                        } else {
                            authority.fail(begun)
                        }
                        if (authority.needsReconcile(currentSnapshot()) || !acceptRemote) {
                            reconcileGap(StoreRefreshReason.STREAM_RECONNECTED)
                        }
                    }
                }

                for (item in staging) {
                    if (!observationStillCurrent()) break
                    when (item) {
                        is StagedItem.Overflow, is StagedItem.RecoveryRequired -> {
                            if (authority.isInFlight() && requiresRecovery()) continue
                            val reason =
                                (health.get() as? MessageSyncHealth.Recovering)?.reason
                                    ?: MessageSyncRecoveryReason.Overflow
                            performClosedLoopRecovery(reason)
                        }
                        is StagedItem.Evaluate -> {
                            reconcileGap(item.reason)
                        }
                        is StagedItem.Event -> {
                            noteDequeued(item.byteCost)
                            if (requiresRecovery() || pendingRecoveryWhileIdle.getAndSet(false)) {
                                if (authority.isInFlight()) {
                                    if (MessageEventRouter.isIncrementalTranscriptEvent(item.event)) {
                                        handleEvent(item.event)
                                    }
                                } else {
                                    val reason =
                                        (health.get() as? MessageSyncHealth.Recovering)?.reason
                                            ?: MessageSyncRecoveryReason.Overflow
                                    performClosedLoopRecovery(reason)
                                }
                            } else {
                                handleEvent(item.event)
                            }
                        }
                    }
                }
            } finally {
                snapshotWatcher?.cancel()
                evaluateConsumer?.cancel()
                evaluateWake.close()
                producer.cancel()
                worldWatcher.cancel()
                invalidationWatcher.cancel()
                staging.close()
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

        data object RecoveryRequired : StagedItem

        data class Evaluate(
            val reason: String,
        ) : StagedItem
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
                val stalled = streaming && isStreamingStalled(storeSlot)
                if (streaming && !stalled) continue
                // TUI does not poll GET …/message while the event iterator is live.
                // Keep the watchdog for transport drop and stalled streaming only.
                val connected = eventStream.connectionState.value == EventStreamConnectionState.Connected
                if (connected && !stalled) continue
                val expectedRevision = engine.revision(storeSlot)
                val load = runCatching { fetchMessages(key.sessionId, directory, "poll") }
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
        data class Accepted(
            val messages: List<Message>,
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
    ): List<Message> {
        restAttempts += reason
        return listMessages(sessionId, directory)
    }

    /**
     * Pays only the generation this REST began. A newer gap that opened
     * during the GET stays unpaid so [GapAuthority.needsReconcile] can start
     * a sequential follow-up instead of pinning inFlight to the old revision.
     */
    private fun acknowledgeExactGeneration(
        authority: GapAuthority,
        begun: Long,
        snapshot: LiveConnectionSnapshot,
    ): Boolean {
        authority.acknowledge(begun)
        return authority.needsReconcile(snapshot)
    }

    private fun gapAuthority(key: MessageSessionKey): GapAuthority =
        authorities.getOrPut(key) { GapAuthority() }

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
                messages = fetchMessages(key.sessionId, directory, reason)
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
        mergeExisting: Boolean = false,
        beforeCommit: suspend () -> Unit = {},
    ): ReloadResult {
        var messages: List<Message>? = null
        var failure: Throwable? = null
        val storeSlot = key.storeSlot
        val directory = key.directory.ifEmpty { null }
        val expectedRevision = engine.revision(storeSlot)
        val elapsedMs =
            measureTimeMillis {
                runCatching { fetchMessages(key.sessionId, directory, reason) }
                    .onSuccess { messages = it }
                    .onFailure { failure = it }
            }
        beforeCommit()
        val loaded = messages
        if (loaded == null) {
            logW("fullReload failed session=${key.sessionId} reason=$reason ms=$elapsedMs")
            return ReloadResult.Failure(failure ?: IllegalStateException("Message load failed"))
        }
        val local = engine.messages(storeSlot)
        val acceptRemote =
            engine.revision(storeSlot) == expectedRevision &&
                MessageSnapshotAuthority.isAheadOrEqual(remote = loaded, local = local)
        if (acceptRemote) {
            if (mergeExisting) {
                engine.mergeAuthoritativeIfRevision(storeSlot, loaded, expectedRevision)
            } else {
                engine.replaceAllIfRevision(storeSlot, loaded, expectedRevision)
            }
        }
        val merged = engine.messages(storeSlot)
        rememberPublishedFrame(storeSlot, merged)
        logI(
            "fullReload session=${key.sessionId} reason=$reason count=${merged.size} ms=$elapsedMs",
        )
        MessagePipelineProbe.emit(MessagePipelineProbe.Marker.ReducerCommitted)
        return if (acceptRemote) {
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
