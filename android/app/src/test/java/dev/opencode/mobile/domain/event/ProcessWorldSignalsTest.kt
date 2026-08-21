package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.connection.DeviceNetworkPath
import dev.opencode.mobile.domain.connection.DeviceNetworkPathStatus
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogBuffer
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            profileSaved = MutableSharedFlow(),
            profileCleared = MutableSharedFlow(),
            scope = scope,
            stopGraceMs = 5_000L,
        )

    private object ReadyPath : DeviceNetworkPath {
        override val status: StateFlow<DeviceNetworkPathStatus> =
            MutableStateFlow(DeviceNetworkPathStatus.Ready).asStateFlow()
    }
}
