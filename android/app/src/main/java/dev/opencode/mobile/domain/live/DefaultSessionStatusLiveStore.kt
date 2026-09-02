package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.event.SilentWorldSignals
import dev.opencode.mobile.domain.event.StoreRefreshReason
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.event.catchUpReason
import dev.opencode.mobile.domain.event.marksStale
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
        private val worldSignals: WorldSignals = SilentWorldSignals,
    ) : SessionStatusLiveStore {
        private val cache = LiveStoreFlowCache<Map<String, SessionStatus>>(liveStoreScope)
        private val invalidationBus = StoreInvalidationBus()
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
                        async { invalidationBus.invalidateAndWait(key, StoreRefreshReason.PULL_TO_REFRESH) }
                    }.awaitAll()
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
                var reconnectRefresh: Job? = null
                var stale = false
                val gate = LiveStoreRefreshGate()
                try {
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
                    ) {
                        // Empty active maps are a between-step gap on v2, not idle.
                        // True idle is session.idle / execution.*. Overlay pending
                        // onto the kept server map; do not skip overlay because
                        // pending is set.
                        if (emptyActiveGuard && loaded.isEmpty() && server.isNotEmpty()) {
                            emitOverlay(server)
                            return
                        }
                        server = loaded
                        current = overlayPending(directory, loaded)
                        send(current)
                    }

                    suspend fun reloadFromServer(
                        reason: String,
                        force: Boolean,
                        emptyActiveGuard: Boolean = false,
                    ): LiveStoreRefreshOutcome {
                        if (!force && !stale) {
                            DiagnosticLog.info(
                                DiagnosticLogArea.Session,
                                "statusLiveStore skip directory=$directory reason=$reason",
                            )
                            return LiveStoreRefreshOutcome.FAILED
                        }
                        val outcome =
                            gate.refresh(
                                load = {
                                    runCatching {
                                        sessionRepository
                                            .getAllSessionStatuses(directory = directory)
                                            .associateBy(SessionStatus::sessionId)
                                    }.getOrNull()?.also {
                                        stale = false
                                        DiagnosticLog.info(
                                            DiagnosticLogArea.Session,
                                            "statusLiveStore refresh directory=$directory reason=$reason",
                                        )
                                    }
                                },
                                commit = { loaded -> commitLoaded(loaded, emptyActiveGuard) },
                            )
                        if (outcome == LiveStoreRefreshOutcome.DISCARDED) {
                            DiagnosticLog.info(
                                DiagnosticLogArea.Session,
                                "statusLiveStore discard directory=$directory reason=$reason " +
                                    "cause=superseded-by-live-patch",
                            )
                        }
                        return outcome
                    }

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
                                    is StatusMutation.ClearLocalBusy -> {
                                        emitOverlay(server)
                                    }
                                    is StatusMutation.MarkIdle -> {
                                        server = server - mutation.sessionId
                                        gate.bump()
                                        emitOverlay(server)
                                    }
                                }
                            }
                    }
                    yield()

                    launch(start = CoroutineStart.UNDISPATCHED) {
                        invalidationBus.events
                            .filter { it.key == null || it.key == directory }
                            .collect { invalidation ->
                                try {
                                    stale = true
                                    reloadFromServer(invalidation.reason, force = true)
                                } finally {
                                    invalidation.ack?.complete(Unit)
                                }
                            }
                    }
                    activeDirectories.add(directory)

                    reloadFromServer(StoreRefreshReason.INITIAL_SUBSCRIBE, force = true)

                    launch {
                        worldSignals.signals.collect { signal ->
                            if (signal.marksStale()) {
                                stale = true
                                return@collect
                            }
                            val reason = signal.catchUpReason() ?: return@collect
                            reloadFromServer(reason, force = false)
                        }
                    }

                    try {
                        eventStream.events.collect { event ->
                            when {
                                event.type == "stream.reconnected" || event.type == "server.connected" -> {
                                    reconnectRefresh?.cancel()
                                    reconnectRefresh =
                                        launch {
                                            delay(LiveStoreRefreshPolicy.STATUS_DEBOUNCE_MS)
                                            reloadFromServer(event.type, force = true)
                                        }
                                }
                                event.type == "session.active.snapshot" -> {
                                    reconnectRefresh?.cancel()
                                    reconnectRefresh =
                                        launch {
                                            delay(LiveStoreRefreshPolicy.STATUS_DEBOUNCE_MS)
                                            reloadFromServer(
                                                event.type,
                                                force = true,
                                                emptyActiveGuard = true,
                                            )
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
                        reconnectRefresh?.cancel()
                    }
                } finally {
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

        private fun pendingIds(directory: String): MutableSet<String> = pendingBusy.getOrPut(directory) { ConcurrentHashMap.newKeySet() }

        private fun overlayPending(
            directory: String,
            base: Map<String, SessionStatus>,
            confirmFrom: Map<String, SessionStatus> = base,
        ): Map<String, SessionStatus> {
            val pending = pendingBusy[directory]?.toSet().orEmpty()
            if (pending.isEmpty()) return base
            var result = base
            for (sessionId in pending) {
                val restType = confirmFrom[sessionId]?.raw?.get("type")
                if (restType == "busy" || restType == "retry") {
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
