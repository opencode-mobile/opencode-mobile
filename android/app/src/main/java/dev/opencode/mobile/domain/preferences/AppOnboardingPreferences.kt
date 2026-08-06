package dev.opencode.mobile.domain.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Local flag for one-time App onboarding (distinct from home Connection onboarding).
 * Clear Data resets completion so the pager can return on a fresh wipe.
 */
object AppOnboardingPreferences {
    private const val PREFS_NAME = "opencode_app_onboarding"
    private const val KEY_COMPLETED = "app_onboarding_completed"

    var isCompleted: Boolean by mutableStateOf(false)
        private set

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs() ?: return
        isCompleted = prefs.getBoolean(KEY_COMPLETED, false)
    }

    fun markCompleted() {
        isCompleted = true
        prefs()?.edit()?.putBoolean(KEY_COMPLETED, true)?.apply()
    }

    fun clear() {
        prefs()?.edit()?.remove(KEY_COMPLETED)?.apply()
        isCompleted = false
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
