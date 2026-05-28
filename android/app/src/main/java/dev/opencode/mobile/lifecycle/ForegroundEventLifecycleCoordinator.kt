package dev.opencode.mobile.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.network.ActiveConnectionManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForegroundEventLifecycleCoordinator
    @Inject
    constructor(
        private val foregroundEventStream: ForegroundEventStream,
        private val activeConnectionManager: ActiveConnectionManager,
    ) : DefaultLifecycleObserver {
        fun register() {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }

        override fun onStart(owner: LifecycleOwner) {
            if (activeConnectionManager.current() != null) {
                foregroundEventStream.start()
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            foregroundEventStream.stop()
        }
    }
