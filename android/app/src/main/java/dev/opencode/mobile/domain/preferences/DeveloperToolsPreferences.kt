package dev.opencode.mobile.domain.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object DeveloperToolsPreferences {
    private const val PREFS_NAME = "opencode_developer_tools"
    private const val KEY_UNLOCKED = "developer_tools_unlocked"

    var unlocked: Boolean by mutableStateOf(false)
        private set

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        unlocked = prefs()?.getBoolean(KEY_UNLOCKED, false) == true
    }

    fun unlock() {
        unlocked = true
        prefs()?.edit()?.putBoolean(KEY_UNLOCKED, true)?.apply()
    }

    fun clear() {
        prefs()?.edit()?.remove(KEY_UNLOCKED)?.apply()
        unlocked = false
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
