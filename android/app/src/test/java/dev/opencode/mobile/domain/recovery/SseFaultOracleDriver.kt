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
import dev.opencode.mobile.domain.event.SseSubscribeTargets
import dev.opencode.mobile.domain.event.StreamOccupancy
import dev.opencode.mobile.domain.event.WorldSignal
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.event.composeLiveUpdates
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.domain.message.InMemoryMessageStore
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageDomainAdapter
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.MessageSync
import dev.opencode.mobile.domain.message.SessionMessageEngine
import dev.opencode.mobile.domain.sync.ProjectionKey
import dev.opencode.mobile.domain.sync.testSyncCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
        if (trace.steps.any { it.op == "target.open" || it.op == "target.close" }) {
            stream.fake.modelLeasesExplicitly()
        }
        stream.fake.applyConfig(profileId.get(), protocol, directories.get())
        val coordinator =
            testSyncCoordinator(
                eventStream = stream,
                worldSignals = world,
                scope = scope.backgroundScope,
                nowMs = { scope.testScheduler.currentTime },
                wait = { ms ->
                if (ms > 200L) delay(25) else Unit
            },
            )
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
                syncCoordinator = coordinator,
                connectionProfileIdProvider = { profileId.get() },
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
                            coordinator.markFocused(
                                ProjectionKey.Transcript(observedDirectory, observedSession),
                            )
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
                }
                "unobserve" -> {
                    coordinator.markFocused(null)
                    observeJobs.forEach { it.cancel() }
                    observeJobs.clear()
                    transcript.set(emptyList())
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
                "openGap",
                "epoch.bump",
                -> {
                    coordinator.invalidate(null)
                    coordinator.bootstrap(dev.opencode.mobile.domain.sync.SyncBootstrapReason.ServerConnected)
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
                "target.open" -> stream.fake.openTarget(checkNotNull(step.target) { "target.open needs target" })
                "target.close" -> stream.fake.closeTarget(checkNotNull(step.target) { "target.close needs target" })
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
        ): List<String> = SseSubscribeTargets.forProtocol(protocol, directories).map { it.key }

        fun assertSnapshot(
            expected: SseFaultSnapshot,
            sync: MessageSync,
            sessionId: String,
            directory: String,
            stream: RecordingForegroundEventStream,
            world: WorldSignals,
            transcript: List<SseFaultTranscriptMessage>,
            catchUpChrome: Boolean,
            liveTargets: List<String>? = null,
            publishedEventTypes: List<String>? = null,
        ) {
            val snapshot = stream.snapshot.value
            expected.catchUpChrome?.let { assertEquals(it, catchUpChrome) }
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
            expected.publishedProfileId?.let { wanted ->
                val actual = sync.publishedProfileId() ?: snapshot.config?.profileId
                assertEquals(wanted, actual)
            }
            expected.transcript?.let { assertEquals(it, transcript) }
            expected.requiredTargets?.let { assertEquals(it, snapshot.requiredTargets) }
            expected.liveTargets?.let { wanted ->
                val actual = liveTargets ?: snapshot.liveTargets
                assertEquals(wanted.toSet(), actual.toSet())
            }
            expected.unpublishedEventTypes?.let { forbidden ->
                val actual = publishedEventTypes.orEmpty()
                forbidden.forEach { type ->
                    assertTrue("$type was published in $actual", type !in actual)
                }
            }
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
