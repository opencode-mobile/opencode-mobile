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
import java.util.concurrent.ConcurrentHashMap
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

        /** Absent directory: refresh on any permission event. Present, even empty: scoped. */
        private val sessionIndex = ConcurrentHashMap<String, Set<String>>()
        private val knownPermissionSessions = ConcurrentHashMap<String, Set<String>>()

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
                                        knownPermissionSessions[directory] =
                                            requests.mapTo(mutableSetOf()) { it.sessionId }
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
                            .collect { event ->
                                if (!shouldRefresh(directory, event.sessionId)) return@collect
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

        override fun noteSessions(
            directory: String,
            sessionIds: Set<String>,
        ) {
            sessionIndex[directory] = sessionIds
        }

        override fun clearSessionIndex() {
            sessionIndex.clear()
            knownPermissionSessions.clear()
        }

        override fun clearCache() {
            cache.clear()
        }

        /**
         * Permission events carry a session id and no directory. Home registers each project's
         * sessions so one event does not refetch every open project. Chat leaves the directory
         * unindexed and still refreshes on every event.
         */
        private fun shouldRefresh(
            directory: String,
            sessionId: String?,
        ): Boolean {
            val indexed = sessionIndex[directory] ?: return true
            if (sessionId == null) return true
            if (sessionId in indexed) return true
            return sessionId in knownPermissionSessions[directory].orEmpty()
        }
    }
