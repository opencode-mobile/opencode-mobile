package dev.opencode.mobile.domain.recovery

import dev.opencode.mobile.domain.connection.DeviceNetworkPathStatus
import dev.opencode.mobile.domain.connection.ReadyDeviceNetworkPath
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.FakeWorldSignals
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.event.ProcessWorldSignals
import dev.opencode.mobile.domain.event.StreamOccupancy
import dev.opencode.mobile.domain.event.WorldSignal
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.event.composeLiveUpdates
import dev.opencode.mobile.domain.event.SseSubscribeTargets
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.domain.message.ConnectionEpoch
import dev.opencode.mobile.domain.message.InMemoryMessageStore
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageDomainAdapter
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.MessageSync
import dev.opencode.mobile.domain.message.MessageSyncHealth
import dev.opencode.mobile.domain.message.SessionMessageEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class SseFaultOracleDriver(
    private val scope: TestScope,
) {
    suspend fun run(trace: SseFaultTrace) {
        if (trace.surface == "transport") {
            SseFaultOracleTransportDriver().run(trace)
            return
        }
        runRecovery(trace)
    }

    private suspend fun runRecovery(trace: SseFaultTrace) {
        val stream = RecordingForegroundEventStream()
        val epoch = ConnectionEpoch()
        val profileId = AtomicReference(trace.seed.profileId)
        val sessionId = AtomicReference(trace.seed.sessionId)
        val directory = AtomicReference(trace.seed.directory)
        val directories = AtomicReference(trace.seed.directories.ifEmpty { listOf(trace.seed.directory) })
        val messages = AtomicReference(seedMessages(trace.seed.messages))
        val fetchReasons = CopyOnWriteArrayList<String>()
        var pendingReason = "initial-subscribe"
        val transcript = AtomicReference<List<SseFaultTranscriptMessage>>(emptyList())
        val catchUp = AtomicReference(false)
        val observeJobs = mutableListOf<Job>()
        val epochAtObserve = AtomicReference<Long?>(null)

        val world: WorldSignals =
            if (trace.surface == "composed") {
                ProcessWorldSignals(
                    stream = stream,
                    deviceNetworkPath = ReadyDeviceNetworkPath,
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    hasActiveConnection = { true },
                    profileSaved = MutableSharedFlow(),
                    profileCleared = MutableSharedFlow(),
                    scope = scope,
                    stopGraceMs = 5_000L,
                )
            } else {
                FakeWorldSignals()
            }

        val sync =
            MessageSync(
                listMessages = { _, _ ->
                    fetchReasons += pendingReason
                    messages.get()
                },
                engine = SessionMessageEngine(InMemoryMessageStore(MessageDomainAdapter())),
                eventStream = stream,
                liveStoreScope = LiveStoreScope(CoroutineScope(SupervisorJob() + scope.coroutineContext)),
                worldSignals = world,
                pollIntervalMs = 60_000L,
                connectionProfileIdProvider = { profileId.get() },
                recoveryDelay = { delay(1) },
                nowMs = { scope.testScheduler.currentTime },
                connectionEpoch = epoch,
            )

        applyConnectionState(stream, trace.seed.connectionState)
        seedOccupancy(world, trace.seed.occupancy)
        scope.runCurrent()

        var lastT = 0L
        for (step in trace.steps) {
            val delta = step.t - lastT
            if (delta > 0) {
                scope.advanceTimeBy(delta)
                scope.runCurrent()
            }
            lastT = step.t
            when (step.op) {
                "observe" -> {
                    pendingReason = "initial-subscribe"
                    val observedSession = sessionId.get()
                    val observedDirectory = directory.get()
                    observeJobs +=
                        scope.launch {
                            sync.observeMessages(observedSession, observedDirectory).collect { update ->
                                if (update is MessageListUpdate.Messages) {
                                    transcript.set(
                                        update.messages.map {
                                            SseFaultTranscriptMessage(it.id, it.text)
                                        },
                                    )
                                }
                            }
                        }
                    observeJobs +=
                        scope.launch {
                            sync.observeCatchUpChrome(observedSession, observedDirectory).collect {
                                catchUp.set(it)
                            }
                        }
                    scope.advanceTimeBy(1)
                    scope.runCurrent()
                    if (epochAtObserve.get() == null) {
                        epochAtObserve.set(epoch.current())
                    }
                }
                "unobserve" -> {
                    observeJobs.forEach { it.cancel() }
                    observeJobs.clear()
                }
                "world.foreground" -> {
                    pendingReason = "background-return"
                    emitWorld(world, WorldSignal.Foreground)
                }
                "world.background" -> emitWorld(world, WorldSignal.Background)
                "world.backgroundStop" -> emitWorld(world, WorldSignal.BackgroundStop)
                "world.pathReady" -> {
                    pendingReason = "path-ready"
                    emitWorld(world, WorldSignal.PathReady)
                }
                "world.pathLost" -> emitWorld(world, WorldSignal.PathLost)
                "world.profileSwitched" -> emitWorld(world, WorldSignal.ProfileSwitched)
                "stream.start" -> stream.start()
                "stream.stop" -> stream.stop()
                "connectionState" -> {
                    val state = checkNotNull(step.state) { "connectionState step needs state" }
                    if (state == "connecting") {
                        pendingReason = "stream.reconnected"
                    }
                    applyConnectionState(stream, state)
                }
                "epoch.bump" -> epoch.bump()
                "emit" -> {
                    val event = checkNotNull(step.event) { "emit step needs event" }
                    if (event.type == "stream.reconnected") {
                        pendingReason = "stream.reconnected"
                    } else if (event.type == "server.connected") {
                        pendingReason = "server.connected"
                    }
                    stream.fake.emit(toOpencodeEvent(event, sessionId.get()))
                }
                "rest.setMessages" -> {
                    messages.set(seedMessages(step.messages.orEmpty()))
                }
                "rest.hold", "rest.release" -> Unit
                "profile.switch" -> {
                    profileId.set(checkNotNull(step.profileId) { "profile.switch needs profileId" })
                    step.sessionId?.let { sessionId.set(it) }
                    step.directory?.let { directory.set(it) }
                    step.directories?.let { directories.set(it) }
                    sync.clearCache()
                    emitWorld(world, WorldSignal.ProfileSwitched)
                }
                "directories.set" -> directories.set(step.directories.orEmpty())
                "snapshot" -> {
                    scope.advanceTimeBy(1)
                    scope.runCurrent()
                    val name = checkNotNull(step.name) { "snapshot step needs name" }
                    val expected =
                        checkNotNull(trace.expected.snapshots[name]) {
                            "Missing expected snapshot $name"
                        }
                    assertSnapshot(
                        expected = expected,
                        sync = sync,
                        sessionId = sessionId.get(),
                        directory = directory.get(),
                        stream = stream,
                        world = world,
                        epoch = epoch,
                        epochAtObserve = epochAtObserve.get(),
                        fetchReasons = fetchReasons.toList(),
                        transcript = transcript.get(),
                        catchUpChrome = catchUp.get(),
                        profileId = profileId.get(),
                        protocol = protocolOf(trace.serverProtocol),
                        directories = directories.get(),
                    )
                }
                else -> error("Unsupported recovery op ${step.op}")
            }
            scope.runCurrent()
        }
        observeJobs.forEach { it.cancel() }
    }

    private fun emitWorld(
        world: WorldSignals,
        signal: WorldSignal,
    ) {
        when (world) {
            is ProcessWorldSignals ->
                when (signal) {
                    WorldSignal.Foreground -> world.onForeground()
                    WorldSignal.Background -> world.onBackground()
                    WorldSignal.BackgroundStop,
                    WorldSignal.PathReady,
                    WorldSignal.PathLost,
                    WorldSignal.ProfileSwitched,
                    -> Unit
                }
            is FakeWorldSignals -> world.emit(signal)
            else -> error("Unsupported world adapter ${world::class.simpleName}")
        }
    }

    private fun seedOccupancy(
        world: WorldSignals,
        occupancy: String?,
    ) {
        if (world !is FakeWorldSignals) return
        when (occupancy) {
            "grace" -> world.emit(WorldSignal.Background)
            "stopped" -> world.emit(WorldSignal.BackgroundStop)
            else -> Unit
        }
    }

    companion object {
        fun seedMessages(messages: List<SseFaultTranscriptMessage>): List<Message> {
            if (messages.isEmpty()) {
                return listOf(Message(id = "msg-1", role = MessageRole.User, text = "Hello"))
            }
            return messages.map { Message(id = it.id, role = MessageRole.User, text = it.text) }
        }

        fun protocolOf(value: String): ServerProtocol =
            when (value) {
                "v1" -> ServerProtocol.V1
                "v2" -> ServerProtocol.V2
                else -> error("Unknown protocol $value")
            }

        fun applyConnectionState(
            stream: RecordingForegroundEventStream,
            state: String?,
        ) {
            if (state == null) return
            stream.fake.setConnectionState(connectionStateOf(state))
        }

        fun connectionStateOf(value: String): EventStreamConnectionState =
            when (value) {
                "idle" -> EventStreamConnectionState.Idle
                "connecting" -> EventStreamConnectionState.Connecting
                "connected" -> EventStreamConnectionState.Connected
                "disconnected" -> EventStreamConnectionState.Disconnected
                "reconnecting" -> EventStreamConnectionState.Reconnecting
                else -> error("Unknown connection state $value")
            }

        fun connectionStateName(state: EventStreamConnectionState): String =
            when (state) {
                EventStreamConnectionState.Idle -> "idle"
                EventStreamConnectionState.Connecting -> "connecting"
                EventStreamConnectionState.Connected -> "connected"
                EventStreamConnectionState.Disconnected -> "disconnected"
                EventStreamConnectionState.Reconnecting -> "reconnecting"
            }

        fun occupancyName(occupancy: StreamOccupancy): String =
            when (occupancy) {
                StreamOccupancy.Active -> "active"
                StreamOccupancy.Grace -> "grace"
                StreamOccupancy.Stopped -> "stopped"
            }

        fun healthName(health: MessageSyncHealth): String =
            when (health) {
                MessageSyncHealth.Healthy -> "healthy"
                is MessageSyncHealth.Stale -> "stale"
                is MessageSyncHealth.Recovering -> "recovering"
            }

        fun healthReason(health: MessageSyncHealth): String? =
            when (health) {
                MessageSyncHealth.Healthy -> null
                is MessageSyncHealth.Stale -> reasonName(health.reason.name)
                is MessageSyncHealth.Recovering -> reasonName(health.reason.name)
            }

        private fun reasonName(enumName: String): String =
            when (enumName) {
                "Overflow" -> "overflow"
                "Reconnect" -> "reconnect"
                "ReloadFailed" -> "reloadFailed"
                "RacedAuthority" -> "racedAuthority"
                "TransportUnavailable" -> "transportUnavailable"
                else -> enumName.replaceFirstChar { it.lowercase() }
            }

        fun toOpencodeEvent(
            event: SseFaultEvent,
            defaultSessionId: String,
        ): OpencodeEvent {
            val sessionId = event.sessionId ?: defaultSessionId
            val properties =
                if (event.text != null) {
                    buildJsonObject { put("delta", event.text) }
                } else {
                    null
                }
            return OpencodeEvent(
                type = event.type,
                sessionId = sessionId,
                rawProperties = properties,
            )
        }

        fun requiredTargets(
            protocol: ServerProtocol,
            directories: List<String>,
        ): List<String> =
            SseSubscribeTargets.forProtocol(protocol, directories).map { target ->
                target.directory?.let { "${target.path}:$it" } ?: target.path
            }

        fun groupedFetches(reasons: List<String>): List<SseFaultRestFetch> =
            reasons
                .groupingBy { it }
                .eachCount()
                .map { SseFaultRestFetch(it.key, it.value) }
                .sortedBy { it.reason }

        fun assertSnapshot(
            expected: SseFaultSnapshot,
            sync: MessageSync,
            sessionId: String,
            directory: String,
            stream: RecordingForegroundEventStream,
            world: WorldSignals,
            epoch: ConnectionEpoch,
            epochAtObserve: Long?,
            fetchReasons: List<String>,
            transcript: List<SseFaultTranscriptMessage>,
            catchUpChrome: Boolean,
            profileId: String,
            protocol: ServerProtocol,
            directories: List<String>,
            liveTargets: List<String>? = null,
            openedPaths: List<String>? = null,
        ) {
            val health = sync.syncHealth(sessionId, directory)
            expected.syncHealth?.let { assertEquals(it, healthName(health)) }
            expected.syncHealthReason?.let { assertEquals(it, healthReason(health)) }
            expected.catchUpChrome?.let { assertEquals(it, catchUpChrome) }
            expected.connectionState?.let {
                assertEquals(it, connectionStateName(stream.connectionState.value))
            }
            expected.occupancy?.let { assertEquals(it, occupancyName(world.occupancy.value)) }
            expected.chrome?.let { label ->
                val chrome =
                    composeLiveUpdates(
                        connectionState = stream.connectionState.value,
                        path = DeviceNetworkPathStatus.Ready,
                        occupancy = world.occupancy.value,
                        hasConnection = true,
                    )
                assertEquals(label, chrome.label.label)
            }
            expected.epochUnchanged?.let { unchanged ->
                val baseline = checkNotNull(epochAtObserve) { "epochUnchanged requires an observe step" }
                assertEquals(unchanged, epoch.current() == baseline)
            }
            expected.restFetches?.let { wanted ->
                assertEquals(wanted.sortedBy { it.reason }, groupedFetches(fetchReasons))
            }
            expected.publishedProfileId?.let { assertEquals(it, profileId) }
            expected.transcript?.let { assertEquals(it, transcript) }
            expected.streamOps?.let { assertEquals(it, stream.ops.toList()) }
            expected.requiredTargets?.let {
                assertEquals(it, requiredTargets(protocol, directories))
            }
            expected.liveTargets?.let { wanted ->
                assertEquals(wanted.toSet(), checkNotNull(liveTargets).toSet())
            }
            expected.openedPaths?.let { wanted ->
                val actual = checkNotNull(openedPaths)
                wanted.forEach { path ->
                    assertTrue("openedPaths missing $path in $actual", actual.any { it == path || it.startsWith(path) })
                }
            }
        }
    }
}
