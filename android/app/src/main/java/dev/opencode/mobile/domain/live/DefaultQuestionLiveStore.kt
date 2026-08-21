package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.SilentWorldSignals
import dev.opencode.mobile.domain.event.StoreRefreshReason
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.event.catchUpReason
import dev.opencode.mobile.domain.event.marksStale
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.SessionRepository
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
class DefaultQuestionLiveStore
    @Inject
    constructor(
        private val sessionRepository: SessionRepository,
        private val eventStream: ForegroundEventStream,
        private val liveStoreScope: LiveStoreScope,
        private val diagnosticLogger: DiagnosticLogger,
        private val worldSignals: WorldSignals = SilentWorldSignals,
    ) : QuestionLiveStore {
        private val cache = ConcurrentHashMap<String, SharedFlow<List<QuestionRequest>>>()
        private val invalidationBus = StoreInvalidationBus()

        override fun observeQuestions(directory: String): Flow<List<QuestionRequest>> =
            cache.getOrPut(directory) {
                channelFlow {
                    var stale = false
                    suspend fun refresh(
                        reason: String,
                        force: Boolean,
                    ) {
                        if (!force && !stale) {
                            diagnosticLogger.info(
                                DiagnosticLogArea.Question,
                                "question skip reason=$reason",
                            )
                            return
                        }
                        runCatching { sessionRepository.listPendingQuestions(directory) }
                            .onSuccess { requests ->
                                stale = false
                                diagnosticLogger.info(
                                    DiagnosticLogArea.Question,
                                    "question refresh reason=$reason count=${requests.size}",
                                )
                                send(requests)
                            }
                            .onFailure {
                                diagnosticLogger.warning(
                                    DiagnosticLogArea.Question,
                                    "question refresh failed reason=$reason",
                                    it,
                                )
                            }
                    }
                    refresh(StoreRefreshReason.INITIAL_SUBSCRIBE, force = true)
                    launch {
                        worldSignals.signals.collect { signal ->
                            if (signal.marksStale()) {
                                stale = true
                                return@collect
                            }
                            signal.catchUpReason()?.let { refresh(it, force = false) }
                        }
                    }
                    launch {
                        invalidationBus.events
                            .filter { it.key == null || it.key == directory }
                            .collect {
                                stale = true
                                refresh(it.reason, force = true)
                            }
                    }
                    eventStream.events
                        .filter {
                            it.type == "question.asked" ||
                                it.type == "question.replied" ||
                                it.type == "question.rejected" ||
                                it.type == "stream.reconnected" ||
                                it.type == "server.connected"
                        }.collect { refresh(it.type, force = true) }
                }.shareIn(
                    liveStoreScope.coroutineScope,
                    SharingStarted.WhileSubscribed(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS),
                    replay = 1,
                )
            }

        override fun invalidate(directory: String?) {
            invalidationBus.invalidate(directory, StoreRefreshReason.PULL_TO_REFRESH)
        }

        override fun clearCache() {
            cache.clear()
        }
    }
