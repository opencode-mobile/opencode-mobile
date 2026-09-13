package dev.opencode.mobile.domain.sync

import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.LiveConnection
import dev.opencode.mobile.domain.event.WorldSignal
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.event.canCatchUp
import dev.opencode.mobile.domain.live.LiveStoreScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSyncCoordinator(
    private val liveConnection: LiveConnection,
    private val worldSignals: WorldSignals,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : SyncCoordinator {
    @Inject
    constructor(
        liveConnection: LiveConnection,
        worldSignals: WorldSignals,
        liveStoreScope: LiveStoreScope,
    ) : this(
        liveConnection = liveConnection,
        worldSignals = worldSignals,
        scope = liveStoreScope.coroutineScope,
    )

    private val mutex = Mutex()
    private val entries = ConcurrentHashMap<ProjectionKey, Entry>()
    private val registrationIds = AtomicLong(0)
    private var focused: ProjectionKey.Transcript? = null
    private var lastActivityMs = nowMs()
    private var pendingForegroundQuiet = false
    private var lastCatchUp = false
    private var lastConnected = false
    private var coalesceJob: Job? = null
    private var bootstrapJob: Job? = null
    private var bootstrapQueued: SyncBootstrapReason? = null

    init {
        scope.launch {
            lastConnected =
                liveConnection.connectionState.value == EventStreamConnectionState.Connected
            liveConnection.connectionState.collect { state ->
                val connected = state == EventStreamConnectionState.Connected
                if (lastConnected && !connected) {
                    invalidate(null)
                }
                lastConnected = connected
                maybeResumeCatchUp()
            }
        }
        scope.launch {
            worldSignals.signals.collect { signal ->
                when (signal) {
                    WorldSignal.BackgroundStop,
                    WorldSignal.PathLost,
                    -> invalidate(null)
                    WorldSignal.ProfileSwitched -> {
                        markFocused(null)
                        dropCachedState()
                        invalidate(null)
                    }
                    WorldSignal.PathReady -> bootstrap(SyncBootstrapReason.ServerConnected)
                    WorldSignal.Foreground -> {
                        onForeground()
                        if (entries.values.any { it.visible && it.state.value != ProjectionState.Fresh }) {
                            bootstrap(SyncBootstrapReason.ServerConnected)
                        }
                    }
                    WorldSignal.Background -> Unit
                }
            }
        }
        scope.launch {
            liveConnection.events.collect { event ->
                when (event.type) {
                    "sse.parse.failed" -> {
                        invalidate(null)
                        scheduleConnectedBootstrap()
                    }
                    "server.connected",
                    "stream.reconnected",
                    -> scheduleConnectedBootstrap()
                }
            }
        }
        scope.launch {
            liveConnection.snapshot.collect {
                maybeResumeCatchUp()
            }
        }
        scope.launch {
            worldSignals.occupancy.collect {
                maybeResumeCatchUp()
            }
        }
    }

    override fun register(
        key: ProjectionKey,
        loader: suspend () -> Unit,
    ): SyncRegistration {
        val id = registrationIds.incrementAndGet()
        val entry = entries.getOrPut(key) { Entry(key) }
        entry.loader = loader
        entry.handles.add(id)
        return Handle(id, key)
    }

    override fun unregister(registration: SyncRegistration) {
        val handle = registration as? Handle ?: return
        val entry = entries[handle.key] ?: return
        entry.handles.remove(handle.id)
        if (entry.handles.isEmpty()) {
            entry.flight?.cancel()
            entries.remove(handle.key, entry)
        }
    }

    override fun observe(key: ProjectionKey): Flow<ProjectionState> =
        entries.getOrPut(key) { Entry(key) }.state

    override suspend fun ensureFresh(key: ProjectionKey) {
        while (true) {
            val entry = entries[key] ?: return
            when (entry.state.value) {
                ProjectionState.Fresh -> return
                ProjectionState.Syncing -> entry.awaitFlight()
                ProjectionState.Stale -> {
                    val serial = entry.staleSerial.get()
                    if (!startFlight(entry, forceFirst = !entry.hasSucceededOnce)) {
                        return
                    }
                    entry.awaitFlight()
                    when (entry.state.value) {
                        ProjectionState.Fresh -> return
                        ProjectionState.Syncing -> Unit
                        ProjectionState.Stale -> {
                            if (entry.staleSerial.get() == serial) return
                        }
                    }
                }
            }
        }
    }

    override fun invalidate(key: ProjectionKey?) {
        val targets =
            if (key == null) {
                entries.values.toList()
            } else {
                listOfNotNull(entries[key])
            }
        for (entry in targets) {
            entry.staleSerial.incrementAndGet()
            if (entry.state.value != ProjectionState.Syncing) {
                entry.state.value = ProjectionState.Stale
            }
        }
    }

    override fun bootstrap(reason: SyncBootstrapReason) {
        scope.launch { runBootstrap(reason) }
    }

    override fun markFocused(sessionKey: ProjectionKey.Transcript?) {
        focused = sessionKey
    }

    override fun visible(
        key: ProjectionKey,
        visible: Boolean,
    ) {
        val entry = entries.getOrPut(key) { Entry(key) }
        entry.visible = visible
    }

    private fun onForeground() {
        val quiet = nowMs() - lastActivityMs >= FOREGROUND_QUIET_MS
        if (!quiet) return
        pendingForegroundQuiet = true
        if (canCatchUpNow()) {
            bootstrap(SyncBootstrapReason.ForegroundQuiet)
        }
    }

    private fun scheduleConnectedBootstrap() {
        coalesceJob?.cancel()
        coalesceJob =
            scope.launch {
                wait(CONNECT_COALESCE_MS)
                bootstrap(SyncBootstrapReason.ServerConnected)
            }
    }

    private fun maybeResumeCatchUp() {
        val catchUp = canCatchUpNow()
        val rose = catchUp && !lastCatchUp
        lastCatchUp = catchUp
        if (!rose) return
        if (pendingForegroundQuiet) {
            bootstrap(SyncBootstrapReason.ForegroundQuiet)
            return
        }
        if (entries.values.any { it.visible && it.state.value != ProjectionState.Fresh }) {
            bootstrap(SyncBootstrapReason.ServerConnected)
        }
    }

    private fun dropCachedState() {
        for (entry in entries.values) {
            entry.hasSucceededOnce = false
            entry.staleSerial.incrementAndGet()
            if (entry.state.value != ProjectionState.Syncing) {
                entry.state.value = ProjectionState.Stale
            }
        }
    }

    private suspend fun runBootstrap(reason: SyncBootstrapReason) {
        mutex.withLock {
            if (bootstrapJob?.isActive == true) {
                bootstrapQueued = reason
                return
            }
            bootstrapJob = scope.launch { executeBootstrap(reason) }
        }
        bootstrapJob?.join()
    }

    private suspend fun executeBootstrap(reason: SyncBootstrapReason) {
        if (reason != SyncBootstrapReason.Manual && !canCatchUpNow()) {
            if (reason == SyncBootstrapReason.ForegroundQuiet) {
                pendingForegroundQuiet = true
            }
            mutex.withLock { bootstrapJob = null }
            return
        }
        if (reason == SyncBootstrapReason.ForegroundQuiet) {
            pendingForegroundQuiet = false
        }
        lastActivityMs = nowMs()
        val snapshot = entries.values.toList()
        val visible = snapshot.filter { it.visible && it.loader != null }
        val directories = visible.map { it.key.directory }.distinct()
        coroutineScope {
            directories
                .map { directory ->
                    async { runVisibleLoader(ProjectionKey.SessionStatuses(directory)) }
                }.forEach { it.await() }
        }
        coroutineScope {
            visible
                .map { it.key }
                .filterIsInstance<ProjectionKey.SessionList>()
                .distinct()
                .map { key -> async { runVisibleLoader(key) } }
                .forEach { it.await() }
        }
        focused?.let { runVisibleLoader(it) }
        val others =
            visible.map { it.key }.filter { key ->
                key !is ProjectionKey.SessionStatuses &&
                    key !is ProjectionKey.SessionList &&
                    key != focused
            }
        coroutineScope {
            others
                .map { key -> async { runVisibleLoader(key) } }
                .forEach { it.await() }
        }
        maybeFinishBootstrap()
    }

    private suspend fun maybeFinishBootstrap() {
        val queued =
            mutex.withLock {
                val next = bootstrapQueued
                bootstrapQueued = null
                bootstrapJob = null
                next
            }
        if (queued != null) {
            runBootstrap(queued)
        }
    }

    private suspend fun runVisibleLoader(key: ProjectionKey) {
        val entry = entries[key] ?: return
        if (!entry.visible || entry.loader == null) return
        if (entry.state.value == ProjectionState.Fresh) return
        startFlight(entry, forceFirst = false)
        entry.awaitFlight()
    }

    private fun startFlight(
        entry: Entry,
        forceFirst: Boolean,
    ): Boolean {
        if (entry.state.value == ProjectionState.Syncing) return true
        val catchUp = canCatchUpNow()
        if (!catchUp && !(forceFirst && !entry.hasSucceededOnce)) {
            return false
        }
        val serial = entry.staleSerial.get()
        entry.state.value = ProjectionState.Syncing
        val done = CompletableDeferred<Unit>()
        entry.flightDone = done
        entry.flight =
            scope.launch {
                try {
                    runLoaderAttempts(entry, serial)
                } finally {
                    done.complete(Unit)
                    if (entry.flightDone === done) {
                        entry.flight = null
                        entry.flightDone = null
                    }
                }
            }
        return true
    }

    private suspend fun runLoaderAttempts(
        entry: Entry,
        serial: Long,
    ) {
        val loader =
            entry.loader ?: run {
                entry.state.value = ProjectionState.Stale
                return
            }
        var attempt = 0
        while (true) {
            if (entry.staleSerial.get() != serial) {
                entry.state.value = ProjectionState.Stale
                return
            }
            try {
                loader()
                lastActivityMs = nowMs()
                entry.hasSucceededOnce = true
                if (entry.staleSerial.get() == serial) {
                    entry.state.value = ProjectionState.Fresh
                } else {
                    entry.state.value = ProjectionState.Stale
                }
                return
            } catch (cancelled: CancellationException) {
                if (entry.state.value == ProjectionState.Syncing) {
                    entry.state.value = ProjectionState.Stale
                }
                throw cancelled
            } catch (_: Throwable) {
                entry.state.value = ProjectionState.Stale
                if (entry.staleSerial.get() != serial) return
                if (!canCatchUpNow()) return
                wait(SyncRetryBackoff.delayMs(attempt))
                if (entry.staleSerial.get() != serial) {
                    entry.state.value = ProjectionState.Stale
                    return
                }
                entry.state.value = ProjectionState.Syncing
                attempt += 1
            }
        }
    }

    private fun canCatchUpNow(): Boolean =
        canCatchUp(worldSignals.occupancy.value, liveConnection.snapshot.value)

    private inner class Handle(
        val id: Long,
        val key: ProjectionKey,
    ) : SyncRegistration {
        override fun close() {
            unregister(this)
        }
    }

    private class Entry(
        val key: ProjectionKey,
    ) {
        val state = MutableStateFlow(ProjectionState.Stale)
        val handles = ConcurrentHashMap.newKeySet<Long>()

        @Volatile
        var loader: (suspend () -> Unit)? = null

        @Volatile
        var visible: Boolean = false

        @Volatile
        var hasSucceededOnce: Boolean = false

        val staleSerial = AtomicLong(0L)

        @Volatile
        var flight: Job? = null

        @Volatile
        var flightDone: CompletableDeferred<Unit>? = null

        suspend fun awaitFlight() {
            while (true) {
                val done = flightDone ?: return
                done.await()
                if (flightDone == null || flightDone === done) return
            }
        }
    }

    companion object {
        const val FOREGROUND_QUIET_MS = 20_000L
        const val CONNECT_COALESCE_MS = 200L
    }
}
