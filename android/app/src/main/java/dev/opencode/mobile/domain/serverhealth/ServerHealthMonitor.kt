package dev.opencode.mobile.domain.serverhealth

import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.event.LiveConnectionSnapshot
import dev.opencode.mobile.domain.event.StreamOccupancy
import dev.opencode.mobile.network.ServerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The server a health check goes to. */
data class ServerHealthPingTarget(
    val profileId: String,
    val connection: ServerConnection,
    val protocol: ServerProtocol,
)

/**
 * Hands stream failures from the foreground SSE loop to [ServerHealthMonitor] without the stream
 * depending on the monitor (which observes the stream).
 */
class ServerHealthStreamFailureRelay : ServerHealthStreamObserver {
    private val _failures =
        MutableSharedFlow<ServerHealthFailureCategory>(
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val failures: SharedFlow<ServerHealthFailureCategory> = _failures.asSharedFlow()

    override fun onStreamFailure(category: ServerHealthFailureCategory) {
        _failures.tryEmit(category)
    }
}

/**
 * Records connection state, stream failures, and periodic health checks of the active profile
 * while the app is open. Observes the app's one live connection; never opens a stream itself.
 */
class ServerHealthMonitor(
    private val snapshots: StateFlow<LiveConnectionSnapshot>,
    private val occupancy: StateFlow<StreamOccupancy>,
    private val streamFailures: Flow<ServerHealthFailureCategory>,
    private val activeTarget: () -> ServerHealthPingTarget?,
    private val pinger: ServerHealthPinger,
    private val store: ServerHealthStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pingIntervalMs: Long = PING_INTERVAL_MS,
) {
    private val mutex = Mutex()
    private var started = false
    private var currentProfile: String? = null
    private var currentOccupancy: StreamOccupancy = StreamOccupancy.Stopped
    private val lastState = mutableMapOf<String, ServerHealthConnectionState>()
    private val failureRecordedSinceLive = mutableMapOf<String, Boolean>()
    private val lastIdentity = mutableMapOf<String, ServerHealthProcessIdentity?>()
    private var pingJob: Job? = null
    private var pingProfile: String? = null
    private var lastPruneMs: Long? = null

    private val _revision = MutableStateFlow(0L)

    /** Bumps after every write, so readers know to reload. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _liveStatus = MutableStateFlow(ServerHealthLiveStatus())
    val liveStatus: StateFlow<ServerHealthLiveStatus> = _liveStatus.asStateFlow()

    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            mutex.withLock { pruneLocked(force = true) }
            combine(snapshots, occupancy) { snapshot, occupancy -> snapshot to occupancy }
                .collect { (snapshot, occupancy) -> onInputs(snapshot, occupancy) }
        }
        scope.launch {
            streamFailures.collect { category -> onStreamFailure(category) }
        }
    }

    private suspend fun onInputs(
        snapshot: LiveConnectionSnapshot,
        occupancy: StreamOccupancy,
    ) {
        mutex.withLock {
            currentOccupancy = occupancy
            val profile =
                snapshot.config?.profileId?.takeIf { it.isNotBlank() }
                    ?: activeTarget()?.profileId?.takeIf { it.isNotBlank() }
            val state =
                if (profile == null || occupancy == StreamOccupancy.Stopped) {
                    ServerHealthConnectionState.Stopped
                } else {
                    ServerHealthConnectionState.from(snapshot.connectionState)
                }
            val previous = currentProfile
            if (previous != null && previous != profile) {
                recordStateLocked(previous, ServerHealthConnectionState.Stopped)
            }
            currentProfile = profile
            if (profile != null) {
                recordStateLocked(profile, state)
                if (state == ServerHealthConnectionState.Live) failureRecordedSinceLive[profile] = false
            }
            _liveStatus.update { status ->
                val sameProfile = status.profileId == profile
                ServerHealthLiveStatus(
                    profileId = profile,
                    state = state,
                    lastPingMs = if (sameProfile) status.lastPingMs else null,
                    lastVersion = if (sameProfile) status.lastVersion else null,
                    liveTargets = if (profile == null) 0 else snapshot.liveTargets.size,
                    requiredTargets = if (profile == null) 0 else snapshot.requiredTargets.size,
                )
            }
            updatePingLoopLocked(profile, occupancy)
        }
    }

    private fun updatePingLoopLocked(
        profile: String?,
        occupancy: StreamOccupancy,
    ) {
        val wanted = profile.takeIf { occupancy == StreamOccupancy.Active }
        if (wanted == pingProfile && pingJob?.isActive == true) return
        pingJob?.cancel()
        pingJob = null
        pingProfile = wanted
        if (wanted == null) return
        pingJob =
            scope.launch {
                while (isActive) {
                    pingOnce(wanted)
                    delay(pingIntervalMs)
                }
            }
    }

    private suspend fun pingOnce(profile: String) {
        val target = activeTarget()?.takeIf { it.profileId == profile } ?: return
        val result =
            try {
                pinger.ping(target.connection, target.protocol)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ServerHealthPingResult.Failure(ServerHealthFailureClassifier.classifyTransport(error))
            }
        mutex.withLock {
            if (pingProfile != profile) return
            val now = clock()
            when (result) {
                is ServerHealthPingResult.Success -> {
                    detectRestartLocked(profile, now, result.pid, result.version)
                    store.appendPing(
                        ServerHealthPing(
                            profileId = profile,
                            atMs = now,
                            latencyMs = result.latencyMs,
                            failure = null,
                            pid = result.pid,
                            version = result.version,
                        ),
                    )
                    if (result.pid != null || result.version != null) {
                        lastIdentity[profile] = ServerHealthProcessIdentity(result.pid, result.version)
                    }
                    _liveStatus.update { status ->
                        if (status.profileId != profile) {
                            status
                        } else {
                            status.copy(lastPingMs = result.latencyMs, lastVersion = result.version ?: status.lastVersion)
                        }
                    }
                }
                is ServerHealthPingResult.Failure -> {
                    store.appendPing(
                        ServerHealthPing(profileId = profile, atMs = now, latencyMs = null, failure = result.category),
                    )
                    _liveStatus.update { status ->
                        if (status.profileId != profile) status else status.copy(lastPingMs = null)
                    }
                }
            }
            bumpLocked()
        }
    }

    private suspend fun detectRestartLocked(
        profile: String,
        now: Long,
        pid: Int?,
        version: String?,
    ) {
        val previous =
            if (lastIdentity.containsKey(profile)) {
                lastIdentity[profile]
            } else {
                store.lastPid(profile).also { lastIdentity[profile] = it }
            } ?: return
        val restarted =
            when {
                previous.pid != null && pid != null -> previous.pid != pid
                previous.pid == null && pid == null ->
                    previous.version != null && version != null && previous.version != version
                else -> false
            }
        if (restarted) {
            store.appendEvent(ServerHealthEvent.restart(profile, now, version, previous.version))
        }
    }

    private suspend fun onStreamFailure(category: ServerHealthFailureCategory) {
        mutex.withLock {
            val profile = currentProfile ?: return
            if (currentOccupancy == StreamOccupancy.Stopped) return
            if (failureRecordedSinceLive[profile] == true) return
            failureRecordedSinceLive[profile] = true
            store.appendEvent(ServerHealthEvent.failure(profile, clock(), category))
            bumpLocked()
        }
    }

    private suspend fun recordStateLocked(
        profile: String,
        state: ServerHealthConnectionState,
    ) {
        val last = lastState[profile] ?: seedStateLocked(profile)
        if (last == state) return
        lastState[profile] = state
        store.appendEvent(ServerHealthEvent.state(profile, clock(), state))
        bumpLocked()
    }

    /**
     * The last recorded state from an earlier run. A run that ended without recording `stopped`
     * (the process was killed) is closed at its last recorded activity, so the gap is not counted.
     */
    private suspend fun seedStateLocked(profile: String): ServerHealthConnectionState {
        val now = clock()
        val recorded = store.events(profile, sinceMs = Long.MAX_VALUE).lastOrNull()?.state
        if (recorded == null || recorded == ServerHealthConnectionState.Stopped) {
            lastState[profile] = ServerHealthConnectionState.Stopped
            return ServerHealthConnectionState.Stopped
        }
        val since = now - ServerHealthStore.RETENTION_MS
        val lastActivity =
            maxOf(
                store.events(profile, since).maxOfOrNull { it.atMs } ?: Long.MIN_VALUE,
                store.pings(profile, since).maxOfOrNull { it.atMs } ?: Long.MIN_VALUE,
            ).takeIf { it != Long.MIN_VALUE } ?: now
        store.appendEvent(ServerHealthEvent.state(profile, minOf(lastActivity, now), ServerHealthConnectionState.Stopped))
        lastState[profile] = ServerHealthConnectionState.Stopped
        return ServerHealthConnectionState.Stopped
    }

    private suspend fun bumpLocked() {
        _revision.value += 1
        pruneLocked(force = false)
    }

    private suspend fun pruneLocked(force: Boolean) {
        val now = clock()
        val last = lastPruneMs
        if (!force && last != null && now - last < PRUNE_INTERVAL_MS) return
        lastPruneMs = now
        store.prune(now - ServerHealthStore.RETENTION_MS)
    }

    companion object {
        const val PING_INTERVAL_MS: Long = 30_000L
        const val PRUNE_INTERVAL_MS: Long = 60L * 60_000L
    }
}
