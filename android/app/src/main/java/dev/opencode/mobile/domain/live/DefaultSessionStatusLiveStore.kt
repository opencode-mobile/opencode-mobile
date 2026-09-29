package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.event.ResumeTiming
import dev.opencode.mobile.domain.event.StreamOccupancy
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.event.asLiveConnection
import dev.opencode.mobile.domain.event.canCatchUp
import dev.opencode.mobile.domain.session.SessionActivity
import dev.opencode.mobile.domain.session.SessionActivityEntry
import dev.opencode.mobile.domain.session.SessionActivitySnapshot
import dev.opencode.mobile.domain.session.SessionActivitySource
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.toSessionActivity
import dev.opencode.mobile.domain.sessioncache.NoOpSessionCache
import dev.opencode.mobile.domain.sessioncache.SessionActivityCacheEntry
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.sync.ProjectionKey
import dev.opencode.mobile.domain.sync.SyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSessionStatusLiveStore
    constructor(
        private val sessionRepository: SessionRepository,
        private val eventStream: ForegroundEventStream,
        private val liveStoreScope: LiveStoreScope,
        private val eventDomainAdapter: EventDomainAdapter,
        private val worldSignals: WorldSignals,
        private val syncCoordinator: SyncCoordinator,
        private val sessionCache: SessionCache = NoOpSessionCache,
        private val nowMs: () -> Long = { System.currentTimeMillis() },
        private val wait: suspend (Long) -> Unit = { delay(it) },
    ) : SessionStatusLiveStore {
        @Inject
        constructor(
            sessionRepository: SessionRepository,
            eventStream: ForegroundEventStream,
            liveStoreScope: LiveStoreScope,
            eventDomainAdapter: EventDomainAdapter,
            worldSignals: WorldSignals,
            syncCoordinator: SyncCoordinator,
            sessionCache: SessionCache,
        ) : this(
            sessionRepository = sessionRepository,
            eventStream = eventStream,
            liveStoreScope = liveStoreScope,
            eventDomainAdapter = eventDomainAdapter,
            worldSignals = worldSignals,
            syncCoordinator = syncCoordinator,
            sessionCache = sessionCache,
            nowMs = { System.currentTimeMillis() },
            wait = { delay(it) },
        )

        private val cache = LiveStoreFlowCache<SessionActivitySnapshot>(liveStoreScope)
        private val activeDirectories = ConcurrentHashMap.newKeySet<String>()
        private val optimistic = ConcurrentHashMap<String, ConcurrentHashMap<String, OptimisticRecord>>()
        private val expiryJobs = ConcurrentHashMap<OptimisticKey, Job>()
        private val mutationBus = MutableSharedFlow<StatusMutation>(extraBufferCapacity = 32)
        private val optimisticSerial = AtomicLong(0)
        private val liveConnection = eventStream.asLiveConnection()

        override fun clearCache() {
            cache.clear()
            optimistic.clear()
            expiryJobs.values.forEach { it.cancel() }
            expiryJobs.clear()
        }

        override fun setOptimistic(
            directory: String,
            sessionId: String,
            activity: SessionActivity,
            ttlMs: Long,
        ) {
            val observedAt = nowMs()
            val record =
                OptimisticRecord(
                    activity = activity,
                    observedAtMs = observedAt,
                    expiresAtMs = observedAt + ttlMs,
                    serial = optimisticSerial.incrementAndGet(),
                )
            directoryOptimistic(directory)[sessionId] = record
            val key = OptimisticKey(directory, sessionId)
            expiryJobs.remove(key)?.cancel()
            expiryJobs[key] =
                liveStoreScope.coroutineScope.launch {
                    wait(ttlMs)
                    val current = optimistic[directory]?.get(sessionId)
                    if (current?.serial == record.serial) {
                        optimistic[directory]?.remove(sessionId, record)
                        expiryJobs.remove(key)
                        mutationBus.tryEmit(StatusMutation.OptimisticChanged(directory, sessionId))
                    }
                }
            mutationBus.tryEmit(StatusMutation.OptimisticChanged(directory, sessionId))
        }

        override suspend fun invalidate(directory: String?) {
            val keys =
                if (directory != null) {
                    listOf(directory).filter { activeDirectories.contains(it) }
                } else {
                    activeDirectories.toList()
                }
            if (keys.isEmpty()) return
            coroutineScope {
                keys
                    .map { key ->
                        async {
                            val projection = ProjectionKey.SessionStatuses(key)
                            syncCoordinator.invalidate(projection)
                            withTimeoutOrNull(LiveStoreRefreshPolicy.INVALIDATION_ACK_TIMEOUT_MS) {
                                syncCoordinator.ensureFresh(projection)
                            }
                        }
                    }.awaitAll()
            }
        }

        override fun observeActivity(directory: String): Flow<SessionActivitySnapshot> =
            cache.observe(directory) { scope -> buildSharedFlow(directory, scope) }

        internal fun activeUpstreamCount(): Int = cache.activeEntryCount()

        private fun buildSharedFlow(
            directory: String,
            sharingScope: CoroutineScope,
        ): SharedFlow<SessionActivitySnapshot> =
            buildStatusFlow(directory)
                .shareIn(
                    scope = sharingScope,
                    started = SharingStarted.WhileSubscribed(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS),
                    replay = 1,
                )

        private fun buildStatusFlow(directory: String): Flow<SessionActivitySnapshot> =
            channelFlow {
                var server = emptyMap<String, SessionActivityEntry>()
                var current = SessionActivitySnapshot()
                var seeded = false
                var fromCache = false
                var reconcileJob: Job? = null
                var idleConfirmJob: Job? = null
                var reconcileRefreshRequested = false
                val idleUnconfirmed = mutableSetOf<String>()
                val touchedSinceLoadStart = mutableSetOf<String>()
                val key = ProjectionKey.SessionStatuses(directory)
                val gate = LiveStoreRefreshGate()

                fun cancelIdleConfirmIfEmpty() {
                    if (idleUnconfirmed.isEmpty()) {
                        idleConfirmJob?.cancel()
                        idleConfirmJob = null
                    }
                }

                fun markTouched(sessionId: String) {
                    touchedSinceLoadStart.add(sessionId)
                    idleUnconfirmed.remove(sessionId)
                    cancelIdleConfirmIfEmpty()
                }

                fun scheduleIdleConfirmRefresh() {
                    if (idleUnconfirmed.isEmpty()) {
                        idleConfirmJob?.cancel()
                        idleConfirmJob = null
                        return
                    }
                    idleConfirmJob?.cancel()
                    idleConfirmJob =
                        launch {
                            while (isActive) {
                                wait(LiveStoreRefreshPolicy.STATUS_IDLE_CONFIRM_MS)
                                if (idleUnconfirmed.isEmpty()) return@launch
                                if (worldSignals.occupancy.value != StreamOccupancy.Active) continue
                                if (!canCatchUp(worldSignals.occupancy.value, liveConnection.snapshot.value)) {
                                    continue
                                }
                                reconcileRefreshRequested = true
                                syncCoordinator.invalidate(key)
                                syncCoordinator.ensureFresh(key)
                                return@launch
                            }
                        }
                }

                suspend fun emitSnapshot() {
                    val next = composeSnapshot(directory, server, seeded, fromCache)
                    if (next != current) {
                        current = next
                        send(current)
                    }
                    reconcileJob =
                        syncRunningReconcile(
                            directory = directory,
                            key = key,
                            server = { server },
                            seeded = { seeded },
                            fromCache = { fromCache },
                            existing = reconcileJob,
                            markReconcile = { reconcileRefreshRequested = true },
                        )
                }

                suspend fun persistServerActivity() {
                    val entries =
                        server.mapNotNull { (sessionId, entry) ->
                            if (entry.source == SessionActivitySource.Optimistic) return@mapNotNull null
                            SessionActivityCacheEntry(
                                sessionId = sessionId,
                                activity = entry.activity,
                                observedAtMs = entry.observedAtMs,
                            )
                        }
                    sessionCache.rememberActivity(directory, entries)
                }

                suspend fun commitLoaded(
                    loaded: List<SessionStatus>,
                    reconcile: Boolean,
                ) {
                    val now = nowMs()
                    for (status in loaded) {
                        dropOptimistic(directory, status.sessionId)
                    }
                    val loadedActivities =
                        loaded.associate { status ->
                            status.sessionId to status.toSessionActivity()
                        }
                    val nextServer = mutableMapOf<String, SessionActivityEntry>()
                    for ((sessionId, activity) in loadedActivities) {
                        if (activity != SessionActivity.Idle) {
                            nextServer[sessionId] =
                                SessionActivityEntry(
                                    activity = activity,
                                    source = SessionActivitySource.Server,
                                    observedAtMs = now,
                                )
                        }
                    }
                    if (reconcile) {
                        for ((sessionId, entry) in server) {
                            if (entry.source != SessionActivitySource.Server) continue
                            if (!entry.activity.isBusy()) continue
                            if (sessionId in touchedSinceLoadStart) continue
                            val loadedActivity = loadedActivities[sessionId]
                            if (loadedActivity != null && loadedActivity.isBusy()) {
                                idleUnconfirmed.remove(sessionId)
                                continue
                            }
                            if (sessionId !in idleUnconfirmed) {
                                nextServer[sessionId] = entry
                                idleUnconfirmed.add(sessionId)
                                DiagnosticLog.info(
                                    DiagnosticLogArea.Session,
                                    "statusLiveStore idle unconfirmed session=$sessionId directory=$directory",
                                )
                            } else {
                                nextServer.remove(sessionId)
                                idleUnconfirmed.remove(sessionId)
                                DiagnosticLog.info(
                                    DiagnosticLogArea.Session,
                                    "statusLiveStore idle confirmed drop session=$sessionId directory=$directory",
                                )
                            }
                        }
                        for ((sessionId, activity) in loadedActivities) {
                            if (activity.isBusy()) {
                                idleUnconfirmed.remove(sessionId)
                            }
                        }
                    } else {
                        idleUnconfirmed.clear()
                    }
                    for (sessionId in touchedSinceLoadStart) {
                        val currentEntry = server[sessionId]
                        if (currentEntry != null) {
                            nextServer[sessionId] = currentEntry
                        } else {
                            nextServer.remove(sessionId)
                        }
                    }
                    touchedSinceLoadStart.clear()
                    server = nextServer
                    seeded = true
                    fromCache = false
                    DiagnosticLog.info(
                        DiagnosticLogArea.Session,
                        "statusLiveStore refresh directory=$directory",
                    )
                    emitSnapshot()
                    persistServerActivity()
                    scheduleIdleConfirmRefresh()
                }

                val registration =
                    syncCoordinator.register(key) {
                        val generation = syncCoordinator.profileGeneration
                        val reconcile = reconcileRefreshRequested
                        reconcileRefreshRequested = false
                        val outcome =
                            gate.refresh(
                                load = {
                                    touchedSinceLoadStart.clear()
                                    runCatching {
                                        sessionRepository.getAllSessionStatuses(directory = directory)
                                    }.getOrNull()
                                },
                                commit = { loaded -> commitLoaded(loaded, reconcile) },
                                isCurrent = { syncCoordinator.profileGeneration == generation },
                            )
                        if (outcome == LiveStoreRefreshOutcome.FAILED) {
                            error("status refresh failed directory=$directory")
                        }
                        if (outcome == LiveStoreRefreshOutcome.DISCARDED) {
                            DiagnosticLog.info(
                                DiagnosticLogArea.Session,
                                "statusLiveStore discarded directory=$directory",
                            )
                        }
                    }
                syncCoordinator.visible(key, true)
                try {
                    launch {
                        mutationBus
                            .filter { it.directory == directory }
                            .collect { emitSnapshot() }
                    }
                    yield()
                    activeDirectories.add(directory)
                    sessionCache.seedActivity(directory)?.let { cached ->
                        server =
                            cached.associate { entry ->
                                entry.sessionId to
                                    SessionActivityEntry(
                                        activity = entry.activity,
                                        source = SessionActivitySource.Cached,
                                        observedAtMs = entry.observedAtMs,
                                    )
                            }
                        fromCache = true
                        ResumeTiming.markCachePainted(nowMs())
                        emitSnapshot()
                    }
                    val eventsJob =
                        launch {
                            eventStream.events.collect { event ->
                                when {
                                    event.triggersStatusRefresh -> {
                                        applyStatusEvent(directory, server, event)?.let {
                                            server = it
                                            persistServerActivity()
                                        }
                                        event.sessionId?.let { markTouched(it) }
                                        emitSnapshot()
                                    }
                                    event.type == "message.updated" -> {
                                        if (applyAssistantCompletedIdle(directory, server, event)) {
                                            event.sessionId?.let { touchedSinceLoadStart.add(it) }
                                            emitSnapshot()
                                        }
                                    }
                                }
                            }
                        }
                    try {
                        syncCoordinator.ensureFresh(key)
                        eventsJob.join()
                    } finally {
                        eventsJob.cancel()
                        reconcileJob?.cancel()
                        idleConfirmJob?.cancel()
                    }
                } finally {
                    syncCoordinator.visible(key, false)
                    syncCoordinator.unregister(registration)
                    activeDirectories.remove(directory)
                }
            }

        private fun composeSnapshot(
            directory: String,
            server: Map<String, SessionActivityEntry>,
            seeded: Boolean,
            fromCache: Boolean,
        ): SessionActivitySnapshot {
            val now = nowMs()
            val pending = optimistic[directory]?.copyByEntryIteration().orEmpty()
            val entries = server.toMutableMap()
            for ((sessionId, record) in pending) {
                if (now < record.expiresAtMs) {
                    entries[sessionId] =
                        SessionActivityEntry(
                            activity = record.activity,
                            source = SessionActivitySource.Optimistic,
                            observedAtMs = record.observedAtMs,
                        )
                }
            }
            return SessionActivitySnapshot(
                entries = entries,
                seeded = seeded,
                fromCache = fromCache && !seeded,
            )
        }

        private fun CoroutineScope.syncRunningReconcile(
            directory: String,
            key: ProjectionKey,
            server: () -> Map<String, SessionActivityEntry>,
            seeded: () -> Boolean,
            fromCache: () -> Boolean,
            existing: Job?,
            markReconcile: () -> Unit,
        ): Job? {
            val needed =
                seeded() &&
                    composeSnapshot(directory, server(), seeded(), fromCache()).entries.values.any { it.activity.isBusy() }
            if (!needed) {
                existing?.cancel()
                return null
            }
            if (existing?.isActive == true) return existing
            return launch {
                while (isActive) {
                    val started = nowMs()
                    wait(LiveStoreRefreshPolicy.STATUS_RUNNING_RECONCILE_MS)
                    if (nowMs() - started < LiveStoreRefreshPolicy.STATUS_RUNNING_RECONCILE_MS) {
                        return@launch
                    }
                    val live = composeSnapshot(directory, server(), seeded(), fromCache())
                    if (!live.entries.values.any { it.activity.isBusy() }) return@launch
                    if (worldSignals.occupancy.value != StreamOccupancy.Active) continue
                    if (!canCatchUp(worldSignals.occupancy.value, liveConnection.snapshot.value)) continue
                    markReconcile()
                    syncCoordinator.invalidate(key)
                    syncCoordinator.ensureFresh(key)
                }
            }
        }

        private fun applyStatusEvent(
            directory: String,
            current: Map<String, SessionActivityEntry>,
            event: OpencodeEvent,
        ): Map<String, SessionActivityEntry>? {
            val now = nowMs()
            return when (event.type) {
                "session.status" -> {
                    val parsed = eventDomainAdapter.sessionActivityFromEvent(event) ?: return null
                    dropOptimistic(directory, parsed.sessionId)
                    if (parsed.activity == SessionActivity.Idle) {
                        if (parsed.sessionId !in current) return null
                        current - parsed.sessionId
                    } else {
                        current +
                            (
                                parsed.sessionId to
                                    SessionActivityEntry(
                                        activity = parsed.activity,
                                        source = SessionActivitySource.Server,
                                        observedAtMs = now,
                                    )
                            )
                    }
                }
                "session.idle" -> {
                    val sessionId = event.sessionId ?: return null
                    dropOptimistic(directory, sessionId)
                    if (sessionId !in current) return null
                    current - sessionId
                }
                else -> null
            }
        }

        private fun applyAssistantCompletedIdle(
            directory: String,
            server: Map<String, SessionActivityEntry>,
            event: OpencodeEvent,
        ): Boolean {
            val sessionId = event.sessionId ?: return false
            if (!eventDomainAdapter.assistantMessageCompleted(event)) return false
            val overlay = optimistic[directory]?.get(sessionId) ?: return false
            if (nowMs() >= overlay.expiresAtMs) return false
            if (overlay.activity != SessionActivity.Running) return false
            val serverActivity = server[sessionId]?.activity
            if (serverActivity != null && serverActivity.isBusy()) return false
            dropOptimistic(directory, sessionId)
            return true
        }

        private fun dropOptimistic(
            directory: String,
            sessionId: String,
        ) {
            optimistic[directory]?.remove(sessionId)
            expiryJobs.remove(OptimisticKey(directory, sessionId))?.cancel()
        }

        private fun directoryOptimistic(directory: String): ConcurrentHashMap<String, OptimisticRecord> =
            optimistic.getOrPut(directory) { ConcurrentHashMap() }

        private data class OptimisticRecord(
            val activity: SessionActivity,
            val observedAtMs: Long,
            val expiresAtMs: Long,
            val serial: Long,
        )

        private data class OptimisticKey(
            val directory: String,
            val sessionId: String,
        )

        private sealed class StatusMutation {
            abstract val directory: String
            abstract val sessionId: String

            data class OptimisticChanged(
                override val directory: String,
                override val sessionId: String,
            ) : StatusMutation()
        }
    }

/**
 * Copy without [Map.toMap]. Kotlin's one-entry fast path calls `iterator().next()` after
 * reading `size`, so a concurrent removal of the last optimistic row throws
 * [NoSuchElementException].
 */
internal fun <K, V> Map<K, V>.copyByEntryIteration(): Map<K, V> {
    val copy = LinkedHashMap<K, V>()
    for (entry in entries) {
        copy[entry.key] = entry.value
    }
    return copy
}
