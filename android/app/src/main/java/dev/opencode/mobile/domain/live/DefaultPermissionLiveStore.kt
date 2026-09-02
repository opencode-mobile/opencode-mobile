package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.SilentWorldSignals
import dev.opencode.mobile.domain.event.StoreRefreshReason
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.event.catchUpReason
import dev.opencode.mobile.domain.event.marksStale
import dev.opencode.mobile.domain.session.PermissionRequest
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
class DefaultPermissionLiveStore
    @Inject
    constructor(
        private val sessionRepository: SessionRepository,
        private val eventStream: ForegroundEventStream,
        private val liveStoreScope: LiveStoreScope,
        private val diagnosticLogger: DiagnosticLogger,
        private val worldSignals: WorldSignals = SilentWorldSignals,
    ) : PermissionLiveStore {
        private val cache = LiveStoreFlowCache<List<PermissionRequest>>(liveStoreScope)
        private val invalidationBus = StoreInvalidationBus()

        override fun observePermissions(directory: String): Flow<List<PermissionRequest>> =
            cache.observe(directory) { sharingScope ->
                channelFlow {
                    var stale = false
                    var reconnectRefresh: Job? = null
                    // Last list actually sent downstream, per observation (reset naturally
                    // when the observation restarts). A refresh that reloads the same list
                    // still commits — the gate revision still bumps — but does not re-emit.
                    //
                    // This relies on `PermissionRequest` value equality (it is a Kotlin
                    // `data class`). A field deliberately excluded from equality would make
                    // updates to that field vanish silently here.
                    var lastEmitted: List<PermissionRequest>? = null
                    // Single-flight gate for this observation. `permission.asked` and
                    // `permission.replied` fire back to back, so two GETs overlap and their
                    // responses can land in the wrong order — the older, emptier list
                    // last. That strands a live prompt: the sheet never appears and the
                    // agent stays blocked server-side with no further event to self-heal
                    // it. Serialising the GETs keeps the newest response last.
                    val gate = LiveStoreRefreshGate()

                    suspend fun refresh(
                        reason: String,
                        force: Boolean,
                    ) {
                        if (!force && !stale) {
                            diagnosticLogger.info(
                                DiagnosticLogArea.Permission,
                                "permission skip reason=$reason",
                            )
                            return
                        }
                        var failure: Throwable? = null
                        gate.refresh(
                            load = {
                                runCatching { sessionRepository.listPendingPermissions(directory) }
                                    .onFailure { failure = it }
                                    .getOrNull()
                            },
                            commit = { requests ->
                                stale = false
                                diagnosticLogger.info(
                                    DiagnosticLogArea.Permission,
                                    "permission refresh reason=$reason count=${requests.size}",
                                )
                                if (requests == lastEmitted) {
                                    // Safe because the flow is shared with `replay = 1`: a
                                    // late subscriber already gets the equal value.
                                    diagnosticLogger.info(
                                        DiagnosticLogArea.Permission,
                                        "permission emit skipped reason=$reason cause=unchanged",
                                    )
                                } else {
                                    lastEmitted = requests
                                    send(requests)
                                }
                            },
                        )
                        failure?.let {
                            diagnosticLogger.warning(
                                DiagnosticLogArea.Permission,
                                "permission refresh failed reason=$reason",
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
                    try {
                        eventStream.events
                            .filter {
                                it.type == "permission.asked" ||
                                    it.type == "permission.replied" ||
                                    it.type == "stream.reconnected" ||
                                    it.type == "server.connected"
                            }.collect { event ->
                                if (event.type == "stream.reconnected" || event.type == "server.connected") {
                                    // The reconnect pair lands within milliseconds of each
                                    // other on every foreground, so coalesce it into one
                                    // catch-up GET with the same cancel-and-delay shape as
                                    // DefaultSessionStatusLiveStore.
                                    reconnectRefresh?.cancel()
                                    reconnectRefresh =
                                        launch {
                                            delay(LiveStoreRefreshPolicy.STATUS_DEBOUNCE_MS)
                                            refresh(event.type, force = true)
                                        }
                                } else {
                                    // `permission.asked` / `permission.replied` stay
                                    // immediate: a blocking interactive prompt must not wait
                                    // on a debounce window.
                                    refresh(event.type, force = true)
                                }
                            }
                    } finally {
                        // A torn-down observation must not fire a late fetch.
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
