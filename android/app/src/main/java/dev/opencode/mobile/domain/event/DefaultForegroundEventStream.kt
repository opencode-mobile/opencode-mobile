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
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
        private val stateLock = Any()
        private val leases = mutableMapOf<String, Lease>()

        /** Invalidates in-flight cancel/error handlers after stop() or a newer start(). */
        private val streamGeneration = AtomicLong(0)

        /** Invalidates overlapping start() applies after stop() or a new occupancy. */
        private val occupancySerial = AtomicLong(0)
        private var appliedConfig: LiveConnectionConfig? = null
        private val pendingStartConfig = AtomicReference<LiveConnectionConfig?>(null)
        private val inFlightStartApplies = AtomicInteger(0)
        private var wasFullyConnected = false
        private var occupyingConnected = false
        private var emittedReconnectForCurrentGap = false
        private var emittedConnectedForCurrentGeneration = false
        private var pendingResumeBarrier = false
        private var suppressReconnectForRequiredGrowth = false
        private var currentGap: LiveGapRevision? = null
        private val gapClock = AtomicLong(0)
        private var lastAllLive = false
        private var lastRequiredTargets: List<String> = emptyList()
        private var lastLiveTargets: Set<String> = emptySet()
        private var lastDirectories: List<String> = emptyList()
        private val configEpoch = AtomicLong(0)

        private val _connectionState = MutableStateFlow(EventStreamConnectionState.Idle)
        override val connectionState: StateFlow<EventStreamConnectionState> = _connectionState.asStateFlow()

        private val _snapshot = MutableStateFlow(LiveConnectionSnapshot())
        override val snapshot: StateFlow<LiveConnectionSnapshot> = _snapshot.asStateFlow()

        private val _events = MutableSharedFlow<OpencodeEvent>(extraBufferCapacity = 4_096)
        override val events: SharedFlow<OpencodeEvent> = _events.asSharedFlow()

        override fun start() {
            val existing = occupancyJob
            if (existing?.isActive == true) {
                val stuckIdle =
                    _connectionState.value == EventStreamConnectionState.Idle && appliedConfig == null
                if (!stuckIdle) {
                    val serial = occupancySerial.get()
                    inFlightStartApplies.incrementAndGet()
                    CoroutineScope(existing).launch {
                        try {
                            applyStartWhileOccupying()
                        } finally {
                            endInFlightStartApply(serial)
                        }
                    }
                    return
                }
                existing.cancel()
                occupancyJob = null
            }
            diagnosticLogger.info(DiagnosticLogArea.Sse, "foreground SSE stream starting")
            val serial = occupancySerial.incrementAndGet()
            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    val bootstrap = pendingStartConfig.getAndSet(null) ?: bootstrapConfig()
                    if (!isActive) return@launch
                    beginOccupancy(this, bootstrap, serial)
                    awaitCancellation()
                }
            occupancyJob = job
            job.start()
        }

        private suspend fun applyStartWhileOccupying() {
            val occupancy = occupancyJob
            if (occupancy?.isActive != true) return
            val generation = streamGeneration.get()
            val config = resolveConfig()
            if (occupancyJob !== occupancy || occupancyJob?.isActive != true) return
            val applied = appliedConfig
            if (applied == null) {
                if (occupancyJob === occupancy && occupancy.isActive) {
                    pendingStartConfig.set(config)
                }
                return
            }
            if (streamGeneration.get() != generation) {
                if (isOccupying()) {
                    pendingStartConfig.set(config)
                }
                return
            }
            if (applied.identity == config.identity && applied.protocol == config.protocol) {
                if (applied.directories == config.directories) {
                    diagnosticLogger.debug(
                        DiagnosticLogArea.Sse,
                        "foreground SSE start ignored; already running",
                    )
                    return
                }
                if (!stillCurrent(generation)) return
                reconcileLeases(config)
                return
            }
            if (!stillCurrent(generation)) return
            retargetIdentity(config, LiveGapReason.ConfigChanged)
        }

        override fun stop() {
            diagnosticLogger.info(DiagnosticLogArea.Sse, "foreground SSE stream stopping")
            val generation: Long
            val snapshot: LiveConnectionSnapshot
            synchronized(stateLock) {
                if (wasFullyConnected || occupyingConnected) {
                    pendingResumeBarrier = true
                }
                occupancySerial.incrementAndGet()
                inFlightStartApplies.set(0)
                generation = streamGeneration.incrementAndGet()
                occupancyJob?.cancel()
                occupancyJob = null
                pendingStartConfig.set(null)
                cancelAllLeases()
                eventDeduper.clear()
                appliedConfig = null
                wasFullyConnected = false
                occupyingConnected = false
                emittedReconnectForCurrentGap = false
                emittedConnectedForCurrentGeneration = false
                suppressReconnectForRequiredGrowth = false
                lastAllLive = false
                lastRequiredTargets = emptyList()
                lastLiveTargets = emptySet()
                openGapLocked(LiveGapReason.Stopped)
                snapshot = buildSnapshotLocked(EventStreamConnectionState.Idle)
            }
            setConnectionState(EventStreamConnectionState.Idle, generation)
            _snapshot.value = snapshot
        }

        private fun endInFlightStartApply(serial: Long) {
            if (occupancySerial.get() == serial) {
                inFlightStartApplies.updateAndGet { current -> maxOf(0, current - 1) }
            }
        }

        private suspend fun waitForInFlightStartApplies(
            occupancyScope: CoroutineScope,
            serial: Long,
        ) {
            while (inFlightStartApplies.get() > 0) {
                if (!occupancyScope.isActive ||
                    !isOccupying() ||
                    occupancySerial.get() != serial
                ) {
                    return
                }
                yield()
            }
        }

        private suspend fun beginOccupancy(
            occupancyScope: CoroutineScope,
            config: LiveConnectionConfig,
            serial: Long,
        ) {
            if (!isOccupying()) return
            waitForInFlightStartApplies(occupancyScope, serial)
            if (!isOccupying() || !occupancyScope.isActive) return
            val merged = pendingStartConfig.getAndSet(null) ?: config
            val generation = beginGeneration(LiveGapReason.OccupancyStart)
            if (!stillCurrent(generation)) return
            appliedConfig = merged
            publishSnapshot()
            deviceNetworkPath.waitUntilReady()
            if (!stillCurrent(generation)) return
            val pending = pendingStartConfig.getAndSet(null)
            val effective = pending ?: merged
            if (!stillCurrent(generation)) return
            setConnectionState(
                if (wasFullyConnected || pendingResumeBarrier) {
                    EventStreamConnectionState.Reconnecting
                } else {
                    EventStreamConnectionState.Connecting
                },
                generation,
            )
            if (pending != null &&
                (pending.identity != merged.identity || pending.protocol != merged.protocol)
            ) {
                if (!stillCurrent(generation)) return
                retargetIdentity(pending, LiveGapReason.ConfigChanged)
            } else if (stillCurrent(generation)) {
                reconcileLeases(effective)
            }
            if (!stillCurrent(generation)) return
            occupancyScope.launch {
                collectConfigurationRevisions(generation)
            }
            occupancyScope.launch {
                probeProtocolWhileOccupying(generation)
            }
        }

        private suspend fun collectConfigurationRevisions(generation: Long) {
            // Recheck after subscribe so a directory mutation in the
            // resolve→subscribe window is not lost.
            onConfigurationRevision(generation)
            connectionProfileStore.configurationRevisions().collect {
                if (!stillCurrent(generation)) return@collect
                onConfigurationRevision(generation)
            }
        }

        private suspend fun probeProtocolWhileOccupying(generation: Long) {
            val resolved = activeConnectionManager.currentProtocolOrProbe()
            if (!stillCurrent(generation)) return
            val applied = appliedConfig ?: return
            if (resolved != applied.protocol) {
                diagnosticLogger.info(
                    DiagnosticLogArea.Sse,
                    "SSE protocol resolved ${applied.protocol.wireValue} -> ${resolved.wireValue}; reconnecting",
                )
                retargetIdentity(applied.copy(protocol = resolved), LiveGapReason.ConfigChanged)
            }
        }

        private suspend fun onConfigurationRevision(generation: Long) {
            val epoch = configEpoch.get()
            val config = resolveConfig()
            if (!stillCurrent(generation)) return
            if (configEpoch.get() != epoch) return
            applyResolvedConfig(config)
        }

        private fun applyResolvedConfig(config: LiveConnectionConfig) {
            val applied = appliedConfig
            if (applied == null) {
                reconcileLeases(config)
                return
            }
            if (applied.identity != config.identity || applied.protocol != config.protocol) {
                retargetIdentity(config, LiveGapReason.ConfigChanged)
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
            if (!isOccupying()) return
            val generation = beginGeneration(reason)
            if (!stillCurrent(generation)) return
            synchronized(stateLock) {
                if (generation != streamGeneration.get()) return
                appliedConfig = config
                eventDeduper.clear()
                occupyingConnected = false
                emittedReconnectForCurrentGap = false
                emittedConnectedForCurrentGeneration = false
                suppressReconnectForRequiredGrowth = false
                cancelAllLeases()
            }
            if (!stillCurrent(generation)) return
            reconcileLeases(config)
        }

        private fun beginGeneration(reason: LiveGapReason): Long =
            synchronized(stateLock) {
                val generation = streamGeneration.incrementAndGet()
                connectionEpoch.bump()
                openGapLocked(reason)
                generation
            }

        private fun openGap(reason: LiveGapReason) {
            synchronized(stateLock) {
                openGapLocked(reason)
            }
        }

        private fun openGapLocked(reason: LiveGapReason) {
            val revision = gapClock.incrementAndGet()
            currentGap = LiveGapRevision(revision, reason)
            emittedReconnectForCurrentGap = false
        }

        private fun reconcileLeases(config: LiveConnectionConfig) {
            val generation = streamGeneration.get()
            if (!stillCurrent(generation)) return
            appliedConfig = config
            lastDirectories = config.directories
            configEpoch.incrementAndGet()
            val targets = SseSubscribeTargets.forProtocol(config.protocol, config.directories)
            val required = targets.associateBy { it.key }
            synchronized(stateLock) {
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
            }
            refreshOverallState(generation)
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
                        val attemptId = lease.nextAttempt()
                        val streamJob =
                            launch {
                                val path = "/${target.path}"
                                eventsApi.streamEvents(
                                    path = target.path,
                                    directory = target.directory,
                                    onHTTPResponse = { statusCode ->
                                        if (lease.currentAttempt() == attemptId) {
                                            lastActivityMs.set(System.currentTimeMillis())
                                            diagnosticLogger.info(
                                                DiagnosticLogArea.Sse,
                                                "GET $path opened status=$statusCode protocol=${appliedConfig?.protocol?.wireValue}",
                                            )
                                            markLeaseLive(lease, generation)
                                            retryAttempt = 0
                                        }
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
                            if (lease.currentAttempt() == attemptId) {
                                lease.streamJob = null
                                markLeaseDead(lease, generation)
                            }
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
            synchronized(stateLock) {
                if (generation != streamGeneration.get()) return
                occupyingConnected = false
            }
            refreshOverallState(generation)
        }

        private data class OverallDecision(
            val generation: Long,
            val connectionState: EventStreamConnectionState,
            val publishReconnect: Boolean,
            val snapshot: LiveConnectionSnapshot,
        )

        private fun refreshOverallState(generation: Long) {
            val decision =
                synchronized(stateLock) {
                    decideOverallStateLocked(generation)
                } ?: return
            setConnectionState(decision.connectionState, decision.generation)
            _snapshot.value = decision.snapshot
            if (decision.publishReconnect) {
                diagnosticLogger.info(DiagnosticLogArea.Sse, "foreground SSE stream reconnected")
                scope.launch {
                    emitIfCurrent(OpencodeEvent(type = "stream.reconnected"), decision.generation)
                }
            }
        }

        private fun decideOverallStateLocked(generation: Long): OverallDecision? {
            if (generation != streamGeneration.get()) return null
            val config = appliedConfig
            val required =
                if (config == null) {
                    emptyList()
                } else {
                    SseSubscribeTargets.forProtocol(config.protocol, config.directories).map { it.key }
                }
            val live =
                synchronized(leaseLock) {
                    leases.filterValues { it.live.get() }.keys.toSet()
                }
            val allLive = required.isNotEmpty() && required.all { it in live }
            val incumbentLost =
                lastRequiredTargets.any { key ->
                    key in required && key in lastLiveTargets && key !in live
                }
            val requiredGrew =
                lastRequiredTargets.isNotEmpty() &&
                    lastRequiredTargets.all { it in required } &&
                    required.size > lastRequiredTargets.size
            val pathLost = deviceNetworkPath.status.value == DeviceNetworkPathStatus.Unavailable
            val lostLive = lastAllLive && !allLive
            if (incumbentLost) {
                openGapLocked(if (pathLost) LiveGapReason.PathLost else LiveGapReason.LeaseLost)
                suppressReconnectForRequiredGrowth = false
            } else if (lostLive && pathLost) {
                openGapLocked(LiveGapReason.PathLost)
            } else if (lostLive && requiredGrew) {
                suppressReconnectForRequiredGrowth = true
            }
            lastAllLive = allLive
            lastRequiredTargets = required
            lastLiveTargets = live
            var publishReconnect = false
            val nextState: EventStreamConnectionState
            if (allLive) {
                val shouldPublishReconnect =
                    (wasFullyConnected || pendingResumeBarrier) &&
                        !emittedReconnectForCurrentGap &&
                        !suppressReconnectForRequiredGrowth
                nextState = EventStreamConnectionState.Connected
                occupyingConnected = true
                wasFullyConnected = true
                suppressReconnectForRequiredGrowth = false
                if (shouldPublishReconnect) {
                    emittedReconnectForCurrentGap = true
                    pendingResumeBarrier = false
                    publishReconnect = true
                }
            } else {
                nextState =
                    if (wasFullyConnected) {
                        EventStreamConnectionState.Reconnecting
                    } else {
                        EventStreamConnectionState.Connecting
                    }
            }
            return OverallDecision(
                generation = generation,
                connectionState = nextState,
                publishReconnect = publishReconnect,
                snapshot = buildSnapshotLocked(nextState),
            )
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
            val snapshot =
                synchronized(stateLock) {
                    buildSnapshotLocked(_connectionState.value)
                }
            _snapshot.value = snapshot
        }

        private fun buildSnapshotLocked(connectionState: EventStreamConnectionState): LiveConnectionSnapshot {
            val config = appliedConfig
            val required =
                config?.let { SseSubscribeTargets.forProtocol(it.protocol, it.directories).map { target -> target.key } }
                    ?: emptyList()
            val live =
                synchronized(leaseLock) {
                    leases.filterValues { it.live.get() }.keys.toList()
                }
            return LiveConnectionSnapshot(
                config = config,
                generation = streamGeneration.get(),
                connectionState = connectionState,
                gap = currentGap,
                requiredTargets = required,
                liveTargets = live,
            )
        }

        private fun stillCurrent(generation: Long): Boolean =
            occupancyJob?.isActive == true && streamGeneration.get() == generation

        private fun isOccupying(): Boolean = occupancyJob?.isActive == true

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

        private fun bootstrapConfig(): LiveConnectionConfig {
            val profile = activeConnectionManager.currentProfile()
            val protocol = activeConnectionManager.protocolHint()
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
                directories = lastDirectories,
            )
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
            private val attempt = AtomicInteger(0)
            var loopJob: Job? = null
            var streamJob: Job? = null

            fun nextAttempt(): Int = attempt.incrementAndGet()

            fun currentAttempt(): Int = attempt.get()

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
