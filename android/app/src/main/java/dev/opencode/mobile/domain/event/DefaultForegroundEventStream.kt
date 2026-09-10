package dev.opencode.mobile.domain.event

import dev.opencode.mobile.data.remote.EventsApi
import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.DeviceNetworkPath
import dev.opencode.mobile.domain.connection.DeviceNetworkPathStatus
import dev.opencode.mobile.domain.connection.ReadyDeviceNetworkPath
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import dev.opencode.mobile.domain.message.ConnectionEpoch
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultForegroundEventStream
    @Inject
    constructor(
        private val eventsApi: EventsApi,
        private val eventDomainAdapter: EventDomainAdapter,
        private val diagnosticLogger: DiagnosticLogger,
        private val activeConnectionManager: ActiveConnectionManager,
        private val connectionProfileStore: ConnectionProfileStore,
        private val deviceNetworkPath: DeviceNetworkPath = ReadyDeviceNetworkPath,
    ) : LiveConnection {
        internal constructor(
            eventsApi: EventsApi,
            eventDomainAdapter: EventDomainAdapter,
            diagnosticLogger: DiagnosticLogger,
            activeConnectionManager: ActiveConnectionManager,
            connectionProfileStore: ConnectionProfileStore,
            deviceNetworkPath: DeviceNetworkPath = ReadyDeviceNetworkPath,
            idleTimeoutMs: Long,
            connectionEpoch: ConnectionEpoch = ConnectionEpoch.shared,
        ) : this(
            eventsApi,
            eventDomainAdapter,
            diagnosticLogger,
            activeConnectionManager,
            connectionProfileStore,
            deviceNetworkPath,
        ) {
            this.idleTimeoutMs = idleTimeoutMs
            this.connectionEpoch = connectionEpoch
        }

        private val scope = CoroutineScope(SupervisorJob())
        private var occupancyJob: Job? = null
        private var idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS
        private var connectionEpoch: ConnectionEpoch = ConnectionEpoch.shared
        private val eventDeduper = SseEventDeduper()
        private val leaseLock = Any()
        private val leases = mutableMapOf<String, Lease>()

        /** Invalidates in-flight cancel/error handlers after stop() or a newer start(). */
        private val streamGeneration = AtomicLong(0)
        private var appliedConfig: LiveConnectionConfig? = null
        private var wasFullyConnected = false
        private var occupyingConnected = false
        private var emittedReconnectForCurrentGap = false
        private var emittedConnectedForCurrentGeneration = false
        private var pendingResumeBarrier = false
        private var currentGap: LiveGapRevision? = null
        private val gapClock = AtomicLong(0)
        private var lastAllLive = false

        private val _connectionState = MutableStateFlow(EventStreamConnectionState.Idle)
        override val connectionState: StateFlow<EventStreamConnectionState> = _connectionState.asStateFlow()

        private val _snapshot = MutableStateFlow(LiveConnectionSnapshot())
        override val snapshot: StateFlow<LiveConnectionSnapshot> = _snapshot.asStateFlow()

        private val _events = MutableSharedFlow<OpencodeEvent>(extraBufferCapacity = 4_096)
        override val events: SharedFlow<OpencodeEvent> = _events.asSharedFlow()

        override fun start() {
            val existing = occupancyJob
            if (existing?.isActive == true) {
                scope.launch { applyStartWhileOccupying() }
                return
            }
            occupancyJob =
                scope.launch {
                    beginOccupancy(resolveConfig())
                    awaitCancellation()
                }
        }

        private suspend fun applyStartWhileOccupying() {
            val config = resolveConfig()
            val applied = appliedConfig
            if (applied == null) return
            if (applied.identity == config.identity) {
                if (applied.directories == config.directories) {
                    diagnosticLogger.debug(
                        DiagnosticLogArea.Sse,
                        "foreground SSE start ignored; already running",
                    )
                    return
                }
                reconcileLeases(config)
                return
            }
            retargetIdentity(config, LiveGapReason.ConfigChanged)
        }

        override fun stop() {
            diagnosticLogger.info(DiagnosticLogArea.Sse, "foreground SSE stream stopping")
            if (wasFullyConnected || occupyingConnected) {
                pendingResumeBarrier = true
            }
            val generation = streamGeneration.incrementAndGet()
            occupancyJob?.cancel()
            occupancyJob = null
            cancelAllLeases()
            eventDeduper.clear()
            appliedConfig = null
            wasFullyConnected = false
            occupyingConnected = false
            emittedReconnectForCurrentGap = false
            emittedConnectedForCurrentGeneration = false
            lastAllLive = false
            openGap(LiveGapReason.Stopped)
            setConnectionState(EventStreamConnectionState.Idle, generation)
            publishSnapshot()
        }

        private suspend fun beginOccupancy(config: LiveConnectionConfig) {
            diagnosticLogger.info(DiagnosticLogArea.Sse, "foreground SSE stream starting")
            val generation = beginGeneration(LiveGapReason.OccupancyStart)
            appliedConfig = config
            publishSnapshot()
            scope.launch {
                connectionProfileStore.configurationRevisions().collect {
                    if (occupancyJob?.isActive != true) return@collect
                    onConfigurationRevision()
                }
            }
            scope.launch {
                val resolved = activeConnectionManager.currentProtocolOrProbe()
                if (occupancyJob?.isActive != true) return@launch
                val applied = appliedConfig ?: return@launch
                if (resolved != applied.protocol) {
                    diagnosticLogger.info(
                        DiagnosticLogArea.Sse,
                        "SSE protocol resolved ${applied.protocol.wireValue} -> ${resolved.wireValue}; reconnecting",
                    )
                    retargetIdentity(applied.copy(protocol = resolved), LiveGapReason.ConfigChanged)
                }
            }
            deviceNetworkPath.waitUntilReady()
            if (generation != streamGeneration.get()) return
            setConnectionState(
                if (wasFullyConnected || pendingResumeBarrier) {
                    EventStreamConnectionState.Reconnecting
                } else {
                    EventStreamConnectionState.Connecting
                },
                generation,
            )
            reconcileLeases(config)
        }

        private suspend fun onConfigurationRevision() {
            val config = resolveConfig()
            val applied = appliedConfig ?: return
            if (applied.identity != config.identity) {
                return
            }
            if (applied.directories != config.directories) {
                reconcileLeases(config)
            }
        }

        private fun retargetIdentity(
            config: LiveConnectionConfig,
            reason: LiveGapReason,
        ) {
            val generation = beginGeneration(reason)
            appliedConfig = config
            eventDeduper.clear()
            occupyingConnected = false
            emittedReconnectForCurrentGap = false
            emittedConnectedForCurrentGeneration = false
            cancelAllLeases()
            if (generation != streamGeneration.get()) return
            reconcileLeases(config)
        }

        private fun beginGeneration(reason: LiveGapReason): Long {
            val generation = streamGeneration.incrementAndGet()
            connectionEpoch.bump()
            openGap(reason)
            return generation
        }

        private fun openGap(reason: LiveGapReason) {
            val revision = gapClock.incrementAndGet()
            currentGap = LiveGapRevision(revision, reason)
            emittedReconnectForCurrentGap = false
        }

        private fun reconcileLeases(config: LiveConnectionConfig) {
            val generation = streamGeneration.get()
            appliedConfig = config
            val targets = SseSubscribeTargets.forProtocol(config.protocol, config.directories)
            val required = targets.associateBy { it.key }
            synchronized(leaseLock) {
                val stale = leases.keys.filter { it !in required }
                stale.forEach { key ->
                    leases.remove(key)?.cancel()
                }
                for (target in targets) {
                    if (leases[target.key] == null) {
                        leases[target.key] = startLease(target, generation)
                    }
                }
            }
            refreshOverallState(generation)
            publishSnapshot()
        }

        private fun startLease(
            target: SseSubscribeTargets.Target,
            generation: Long,
        ): Lease {
            val lease = Lease(target)
            lease.loopJob =
                scope.launch {
                    var retryAttempt = 0
                    while (generation == streamGeneration.get() && occupancyJob?.isActive == true) {
                        if (!isRequired(target.key)) return@launch
                        deviceNetworkPath.waitUntilReady()
                        if (generation != streamGeneration.get() || !isRequired(target.key)) return@launch
                        val lastActivityMs = lease.lastActivityMs
                        lastActivityMs.set(System.currentTimeMillis())
                        val streamJob =
                            launch {
                                val path = "/${target.path}"
                                eventsApi.streamEvents(
                                    path = target.path,
                                    directory = target.directory,
                                    onHTTPResponse = { statusCode ->
                                        lastActivityMs.set(System.currentTimeMillis())
                                        diagnosticLogger.info(
                                            DiagnosticLogArea.Sse,
                                            "GET $path opened status=$statusCode protocol=${appliedConfig?.protocol?.wireValue}",
                                        )
                                        markLeaseLive(lease, generation)
                                        retryAttempt = 0
                                    },
                                    onActivity = {
                                        lastActivityMs.set(System.currentTimeMillis())
                                    },
                                ) { dataLine ->
                                    lastActivityMs.set(System.currentTimeMillis())
                                    publishDataLine(dataLine, generation)
                                }
                            }
                        lease.streamJob = streamJob
                        val watchJob =
                            launch {
                                while (generation == streamGeneration.get() && streamJob.isActive) {
                                    val remainingMs =
                                        idleTimeoutMs - (System.currentTimeMillis() - lastActivityMs.get())
                                    if (remainingMs <= 0L) {
                                        diagnosticLogger.warning(
                                            DiagnosticLogArea.Sse,
                                            "foreground SSE idle timeout target=${target.key}; reconnecting",
                                        )
                                        streamJob.cancel()
                                        return@launch
                                    }
                                    delay(remainingMs.coerceAtMost(idleTimeoutMs))
                                }
                            }
                        val pathWatchJob =
                            launch {
                                deviceNetworkPath.status.collect { status ->
                                    if (status == DeviceNetworkPathStatus.Unavailable) {
                                        streamJob.cancel()
                                    }
                                }
                            }
                        try {
                            streamJob.join()
                        } catch (cancelled: CancellationException) {
                            if (generation != streamGeneration.get() || !isRequired(target.key)) {
                                watchJob.cancel()
                                pathWatchJob.cancel()
                                throw cancelled
                            }
                        } catch (throwable: Throwable) {
                            diagnosticLogger.warning(
                                DiagnosticLogArea.Sse,
                                "foreground SSE stream failed target=${target.key}",
                                throwable,
                            )
                        } finally {
                            watchJob.cancel()
                            pathWatchJob.cancel()
                            lease.streamJob = null
                            markLeaseDead(lease, generation)
                        }
                        if (generation != streamGeneration.get() || !isRequired(target.key)) return@launch
                        if (deviceNetworkPath.status.value == DeviceNetworkPathStatus.Unavailable) {
                            continue
                        }
                        retryAttempt += 1
                        val backoffMs = (1_000L * retryAttempt).coerceAtMost(10_000L)
                        diagnosticLogger.debug(
                            DiagnosticLogArea.Sse,
                            "foreground SSE retry target=${target.key} in ${backoffMs}ms",
                        )
                        delay(backoffMs)
                    }
                }
            return lease
        }

        private suspend fun publishDataLine(
            dataLine: String,
            generation: Long,
        ) {
            if (generation != streamGeneration.get()) return
            val parsed = eventDomainAdapter.parseEvents(dataLine)
            if (parsed.isEmpty()) {
                diagnosticLogger.warning(
                    DiagnosticLogArea.Sse,
                    SseDiagnosticLogging.parseFailedMessage(dataLine),
                )
                if (dataLine.isNotBlank()) {
                    openGap(LiveGapReason.ParseFailed)
                    publishSnapshot()
                    emitIfCurrent(OpencodeEvent(type = "sse.parse.failed"), generation)
                }
                return
            }
            for (event in parsed) {
                val bufferLine = SseDiagnosticLogging.bufferMessage(event)
                if (bufferLine != null) {
                    diagnosticLogger.info(DiagnosticLogArea.Sse, bufferLine)
                } else if (event.type != "server.heartbeat") {
                    diagnosticLogger.debug(
                        DiagnosticLogArea.Sse,
                        "SSE event type=${event.type} session=${event.sessionId ?: "-"}",
                    )
                }
                when (event.type) {
                    "server.connected" -> {
                        if (!emittedConnectedForCurrentGeneration) {
                            emittedConnectedForCurrentGeneration = true
                            emitIfCurrent(event, generation)
                        }
                    }
                    "server.heartbeat" -> Unit
                    else -> {
                        if (eventDeduper.accept(event.id)) {
                            emitIfCurrent(event, generation)
                        }
                    }
                }
            }
        }

        private suspend fun emitIfCurrent(
            event: OpencodeEvent,
            generation: Long,
        ) {
            if (generation != streamGeneration.get()) return
            _events.emit(event)
        }

        private fun markLeaseLive(
            lease: Lease,
            generation: Long,
        ) {
            if (generation != streamGeneration.get()) return
            lease.live.set(true)
            refreshOverallState(generation)
        }

        private fun markLeaseDead(
            lease: Lease,
            generation: Long,
        ) {
            if (generation != streamGeneration.get()) return
            lease.live.set(false)
            occupyingConnected = false
            emittedReconnectForCurrentGap = false
            refreshOverallState(generation)
        }

        private fun refreshOverallState(generation: Long) {
            if (generation != streamGeneration.get()) return
            val config = appliedConfig
            val required =
                if (config == null) {
                    emptyList()
                } else {
                    SseSubscribeTargets.forProtocol(config.protocol, config.directories).map { it.key }
                }
            val allLive =
                required.isNotEmpty() &&
                    required.all { key ->
                        synchronized(leaseLock) { leases[key]?.live?.get() == true }
                    }
            if (lastAllLive && !allLive) {
                val pathLost = deviceNetworkPath.status.value == DeviceNetworkPathStatus.Unavailable
                openGap(if (pathLost) LiveGapReason.PathLost else LiveGapReason.LeaseLost)
            }
            lastAllLive = allLive
            if (allLive) {
                val shouldPublishReconnect =
                    (wasFullyConnected || pendingResumeBarrier) && !emittedReconnectForCurrentGap
                if (_connectionState.value != EventStreamConnectionState.Connected) {
                    setConnectionState(EventStreamConnectionState.Connected, generation)
                }
                occupyingConnected = true
                wasFullyConnected = true
                if (shouldPublishReconnect) {
                    diagnosticLogger.info(DiagnosticLogArea.Sse, "foreground SSE stream reconnected")
                    emittedReconnectForCurrentGap = true
                    pendingResumeBarrier = false
                    scope.launch {
                        emitIfCurrent(OpencodeEvent(type = "stream.reconnected"), generation)
                    }
                }
            } else {
                val next =
                    if (wasFullyConnected) {
                        EventStreamConnectionState.Reconnecting
                    } else {
                        EventStreamConnectionState.Connecting
                    }
                setConnectionState(next, generation)
            }
            publishSnapshot()
        }

        private fun setConnectionState(
            state: EventStreamConnectionState,
            generation: Long? = null,
        ) {
            if (generation != null && generation != streamGeneration.get()) return
            if (_connectionState.value != state) {
                diagnosticLogger.debug(
                    DiagnosticLogArea.Sse,
                    "foreground SSE state ${_connectionState.value} -> $state",
                )
            }
            _connectionState.value = state
        }

        private fun publishSnapshot() {
            val config = appliedConfig
            val required =
                config?.let { SseSubscribeTargets.forProtocol(it.protocol, it.directories).map { target -> target.key } }
                    ?: emptyList()
            val live =
                synchronized(leaseLock) {
                    leases.filterValues { it.live.get() }.keys.toList()
                }
            _snapshot.value =
                LiveConnectionSnapshot(
                    config = config,
                    generation = streamGeneration.get(),
                    connectionState = _connectionState.value,
                    gap = currentGap,
                    requiredTargets = required,
                    liveTargets = live,
                )
        }

        private fun isRequired(key: String): Boolean {
            val config = appliedConfig ?: return false
            return SseSubscribeTargets.forProtocol(config.protocol, config.directories).any { it.key == key }
        }

        private fun cancelAllLeases() {
            synchronized(leaseLock) {
                leases.values.forEach { it.cancel() }
                leases.clear()
            }
        }

        private suspend fun resolveConfig(): LiveConnectionConfig {
            val profile = activeConnectionManager.currentProfile()
            val protocol = activeConnectionManager.protocolHint()
            val directories = instanceDirectories()
            val identity =
                if (profile == null) {
                    ""
                } else {
                    "${ConnectionProfileIdentity.normalizeBaseUrl(profile.baseUrl)}|${ConnectionProfileIdentity.normalizeUsername(profile.username)}"
                }
            return LiveConnectionConfig(
                profileId = profile?.id?.ifBlank { identity } ?: identity,
                connectionIdentity = identity,
                protocol = protocol,
                directories = directories,
            )
        }

        private suspend fun instanceDirectories(): List<String> {
            val baseUrl = activeConnectionManager.current()?.baseUrl ?: return emptyList()
            return runCatching { connectionProfileStore.addedWorktrees(baseUrl) }.getOrDefault(emptyList())
        }

        private class Lease(
            val target: SseSubscribeTargets.Target,
        ) {
            val lastActivityMs = AtomicLong(System.currentTimeMillis())
            val live = AtomicBoolean(false)
            var loopJob: Job? = null
            var streamJob: Job? = null

            fun cancel() {
                live.set(false)
                streamJob?.cancel()
                loopJob?.cancel()
                streamJob = null
                loopJob = null
            }
        }

        private companion object {
            const val DEFAULT_IDLE_TIMEOUT_MS = 45_000L
        }
    }
