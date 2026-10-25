package dev.opencode.mobile.domain.event

import dev.opencode.mobile.data.remote.EventsApi
import dev.opencode.mobile.data.remote.SseAttemptOutcome
import dev.opencode.mobile.domain.connection.ConnectionProfile
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Occupancy owner for foreground SSE. Config apply, live policy, and event
 * filtering live in [LiveConnectionApply], [LiveOverallPolicy], and
 * [SseEventIngress].
 */
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
            protocolRecheckIntervalMs: Long = ProtocolRecheckPolicy.MIN_INTERVAL_MS,
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
            this.protocolRecheckIntervalMs = protocolRecheckIntervalMs
        }

        private val scope =
            CoroutineScope(
                SupervisorJob() +
                    CoroutineExceptionHandler { _, throwable ->
                        diagnosticLogger.warning(
                            DiagnosticLogArea.Sse,
                            "foreground SSE uncaught",
                            throwable,
                        )
                    },
            )
        private var occupancyJob: Job? = null
        private var idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS
        private var protocolRecheckIntervalMs: Long = ProtocolRecheckPolicy.MIN_INTERVAL_MS
        private var connectionEpoch: ConnectionEpoch = ConnectionEpoch.shared
        private val leaseLock = Any()
        private val stateLock = Any()
        private val leases = mutableMapOf<String, Lease>()

        /** Invalidates in-flight cancel/error handlers after stop() or a newer start(). */
        private val streamGeneration = AtomicLong(0)

        /** Invalidates overlapping start() applies after stop() or a new occupancy. */
        private var occupancySerial = 0L
        private var appliedConfig: LiveConnectionConfig? = null
        private var pendingStartConfig: LiveConnectionConfig? = null
        private var inFlightStartApplies = 0
        private val lastDirectoriesByIdentity = mutableMapOf<LiveConnectionConfig.Identity, List<String>>()
        private val eventIngress = SseEventIngress(eventDomainAdapter)
        private var overall = LiveOverallFlags()
        private val configEpoch = AtomicLong(0)

        /** Leases whose last attempt was rejected with 401/403; guarded by [leaseLock]. */
        private val authRejectedTargets = mutableSetOf<String>()
        private val protocolConfirmed = AtomicBoolean(false)

        /** The last protocol re-check found no protocol answering; cleared once one does. */
        private val serverUnreachable = AtomicBoolean(false)

        /** When the last protocol re-check started; guarded by [stateLock]. */
        private var lastProtocolRecheckMs: Long? = null
        private var protocolRecheckJob: Job? = null

        /** A request that arrived inside the window, run when it closes; guarded by [stateLock]. */
        private var deferredProtocolRecheckJob: Job? = null

        /** The last recheck got no answer, so the next stream to connect must confirm the protocol. */
        private val protocolUnverified = AtomicBoolean(false)

        /** Counts leases going live, so a recheck can tell its no-answer result went stale. */
        private val leaseLiveEpoch = AtomicLong(0)
        private val reconnectWakeEpoch = AtomicLong(0)
        private val reconnectWake =
            MutableSharedFlow<Unit>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        private val _connectionState = MutableStateFlow(EventStreamConnectionState.Idle)
        override val connectionState: StateFlow<EventStreamConnectionState> = _connectionState.asStateFlow()

        private val _snapshot = MutableStateFlow(LiveConnectionSnapshot())
        override val snapshot: StateFlow<LiveConnectionSnapshot> = _snapshot.asStateFlow()

        private val _events = MutableSharedFlow<OpencodeEvent>(extraBufferCapacity = 4_096)
        override val events: SharedFlow<OpencodeEvent> = _events.asSharedFlow()

        override fun start() {
            var overlappingJob: Job? = null
            var overlappingSerial = 0L
            var newJob: Job? = null
            synchronized(stateLock) {
                val existing = occupancyJob
                if (existing?.isActive == true) {
                    val stuckIdle =
                        _connectionState.value == EventStreamConnectionState.Idle && appliedConfig == null
                    if (!stuckIdle) {
                        inFlightStartApplies += 1
                        overlappingJob = existing
                        overlappingSerial = occupancySerial
                        return@synchronized
                    }
                    existing.cancel()
                    occupancyJob = null
                }
                diagnosticLogger.info(DiagnosticLogArea.Sse, "foreground SSE stream starting")
                occupancySerial += 1
                val serial = occupancySerial
                val job =
                    scope.launch(start = CoroutineStart.LAZY) {
                        val bootstrap =
                            synchronized(stateLock) {
                                pendingStartConfig.also { pendingStartConfig = null }
                            } ?: bootstrapConfig()
                        if (!isActive) return@launch
                        beginOccupancy(this, bootstrap, serial)
                        awaitCancellation()
                    }
                occupancyJob = job
                newJob = job
            }
            if (overlappingJob != null) {
                val serial = overlappingSerial
                scope.launch {
                    try {
                        applyStartWhileOccupying()
                    } finally {
                        endInFlightStartApply(serial)
                    }
                }
                return
            }
            newJob?.start()
        }

        private suspend fun applyStartWhileOccupying() {
            val occupancy: Job
            synchronized(stateLock) {
                val job = occupancyJob
                if (job?.isActive != true) return
                occupancy = job
            }
            val claim = claimConfigEpoch()
            val config = resolveConfig()
            if (!isLatestConfigClaim(claim)) return
            val decision =
                synchronized(stateLock) {
                    val occupancyCurrent =
                        occupancyJob === occupancy && occupancyJob?.isActive == true
                    val decided =
                        LiveConnectionApply.startDecision(
                            occupancyCurrent = occupancyCurrent,
                            applied = appliedConfig,
                            requested = config,
                            generation = streamGeneration.get(),
                        )
                    if (decided is LiveConnectionApply.StartDecision.StashPending) {
                        pendingStartConfig = config
                    }
                    decided
                }
            when (decision) {
                LiveConnectionApply.StartDecision.Ignore,
                LiveConnectionApply.StartDecision.StashPending,
                -> Unit
                LiveConnectionApply.StartDecision.Idempotent -> {
                    diagnosticLogger.debug(
                        DiagnosticLogArea.Sse,
                        "foreground SSE start ignored; already running",
                    )
                }
                is LiveConnectionApply.StartDecision.Reconcile -> {
                    if (!stillCurrent(decision.generation)) return
                    reconcileLeases(decision.config)
                }
                is LiveConnectionApply.StartDecision.Retarget -> {
                    if (!stillCurrent(decision.generation)) return
                    retargetIdentity(decision.config)
                }
            }
        }

        override fun onReconnectWake() {
            synchronized(stateLock) { lastProtocolRecheckMs = null }
            reconnectWakeEpoch.incrementAndGet()
            reconnectWake.tryEmit(Unit)
        }

        override suspend fun stop() {
            diagnosticLogger.info(DiagnosticLogArea.Sse, "foreground SSE stream stopping")
            val generation: Long
            val snapshot: LiveConnectionSnapshot
            val occupancyToJoin: Job?
            val leaseJobs: List<Job>
            protocolConfirmed.set(false)
            serverUnreachable.set(false)
            protocolUnverified.set(false)
            synchronized(stateLock) {
                lastProtocolRecheckMs = null
                protocolRecheckJob?.cancel()
                protocolRecheckJob = null
                deferredProtocolRecheckJob?.cancel()
                deferredProtocolRecheckJob = null
                val wasFullyConnected = overall.wasFullyConnected
                occupancySerial += 1
                inFlightStartApplies = 0
                generation = streamGeneration.incrementAndGet()
                occupancyToJoin = occupancyJob
                occupancyJob = null
                pendingStartConfig = null
                leaseJobs = cancelAllLeases()
                eventIngress.reset()
                appliedConfig = null
                overall = LiveOverallFlags(wasFullyConnected = wasFullyConnected)
                snapshot = buildSnapshotLocked(EventStreamConnectionState.Idle)
            }
            eventsApi.retire(olderThan = generation)
            occupancyToJoin?.cancel()
            val toJoin =
                buildList {
                    occupancyToJoin?.let { add(it) }
                    addAll(leaseJobs)
                }
            toJoin.forEach { it.join() }
            setConnectionState(EventStreamConnectionState.Idle, generation)
            _snapshot.value = snapshot
        }

        private fun endInFlightStartApply(serial: Long) {
            synchronized(stateLock) {
                if (occupancySerial == serial) {
                    inFlightStartApplies = maxOf(0, inFlightStartApplies - 1)
                }
            }
        }

        private suspend fun waitForInFlightStartApplies(
            occupancyScope: CoroutineScope,
            serial: Long,
        ) {
            while (true) {
                val done =
                    synchronized(stateLock) {
                        inFlightStartApplies == 0 ||
                            !occupancyScope.isActive ||
                            occupancyJob?.isActive != true ||
                            occupancySerial != serial
                    }
                if (done) return
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
            val merged =
                synchronized(stateLock) {
                    val pending = pendingStartConfig
                    pendingStartConfig = null
                    (pending ?: config).also { appliedConfig = it }
                }
            val generation = beginGeneration()
            if (!stillCurrent(generation)) return
            publishSnapshot()
            deviceNetworkPath.waitUntilReady()
            if (!isOccupying() || !occupancyScope.isActive) return
            val latest: LiveConnectionConfig
            val appliedNow: LiveConnectionConfig?
            val generationAfterWait: Long
            val resumeChrome: Boolean
            synchronized(stateLock) {
                val pending = pendingStartConfig
                pendingStartConfig = null
                latest = pending ?: appliedConfig ?: merged
                appliedNow = appliedConfig
                generationAfterWait = streamGeneration.get()
                resumeChrome = overall.wasFullyConnected
            }
            setConnectionState(
                if (resumeChrome) {
                    EventStreamConnectionState.Reconnecting
                } else {
                    EventStreamConnectionState.Connecting
                },
                generationAfterWait,
            )
            val resolvedConfig =
                resolveProtocolBeforeLeases(latest).also { resolved ->
                    synchronized(stateLock) {
                        appliedConfig = resolved
                    }
                }
            if (stillCurrent(generationAfterWait)) {
                if (appliedNow != null &&
                    LiveConnectionApply.change(appliedNow, resolvedConfig) == LiveConnectionApply.Change.Identity
                ) {
                    retargetIdentity(resolvedConfig)
                } else {
                    reconcileLeases(resolvedConfig)
                }
            }
            val collectorGeneration = streamGeneration.get()
            if (!stillCurrent(collectorGeneration)) return
            occupancyScope.launch(start = CoroutineStart.UNDISPATCHED) {
                collectConfigurationRevisions(collectorGeneration)
            }
            occupancyScope.launch {
                probeProtocolWhileOccupying(collectorGeneration)
            }
        }

        private suspend fun collectConfigurationRevisions(generation: Long) {
            // Subscribe first so a replay-0 revision emitted during the
            // initial resolve is not lost, then recheck current worktrees.
            coroutineScope {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    connectionProfileStore.configurationRevisions().collect {
                        if (!stillCurrent(generation)) return@collect
                        onConfigurationRevision(generation)
                    }
                }
                onConfigurationRevision(generation)
            }
        }

        private suspend fun probeProtocolWhileOccupying(generation: Long) {
            val hadCached = activeConnectionManager.currentProtocol() != null
            if (hadCached) {
                // The saved protocol opened the leases; confirm the server still speaks it.
                protocolConfirmed.set(true)
                requestProtocolRecheck(generation, "start")
                return
            }
            val resolved = activeConnectionManager.currentProtocolOrProbe()
            if (!stillCurrent(generation)) return
            if (hadCached || activeConnectionManager.currentProtocol() != null) {
                protocolConfirmed.set(true)
            }
            val applied = appliedConfig ?: return
            if (resolved != applied.protocol) {
                diagnosticLogger.info(
                    DiagnosticLogArea.Sse,
                    "SSE protocol resolved ${applied.protocol.wireValue} -> ${resolved.wireValue}; reconnecting",
                )
                protocolConfirmed.set(true)
                retargetIdentity(applied.copy(protocol = resolved))
            }
        }

        private suspend fun resolveProtocolBeforeLeases(config: LiveConnectionConfig): LiveConnectionConfig {
            val hadCached = activeConnectionManager.currentProtocol() != null
            if (hadCached) {
                protocolConfirmed.set(true)
                val cached = activeConnectionManager.currentProtocol() ?: config.protocol
                return config.copy(protocol = cached)
            }
            val resolved = activeConnectionManager.currentProtocolOrProbe()
            if (activeConnectionManager.currentProtocol() != null) {
                protocolConfirmed.set(true)
            }
            return config.copy(protocol = resolved)
        }

        private suspend fun onConfigurationRevision(generation: Long) {
            val claim = claimConfigEpoch()
            val config = resolveConfig()
            if (!stillCurrent(generation)) return
            if (!isLatestConfigClaim(claim)) return
            applyResolvedConfig(config)
        }

        private fun claimConfigEpoch(): Long = configEpoch.incrementAndGet()

        private fun isLatestConfigClaim(epoch: Long): Boolean = configEpoch.get() == epoch

        private fun applyResolvedConfig(config: LiveConnectionConfig) {
            val (applied, effective) =
                synchronized(stateLock) {
                    val applied = appliedConfig
                    val pending = pendingStartConfig
                    pendingStartConfig = null
                    val effective = if (applied == null) pending ?: config else config
                    applied to effective
                }
            if (applied == null) {
                reconcileLeases(effective)
                return
            }
            when (LiveConnectionApply.change(applied, effective)) {
                LiveConnectionApply.Change.Identity ->
                    retargetIdentity(effective)
                LiveConnectionApply.Change.Directories -> reconcileLeases(effective)
                LiveConnectionApply.Change.None -> Unit
            }
        }

        private fun retargetIdentity(config: LiveConnectionConfig) {
            if (!isOccupying()) return
            val generation = beginGeneration()
            if (!stillCurrent(generation)) return
            synchronized(stateLock) {
                if (generation != streamGeneration.get()) return
                appliedConfig = config
                eventIngress.reset()
                cancelAllLeases()
            }
            if (!stillCurrent(generation)) return
            reconcileLeases(config)
        }

        private fun beginGeneration(): Long =
            synchronized(stateLock) {
                val generation = streamGeneration.incrementAndGet()
                connectionEpoch.bump()
                generation
            }

        private fun reconcileLeases(config: LiveConnectionConfig) {
            val generation = streamGeneration.get()
            if (!stillCurrent(generation)) return
            val targets = SseSubscribeTargets.forProtocol(config.protocol, config.directories)
            val required = targets.associateBy { it.key }
            synchronized(stateLock) {
                if (generation != streamGeneration.get() || occupancyJob?.isActive != true) return
                appliedConfig = config
                lastDirectoriesByIdentity[config.identity] = config.directories
                configEpoch.incrementAndGet()
                synchronized(leaseLock) {
                    val stale = leases.keys.filter { it !in required }
                    stale.forEach { key ->
                        leases.remove(key)?.cancel()
                        authRejectedTargets.remove(key)
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
                        val wakeAtStart = reconnectWakeEpoch.get()
                        val attempt = SseLeaseAttempt()
                        val serverConnected = CompletableDeferred<Unit>()
                        val headersOpened = CompletableDeferred<Boolean>()
                        val attemptOutcome = CompletableDeferred<SseAttemptOutcome>()
                        val streamJob =
                            launch {
                                val path = "/${target.path}"
                                val readJob = currentCoroutineContext()[Job]!!
                                val handshakeTimeout =
                                    launch {
                                        try {
                                            val opened = headersOpened.await()
                                            if (!opened) return@launch
                                            withTimeout(FIRST_FRAME_TIMEOUT_MS) {
                                                serverConnected.await()
                                            }
                                        } catch (_: TimeoutCancellationException) {
                                            if (lease.currentAttempt() == attemptId) {
                                                diagnosticLogger.warning(
                                                    DiagnosticLogArea.Sse,
                                                    "SSE first frame timeout target=${target.key}",
                                                )
                                            }
                                            readJob.cancel(
                                                CancellationException(FIRST_FRAME_FAILED),
                                            )
                                        }
                                    }
                                try {
                                    val outcome =
                                        eventsApi.streamEvents(
                                            path = target.path,
                                            directory = target.directory,
                                            generation = generation,
                                            onHTTPResponse = { statusCode ->
                                                if (lease.currentAttempt() == attemptId) {
                                                    lastActivityMs.set(System.currentTimeMillis())
                                                    diagnosticLogger.info(
                                                        DiagnosticLogArea.Sse,
                                                        "GET $path status=$statusCode target=${target.key} protocol=${appliedConfig?.protocol?.wireValue}",
                                                    )
                                                    headersOpened.complete(attempt.onHttpStatus(statusCode))
                                                }
                                            },
                                            onActivity = {
                                                lastActivityMs.set(System.currentTimeMillis())
                                            },
                                        ) { dataLine ->
                                            lastActivityMs.set(System.currentTimeMillis())
                                            if (!attempt.serverConnected) {
                                                val parsed = eventDomainAdapter.parseEvents(dataLine)
                                                when (attempt.onFirstDataEvents(parsed)) {
                                                    SseFirstFrameResult.Rejected -> {
                                                        diagnosticLogger.warning(
                                                            DiagnosticLogArea.Sse,
                                                            "SSE first frame is not server.connected target=${target.key}",
                                                        )
                                                        attemptOutcome.complete(
                                                            SseAttemptOutcome.Transport(null),
                                                        )
                                                        readJob.cancel(
                                                            CancellationException(FIRST_FRAME_FAILED),
                                                        )
                                                        return@streamEvents
                                                    }
                                                    SseFirstFrameResult.Connected -> {
                                                        serverConnected.complete(Unit)
                                                        markLeaseLive(lease, generation)
                                                        retryAttempt = 0
                                                    }
                                                    SseFirstFrameResult.Subsequent -> Unit
                                                }
                                            }
                                            publishDataLine(dataLine, generation)
                                        }
                                    attemptOutcome.complete(attempt.terminalOutcome(outcome))
                                } catch (cancelled: CancellationException) {
                                    if (!attemptOutcome.isCompleted) {
                                        attemptOutcome.complete(
                                            if (cancelled.message == FIRST_FRAME_FAILED) {
                                                SseAttemptOutcome.Transport(cancelled)
                                            } else {
                                                SseAttemptOutcome.Cancelled
                                            },
                                        )
                                    }
                                    if (cancelled.message != FIRST_FRAME_FAILED) {
                                        throw cancelled
                                    }
                                } finally {
                                    headersOpened.complete(false)
                                    handshakeTimeout.cancel()
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
                        val outcome =
                            try {
                                streamJob.join()
                                if (attemptOutcome.isCompleted) {
                                    attempt.terminalOutcome(attemptOutcome.await())
                                } else {
                                    SseAttemptOutcome.Transport(null)
                                }
                            } catch (cancelled: CancellationException) {
                                if (generation != streamGeneration.get() || !isRequired(target.key)) {
                                    watchJob.cancel()
                                    pathWatchJob.cancel()
                                    throw cancelled
                                }
                                if (attemptOutcome.isCompleted) {
                                    attempt.terminalOutcome(attemptOutcome.await())
                                } else {
                                    SseAttemptOutcome.Cancelled
                                }
                            } finally {
                                watchJob.cancel()
                                pathWatchJob.cancel()
                                if (lease.currentAttempt() == attemptId) {
                                    lease.streamJob = null
                                    markLeaseDead(lease, generation)
                                }
                            }
                        if (generation != streamGeneration.get() || !isRequired(target.key)) return@launch
                        if (lease.currentAttempt() == attemptId) {
                            logAttemptOutcome(target.key, outcome)
                        }
                        if (ProtocolRecheckPolicy.suggestsProtocolChange(
                                outcome = outcome,
                                httpOpened = attempt.httpOpened,
                                serverConnected = attempt.serverConnected,
                            )
                        ) {
                            requestProtocolRecheck(generation, "sse target=${target.key}")
                        }
                        when (
                            val decision =
                                SseAttemptPolicy.decide(
                                    outcome = outcome,
                                    protocolConfirmed = protocolConfirmed.get(),
                                    attempt = retryAttempt + 1,
                                )
                        ) {
                            is SseRetryDecision.AuthFailed -> {
                                val status =
                                    (outcome as? SseAttemptOutcome.Http)?.statusCode ?: 0
                                markAuthFailed(generation, target.key, status, decision.delayMs)
                                if (reconnectWakeEpoch.get() != wakeAtStart) {
                                    retryAttempt = 0
                                    continue
                                }
                                retryAttempt += 1
                                if (awaitReconnectWakeOrDelay(decision.delayMs)) {
                                    retryAttempt = 0
                                }
                            }
                            is SseRetryDecision.Retry -> {
                                if (deviceNetworkPath.status.value == DeviceNetworkPathStatus.Unavailable) {
                                    continue
                                }
                                if (reconnectWakeEpoch.get() != wakeAtStart) {
                                    retryAttempt = 0
                                    continue
                                }
                                retryAttempt += 1
                                diagnosticLogger.debug(
                                    DiagnosticLogArea.Sse,
                                    "foreground SSE retry target=${target.key} attempt=$retryAttempt backoffMs=${decision.delayMs}",
                                )
                                val wokeEarly = awaitReconnectWakeOrDelay(decision.delayMs)
                                if (wokeEarly) {
                                    retryAttempt = 0
                                }
                            }
                        }
                    }
                }
            return lease
        }

        private suspend fun awaitReconnectWakeOrDelay(backoffMs: Long): Boolean =
            if (backoffMs <= 0L) {
                false
            } else {
                withTimeoutOrNull(backoffMs) {
                    reconnectWake.first()
                    true
                } == true
            }

        /**
         * Shows the rejected credential without giving up: the lease keeps retrying on the slow auth
         * backoff (woken early by foreground or refresh) so the stream recovers once auth does.
         */
        private fun markAuthFailed(
            generation: Long,
            targetKey: String,
            statusCode: Int,
            retryInMs: Long,
        ) {
            if (generation != streamGeneration.get()) return
            synchronized(leaseLock) { authRejectedTargets += targetKey }
            diagnosticLogger.warning(
                DiagnosticLogArea.Sse,
                "SSE auth failed target=$targetKey status=$statusCode; retrying in ${retryInMs}ms",
            )
            refreshOverallState(generation)
        }

        private fun logAttemptOutcome(
            targetKey: String,
            outcome: SseAttemptOutcome,
        ) {
            when (outcome) {
                SseAttemptOutcome.Ended ->
                    diagnosticLogger.debug(
                        DiagnosticLogArea.Sse,
                        "SSE attempt ended target=$targetKey",
                    )
                SseAttemptOutcome.Cancelled ->
                    diagnosticLogger.debug(
                        DiagnosticLogArea.Sse,
                        "SSE attempt cancelled target=$targetKey",
                    )
                is SseAttemptOutcome.Http ->
                    diagnosticLogger.info(
                        DiagnosticLogArea.Sse,
                        "SSE attempt http target=$targetKey status=${outcome.statusCode}",
                    )
                is SseAttemptOutcome.Transport ->
                    diagnosticLogger.warning(
                        DiagnosticLogArea.Sse,
                        "SSE attempt transport target=$targetKey",
                        outcome.cause,
                    )
            }
        }

        private suspend fun publishDataLine(
            dataLine: String,
            generation: Long,
        ) {
            if (generation != streamGeneration.get()) return
            val result = eventIngress.accept(dataLine)
            if (result.parsed.isEmpty()) {
                if (result.parseFailed) {
                    diagnosticLogger.warning(
                        DiagnosticLogArea.Sse,
                        SseDiagnosticLogging.parseFailedMessage(dataLine),
                    )
                    publishSnapshot()
                    for (event in result.toEmit) {
                        emitIfCurrent(event, generation)
                    }
                } else if (result.droppedType != null) {
                    diagnosticLogger.debug(
                        DiagnosticLogArea.Sse,
                        SseDiagnosticLogging.droppedMessage(result.droppedType),
                    )
                }
                return
            }
            for (event in result.parsed) {
                val bufferLine = SseDiagnosticLogging.bufferMessage(event)
                if (bufferLine != null) {
                    diagnosticLogger.info(DiagnosticLogArea.Sse, bufferLine)
                } else if (event.type != "server.heartbeat") {
                    diagnosticLogger.debug(
                        DiagnosticLogArea.Sse,
                        "SSE event type=${event.type} session=${event.sessionId ?: "-"}",
                    )
                }
            }
            for (event in result.toEmit) {
                emitIfCurrent(event, generation)
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
            leaseLiveEpoch.incrementAndGet()
            serverUnreachable.set(false)
            synchronized(leaseLock) { authRejectedTargets -= lease.target.key }
            refreshOverallState(generation)
        }

        private fun markLeaseDead(
            lease: Lease,
            generation: Long,
        ) {
            if (generation != streamGeneration.get()) return
            lease.live.set(false)
            refreshOverallState(generation)
        }

        private data class OverallDecision(
            val generation: Long,
            val connectionState: EventStreamConnectionState,
            val publishReconnect: Boolean,
            val snapshot: LiveConnectionSnapshot,
            val recovered: Boolean = false,
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
            if (decision.recovered) {
                // A server restarted at this address, or one that did not answer the last check,
                // may have come back speaking the other protocol.
                requestProtocolRecheck(decision.generation, "reconnected")
            }
        }

        private fun requestProtocolRecheck(
            generation: Long,
            reason: String,
        ) {
            synchronized(stateLock) {
                if (occupancyJob?.isActive != true || generation != streamGeneration.get()) return
                val now = System.currentTimeMillis()
                val waitMs = ProtocolRecheckPolicy.delayUntilDueMs(now, lastProtocolRecheckMs, protocolRecheckIntervalMs)
                if (protocolRecheckJob?.isActive == true || waitMs > 0L) {
                    deferProtocolRecheckLocked(reason)
                    return
                }
                lastProtocolRecheckMs = now
                protocolRecheckJob = scope.launch { recheckProtocol(generation, reason) }
            }
        }

        private fun deferProtocolRecheckLocked(reason: String) {
            if (deferredProtocolRecheckJob?.isActive == true) return
            val inFlight = protocolRecheckJob
            deferredProtocolRecheckJob =
                scope.launch {
                    // An answer still in flight may predate the change that asked for this check.
                    inFlight?.join()
                    val waitMs =
                        synchronized(stateLock) {
                            ProtocolRecheckPolicy.delayUntilDueMs(
                                System.currentTimeMillis(),
                                lastProtocolRecheckMs,
                                protocolRecheckIntervalMs,
                            )
                        }
                    delay(waitMs)
                    val generation =
                        synchronized(stateLock) {
                            deferredProtocolRecheckJob = null
                            streamGeneration.get()
                        }
                    requestProtocolRecheck(generation, "$reason (deferred)")
                }
        }

        private suspend fun recheckProtocol(
            generation: Long,
            reason: String,
        ) {
            diagnosticLogger.info(DiagnosticLogArea.Sse, "SSE protocol recheck reason=$reason")
            val liveEpochAtStart = leaseLiveEpoch.get()
            val result = activeConnectionManager.redetectProtocol()
            if (!stillCurrent(generation)) return
            val applied = synchronized(stateLock) { appliedConfig } ?: return
            val action =
                ProtocolRecheckPolicy.action(
                    result = result,
                    applied = applied.protocol,
                    leaseWentLive = leaseLiveEpoch.get() != liveEpochAtStart,
                )
            when (action) {
                is ProtocolRecheckAction.Retarget -> {
                    diagnosticLogger.info(
                        DiagnosticLogArea.Sse,
                        "SSE protocol changed ${applied.protocol.wireValue} -> ${action.protocol.wireValue}; reconnecting",
                    )
                    serverUnreachable.set(false)
                    protocolUnverified.set(false)
                    protocolConfirmed.set(true)
                    retargetIdentity(applied.copy(protocol = action.protocol))
                }
                ProtocolRecheckAction.KeepProtocol -> {
                    protocolUnverified.set(false)
                    if (serverUnreachable.getAndSet(false)) refreshOverallState(generation)
                }
                ProtocolRecheckAction.MarkUnreachable -> {
                    diagnosticLogger.warning(DiagnosticLogArea.Sse, "SSE protocol recheck: no protocol answered")
                    serverUnreachable.set(true)
                    protocolUnverified.set(true)
                    refreshOverallState(generation)
                }
                ProtocolRecheckAction.None -> Unit
            }
        }

        private fun decideOverallStateLocked(generation: Long): OverallDecision? {
            if (generation != streamGeneration.get()) return null
            if (synchronized(leaseLock) { authRejectedTargets.isNotEmpty() }) {
                return OverallDecision(
                    generation = generation,
                    connectionState = EventStreamConnectionState.AuthFailed,
                    publishReconnect = false,
                    snapshot = buildSnapshotLocked(EventStreamConnectionState.AuthFailed),
                )
            }
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
            val currentlyConnected =
                _connectionState.value == EventStreamConnectionState.Connected
            val decided =
                LiveOverallPolicy.decide(
                    overall,
                    LiveOverallInput(
                        required = required,
                        live = live,
                    ),
                    currentlyConnected = currentlyConnected,
                )
            val wasFullyConnected = overall.wasFullyConnected
            overall = decided.flags
            val connectionState =
                if (decided.connectionState != EventStreamConnectionState.Connected && serverUnreachable.get()) {
                    EventStreamConnectionState.Disconnected
                } else {
                    decided.connectionState
                }
            return OverallDecision(
                generation = generation,
                connectionState = connectionState,
                publishReconnect = decided.publishReconnect,
                snapshot = buildSnapshotLocked(connectionState),
                recovered = decided.publishReconnect && (wasFullyConnected || protocolUnverified.get()),
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
                requiredTargets = required,
                liveTargets = live,
            )
        }

        private fun stillCurrent(generation: Long): Boolean =
            synchronized(stateLock) {
                occupancyJob?.isActive == true && streamGeneration.get() == generation
            }

        private fun isOccupying(): Boolean =
            synchronized(stateLock) {
                occupancyJob?.isActive == true
            }

        private fun isRequired(key: String): Boolean {
            val config = appliedConfig ?: return false
            return SseSubscribeTargets.forProtocol(config.protocol, config.directories).any { it.key == key }
        }

        private fun cancelAllLeases(): List<Job> {
            synchronized(leaseLock) {
                val jobs =
                    leases.values.flatMap { lease ->
                        listOfNotNull(lease.loopJob, lease.streamJob)
                    }
                leases.values.forEach { it.cancel() }
                leases.clear()
                authRejectedTargets.clear()
                return jobs
            }
        }

        private fun bootstrapConfig(): LiveConnectionConfig {
            val profile = activeConnectionManager.currentProfile()
            val protocol = activeConnectionManager.protocolHint()
            val resolved = profileIdentity(profile)
            val directories =
                synchronized(stateLock) {
                    lastDirectoriesByIdentity[resolved.identity(protocol)].orEmpty()
                }
            return LiveConnectionConfig(
                profileId = resolved.profileId,
                connectionIdentity = resolved.connectionIdentity,
                protocol = protocol,
                directories = directories,
                credentialFingerprint = resolved.credentialFingerprint,
            )
        }

        private suspend fun resolveConfig(): LiveConnectionConfig {
            val profile = activeConnectionManager.currentProfile()
            val protocol = activeConnectionManager.protocolHint()
            val directories = instanceDirectories()
            val resolved = profileIdentity(profile)
            return LiveConnectionConfig(
                profileId = resolved.profileId,
                connectionIdentity = resolved.connectionIdentity,
                protocol = protocol,
                directories = directories,
                credentialFingerprint = resolved.credentialFingerprint,
            )
        }

        private fun profileIdentity(profile: ConnectionProfile?): ProfileIdentity {
            val connectionIdentity =
                if (profile == null) {
                    ""
                } else {
                    "${ConnectionProfileIdentity.normalizeBaseUrl(
                        profile.baseUrl,
                    )}|${ConnectionProfileIdentity.normalizeUsername(profile.username)}"
                }
            val profileId = profile?.id?.ifBlank { connectionIdentity } ?: connectionIdentity
            val fingerprint =
                if (profile == null) {
                    ""
                } else {
                    ConnectionProfileIdentity.credentialFingerprint(profile.username, profile.password)
                }
            return ProfileIdentity(
                profileId = profileId,
                connectionIdentity = connectionIdentity,
                credentialFingerprint = fingerprint,
            )
        }

        private data class ProfileIdentity(
            val profileId: String,
            val connectionIdentity: String,
            val credentialFingerprint: String,
        ) {
            fun identity(protocol: ServerProtocol) =
                LiveConnectionConfig.Identity(
                    profileId = profileId,
                    connectionIdentity = connectionIdentity,
                    protocol = protocol,
                    credentialFingerprint = credentialFingerprint,
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
            const val FIRST_FRAME_TIMEOUT_MS = 3_000L
            const val FIRST_FRAME_FAILED = "SSE first frame failed"
        }
    }
