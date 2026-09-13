package dev.opencode.mobile.domain.sync

import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.FakeForegroundEventStream
import dev.opencode.mobile.domain.event.FakeWorldSignals
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.event.WorldSignal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSyncCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun register_startsStale_untilSuccessfulLoader() =
        runTest(dispatcher) {
            val env = env()
            val key = ProjectionKey.SessionList("/repo")
            env.coordinator.register(key) { }
            env.coordinator.visible(key, true)
            assertEquals(ProjectionState.Stale, env.coordinator.observe(key).first())

            env.coordinator.ensureFresh(key)
            runCurrent()
            assertEquals(ProjectionState.Fresh, env.coordinator.observe(key).first())
        }

    @Test
    fun ensureFresh_singleFlight() =
        runTest(dispatcher) {
            val env = env()
            val key = ProjectionKey.SessionList("/repo")
            val started = AtomicInteger(0)
            val gate = CompletableDeferred<Unit>()
            env.coordinator.register(key) {
                started.incrementAndGet()
                gate.await()
            }
            env.coordinator.visible(key, true)
            val first = async { env.coordinator.ensureFresh(key) }
            val second = async { env.coordinator.ensureFresh(key) }
            runCurrent()
            assertEquals(1, started.get())
            assertEquals(ProjectionState.Syncing, env.coordinator.observe(key).first())
            gate.complete(Unit)
            first.await()
            second.await()
            assertEquals(1, started.get())
            assertEquals(ProjectionState.Fresh, env.coordinator.observe(key).first())
        }

    @Test
    fun invalidate_doesNotCallNetwork() =
        runTest(dispatcher) {
            val env = env()
            val key = ProjectionKey.SessionList("/repo")
            val loads = AtomicInteger(0)
            env.coordinator.register(key) { loads.incrementAndGet() }
            env.coordinator.visible(key, true)
            env.coordinator.ensureFresh(key)
            runCurrent()
            assertEquals(1, loads.get())

            env.coordinator.invalidate(key)
            runCurrent()
            assertEquals(ProjectionState.Stale, env.coordinator.observe(key).first())
            assertEquals(1, loads.get())
        }

    @Test
    fun invalidateDuringLoad_reMarksStaleAndReruns() =
        runTest(dispatcher) {
            val env = env()
            val key = ProjectionKey.SessionList("/repo")
            val loads = AtomicInteger(0)
            val gate = CompletableDeferred<Unit>()
            env.coordinator.register(key) {
                loads.incrementAndGet()
                if (loads.get() == 1) gate.await()
            }
            env.coordinator.visible(key, true)
            val job = async { env.coordinator.ensureFresh(key) }
            runCurrent()
            env.coordinator.invalidate(key)
            gate.complete(Unit)
            job.await()
            runCurrent()
            assertEquals(2, loads.get())
            assertEquals(ProjectionState.Fresh, env.coordinator.observe(key).first())
        }

    @Test
    fun loaderFailure_retriesWithBackoffWhileCanCatchUp() =
        runTest(dispatcher) {
            val env = env()
            val key = ProjectionKey.Transcript("/repo", "ses-1")
            val attempts = AtomicInteger(0)
            env.coordinator.register(key) {
                val n = attempts.incrementAndGet()
                if (n < 3) error("fail $n")
            }
            env.coordinator.visible(key, true)
            val job = async { env.coordinator.ensureFresh(key) }
            runCurrent()
            assertEquals(1, attempts.get())
            advanceTimeBy(SyncRetryBackoff.delayMs(0))
            runCurrent()
            assertEquals(2, attempts.get())
            advanceTimeBy(SyncRetryBackoff.delayMs(1))
            runCurrent()
            assertEquals(3, attempts.get())
            job.await()
            assertEquals(ProjectionState.Fresh, env.coordinator.observe(key).first())
        }

    @Test
    fun ensureFresh_staysStaleWhenCannotCatchUpAfterFirstSuccess() =
        runTest(dispatcher) {
            val env = env()
            val key = ProjectionKey.SessionList("/repo")
            val loads = AtomicInteger(0)
            env.coordinator.register(key) { loads.incrementAndGet() }
            env.coordinator.visible(key, true)
            env.coordinator.ensureFresh(key)
            runCurrent()
            env.stream.setConnectionState(EventStreamConnectionState.Connecting)
            runCurrent()
            env.coordinator.ensureFresh(key)
            runCurrent()
            assertEquals(ProjectionState.Stale, env.coordinator.observe(key).first())
            assertEquals(1, loads.get())
        }

    @Test
    fun bootstrap_runsStatusesAndFocusedTranscriptThenListsThenOthers() =
        runTest(dispatcher) {
            val env = env()
            val order = CopyOnWriteArrayList<String>()

            suspend fun loader(name: String): suspend () -> Unit =
                {
                    order += "start:$name"
                    delay(10)
                    order += "end:$name"
                }

            val statuses = ProjectionKey.SessionStatuses("/repo")
            val list = ProjectionKey.SessionList("/repo")
            val transcript = ProjectionKey.Transcript("/repo", "ses-1")
            val todos = ProjectionKey.Todos("/repo", "ses-1")
            val permissions = ProjectionKey.Permissions("/repo")
            env.coordinator.register(statuses, loader("statuses"))
            env.coordinator.register(list, loader("list"))
            env.coordinator.register(transcript, loader("transcript"))
            env.coordinator.register(todos, loader("todos"))
            env.coordinator.register(permissions, loader("permissions"))
            env.coordinator.visible(statuses, true)
            env.coordinator.visible(list, true)
            env.coordinator.visible(transcript, true)
            env.coordinator.visible(todos, true)
            env.coordinator.visible(permissions, true)
            env.coordinator.markFocused(transcript)
            env.coordinator.bootstrap(SyncBootstrapReason.Manual)
            advanceTimeBy(100)
            runCurrent()
            val starts = order.filter { it.startsWith("start:") }
            assertTrue("start:statuses" in starts)
            assertTrue("start:transcript" in starts)
            assertTrue(order.indexOf("start:transcript") < order.indexOf("end:statuses"))
            assertTrue(order.indexOf("start:statuses") < order.indexOf("end:transcript"))
            assertTrue(order.indexOf("end:statuses") < order.indexOf("start:list"))
            assertTrue(order.indexOf("end:transcript") < order.indexOf("start:list"))
            assertTrue(order.indexOf("end:list") < order.indexOf("start:todos"))
            assertTrue("start:permissions" in starts)
        }

    @Test
    fun bootstrap_leavesInvisibleStale() =
        runTest(dispatcher) {
            val env = env()
            val hidden = ProjectionKey.Questions("/repo")
            val loads = AtomicInteger(0)
            env.coordinator.register(hidden) { loads.incrementAndGet() }
            env.coordinator.visible(hidden, false)
            env.coordinator.bootstrap(SyncBootstrapReason.Manual)
            runCurrent()
            assertEquals(0, loads.get())
            assertEquals(ProjectionState.Stale, env.coordinator.observe(hidden).first())
        }

    @Test
    fun foregroundQuiet_bootstrapsAfter20sOnceCanCatchUp() =
        runTest(dispatcher) {
            val env = env(startMs = 0L)
            val key = ProjectionKey.SessionList("/repo")
            val loads = AtomicInteger(0)
            env.coordinator.register(key) { loads.incrementAndGet() }
            env.coordinator.visible(key, true)
            env.coordinator.ensureFresh(key)
            runCurrent()
            assertEquals(1, loads.get())

            env.stream.setConnectionState(EventStreamConnectionState.Connecting)
            runCurrent()
            advanceTimeBy(DefaultSyncCoordinator.FOREGROUND_QUIET_MS)
            env.world.emit(WorldSignal.Foreground)
            runCurrent()
            assertEquals(1, loads.get())

            env.stream.setConnectionState(EventStreamConnectionState.Connected)
            runCurrent()
            assertEquals(2, loads.get())
        }

    @Test
    fun briefForeground_within20s_doesNotBootstrap() =
        runTest(dispatcher) {
            val env = env()
            val key = ProjectionKey.SessionList("/repo")
            val loads = AtomicInteger(0)
            env.coordinator.register(key) { loads.incrementAndGet() }
            env.coordinator.visible(key, true)
            env.coordinator.ensureFresh(key)
            runCurrent()
            env.world.emit(WorldSignal.Background)
            env.world.emit(WorldSignal.Foreground)
            runCurrent()
            assertEquals(1, loads.get())
        }

    @Test
    fun serverConnectedAndStreamReconnected_coalesceIntoOneBootstrap() =
        runTest(dispatcher) {
            val env = env()
            val key = ProjectionKey.SessionList("/repo")
            val loads = AtomicInteger(0)
            env.coordinator.register(key) { loads.incrementAndGet() }
            env.coordinator.visible(key, true)
            env.coordinator.ensureFresh(key)
            runCurrent()
            assertEquals(1, loads.get())

            env.coordinator.invalidate(key)
            env.stream.emit(OpencodeEvent(type = "server.connected"))
            env.stream.emit(OpencodeEvent(type = "stream.reconnected"))
            advanceTimeBy(DefaultSyncCoordinator.CONNECT_COALESCE_MS)
            runCurrent()
            assertEquals(2, loads.get())
        }

    @Test
    fun leavingConnected_invalidatesAll() =
        runTest(dispatcher) {
            val env = env()
            val key = ProjectionKey.SessionList("/repo")
            env.coordinator.register(key) { }
            env.coordinator.visible(key, true)
            env.coordinator.ensureFresh(key)
            runCurrent()
            env.stream.setConnectionState(EventStreamConnectionState.Reconnecting)
            runCurrent()
            assertEquals(ProjectionState.Stale, env.coordinator.observe(key).first())
        }

    @Test
    fun beginProfileSwitch_discardsInFlightLoaderFromOldGeneration() =
        runTest(dispatcher) {
            val env = env()
            val key = ProjectionKey.SessionList("/repo")
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var committed = false
            env.coordinator.register(key) {
                started.complete(Unit)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    finish.await()
                    committed = true
                }
            }
            env.coordinator.visible(key, true)
            val job = async { env.coordinator.ensureFresh(key) }
            runCurrent()
            started.await()
            env.coordinator.beginProfileSwitch(2)
            job.cancel()
            finish.complete(Unit)
            runCurrent()
            assertTrue(committed)
            assertEquals(ProjectionState.Stale, env.coordinator.observe(key).first())
            assertEquals(2, env.coordinator.profileGeneration)
        }

    private fun TestScope.env(startMs: Long = dispatcher.scheduler.currentTime): Env {
        val stream = FakeForegroundEventStream()
        stream.setConnectionState(EventStreamConnectionState.Connected)
        val world = FakeWorldSignals()
        val coordinator =
            DefaultSyncCoordinator(
                liveConnection = stream,
                worldSignals = world,
                scope = backgroundScope,
                nowMs = { dispatcher.scheduler.currentTime },
                wait = { delay(it) },
            )
        runCurrent()
        return Env(coordinator, stream, world)
    }

    private class Env(
        val coordinator: DefaultSyncCoordinator,
        val stream: FakeForegroundEventStream,
        val world: FakeWorldSignals,
    )
}
