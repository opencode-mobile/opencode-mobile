package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.session.PermissionRequest
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
class DefaultPermissionLiveStore
    @Inject
    constructor(
        private val sessionRepository: SessionRepository,
        private val eventStream: ForegroundEventStream,
        private val liveStoreScope: LiveStoreScope,
        private val diagnosticLogger: DiagnosticLogger,
    ) : PermissionLiveStore {
        private val cache = ConcurrentHashMap<String, SharedFlow<List<PermissionRequest>>>()

        override fun observePermissions(directory: String): Flow<List<PermissionRequest>> =
            cache.getOrPut(directory) {
                channelFlow {
                    suspend fun refresh(trigger: String) {
                        runCatching { sessionRepository.listPendingPermissions(directory) }
                            .onSuccess { requests ->
                                diagnosticLogger.info(
                                    DiagnosticLogArea.Permission,
                                    "permission refresh trigger=$trigger count=${requests.size}",
                                )
                                send(requests)
                            }
                            .onFailure {
                                diagnosticLogger.warning(
                                    DiagnosticLogArea.Permission,
                                    "permission refresh failed trigger=$trigger",
                                    it,
                                )
                            }
                    }
                    refresh("initial")
                    eventStream.events
                        .filter {
                            it.type == "permission.asked" ||
                                it.type == "permission.replied" ||
                                it.type == "stream.reconnected" ||
                                it.type == "server.connected"
                        }
                        .collect { refresh(it.type) }
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
