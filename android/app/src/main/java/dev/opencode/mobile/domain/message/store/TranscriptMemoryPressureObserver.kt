package dev.opencode.mobile.domain.message

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.domain.textmate.memoryPressureForTrimLevel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Releases every closed session's transcript on process memory pressure, ignoring the
 * grace period and warm cap of [TranscriptRetentionPolicy]. Open sessions are untouched.
 * Uses the same trim ladder as the highlighting engine.
 */
@Singleton
class TranscriptMemoryPressureObserver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val messageSync: MessageSync,
    ) : ComponentCallbacks2 {
        private var registered = false

        fun start() {
            if (registered) return
            registered = true
            context.registerComponentCallbacks(this)
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        override fun onLowMemory() {
            messageSync.releaseClosedSessions()
        }

        override fun onTrimMemory(level: Int) {
            if (memoryPressureForTrimLevel(level) != null) messageSync.releaseClosedSessions()
        }
    }
