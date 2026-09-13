package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
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
        ) : this(
            sessionRepository = sessionRepository,
            eventStream = eventStream,
            liveStoreScope = liveStoreScope,
            eventDomainAdapter = eventDomainAdapter,
            worldSignals = worldSignals,
            syncCoordinator = syncCoordinator,
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
                    }
                    .awaitAll()
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
                var reconcileJob: Job? = null
                val key = ProjectionKey.SessionStatuses(directory)
                val gate = LiveStoreRefreshGate()

                suspend fun emitSnapshot() {
                    val next = composeSnapshot(directory, server, seeded)
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
                            existing = reconcileJob,
                        )
                }

                suspend fun commitLoaded(loaded: List<SessionStatus>) {
                    val now = nowMs()
                    for (status in loaded) {
                        dropOptimistic(directory, status.sessionId)
                    }
                    val nextServer = mutableMapOf<String, SessionActivityEntry>()
                    for (status in loaded) {
                        val activity = status.toSessionActivity()
                        if (activity != SessionActivity.Idle) {
                            nextServer[status.sessionId] =
                                SessionActivityEntry(
                                    activity = activity,
                                    source = SessionActivitySource.Server,
                                    observedAtMs = now,
                                )
                        }
                    }
                    server = nextServer
                    seeded = true
                    DiagnosticLog.info(
                        DiagnosticLogArea.Session,
                        "statusLiveStore refresh directory=$directory",
                    )
                    emitSnapshot()
                }

                val registration =
                    syncCoordinator.register(key) {
                        val outcome =
                            gate.refresh(
                                load = {
                                    runCatching {
                                        sessionRepository.getAllSessionStatuses(directory = directory)
                                    }.getOrNull()
                                },
                                commit = { loaded -> commitLoaded(loaded) },
                            )
                        if (outcome == LiveStoreRefreshOutcome.FAILED) {
                            error("status refresh failed directory=$directory")
                        }
                        if (outcome == LiveStoreRefreshOutcome.DISCARDED) {
                            syncCoordinator.invalidate(key)
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
                    syncCoordinator.ensureFresh(key)
                    try {
                        eventStream.events.collect { event ->
                            when {
                                event.triggersStatusRefresh -> {
                                    applyStatusEvent(directory, server, event)?.let {
                                        server = it
                                        gate.bump()
                                    }
                                    emitSnapshot()
                                }
                                event.type == "message.updated" -> {
                                    if (applyAssistantCompletedIdle(directory, server, event)) {
                                        emitSnapshot()
                                    }
                                }
                            }
                        }
                    } finally {
                        reconcileJob?.cancel()
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
        ): SessionActivitySnapshot {
            val now = nowMs()
            val pending = optimistic[directory]?.toMap().orEmpty()
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
            return SessionActivitySnapshot(entries = entries, seeded = seeded)
        }

        private fun CoroutineScope.syncRunningReconcile(
            directory: String,
            key: ProjectionKey,
            server: () -> Map<String, SessionActivityEntry>,
            seeded: () -> Boolean,
            existing: Job?,
        ): Job? {
            val needed = composeSnapshot(directory, server(), seeded()).entries.values.any { it.activity.isBusy() }
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
                    val live = composeSnapshot(directory, server(), seeded())
                    if (!live.entries.values.any { it.activity.isBusy() }) return@launch
                    if (worldSignals.occupancy.value != StreamOccupancy.Active) continue
                    if (!canCatchUp(worldSignals.occupancy.value, liveConnection.snapshot.value)) continue
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
