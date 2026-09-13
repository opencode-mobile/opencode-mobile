package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.event.WorldSignal
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSessionStatusLiveStore
    @Inject
    constructor(
        private val sessionRepository: SessionRepository,
        private val eventStream: ForegroundEventStream,
        private val liveStoreScope: LiveStoreScope,
        private val eventDomainAdapter: EventDomainAdapter,
        private val worldSignals: WorldSignals,
        private val syncCoordinator: SyncCoordinator,
    ) : SessionStatusLiveStore {
        private val cache = LiveStoreFlowCache<Map<String, SessionStatus>>(liveStoreScope)
        private val activeDirectories = ConcurrentHashMap.newKeySet<String>()
        private val pendingBusy = ConcurrentHashMap<String, MutableSet<String>>()
        private val mutationBus = MutableSharedFlow<StatusMutation>(extraBufferCapacity = 32)

        override fun clearCache() {
            cache.clear()
            pendingBusy.clear()
        }

        override fun markBusy(
            directory: String,
            sessionId: String,
        ) {
            pendingIds(directory).add(sessionId)
            mutationBus.tryEmit(StatusMutation.MarkBusy(directory, sessionId))
        }

        override fun clearLocalBusy(
            directory: String,
            sessionId: String,
        ) {
            pendingBusy[directory]?.remove(sessionId)
            mutationBus.tryEmit(StatusMutation.ClearLocalBusy(directory, sessionId))
        }

        override fun markIdle(
            directory: String,
            sessionId: String,
        ) {
            pendingBusy[directory]?.remove(sessionId)
            mutationBus.tryEmit(StatusMutation.MarkIdle(directory, sessionId))
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

        override fun observeStatuses(directory: String): Flow<Map<String, SessionStatus>> =
            cache.observe(directory) { scope -> buildSharedFlow(directory, scope) }

        internal fun activeUpstreamCount(): Int = cache.activeEntryCount()

        private fun buildSharedFlow(
            directory: String,
            sharingScope: CoroutineScope,
        ): SharedFlow<Map<String, SessionStatus>> =
            buildStatusFlow(directory)
                .shareIn(
                    scope = sharingScope,
                    started = SharingStarted.WhileSubscribed(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS),
                    replay = 1,
                )

        private fun buildStatusFlow(directory: String): Flow<Map<String, SessionStatus>> =
            channelFlow {
                var server = emptyMap<String, SessionStatus>()
                var current = emptyMap<String, SessionStatus>()
                var snapshotRefresh: Job? = null
                val key = ProjectionKey.SessionStatuses(directory)
                val gate = LiveStoreRefreshGate()
                var dropUnconfirmedPendingAfterBackgroundCatchUp = false
                suspend fun emitOverlay(base: Map<String, SessionStatus>) {
                    val next = overlayPending(directory, base, confirmFrom = emptyMap())
                    if (next != current) {
                        current = next
                        send(current)
                    }
                }

                suspend fun commitLoaded(
                    loaded: Map<String, SessionStatus>,
                    emptyActiveGuard: Boolean = false,
                    dropUnconfirmedPending: Boolean = false,
                ) {
                    if (emptyActiveGuard && loaded.isEmpty() && server.isNotEmpty()) {
                        emitOverlay(server)
                        return
                    }
                    server = loaded
                    current =
                        overlayPending(
                            directory,
                            loaded,
                            dropUnconfirmedPending = dropUnconfirmedPending,
                        )
                    if (dropUnconfirmedPending) {
                        dropUnconfirmedPendingAfterBackgroundCatchUp = false
                    }
                    send(current)
                }

                val registration =
                    syncCoordinator.register(key) {
                        val outcome =
                            gate.refresh(
                                load = {
                                    runCatching {
                                        sessionRepository
                                            .getAllSessionStatuses(directory = directory)
                                            .associateBy(SessionStatus::sessionId)
                                    }.getOrNull()
                                },
                                commit = { loaded ->
                                    DiagnosticLog.info(
                                        DiagnosticLogArea.Session,
                                        "statusLiveStore refresh directory=$directory",
                                    )
                                    commitLoaded(
                                        loaded,
                                        emptyActiveGuard = false,
                                        dropUnconfirmedPending = dropUnconfirmedPendingAfterBackgroundCatchUp,
                                    )
                                },
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
                            .collect { mutation ->
                                when (mutation) {
                                    is StatusMutation.MarkBusy -> {
                                        if (pendingBusy[directory]?.contains(mutation.sessionId) != true) {
                                            return@collect
                                        }
                                        emitOverlay(server)
                                    }
                                    is StatusMutation.ClearLocalBusy -> emitOverlay(server)
                                    is StatusMutation.MarkIdle -> {
                                        server = server - mutation.sessionId
                                        gate.bump()
                                        emitOverlay(server)
                                    }
                                }
                            }
                    }
                    yield()
                    activeDirectories.add(directory)
                    launch {
                        worldSignals.signals.collect { signal ->
                            if (signal == WorldSignal.BackgroundStop) {
                                dropUnconfirmedPendingAfterBackgroundCatchUp = true
                            }
                        }
                    }
                    syncCoordinator.ensureFresh(key)
                    try {
                        eventStream.events.collect { event ->
                            when {
                                event.type == "session.active.snapshot" -> {
                                    snapshotRefresh?.cancel()
                                    snapshotRefresh =
                                        launch {
                                            delay(LiveStoreRefreshPolicy.STATUS_DEBOUNCE_MS)
                                            val outcome =
                                                gate.refresh(
                                                    load = {
                                                        runCatching {
                                                            sessionRepository
                                                                .getAllSessionStatuses(directory = directory)
                                                                .associateBy(SessionStatus::sessionId)
                                                        }.getOrNull()
                                                    },
                                                    commit = { loaded ->
                                                        commitLoaded(
                                                            loaded,
                                                            emptyActiveGuard = true,
                                                            dropUnconfirmedPending =
                                                                dropUnconfirmedPendingAfterBackgroundCatchUp,
                                                        )
                                                    },
                                                )
                                            if (outcome == LiveStoreRefreshOutcome.COMMITTED) {
                                                DiagnosticLog.info(
                                                    DiagnosticLogArea.Session,
                                                    "statusLiveStore snapshot directory=$directory",
                                                )
                                            }
                                        }
                                }
                                event.triggersStatusRefresh -> {
                                    applyStatusEvent(directory, server, event)?.let {
                                        server = it
                                        gate.bump()
                                    }
                                    emitOverlay(server)
                                }
                            }
                        }
                    } finally {
                        snapshotRefresh?.cancel()
                    }
                } finally {
                    syncCoordinator.visible(key, false)
                    syncCoordinator.unregister(registration)
                    activeDirectories.remove(directory)
                }
            }

        private fun applyStatusEvent(
            directory: String,
            current: Map<String, SessionStatus>,
            event: OpencodeEvent,
        ): Map<String, SessionStatus>? {
            return when (event.type) {
                "session.status" -> {
                    val status = eventDomainAdapter.sessionStatusFromEvent(event) ?: return null
                    if (status.raw["type"] == "idle") {
                        pendingBusy[directory]?.remove(status.sessionId)
                        if (status.sessionId !in current) return null
                        current - status.sessionId
                    } else {
                        pendingBusy[directory]?.remove(status.sessionId)
                        current + (status.sessionId to status)
                    }
                }
                "session.idle" -> {
                    val sessionId = event.sessionId ?: return null
                    pendingBusy[directory]?.remove(sessionId)
                    if (sessionId !in current) return null
                    current - sessionId
                }
                else -> null
            }
        }

        private fun pendingIds(directory: String): MutableSet<String> =
            pendingBusy.getOrPut(directory) { ConcurrentHashMap.newKeySet() }

        private fun overlayPending(
            directory: String,
            base: Map<String, SessionStatus>,
            confirmFrom: Map<String, SessionStatus> = base,
            dropUnconfirmedPending: Boolean = false,
        ): Map<String, SessionStatus> {
            val pending = pendingBusy[directory]?.toSet().orEmpty()
            if (pending.isEmpty()) return base
            var result = base
            for (sessionId in pending) {
                val restType = confirmFrom[sessionId]?.raw?.get("type")
                if (restType == "busy" || restType == "retry") {
                    pendingBusy[directory]?.remove(sessionId)
                } else if (dropUnconfirmedPending) {
                    pendingBusy[directory]?.remove(sessionId)
                } else {
                    result = result + (sessionId to localBusy(sessionId))
                }
            }
            return result
        }
    }

private fun localBusy(sessionId: String) = SessionStatus(sessionId = sessionId, raw = mapOf("type" to "busy"))

private sealed class StatusMutation {
    abstract val directory: String
    abstract val sessionId: String

    data class MarkBusy(
        override val directory: String,
        override val sessionId: String,
    ) : StatusMutation()

    data class ClearLocalBusy(
        override val directory: String,
        override val sessionId: String,
    ) : StatusMutation()

    data class MarkIdle(
        override val directory: String,
        override val sessionId: String,
    ) : StatusMutation()
}
