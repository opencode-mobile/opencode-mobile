package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.sync.ProjectionKey
import dev.opencode.mobile.domain.sync.SyncCoordinator
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
        private val syncCoordinator: SyncCoordinator,
    ) : PermissionLiveStore {
        private val cache = LiveStoreFlowCache<List<PermissionRequest>>(liveStoreScope)

        override fun observePermissions(directory: String): Flow<List<PermissionRequest>> =
            cache.observe(directory) { sharingScope ->
                channelFlow {
                    val key = ProjectionKey.Permissions(directory)
                    var lastEmitted: List<PermissionRequest>? = null
                    val gate = LiveStoreRefreshGate()
                    val registration =
                    syncCoordinator.register(key) {
                        val generation = syncCoordinator.profileGeneration
                        var failure: Throwable? = null
                        val outcome =
                            gate.refresh(
                                load = {
                                    runCatching { sessionRepository.listPendingPermissions(directory) }
                                        .onFailure { failure = it }
                                        .getOrNull()
                                },
                                commit = { requests ->
                                    diagnosticLogger.info(
                                        DiagnosticLogArea.Permission,
                                        "permission refresh count=${requests.size}",
                                    )
                                    if (requests != lastEmitted) {
                                        lastEmitted = requests
                                        send(requests)
                                    }
                                },
                                isCurrent = { syncCoordinator.profileGeneration == generation },
                            )
                            failure?.let {
                                diagnosticLogger.warning(
                                    DiagnosticLogArea.Permission,
                                    "permission refresh failed",
                                    it,
                                )
                            }
                            if (outcome == LiveStoreRefreshOutcome.FAILED) {
                                throw failure ?: IllegalStateException("permission refresh failed")
                            }
                        }
                    syncCoordinator.visible(key, true)
                    try {
                        syncCoordinator.ensureFresh(key)
                        eventStream.events
                            .filter { it.type == "permission.asked" || it.type == "permission.replied" }
                            .collect {
                                launch {
                                    syncCoordinator.invalidate(key)
                                    syncCoordinator.ensureFresh(key)
                                }
                            }
                    } finally {
                        syncCoordinator.visible(key, false)
                        syncCoordinator.unregister(registration)
                    }
                }.shareIn(
                    sharingScope,
                    SharingStarted.WhileSubscribed(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS),
                    replay = 1,
                )
            }

        override fun invalidate(directory: String?) {
            if (directory == null) return
            val key = ProjectionKey.Permissions(directory)
            liveStoreScope.coroutineScope.launch {
                syncCoordinator.invalidate(key)
                syncCoordinator.ensureFresh(key)
            }
        }

        override fun clearCache() {
            cache.clear()
        }
    }
