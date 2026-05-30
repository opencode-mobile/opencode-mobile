package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
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
class DefaultSessionStatusLiveStore
    @Inject
    constructor(
        private val sessionRepository: SessionRepository,
        private val eventStream: ForegroundEventStream,
        private val liveStoreScope: LiveStoreScope,
    ) : SessionStatusLiveStore {
        private val cache = ConcurrentHashMap<String, SharedFlow<Map<String, SessionStatus>>>()

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
                suspend fun loadStatuses(): Map<String, SessionStatus> =
                    runCatching {
                        sessionRepository
                            .getAllSessionStatuses(directory = directory)
                            .associateBy(SessionStatus::sessionId)
                    }.getOrDefault(emptyMap())

                send(loadStatuses())

                merge(
                    eventStream.events.filter { it.type == "stream.reconnected" }.map { },
                    eventStream.events.filter { it.triggersStatusRefresh }.map { },
                ).debounce(LiveStoreRefreshPolicy.STATUS_DEBOUNCE_MS)
                    .collect {
                        send(loadStatuses())
                    }
            }
    }
