package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
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
    ) : SessionStatusLiveStore {
        private val cache = ConcurrentHashMap<String, SharedFlow<Map<String, SessionStatus>>>()

        override fun clearCache() {
            cache.clear()
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

                suspend fun reloadFromServer(): Map<String, SessionStatus>? =
                    runCatching {
                        sessionRepository
                            .getAllSessionStatuses(directory = directory)
                            .associateBy(SessionStatus::sessionId)
                    }.getOrNull()

                reloadFromServer()?.let {
                    current = it
                    send(current)
                    logStatusEmit(directory, current, reason = "initial")
                }

                try {
                    eventStream.events.collect { event ->
                        when {
                            event.type == "stream.reconnected" || event.type == "server.connected" -> {
                                reconnectRefresh?.cancel()
                                reconnectRefresh =
                                    launch {
                                        delay(LiveStoreRefreshPolicy.STATUS_DEBOUNCE_MS)
                                        reloadFromServer()?.let { statuses ->
                                            current = statuses
                                            send(current)
                                            logStatusEmit(directory, current, reason = event.type)
                                        }
                                    }
                            }
                            event.type == "session.active.snapshot" -> {
                                reconnectRefresh?.cancel()
                                reconnectRefresh =
                                    launch {
                                        delay(LiveStoreRefreshPolicy.STATUS_DEBOUNCE_MS)
                                        reloadFromServer()?.let { statuses ->
                                            // Empty active maps are a between-step gap on v2,
                                            // not idle. True idle is session.idle / execution.*.
                                            if (statuses.isEmpty() && current.isNotEmpty()) return@let
                                            current = statuses
                                            send(current)
                                            logStatusEmit(directory, current, reason = "session.active.snapshot")
                                        }
                                    }
                            }
                            event.triggersStatusRefresh -> {
                                val next = applyStatusEvent(current, event) ?: return@collect
                                if (next != current) {
                                    current = next
                                    send(current)
                                    logStatusEmit(directory, current, reason = event.type)
                                }
                            }
                        }
                    }
                } finally {
                    reconnectRefresh?.cancel()
                }
            }

        private fun logStatusEmit(
            directory: String,
            statuses: Map<String, SessionStatus>,
            reason: String,
        ) {
            val busy =
                statuses.values.count { status ->
                    status.raw["type"] == "busy" || status.raw["type"] == "retry"
                }
            DiagnosticLog.debug(
                DiagnosticLogArea.Session,
                "session-status emit directory=$directory reason=$reason size=${statuses.size} busy=$busy",
            )
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
