package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.GapAuthority
import dev.opencode.mobile.domain.event.SilentWorldSignals
import dev.opencode.mobile.domain.event.StoreRefreshReason
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.event.asLiveConnection
import dev.opencode.mobile.domain.event.catchUpReason
import dev.opencode.mobile.domain.event.marksStale
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.SessionRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
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
        private val cache = LiveStoreFlowCache<List<QuestionRequest>>(liveStoreScope)
        private val invalidationBus = StoreInvalidationBus()

        override fun observeQuestions(directory: String): Flow<List<QuestionRequest>> =
            cache.observe(directory) { sharingScope ->
                channelFlow {
                    val liveConnection = eventStream.asLiveConnection()
                    val authority = GapAuthority()
                    var reconnectRefresh: Job? = null
                    var lastEmitted: List<QuestionRequest>? = null
                    val gate = LiveStoreRefreshGate()

                    suspend fun refresh(
                        reason: String,
                        force: Boolean,
                    ) {
                        val snapshot = liveConnection.snapshot.value
                        val generation =
                            if (force) {
                                null
                            } else {
                                authority.tryBegin(snapshot) ?: run {
                                    diagnosticLogger.info(
                                        DiagnosticLogArea.Question,
                                        "question skip reason=$reason",
                                    )
                                    return
                                }
                            }
                        var failure: Throwable? = null
                        val outcome =
                            gate.refresh(
                                load = {
                                    runCatching { sessionRepository.listPendingQuestions(directory) }
                                        .onFailure { failure = it }
                                        .getOrNull()
                                },
                                commit = { requests ->
                                    diagnosticLogger.info(
                                        DiagnosticLogArea.Question,
                                        "question refresh reason=$reason count=${requests.size}",
                                    )
                                    if (requests == lastEmitted) {
                                        diagnosticLogger.info(
                                            DiagnosticLogArea.Question,
                                            "question emit skipped reason=$reason cause=unchanged",
                                        )
                                    } else {
                                        lastEmitted = requests
                                        send(requests)
                                    }
                                },
                            )
                        if (generation != null) {
                            when (outcome) {
                                LiveStoreRefreshOutcome.COMMITTED -> authority.acknowledge(generation)
                                LiveStoreRefreshOutcome.FAILED,
                                LiveStoreRefreshOutcome.DISCARDED,
                                -> authority.fail(generation)
                            }
                        }
                        failure?.let {
                            diagnosticLogger.warning(
                                DiagnosticLogArea.Question,
                                "question refresh failed reason=$reason",
                                it,
                            )
                        }
                    }
                    refresh(StoreRefreshReason.INITIAL_SUBSCRIBE, force = true)
                    authority.acknowledgeCurrent(liveConnection.snapshot.value)
                    launch {
                        worldSignals.signals.collect { signal ->
                            if (signal.marksStale()) {
                                authority.invalidate(liveConnection.snapshot.value)
                                return@collect
                            }
                            signal.catchUpReason()?.let { refresh(it, force = false) }
                        }
                    }
                    launch {
                        liveConnection.snapshot.collect {
                            refresh(StoreRefreshReason.STREAM_RECONNECTED, force = false)
                        }
                    }
                    launch {
                        invalidationBus.events
                            .filter { it.key == null || it.key == directory }
                            .collect {
                                authority.invalidate(liveConnection.snapshot.value)
                                refresh(it.reason, force = false)
                            }
                    }
                    try {
                        eventStream.events
                            .filter {
                                it.type == "question.asked" ||
                                    it.type == "question.replied" ||
                                    it.type == "question.rejected" ||
                                    it.type == "stream.reconnected" ||
                                    it.type == "server.connected"
                            }.collect { event ->
                                if (event.type == "stream.reconnected" || event.type == "server.connected") {
                                    reconnectRefresh?.cancel()
                                    reconnectRefresh =
                                        launch {
                                            delay(LiveStoreRefreshPolicy.STATUS_DEBOUNCE_MS)
                                            refresh(event.type, force = false)
                                        }
                                } else {
                                    refresh(event.type, force = true)
                                }
                            }
                    } finally {
                        reconnectRefresh?.cancel()
                    }
                }.shareIn(
                    sharingScope,
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
