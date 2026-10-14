package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.connection.ServerCapabilities
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.event.ResumeTiming
import dev.opencode.mobile.domain.event.SessionViewedUpdate
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.sync.ProjectionKey
import dev.opencode.mobile.domain.sync.SyncCoordinator
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(FlowPreview::class)
@Singleton
class DefaultSessionLiveStore
    constructor(
        private val sessionRepository: SessionRepository,
        private val eventStream: ForegroundEventStream,
        private val liveStoreScope: LiveStoreScope,
        private val sessionCache: SessionCache,
        private val syncCoordinator: SyncCoordinator,
        private val eventDomainAdapter: EventDomainAdapter = EventDomainAdapter(Json { ignoreUnknownKeys = true }),
        private val capabilities: () -> ServerCapabilities = { ServerCapabilities.forProtocol(ServerProtocol.V1) },
    ) : SessionLiveStore {
        @Inject
        constructor(
            sessionRepository: SessionRepository,
            eventStream: ForegroundEventStream,
            liveStoreScope: LiveStoreScope,
            sessionCache: SessionCache,
            syncCoordinator: SyncCoordinator,
            eventDomainAdapter: EventDomainAdapter,
            activeConnectionManager: ActiveConnectionManager,
        ) : this(
            sessionRepository = sessionRepository,
            eventStream = eventStream,
            liveStoreScope = liveStoreScope,
            sessionCache = sessionCache,
            syncCoordinator = syncCoordinator,
            eventDomainAdapter = eventDomainAdapter,
            capabilities = { activeConnectionManager.currentCapabilities() },
        )

        private val cache = LiveStoreFlowCache<SessionListSnapshot>(liveStoreScope)
        private val activeDirectories = ConcurrentHashMap.newKeySet<String>()

        /** Highest acknowledged idle watermark per session (v2), so stale REST lists never regress. */
        private val viewedWatermarks = ConcurrentHashMap<String, Long>()
        private val localViewedUpdates = MutableSharedFlow<SessionViewedUpdate>(extraBufferCapacity = 64)
        private val knownSessions = MutableStateFlow<Map<String, Session>>(emptyMap())

        override fun clearCache() {
            cache.clear()
            viewedWatermarks.clear()
            knownSessions.value = emptyMap()
        }

        override fun observeKnownSession(sessionId: String): Flow<Session?> = knownSessions.map { it[sessionId] }.distinctUntilChanged()

        override fun applySessionViewed(
            sessionId: String,
            idleAtEpochMs: Long,
        ) {
            recordViewed(sessionId, idleAtEpochMs)
            localViewedUpdates.tryEmit(SessionViewedUpdate(sessionId = sessionId, idleAtEpochMs = idleAtEpochMs))
        }

        private fun recordViewed(
            sessionId: String,
            idleAtEpochMs: Long,
        ) {
            viewedWatermarks.merge(sessionId, idleAtEpochMs, ::maxOf)
            knownSessions.update { known ->
                val session = known[sessionId] ?: return@update known
                known + (sessionId to session.withViewed(idleAtEpochMs))
            }
        }

        private fun withKnownViewed(sessions: List<Session>): List<Session> {
            if (viewedWatermarks.isEmpty()) return sessions
            return sessions.map { session ->
                viewedWatermarks[session.id]?.let(session::withViewed) ?: session
            }
        }

        private fun remember(sessions: List<Session>) {
            knownSessions.update { known -> known + sessions.associateBy { it.id } }
        }

        /** v2 run end: the server sets `time.idle` / `outcome` without bumping `time.updated`. */
        private fun triggersListRefresh(event: OpencodeEvent): Boolean =
            event.triggersHomeRefresh ||
                (event.type == "session.idle" && capabilities().supportsSessionViewed)

        override suspend fun invalidate(directory: String?) {
            val keys =
                if (directory != null) {
                    listOf(directory).filter { activeDirectories.contains(it) }
                } else {
                    activeDirectories.toList()
                }
            if (keys.isEmpty()) return
            coroutineScope {
                keys
                    .map { key ->
                        async {
                            val projection = ProjectionKey.SessionList(key)
                            syncCoordinator.invalidate(projection)
                            withTimeoutOrNull(LiveStoreRefreshPolicy.INVALIDATION_ACK_TIMEOUT_MS) {
                                syncCoordinator.ensureFresh(projection)
                            }
                        }
                    }.awaitAll()
            }
        }

        override fun observeSessions(directory: String): Flow<SessionListSnapshot> =
            cache.observe(directory) { scope -> buildSharedFlow(directory, scope) }

        private fun buildSharedFlow(
            directory: String,
            sharingScope: CoroutineScope,
        ): SharedFlow<SessionListSnapshot> =
            buildSessionFlow(directory)
                .shareIn(
                    scope = sharingScope,
                    started = SharingStarted.WhileSubscribed(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS),
                    replay = 1,
                )

        private fun buildSessionFlow(directory: String): Flow<SessionListSnapshot> =
            channelFlow {
                val key = ProjectionKey.SessionList(directory)
                val gate = LiveStoreRefreshGate()
                val publishLock = Mutex()
                var latest: SessionListSnapshot? = null

                suspend fun publish(snapshot: SessionListSnapshot) {
                    latest = snapshot
                    remember(snapshot.sessions)
                    send(snapshot)
                }
                val registration =
                    syncCoordinator.register(key) {
                        val generation = syncCoordinator.profileGeneration
                        val outcome =
                            gate.refresh(
                                load = {
                                    runCatching { sessionRepository.listSessions(directory = directory) }.getOrNull()
                                },
                                commit = { fetched ->
                                    val sessions =
                                        publishLock.withLock {
                                            withKnownViewed(fetched).also { sessions ->
                                                publish(SessionListSnapshot(sessions = sessions, isFromCache = false))
                                            }
                                        }
                                    sessionCache.rememberSessions(directory, sessions)
                                    DiagnosticLog.info(
                                        DiagnosticLogArea.Session,
                                        "sessionLiveStore refresh directory=$directory count=${sessions.size}",
                                    )
                                },
                                isCurrent = { syncCoordinator.profileGeneration == generation },
                            )
                        if (outcome == LiveStoreRefreshOutcome.FAILED) {
                            error("session list refresh failed directory=$directory")
                        }
                    }
                syncCoordinator.visible(key, true)
                try {
                    activeDirectories.add(directory)
                    sessionCache.seedSessions(directory)?.let { seeded ->
                        ResumeTiming.markCachePainted(System.currentTimeMillis())
                        publishLock.withLock {
                            publish(SessionListSnapshot(sessions = withKnownViewed(seeded), isFromCache = true))
                        }
                    }
                    launch {
                        merge(
                            eventStream.events.mapNotNull { eventDomainAdapter.sessionViewed(it) },
                            localViewedUpdates,
                        ).collect { update ->
                            recordViewed(update.sessionId, update.idleAtEpochMs)
                            publishLock.withLock {
                                val current = latest ?: return@withLock
                                if (current.sessions.none { it.id == update.sessionId }) return@withLock
                                val patched = withKnownViewed(current.sessions)
                                if (patched == current.sessions) return@withLock
                                publish(current.copy(sessions = patched))
                                sessionCache.rememberSessions(directory, patched)
                            }
                        }
                    }
                    syncCoordinator.ensureFresh(key)
                    eventStream.events
                        .filter(::triggersListRefresh)
                        .map { it.type }
                        .debounce(LiveStoreRefreshPolicy.SESSION_DEBOUNCE_MS)
                        .collect { reason ->
                            DiagnosticLog.info(
                                DiagnosticLogArea.Session,
                                "sessionLiveStore mutation directory=$directory reason=$reason",
                            )
                            syncCoordinator.invalidate(key)
                            launch { syncCoordinator.ensureFresh(key) }
                        }
                } finally {
                    syncCoordinator.visible(key, false)
                    syncCoordinator.unregister(registration)
                    activeDirectories.remove(directory)
                }
            }
    }
