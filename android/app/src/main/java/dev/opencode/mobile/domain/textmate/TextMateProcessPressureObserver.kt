package dev.opencode.mobile.domain.textmate

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.PowerManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.opencode.mobile.markdown.MemoryPressure
import dev.opencode.mobile.markdown.TextMateHighlightingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Forwards process memory, foreground, and low-power hints into the TextMate engine owner.
 * Registration is idempotent and owned by the singleton lifetime.
 */
@Singleton
class TextMateProcessPressureObserver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val engine: TextMateHighlightingEngine,
    ) : ComponentCallbacks2,
        DefaultLifecycleObserver {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val pressure = TextMatePressureForwarder(engine, scope)
        private var registered = false

        fun start() {
            if (registered) return
            registered = true
            context.registerComponentCallbacks(this)
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            publishPowerState()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        override fun onLowMemory() {
            pressure.onLowMemory()
        }

        override fun onTrimMemory(level: Int) {
            if (pressure.onTrimMemory(level)) publishPowerState()
        }

        override fun onStart(owner: LifecycleOwner) {
            pressure.onForeground()
        }

        private fun publishPowerState() {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lowPower = power.isPowerSaveMode
            val androidEngine = engine as? AndroidTextMateHighlightingEngine ?: return
            scope.launch {
                androidEngine.setLowPowerOrThermal(lowPower)
            }
        }
    }

/**
 * Turns process signals into scheduler trims. Android never reports that pressure is
 * over, so coming back to the foreground sends [MemoryPressure.Normal].
 */
internal class TextMatePressureForwarder(
    private val engine: TextMateHighlightingEngine,
    private val scope: CoroutineScope,
) {
    fun onLowMemory() {
        scope.launch { engine.trim(MemoryPressure.Critical) }
    }

    /** Returns whether [level] was forwarded as a trim. */
    fun onTrimMemory(level: Int): Boolean {
        val pressure = memoryPressureForTrimLevel(level) ?: return false
        scope.launch { engine.trim(pressure) }
        return true
    }

    fun onForeground() {
        scope.launch { engine.trim(MemoryPressure.Normal) }
    }
}

/**
 * Trim levels are two ladders, not one scale: running levels (5, 10, 15) arrive while
 * the app is visible, and UI_HIDDEN (20) and above arrive once it is not. Leaving the
 * app is not critical pressure.
 */
@Suppress("DEPRECATION")
internal fun memoryPressureForTrimLevel(level: Int): MemoryPressure? =
    when {
        level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryPressure.Critical
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> MemoryPressure.Moderate
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> MemoryPressure.Critical
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> MemoryPressure.Moderate
        else -> null
    }
