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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
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
        private val cache = ConcurrentHashMap<String, SharedFlow<Map<String, SessionStatus>>>()
        private val invalidationBus = StoreInvalidationBus()

        override fun clearCache() {
            cache.clear()
        }

        override fun invalidate(directory: String?) {
            invalidationBus.invalidate(directory, StoreRefreshReason.PULL_TO_REFRESH)
        }

        override fun observeStatuses(directory: String): Flow<Map<String, SessionStatus>> =
            cache.getOrPut(directory) { buildSharedFlow(directory) }

        private fun buildSharedFlow(directory: String): SharedFlow<Map<String, SessionStatus>> =
            buildStatusFlow(directory)
                .shareIn(
                    scope = liveStoreScope.coroutineScope,
                    started = SharingStarted.WhileSubscribed(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS),
                    replay = 1,
                )

        private fun buildStatusFlow(directory: String): Flow<Map<String, SessionStatus>> =
            channelFlow {
                var current = emptyMap<String, SessionStatus>()
                var reconnectRefresh: Job? = null
                var stale = false

                suspend fun reloadFromServer(
                    reason: String,
                    force: Boolean,
                ): Map<String, SessionStatus>? {
                    if (!force && !stale) {
                        DiagnosticLog.info(
                            DiagnosticLogArea.Session,
                            "statusLiveStore skip directory=$directory reason=$reason",
                        )
                        return null
                    }
                    val loaded =
                        runCatching {
                            sessionRepository
                                .getAllSessionStatuses(directory = directory)
                                .associateBy(SessionStatus::sessionId)
                        }.getOrNull() ?: return null
                    stale = false
                    DiagnosticLog.info(
                        DiagnosticLogArea.Session,
                        "statusLiveStore refresh directory=$directory reason=$reason",
                    )
                    return loaded
                }

                reloadFromServer(StoreRefreshReason.INITIAL_SUBSCRIBE, force = true)?.let {
                    current = it
                    send(current)
                }

                launch {
                    worldSignals.signals.collect { signal ->
                        if (signal.marksStale()) {
                            stale = true
                            return@collect
                        }
                        val reason = signal.catchUpReason() ?: return@collect
                        reloadFromServer(reason, force = false)?.let { statuses ->
                            current = statuses
                            send(current)
                        }
                    }
                }
                launch {
                    invalidationBus.events
                        .filter { it.key == null || it.key == directory }
                        .collect {
                            stale = true
                            reloadFromServer(it.reason, force = true)?.let { statuses ->
                                current = statuses
                                send(current)
                            }
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
                                        reloadFromServer(event.type, force = true)?.let { statuses ->
                                            current = statuses
                                            send(current)
                                        }
                                    }
                            }
                            event.type == "session.active.snapshot" -> {
                                reconnectRefresh?.cancel()
                                reconnectRefresh =
                                    launch {
                                        delay(LiveStoreRefreshPolicy.STATUS_DEBOUNCE_MS)
                                        reloadFromServer(event.type, force = true)?.let { statuses ->
                                            // Empty active maps are a between-step gap on v2,
                                            // not idle. True idle is session.idle / execution.*.
                                            if (statuses.isEmpty() && current.isNotEmpty()) return@let
                                            current = statuses
                                            send(current)
                                        }
                                    }
                            }
                            event.triggersStatusRefresh -> {
                                val next = applyStatusEvent(current, event) ?: return@collect
                                if (next != current) {
                                    current = next
                                    send(current)
                                }
                            }
                        }
                    }
                } finally {
                    reconnectRefresh?.cancel()
                }
            }

        private fun applyStatusEvent(
            current: Map<String, SessionStatus>,
            event: OpencodeEvent,
        ): Map<String, SessionStatus>? {
            return when (event.type) {
                "session.status" -> {
                    val status = eventDomainAdapter.sessionStatusFromEvent(event) ?: return null
                    if (status.raw["type"] == "idle") {
                        if (status.sessionId !in current) return null
                        current - status.sessionId
                    } else {
                        current + (status.sessionId to status)
                    }
                }
                "session.idle" -> {
                    val sessionId = event.sessionId ?: return null
                    if (sessionId !in current) return null
                    current - sessionId
                }
                else -> null
            }
        }
    }
