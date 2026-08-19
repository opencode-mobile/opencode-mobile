package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.ForegroundEventStream
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
    ) : SessionLiveStore {
        private val cache = ConcurrentHashMap<String, SharedFlow<SessionListSnapshot>>()

        override fun clearCache() {
            cache.clear()
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
                suspend fun refreshFromNetwork(): Boolean {
                    val sessions =
                        runCatching { sessionRepository.listSessions(directory = directory) }.getOrNull()
                            ?: return false
                    sessionCache.rememberSessions(directory, sessions)
                    send(SessionListSnapshot(sessions = sessions, isFromCache = false))
                    DiagnosticLog.debug(
                        DiagnosticLogArea.Session,
                        "session-list refresh directory=$directory count=${sessions.size} fromCache=false",
                    )
                    return true
                }

                sessionCache.seedSessions(directory)?.let { seeded ->
                    send(SessionListSnapshot(sessions = seeded, isFromCache = true))
                    DiagnosticLog.debug(
                        DiagnosticLogArea.Session,
                        "session-list seed directory=$directory count=${seeded.size} fromCache=true",
                    )
                }

                refreshFromNetwork()

                merge(
                    eventStream.events
                        .filter {
                            it.type == "stream.reconnected" || it.type == "server.connected"
                        }.map { },
                    eventStream.events.filter { it.triggersHomeRefresh }.map { },
                ).debounce(LiveStoreRefreshPolicy.SESSION_DEBOUNCE_MS)
                    .collect {
                        refreshFromNetwork()
                    }
            }
    }
