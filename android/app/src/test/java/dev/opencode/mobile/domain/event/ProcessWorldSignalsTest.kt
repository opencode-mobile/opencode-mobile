package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.connection.DeviceNetworkPath
import dev.opencode.mobile.domain.connection.DeviceNetworkPathStatus
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import dev.opencode.mobile.domain.recovery.RecordingForegroundEventStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessWorldSignalsTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun briefBackgroundDoesNotStopStreamWhenForegroundCancelsGrace() =
        runTest(testDispatcher) {
            val stream = FakeForegroundEventStream()
            val signals = processWorldSignals(stream, this)
            val collected = mutableListOf<WorldSignal>()
            val job = launch { signals.signals.collect { collected.add(it) } }
            runCurrent()

            signals.onForeground()
            runCurrent()
            assertEquals(EventStreamConnectionState.Connected, stream.connectionState.value)
            assertEquals(listOf(WorldSignal.Foreground), collected)

            signals.onBackground()
            runCurrent()
            advanceTimeBy(1_000L)
            assertEquals(EventStreamConnectionState.Connected, stream.connectionState.value)
            assertEquals(
                listOf(WorldSignal.Foreground, WorldSignal.Background),
                collected,
            )

            signals.onForeground()
            runCurrent()
            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(EventStreamConnectionState.Connected, stream.connectionState.value)
            assertEquals(
                listOf(
                    WorldSignal.Foreground,
                    WorldSignal.Background,
                    WorldSignal.Foreground,
                ),
                collected,
            )
            job.cancel()
        }

    @Test
    fun sustainedBackgroundStopsStreamAfterGrace() =
        runTest(testDispatcher) {
            val stream = FakeForegroundEventStream()
            val signals = processWorldSignals(stream, this)
            val collected = mutableListOf<WorldSignal>()
            val job = launch { signals.signals.collect { collected.add(it) } }
            runCurrent()

            signals.onForeground()
            signals.onBackground()
            runCurrent()
            assertEquals(listOf(WorldSignal.Foreground, WorldSignal.Background), collected)
            advanceTimeBy(4_999L)
            runCurrent()
            assertEquals(EventStreamConnectionState.Connected, stream.connectionState.value)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(EventStreamConnectionState.Idle, stream.connectionState.value)
            assertEquals(
                listOf(
                    WorldSignal.Foreground,
                    WorldSignal.Background,
                    WorldSignal.BackgroundStop,
                ),
                collected,
            )
            job.cancel()
        }

    @Test
    fun profileSwitchWhileOccupyingEmitsOnly() =
        runTest(testDispatcher) {
            val stream = RecordingForegroundEventStream()
            val signals =
                ProcessWorldSignals(
                    stream = stream,
                    deviceNetworkPath = ReadyPath,
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    hasActiveConnection = { true },
                    scope = this,
                    stopGraceMs = 5_000L,
                )
            val collected = mutableListOf<WorldSignal>()
            val job = launch { signals.signals.collect { collected.add(it) } }
            runCurrent()
            signals.onForeground()
            runCurrent()
            assertEquals(EventStreamConnectionState.Connected, stream.connectionState.value)

            signals.onProfileSwitched()
            runCurrent()
            assertEquals(EventStreamConnectionState.Connected, stream.connectionState.value)
            assertEquals(listOf("start"), stream.ops.toList())
            assertEquals(listOf(WorldSignal.Foreground, WorldSignal.ProfileSwitched), collected)
            job.cancel()
        }

    @Test
    fun occupancyTracksForegroundGraceAndStop() =
        runTest(testDispatcher) {
            val stream = FakeForegroundEventStream()
            val signals = processWorldSignals(stream, this)
            assertEquals(StreamOccupancy.Active, signals.occupancy.value)

            signals.onBackground()
            runCurrent()
            assertEquals(StreamOccupancy.Grace, signals.occupancy.value)

            advanceTimeBy(5_000L)
            runCurrent()
            assertEquals(StreamOccupancy.Stopped, signals.occupancy.value)

            signals.onForeground()
            runCurrent()
            assertEquals(StreamOccupancy.Active, signals.occupancy.value)
            assertEquals(EventStreamConnectionState.Connected, stream.connectionState.value)
        }

    @Test
    fun sustainedBackgroundThenForegroundRestartsStream() =
        runTest(testDispatcher) {
            val stream = RecordingForegroundEventStream()
            val signals =
                ProcessWorldSignals(
                    stream = stream,
                    deviceNetworkPath = ReadyPath,
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    hasActiveConnection = { true },
                    scope = this,
                    stopGraceMs = 5_000L,
                )
            signals.onForeground()
            runCurrent()
            signals.onBackground()
            runCurrent()
            advanceTimeBy(5_000L)
            runCurrent()
            assertEquals(EventStreamConnectionState.Idle, stream.connectionState.value)
            assertEquals(listOf("start", "stop"), stream.ops.toList())

            signals.onForeground()
            runCurrent()
            assertEquals(EventStreamConnectionState.Connected, stream.connectionState.value)
            assertEquals(listOf("start", "stop", "start"), stream.ops.toList())
        }

    @Test
    fun foregroundWithoutConnectionThenConnectionAvailableStartsStream() =
        runTest(testDispatcher) {
            val stream = RecordingForegroundEventStream()
            var connected = false
            val signals =
                ProcessWorldSignals(
                    stream = stream,
                    deviceNetworkPath = ReadyPath,
                    diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
                    hasActiveConnection = { connected },
                    scope = this,
                    stopGraceMs = 5_000L,
                )
            signals.onForeground()
            runCurrent()
            assertEquals(emptyList<String>(), stream.ops.toList())
            assertEquals(EventStreamConnectionState.Idle, stream.connectionState.value)

            signals.onConnectionAvailable()
            runCurrent()
            assertEquals(emptyList<String>(), stream.ops.toList())

            connected = true
            signals.onConnectionAvailable()
            runCurrent()
            assertEquals(listOf("start"), stream.ops.toList())
            assertEquals(EventStreamConnectionState.Connected, stream.connectionState.value)
        }

    @Test
    fun foregroundAfterBackgroundStop_beginsResumeTiming() =
        runTest(testDispatcher) {
            ResumeTiming.reset()
            val stream = FakeForegroundEventStream()
            val signals = processWorldSignals(stream, this)
            signals.onForeground()
            runCurrent()
            signals.onBackgroundStop()
            runCurrent()
            signals.onForeground()
            runCurrent()
            ResumeTiming.markCachePainted(System.currentTimeMillis())
            ResumeTiming.markConnected(System.currentTimeMillis())
            ResumeTiming.markBootstrapDone(System.currentTimeMillis())
            val line =
                DiagnosticLogBuffer.shared
                    .snapshot()
                    .entries
                    .map { it.message }
                    .lastOrNull { it.startsWith("resume:") }
            assertTrue(line != null && line.startsWith("resume: cache painted +"))
            ResumeTiming.reset()
        }

    private fun processWorldSignals(
        stream: FakeForegroundEventStream,
        scope: CoroutineScope,
    ): ProcessWorldSignals =
        ProcessWorldSignals(
            stream = stream,
            deviceNetworkPath = ReadyPath,
            diagnosticLogger = DiagnosticLogger(DiagnosticLogBuffer()),
            hasActiveConnection = { true },
            scope = scope,
            stopGraceMs = 5_000L,
        )

    private object ReadyPath : DeviceNetworkPath {
        override val status: StateFlow<DeviceNetworkPathStatus> =
            MutableStateFlow(DeviceNetworkPathStatus.Ready).asStateFlow()
    }
}
