package dev.opencode.mobile.domain.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Optional biometric / device-credential lock when the app returns from the background (#755). */
object AppLockPreferences {
    private const val PREFS_NAME = "opencode_app_lock"
    private const val KEY_ENABLED = "app_lock_enabled"

    var enabled: Boolean by mutableStateOf(false)
        private set

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        enabled = prefs()?.getBoolean(KEY_ENABLED, false) ?: false
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
