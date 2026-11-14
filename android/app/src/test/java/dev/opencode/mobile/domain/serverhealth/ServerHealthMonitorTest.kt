package dev.opencode.mobile.domain.serverhealth

import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.LiveConnectionConfig
import dev.opencode.mobile.domain.event.LiveConnectionSnapshot
import dev.opencode.mobile.domain.event.StreamOccupancy
import dev.opencode.mobile.network.ServerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerHealthMonitorTest {
    private class FakePinger : ServerHealthPinger {
        val results = ArrayDeque<ServerHealthPingResult>()
        var calls = 0

        override suspend fun ping(
            connection: ServerConnection,
            protocol: ServerProtocol,
        ): ServerHealthPingResult {
            calls += 1
            return results.removeFirstOrNull() ?: ServerHealthPingResult.Success(latencyMs = 20, pid = null, version = null)
        }
    }

    private class Harness(
        testScope: TestScope,
        backgroundScope: CoroutineScope,
        val store: InMemoryServerHealthStore = InMemoryServerHealthStore(),
        initialOccupancy: StreamOccupancy = StreamOccupancy.Active,
    ) {
        val snapshots = MutableStateFlow(LiveConnectionSnapshot())
        val occupancy = MutableStateFlow(initialOccupancy)
        val relay = ServerHealthStreamFailureRelay()
        val pinger = FakePinger()
        var activeProfile: String? = "a"
        val monitor =
            ServerHealthMonitor(
                snapshots = snapshots,
                occupancy = occupancy,
                streamFailures = relay.failures,
                activeTarget = {
                    activeProfile?.let {
                        ServerHealthPingTarget(it, ServerConnection(baseUrl = "http://192.168.1.2:4096"), ServerProtocol.V1)
                    }
                },
                pinger = pinger,
                store = store,
                scope = backgroundScope,
                clock = { testScope.testScheduler.currentTime + EPOCH },
            )

        var serial = 0

        fun emit(
            profile: String?,
            state: EventStreamConnectionState,
        ) {
            serial += 1
            snapshots.value =
                LiveConnectionSnapshot(
                    config = profile?.let { LiveConnectionConfig(it, "identity-$it", ServerProtocol.V1, emptyList()) },
                    generation = serial.toLong(),
                    connectionState = state,
                    requiredTargets = listOf("global"),
                    liveTargets = if (state == EventStreamConnectionState.Connected) listOf("global") else emptyList(),
                )
        }

        fun states(profile: String): List<String> =
            store.allEvents.filter { it.profileId == profile && it.kind == ServerHealthEventKind.State }.map { it.value }

        fun failures(profile: String): List<String> =
            store.allEvents.filter { it.profileId == profile && it.kind == ServerHealthEventKind.Failure }.map { it.value }

        fun restarts(profile: String): List<ServerHealthEvent> =
            store.allEvents.filter { it.profileId == profile && it.kind == ServerHealthEventKind.Restart }
    }

    @Test
    fun states_areRecordedOnlyWhenTheyChange() =
        runTest {
            val h = Harness(this, backgroundScope, initialOccupancy = StreamOccupancy.Grace)
            h.monitor.start()
            runCurrent()
            h.emit("a", EventStreamConnectionState.Connecting)
            runCurrent()
            h.emit("a", EventStreamConnectionState.Connected)
            runCurrent()
            h.emit("a", EventStreamConnectionState.Connected)
            runCurrent()

            assertEquals(listOf("connecting", "live"), h.states("a"))
            assertEquals(ServerHealthConnectionState.Live, h.monitor.liveStatus.value.state)
            assertEquals(1, h.monitor.liveStatus.value.liveTargets)
        }

    @Test
    fun occupancyStopped_recordsStopped_andGraceKeepsRecording() =
        runTest {
            val h = Harness(this, backgroundScope, initialOccupancy = StreamOccupancy.Grace)
            h.monitor.start()
            h.emit("a", EventStreamConnectionState.Connected)
            runCurrent()
            h.occupancy.value = StreamOccupancy.Grace
            runCurrent()
            h.emit("a", EventStreamConnectionState.Reconnecting)
            runCurrent()
            h.occupancy.value = StreamOccupancy.Stopped
            runCurrent()

            assertEquals(listOf("live", "reconnecting", "stopped"), h.states("a"))
        }

    @Test
    fun profileSwitch_stopsThePreviousProfile() =
        runTest {
            val h = Harness(this, backgroundScope, initialOccupancy = StreamOccupancy.Grace)
            h.monitor.start()
            h.emit("a", EventStreamConnectionState.Connected)
            runCurrent()
            h.activeProfile = "b"
            h.emit("b", EventStreamConnectionState.Connected)
            runCurrent()

            assertEquals(listOf("live", "stopped"), h.states("a"))
            assertEquals(listOf("live"), h.states("b"))
        }

    @Test
    fun relaunch_seedsFromTheStore_andClosesAnUnstoppedRun() =
        runTest {
            val store = InMemoryServerHealthStore()
            store.appendEvent(ServerHealthEvent.state("a", EPOCH - 10_000, ServerHealthConnectionState.Live))
            val h = Harness(this, backgroundScope, store = store, initialOccupancy = StreamOccupancy.Grace)
            h.monitor.start()
            h.emit("a", EventStreamConnectionState.Connected)
            runCurrent()

            val states = store.allEvents.filter { it.kind == ServerHealthEventKind.State }
            assertEquals(listOf("live", "stopped", "live"), states.map { it.value })
            assertEquals(EPOCH - 10_000, states[1].atMs)
        }

    @Test
    fun streamFailures_recordOncePerDropEpisode() =
        runTest {
            val h = Harness(this, backgroundScope, initialOccupancy = StreamOccupancy.Grace)
            h.monitor.start()
            h.emit("a", EventStreamConnectionState.Connected)
            runCurrent()
            h.emit("a", EventStreamConnectionState.Reconnecting)
            runCurrent()
            h.relay.onStreamFailure(ServerHealthFailureCategory.Heartbeat)
            runCurrent()
            h.relay.onStreamFailure(ServerHealthFailureCategory.Network)
            h.relay.onStreamFailure(ServerHealthFailureCategory.Timeout)
            runCurrent()
            h.emit("a", EventStreamConnectionState.Connected)
            runCurrent()
            h.relay.onStreamFailure(ServerHealthFailureCategory.Server)
            runCurrent()
            h.relay.onStreamFailure(ServerHealthFailureCategory.Network)
            runCurrent()

            assertEquals(listOf("heartbeat", "server"), h.failures("a"))
        }

    @Test
    fun restart_isDetectedFromAChangedPid() =
        runTest {
            val h = Harness(this, backgroundScope)
            h.pinger.results +=
                listOf(
                    ServerHealthPingResult.Success(20, pid = 100, version = "1.0.0"),
                    ServerHealthPingResult.Success(21, pid = 100, version = "1.0.0"),
                    ServerHealthPingResult.Success(22, pid = 200, version = "1.0.1"),
                )
            h.monitor.start()
            h.emit("a", EventStreamConnectionState.Connected)
            runCurrent()
            advanceTimeBy(ServerHealthMonitor.PING_INTERVAL_MS)
            runCurrent()
            advanceTimeBy(ServerHealthMonitor.PING_INTERVAL_MS)
            runCurrent()

            val restarts = h.restarts("a")
            assertEquals(1, restarts.size)
            assertEquals("1.0.1", restarts.single().value)
            assertEquals("1.0.0", restarts.single().detail)
            assertEquals("1.0.1", h.monitor.liveStatus.value.lastVersion)
            assertEquals(22, h.monitor.liveStatus.value.lastPingMs)
        }

    @Test
    fun restart_fallsBackToVersionWithoutPids_andComparesAgainstTheStore() =
        runTest {
            val store = InMemoryServerHealthStore()
            store.appendPing(ServerHealthPing("a", EPOCH - 60_000, 20, null, pid = null, version = "1.0.0"))
            val h = Harness(this, backgroundScope, store = store)
            h.pinger.results +=
                listOf(
                    ServerHealthPingResult.Success(20, pid = null, version = "1.0.0"),
                    ServerHealthPingResult.Success(20, pid = null, version = "1.1.0"),
                )
            h.monitor.start()
            h.emit("a", EventStreamConnectionState.Connected)
            runCurrent()
            advanceTimeBy(ServerHealthMonitor.PING_INTERVAL_MS)
            runCurrent()

            assertEquals(listOf("1.1.0"), h.restarts("a").map { it.value })
            assertEquals(listOf("1.0.0"), h.restarts("a").map { it.detail })
        }

    @Test
    fun restart_isNotRecordedForTheFirstEverPing() =
        runTest {
            val h = Harness(this, backgroundScope)
            h.pinger.results += ServerHealthPingResult.Success(20, pid = 5, version = "1.0.0")
            h.monitor.start()
            h.emit("a", EventStreamConnectionState.Connected)
            runCurrent()

            assertEquals(emptyList<ServerHealthEvent>(), h.restarts("a"))
            assertEquals(1, h.store.allPings.size)
        }

    @Test
    fun failedPing_isStoredWithItsCategory_andClearsTheCurrentPing() =
        runTest {
            val h = Harness(this, backgroundScope)
            h.pinger.results +=
                listOf(
                    ServerHealthPingResult.Success(20, pid = null, version = null),
                    ServerHealthPingResult.Failure(ServerHealthFailureCategory.Auth),
                )
            h.monitor.start()
            h.emit("a", EventStreamConnectionState.Connected)
            runCurrent()
            assertEquals(20, h.monitor.liveStatus.value.lastPingMs)
            advanceTimeBy(ServerHealthMonitor.PING_INTERVAL_MS)
            runCurrent()

            val last = h.store.allPings.last()
            assertEquals(ServerHealthFailureCategory.Auth, last.failure)
            assertNull(last.latencyMs)
            assertNull(h.monitor.liveStatus.value.lastPingMs)
        }

    @Test
    fun pingLoop_runsOnlyWhileActive() =
        runTest {
            val h = Harness(this, backgroundScope)
            h.monitor.start()
            h.emit("a", EventStreamConnectionState.Connected)
            runCurrent()
            assertEquals(1, h.pinger.calls)
            advanceTimeBy(ServerHealthMonitor.PING_INTERVAL_MS)
            runCurrent()
            assertEquals(2, h.pinger.calls)

            h.occupancy.value = StreamOccupancy.Grace
            runCurrent()
            advanceTimeBy(ServerHealthMonitor.PING_INTERVAL_MS * 3)
            runCurrent()
            assertEquals(2, h.pinger.calls)

            h.occupancy.value = StreamOccupancy.Active
            runCurrent()
            assertEquals(3, h.pinger.calls)

            h.activeProfile = null
            h.emit(null, EventStreamConnectionState.Idle)
            runCurrent()
            advanceTimeBy(ServerHealthMonitor.PING_INTERVAL_MS * 2)
            runCurrent()
            assertEquals(3, h.pinger.calls)
        }

    @Test
    fun prune_dropsDataOlderThanTheRetentionOnStart() =
        runTest {
            val store = InMemoryServerHealthStore()
            store.appendPing(ServerHealthPing("a", EPOCH - ServerHealthStore.RETENTION_MS - 1, 20, null))
            store.appendPing(ServerHealthPing("a", EPOCH - 1, 20, null))
            val h = Harness(this, backgroundScope, store = store, initialOccupancy = StreamOccupancy.Grace)
            h.monitor.start()
            runCurrent()

            assertEquals(listOf(EPOCH - 1), store.allPings.map { it.atMs })
        }

    private companion object {
        const val EPOCH = 1_000_000_000_000L
    }
}
