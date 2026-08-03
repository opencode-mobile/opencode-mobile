package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.shareIn
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultQuestionLiveStore
@Inject
constructor(
    private val sessionRepository: SessionRepository,
    private val eventStream: ForegroundEventStream,
    private val liveStoreScope: LiveStoreScope,
) : QuestionLiveStore {
    private val cache = ConcurrentHashMap<String, SharedFlow<List<QuestionRequest>>>()

    override fun observeQuestions(directory: String): Flow<List<QuestionRequest>> =
        cache.getOrPut(directory) {
            channelFlow {
                suspend fun refresh() {
                    runCatching { sessionRepository.listPendingQuestions(directory) }.onSuccess { send(it) }
                }
                refresh()
                eventStream.events
                    .filter {
                        it.type == "question.asked" ||
                            it.type == "question.replied" ||
                            it.type == "question.rejected" ||
                            it.type == "stream.reconnected" ||
                            it.type == "server.connected"
                    }.collect { refresh() }
            }.shareIn(
                liveStoreScope.coroutineScope,
                SharingStarted.WhileSubscribed(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS),
                replay = 1,
            )
        }

    override fun clearCache() {
        cache.clear()
    }
}
