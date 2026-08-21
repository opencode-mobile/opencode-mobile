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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
            profileSaved.collect { emit(WorldSignal.ProfileSwitched) }
        }
        scope.launch {
            profileCleared.collect { emit(WorldSignal.ProfileSwitched) }
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
        emit(WorldSignal.Foreground)
        if (hasActiveConnection()) {
            stream.start()
        }
    }

    fun onBackground() {
        stopJob?.cancel()
        emit(WorldSignal.Background)
        stopJob =
            scope.launch {
                delay(stopGraceMs)
                emit(WorldSignal.BackgroundStop)
                stream.stop()
            }
    }

    private fun emit(signal: WorldSignal) {
        diagnosticLogger.info(DiagnosticLogArea.Sse, "world signal=${signal.name}")
        _signals.tryEmit(signal)
    }

    companion object {
        const val STOP_GRACE_MS = 5_000L
    }
}
