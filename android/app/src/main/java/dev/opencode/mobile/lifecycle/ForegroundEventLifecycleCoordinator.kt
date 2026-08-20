package dev.opencode.mobile.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.opencode.mobile.domain.event.ForegroundRestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Starts/stops foreground SSE + restore with process lifecycle.
 *
 * Android photo/document pickers briefly move the process to stopped while the
 * system picker activity is up. Stopping SSE immediately forces a reconnect
 * (and ConnectionEpoch bump) on return; a short grace period keeps the stream
 * alive across those transient overlays, matching iOS which only stops on
 * `.background` and ignores `.inactive`.
 */
class ForegroundEventLifecycleCoordinator(
    private val foregroundRestore: ForegroundRestore,
    private val hasActiveConnection: () -> Boolean,
    private val scope: CoroutineScope,
    private val stopGraceMs: Long = STOP_GRACE_MS,
) : DefaultLifecycleObserver {
    private var stopJob: Job? = null

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        stopJob?.cancel()
        stopJob = null
        if (hasActiveConnection()) {
            foregroundRestore.restore()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        stopJob?.cancel()
        foregroundRestore.noteBackground()
        stopJob =
            scope.launch {
                delay(stopGraceMs)
                foregroundRestore.noteBackgroundStop()
            }
    }

    companion object {
        /** Grace before tearing down SSE after process onStop. */
        const val STOP_GRACE_MS = 5_000L
    }
}
