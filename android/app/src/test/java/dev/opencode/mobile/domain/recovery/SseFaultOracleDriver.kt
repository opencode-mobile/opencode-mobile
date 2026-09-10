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
import dev.opencode.mobile.domain.message.InMemoryMessageStore
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageDomainAdapter
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.MessageSync
import dev.opencode.mobile.domain.message.MessageSyncHealth
import dev.opencode.mobile.domain.message.SessionMessageEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
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
        val profileId = AtomicReference(trace.seed.profileId)
        val sessionId = AtomicReference(trace.seed.sessionId)
        val directory = AtomicReference(trace.seed.directory)
        val directories = AtomicReference(trace.seed.directories.ifEmpty { listOf(trace.seed.directory) })
        val messages = AtomicReference(seedMessages(trace.seed.messages))
        val transcript = AtomicReference<List<SseFaultTranscriptMessage>>(emptyList())
        val catchUp = AtomicReference(false)
        val observeJobs = mutableListOf<Job>()
        val generationAtObserve = AtomicReference<Long?>(null)
        val restHold = RestHoldGate()
        val restFailures = AtomicInteger(0)

        val world: WorldSignals =
            if (trace.surface == "composed") {
                ProcessWorldSignals(
                    stream = stream,
                    deviceNetworkPath = ReadyDeviceNetworkPath,
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    hasActiveConnection = { true },
                    profileSaved = MutableSharedFlow(),
                    profileCleared = MutableSharedFlow(),
                    scope = scope.backgroundScope,
                    stopGraceMs = 5_000L,
                )
            } else {
                FakeWorldSignals()
            }

        val protocol = protocolOf(trace.serverProtocol)
        stream.fake.applyConfig(profileId.get(), protocol, directories.get())
        val sync =
            MessageSync(
                listMessages = { _, _ ->
                    restHold.awaitIfHeld()
                    if (restFailures.get() > 0) {
                        restFailures.decrementAndGet()
                        error("oracle rest failure")
                    }
                    messages.get()
                },
                engine = SessionMessageEngine(InMemoryMessageStore(MessageDomainAdapter())),
                eventStream = stream,
                liveStoreScope = LiveStoreScope(scope.backgroundScope),
                worldSignals = world,
                pollIntervalMs = 60_000L,
                connectionProfileIdProvider = { profileId.get() },
                recoveryDelay = { delay(25) },
                nowMs = { scope.testScheduler.currentTime },
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
                    if (generationAtObserve.get() == null) {
                        generationAtObserve.set(stream.snapshot.value.generation)
                    }
                }
                "unobserve" -> {
                    observeJobs.forEach { it.cancel() }
                    observeJobs.clear()
                }
                "world.foreground" -> emitWorld(world, WorldSignal.Foreground)
                "world.background" -> emitWorld(world, WorldSignal.Background)
                "world.backgroundStop" -> emitWorld(world, WorldSignal.BackgroundStop)
                "world.pathReady" -> emitWorld(world, WorldSignal.PathReady)
                "world.pathLost" -> emitWorld(world, WorldSignal.PathLost)
                "world.profileSwitched" -> emitWorld(world, WorldSignal.ProfileSwitched)
                "stream.start" -> stream.start()
                "stream.stop" -> stream.stop()
                "connectionState" -> {
                    val state = checkNotNull(step.state) { "connectionState step needs state" }
                    applyConnectionState(stream, state)
                }
                "epoch.bump" -> stream.fake.openGap(dev.opencode.mobile.domain.event.LiveGapReason.Reconnect)
                "openGap" -> {
                    val reason =
                        when (step.state) {
                            "parseFailed" -> dev.opencode.mobile.domain.event.LiveGapReason.ParseFailed
                            "leaseLost" -> dev.opencode.mobile.domain.event.LiveGapReason.LeaseLost
                            "pathLost" -> dev.opencode.mobile.domain.event.LiveGapReason.PathLost
                            "configChanged" -> dev.opencode.mobile.domain.event.LiveGapReason.ConfigChanged
                            else -> dev.opencode.mobile.domain.event.LiveGapReason.Reconnect
                        }
                    stream.fake.openGap(reason)
                }
                "cache.clear" -> sync.clearCache()
                "protocol.set" -> {
                    val next = protocolOf(checkNotNull(step.state) { "protocol.set needs state" })
                    stream.fake.applyConfig(profileId.get(), next, directories.get())
                    stream.start()
                }
                "emit" -> {
                    val event = checkNotNull(step.event) { "emit step needs event" }
                    stream.fake.emit(toOpencodeEvent(event, sessionId.get()))
                }
                "rest.setMessages" -> {
                    messages.set(seedMessages(step.messages.orEmpty()))
                }
                "rest.hold" -> restHold.hold()
                "rest.release" -> restHold.release()
                "rest.fail" -> restFailures.incrementAndGet()
                "profile.switch" -> {
                    profileId.set(checkNotNull(step.profileId) { "profile.switch needs profileId" })
                    step.sessionId?.let { sessionId.set(it) }
                    step.directory?.let { directory.set(it) }
                    step.directories?.let { directories.set(it) }
                    sync.clearCache()
                    stream.fake.applyConfig(profileId.get(), protocol, directories.get())
                    if (world is ProcessWorldSignals) {
                        world.onProfileSwitched()
                    } else {
                        emitWorld(world, WorldSignal.ProfileSwitched)
                    }
                }
                "directories.set" -> {
                    directories.set(step.directories.orEmpty())
                    stream.fake.applyConfig(profileId.get(), protocol, directories.get())
                }
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
                        generationAtObserve = generationAtObserve.get(),
                        fetchReasons = sync.recordedRestAttempts(),
                        transcript = transcript.get(),
                        catchUpChrome = catchUp.get(),
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
                    WorldSignal.BackgroundStop -> world.onBackgroundStop()
                    WorldSignal.PathReady -> world.onPathReady()
                    WorldSignal.PathLost -> world.onPathLost()
                    WorldSignal.ProfileSwitched -> world.onProfileSwitched()
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
            val source =
                if (messages.isEmpty()) {
                    listOf(SseFaultTranscriptMessage("msg-1", "Hello"))
                } else {
                    messages
                }
            return source.map {
                Message(
                    id = it.id,
                    role = MessageRole.User,
                    text = it.text,
                    parts = listOf(MessagePart.Text(text = it.text, id = "part-1")),
                )
            }
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
                if (event.type == "message.part.delta" || event.text != null) {
                    buildJsonObject {
                        put("delta", event.text ?: "")
                        put("field", event.field ?: "text")
                        put("messageID", event.messageId ?: "msg-1")
                        put("partID", event.partId ?: "part-1")
                    }
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
            SseSubscribeTargets.forProtocol(protocol, directories).map { it.key }

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
            generationAtObserve: Long?,
            fetchReasons: List<String>,
            transcript: List<SseFaultTranscriptMessage>,
            catchUpChrome: Boolean,
            liveTargets: List<String>? = null,
            openedPaths: List<String>? = null,
            openedPathCounts: Map<String, Int>? = null,
        ) {
            val snapshot = stream.snapshot.value
            val health = sync.syncHealth(sessionId, directory)
            expected.syncHealth?.let { assertEquals(it, healthName(health)) }
            expected.syncHealthReason?.let { assertEquals(it, healthReason(health)) }
            expected.catchUpChrome?.let { assertEquals(it, catchUpChrome) }
            expected.connectionState?.let {
                assertEquals(it, connectionStateName(snapshot.connectionState))
            }
            expected.occupancy?.let { assertEquals(it, occupancyName(world.occupancy.value)) }
            expected.chrome?.let { label ->
                val chrome =
                    composeLiveUpdates(
                        connectionState = snapshot.connectionState,
                        path = DeviceNetworkPathStatus.Ready,
                        occupancy = world.occupancy.value,
                        hasConnection = true,
                    )
                assertEquals(label, chrome.label.label)
            }
            expected.generationUnchanged?.let { unchanged ->
                val baseline = checkNotNull(generationAtObserve) { "generationUnchanged requires an observe step" }
                assertEquals(unchanged, snapshot.generation == baseline)
            }
            expected.gapReason?.let { wanted ->
                assertEquals(wanted, gapReasonName(snapshot.gap?.reason))
            }
            expected.restFetches?.let { wanted ->
                assertEquals(wanted.sortedBy { it.reason }, groupedFetches(fetchReasons))
            }
            expected.publishedProfileId?.let { wanted ->
                val actual = sync.publishedProfileId() ?: snapshot.config?.profileId
                assertEquals(wanted, actual)
            }
            expected.transcript?.let { assertEquals(it, transcript) }
            expected.streamOps?.let { assertEquals(it, stream.ops.toList()) }
            expected.requiredTargets?.let { assertEquals(it, snapshot.requiredTargets) }
            expected.liveTargets?.let { wanted ->
                val actual = liveTargets ?: snapshot.liveTargets
                assertEquals(wanted.toSet(), actual.toSet())
            }
            expected.openedPaths?.let { wanted ->
                val actual = checkNotNull(openedPaths)
                wanted.forEach { path ->
                    assertTrue("openedPaths missing $path in $actual", actual.any { it == path || it.startsWith(path) })
                }
            }
            expected.openedPathCounts?.let { wanted ->
                val actual = checkNotNull(openedPaths)
                wanted.forEach { (path, count) ->
                    val matches =
                        actual.count {
                            it == path || it.startsWith("$path?") || path.startsWith("$it?")
                        }
                    assertEquals("openedPathCounts for $path", count, matches)
                }
            }
        }

        fun gapReasonName(reason: dev.opencode.mobile.domain.event.LiveGapReason?): String? =
            when (reason) {
                null -> null
                dev.opencode.mobile.domain.event.LiveGapReason.OccupancyStart -> "occupancyStart"
                dev.opencode.mobile.domain.event.LiveGapReason.Reconnect -> "reconnect"
                dev.opencode.mobile.domain.event.LiveGapReason.ConfigChanged -> "configChanged"
                dev.opencode.mobile.domain.event.LiveGapReason.Stopped -> "stopped"
                dev.opencode.mobile.domain.event.LiveGapReason.PathLost -> "pathLost"
                dev.opencode.mobile.domain.event.LiveGapReason.LeaseLost -> "leaseLost"
                dev.opencode.mobile.domain.event.LiveGapReason.ParseFailed -> "parseFailed"
            }
    }
}

class RestHoldGate {
    private val held = AtomicBoolean(false)
    private val latch = AtomicReference(CompletableDeferred(Unit))

    fun hold() {
        held.set(true)
        latch.set(CompletableDeferred())
    }

    fun release() {
        held.set(false)
        latch.get().complete(Unit)
    }

    suspend fun awaitIfHeld() {
        if (held.get()) {
            latch.get().await()
        }
    }
}
