package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.SilentWorldSignals
import dev.opencode.mobile.domain.event.StoreRefreshReason
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.event.catchUpReason
import dev.opencode.mobile.domain.event.marksStale
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.sessioncache.SessionCache
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(FlowPreview::class)
@Singleton
class DefaultSessionLiveStore
    @Inject
    constructor(
        private val sessionRepository: SessionRepository,
        private val eventStream: ForegroundEventStream,
        private val liveStoreScope: LiveStoreScope,
        private val sessionCache: SessionCache,
        private val worldSignals: WorldSignals = SilentWorldSignals,
    ) : SessionLiveStore {
        private val cache = ConcurrentHashMap<String, SharedFlow<SessionListSnapshot>>()
        private val invalidationBus = StoreInvalidationBus()

        override fun clearCache() {
            cache.clear()
        }

        override fun invalidate(directory: String?) {
            invalidationBus.invalidate(directory, StoreRefreshReason.PULL_TO_REFRESH)
        }

        override fun observeSessions(directory: String): Flow<SessionListSnapshot> =
            cache.getOrPut(directory) { buildSharedFlow(directory) }

        private fun buildSharedFlow(directory: String): SharedFlow<SessionListSnapshot> =
            buildSessionFlow(directory)
                .shareIn(
                    scope = liveStoreScope.coroutineScope,
                    started = SharingStarted.WhileSubscribed(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS),
                    replay = 1,
                )

        private fun buildSessionFlow(directory: String): Flow<SessionListSnapshot> =
            channelFlow {
                var stale = false

                suspend fun refreshFromNetwork(
                    reason: String,
                    force: Boolean,
                ): Boolean {
                    if (!force && !stale) {
                        DiagnosticLog.info(
                            DiagnosticLogArea.Session,
                            "sessionLiveStore skip directory=$directory reason=$reason",
                        )
                        return false
                    }
                    val sessions =
                        runCatching { sessionRepository.listSessions(directory = directory) }.getOrNull()
                            ?: return false
                    sessionCache.rememberSessions(directory, sessions)
                    send(SessionListSnapshot(sessions = sessions, isFromCache = false))
                    stale = false
                    DiagnosticLog.info(
                        DiagnosticLogArea.Session,
                        "sessionLiveStore refresh directory=$directory reason=$reason count=${sessions.size}",
                    )
                    return true
                }

                sessionCache.seedSessions(directory)?.let { seeded ->
                    send(SessionListSnapshot(sessions = seeded, isFromCache = true))
                }

                refreshFromNetwork(StoreRefreshReason.INITIAL_SUBSCRIBE, force = true)

                launch {
                    worldSignals.signals.collect { signal ->
                        if (signal.marksStale()) {
                            stale = true
                            return@collect
                        }
                        signal.catchUpReason()?.let { reason ->
                            refreshFromNetwork(reason, force = false)
                        }
                    }
                }
                launch {
                    invalidationBus.events
                        .filter { it.key == null || it.key == directory }
                        .collect {
                            stale = true
                            refreshFromNetwork(it.reason, force = true)
                        }
                }

                merge(
                    eventStream.events
                        .filter {
                            it.type == "stream.reconnected" || it.type == "server.connected"
                        }.map { it.type },
                    eventStream.events.filter { it.triggersHomeRefresh }.map { it.type },
                ).debounce(LiveStoreRefreshPolicy.SESSION_DEBOUNCE_MS)
                    .collect { reason ->
                        refreshFromNetwork(reason, force = true)
                    }
            }
    }
