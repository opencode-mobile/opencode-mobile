package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionRepository
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
import kotlinx.coroutines.FlowPreview

@OptIn(FlowPreview::class)
@Singleton
class DefaultSessionLiveStore
    @Inject
    constructor(
        private val sessionRepository: SessionRepository,
        private val eventStream: ForegroundEventStream,
        private val liveStoreScope: LiveStoreScope,
    ) : SessionLiveStore {
        private val cache = ConcurrentHashMap<String, SharedFlow<List<Session>>>()

        override fun observeSessions(directory: String): Flow<List<Session>> =
            cache.getOrPut(directory) { buildSharedFlow(directory) }

        private fun buildSharedFlow(directory: String): SharedFlow<List<Session>> =
            buildSessionFlow(directory)
                .shareIn(
                    scope = liveStoreScope.coroutineScope,
                    started = SharingStarted.WhileSubscribed(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS),
                    replay = 1,
                )

        private fun buildSessionFlow(directory: String): Flow<List<Session>> =
            channelFlow {
                suspend fun loadSessions(): List<Session>? =
                    runCatching { sessionRepository.listSessions(directory = directory) }.getOrNull()

                loadSessions()?.let { send(it) }

                merge(
                    eventStream.events.filter { it.type == "stream.reconnected" }.map { },
                    eventStream.events.filter { it.triggersHomeRefresh }.map { },
                ).debounce(LiveStoreRefreshPolicy.SESSION_DEBOUNCE_MS)
                    .collect {
                        loadSessions()?.let { send(it) }
                    }
            }
    }
