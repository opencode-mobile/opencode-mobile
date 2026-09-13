package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.ResumeTiming
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.sync.ProjectionKey
import dev.opencode.mobile.domain.sync.SyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
        private val syncCoordinator: SyncCoordinator,
    ) : SessionLiveStore {
        private val cache = LiveStoreFlowCache<SessionListSnapshot>(liveStoreScope)
        private val activeDirectories = ConcurrentHashMap.newKeySet<String>()

        override fun clearCache() {
            cache.clear()
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
                            val projection = ProjectionKey.SessionList(key)
                            syncCoordinator.invalidate(projection)
                            withTimeoutOrNull(LiveStoreRefreshPolicy.INVALIDATION_ACK_TIMEOUT_MS) {
                                syncCoordinator.ensureFresh(projection)
                            }
                        }
                    }
                    .awaitAll()
            }
        }

        override fun observeSessions(directory: String): Flow<SessionListSnapshot> =
            cache.observe(directory) { scope -> buildSharedFlow(directory, scope) }

        private fun buildSharedFlow(
            directory: String,
            sharingScope: CoroutineScope,
        ): SharedFlow<SessionListSnapshot> =
            buildSessionFlow(directory)
                .shareIn(
                    scope = sharingScope,
                    started = SharingStarted.WhileSubscribed(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS),
                    replay = 1,
                )

        private fun buildSessionFlow(directory: String): Flow<SessionListSnapshot> =
            channelFlow {
                val key = ProjectionKey.SessionList(directory)
                val gate = LiveStoreRefreshGate()
                val registration =
                    syncCoordinator.register(key) {
                        val generation = syncCoordinator.profileGeneration
                        val outcome =
                            gate.refresh(
                                load = {
                                    runCatching { sessionRepository.listSessions(directory = directory) }.getOrNull()
                                },
                                commit = { sessions ->
                                    sessionCache.rememberSessions(directory, sessions)
                                    send(SessionListSnapshot(sessions = sessions, isFromCache = false))
                                    DiagnosticLog.info(
                                        DiagnosticLogArea.Session,
                                        "sessionLiveStore refresh directory=$directory count=${sessions.size}",
                                    )
                                },
                                isCurrent = { syncCoordinator.profileGeneration == generation },
                            )
                        if (outcome == LiveStoreRefreshOutcome.FAILED) {
                            error("session list refresh failed directory=$directory")
                        }
                    }
                syncCoordinator.visible(key, true)
                try {
                    activeDirectories.add(directory)
                    sessionCache.seedSessions(directory)?.let { seeded ->
                        ResumeTiming.markCachePainted(System.currentTimeMillis())
                        send(SessionListSnapshot(sessions = seeded, isFromCache = true))
                    }
                    syncCoordinator.ensureFresh(key)
                    eventStream.events
                        .filter { it.triggersHomeRefresh }
                        .map { it.type }
                        .debounce(LiveStoreRefreshPolicy.SESSION_DEBOUNCE_MS)
                        .collect { reason ->
                            DiagnosticLog.info(
                                DiagnosticLogArea.Session,
                                "sessionLiveStore mutation directory=$directory reason=$reason",
                            )
                            syncCoordinator.invalidate(key)
                            launch { syncCoordinator.ensureFresh(key) }
                        }
                } finally {
                    syncCoordinator.visible(key, false)
                    syncCoordinator.unregister(registration)
                    activeDirectories.remove(directory)
                }
            }
    }
