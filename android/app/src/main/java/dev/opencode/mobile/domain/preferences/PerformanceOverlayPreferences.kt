package dev.opencode.mobile.domain.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Developer Tools toggle for the live frame-time HUD. Off until turned on. */
object PerformanceOverlayPreferences {
    private const val PREFS_NAME = "opencode_performance_overlay"
    private const val KEY_ENABLED = "performance_overlay_enabled"

    var enabled: Boolean by mutableStateOf(false)
        private set

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        enabled = prefs()?.getBoolean(KEY_ENABLED, false) == true
    }

    fun updateEnabled(value: Boolean) {
        enabled = value
        prefs()?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
    }

    fun clear() {
        prefs()?.edit()?.remove(KEY_ENABLED)?.apply()
        enabled = false
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
