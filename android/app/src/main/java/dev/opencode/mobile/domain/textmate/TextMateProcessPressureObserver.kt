package dev.opencode.mobile.domain.textmate

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.PowerManager
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
 * Forwards process memory and low-power hints into the TextMate engine owner.
 * Registration is idempotent and owned by the singleton lifetime.
 */
@Singleton
class TextMateProcessPressureObserver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val engine: TextMateHighlightingEngine,
        private val textMate: AndroidTextMateHighlightingEngine,
    ) : ComponentCallbacks2 {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var registered = false

        fun start() {
            if (registered) return
            registered = true
            context.registerComponentCallbacks(this)
            publishPowerState()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        override fun onLowMemory() {
            scope.launch {
                engine.trim(MemoryPressure.Critical)
            }
        }

        override fun onTrimMemory(level: Int) {
            val pressure =
                when {
                    level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
                        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ->
                        MemoryPressure.Critical
                    level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
                        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
                        MemoryPressure.Moderate
                    else -> return
                }
            scope.launch {
                engine.trim(pressure)
            }
            publishPowerState()
        }

        private fun publishPowerState() {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lowPower = power.isPowerSaveMode
            scope.launch {
                textMate.setLowPowerOrThermal(lowPower)
            }
        }
    }
