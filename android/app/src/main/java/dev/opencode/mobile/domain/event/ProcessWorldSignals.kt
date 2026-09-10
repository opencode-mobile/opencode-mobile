package dev.opencode.mobile.domain.event

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.opencode.mobile.domain.connection.DeviceNetworkPath
import dev.opencode.mobile.domain.connection.DeviceNetworkPathStatus
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-level world adapter: lifecycle, path, and profile edges.
 * Starts and stops [ForegroundEventStream]. Does not call feature REST.
 */
class ProcessWorldSignals(
    private val stream: ForegroundEventStream,
    private val deviceNetworkPath: DeviceNetworkPath,
    private val diagnosticLogger: DiagnosticLogger,
    private val hasActiveConnection: () -> Boolean,
    private val profileSaved: SharedFlow<Unit>,
    private val profileCleared: SharedFlow<Unit>,
    private val scope: CoroutineScope,
    private val stopGraceMs: Long = STOP_GRACE_MS,
) : WorldSignals,
    DefaultLifecycleObserver {
    private val _signals = MutableSharedFlow<WorldSignal>(extraBufferCapacity = 32)
    override val signals: SharedFlow<WorldSignal> = _signals.asSharedFlow()
    private val _occupancy = MutableStateFlow(StreamOccupancy.Active)
    override val occupancy: StateFlow<StreamOccupancy> = _occupancy.asStateFlow()

    private var stopJob: Job? = null
    private var registered = false
    private var lastPath: DeviceNetworkPathStatus? = null

    override fun register() {
        if (registered) return
        registered = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        startCollectors()
    }

    fun startCollectors() {
        scope.launch {
            deviceNetworkPath.updates.collect { status ->
                val previous = lastPath
                lastPath = status
                if (previous == null) return@collect
                when (status) {
                    DeviceNetworkPathStatus.Ready -> emit(WorldSignal.PathReady)
                    DeviceNetworkPathStatus.Unavailable -> emit(WorldSignal.PathLost)
                }
            }
        }
        scope.launch {
            profileSaved.collect { onProfileSwitched() }
        }
        scope.launch {
            profileCleared.collect { onProfileCleared() }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        onForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        onBackground()
    }

    fun onForeground() {
        stopJob?.cancel()
        stopJob = null
        _occupancy.value = StreamOccupancy.Active
        emit(WorldSignal.Foreground)
        if (hasActiveConnection()) {
            stream.start()
        }
    }

    fun onBackground() {
        stopJob?.cancel()
        _occupancy.value = StreamOccupancy.Grace
        emit(WorldSignal.Background)
        stopJob =
            scope.launch {
                delay(stopGraceMs)
                _occupancy.value = StreamOccupancy.Stopped
                emit(WorldSignal.BackgroundStop)
                stream.stop()
            }
    }

    fun onBackgroundStop() {
        stopJob?.cancel()
        stopJob = null
        _occupancy.value = StreamOccupancy.Stopped
        emit(WorldSignal.BackgroundStop)
        stream.stop()
    }

    fun onPathReady() {
        emit(WorldSignal.PathReady)
    }

    fun onPathLost() {
        emit(WorldSignal.PathLost)
    }

    fun onProfileSwitched() {
        emit(WorldSignal.ProfileSwitched)
        if (_occupancy.value != StreamOccupancy.Stopped && hasActiveConnection()) {
            stream.stop()
            stream.start()
        }
    }

    fun onProfileCleared() {
        emit(WorldSignal.ProfileSwitched)
        stream.stop()
    }

    private fun emit(signal: WorldSignal) {
        diagnosticLogger.info(DiagnosticLogArea.Sse, "world signal=${signal.name}")
        _signals.tryEmit(signal)
    }

    companion object {
        const val STOP_GRACE_MS = 5_000L
    }
}
