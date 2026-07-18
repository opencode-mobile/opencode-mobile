package dev.opencode.mobile.domain.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object HomeDisplayPreferences {
    private const val PREFS_NAME = "opencode_home_display"
    private const val KEY_SHOW_SESSION_LIST_ON_HOME = "show_session_list_on_home"

    var showSessionListOnHome: Boolean by mutableStateOf(false)
        private set

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs() ?: return
        showSessionListOnHome =
            if (prefs.contains(KEY_SHOW_SESSION_LIST_ON_HOME)) {
                prefs.getBoolean(KEY_SHOW_SESSION_LIST_ON_HOME, false)
            } else {
                false
            }
    }

    fun updateShowSessionListOnHome(value: Boolean) {
        showSessionListOnHome = value
        prefs()?.edit()?.putBoolean(KEY_SHOW_SESSION_LIST_ON_HOME, value)?.apply()
    }

    fun clear() {
        prefs()?.edit()?.remove(KEY_SHOW_SESSION_LIST_ON_HOME)?.apply()
        showSessionListOnHome = false
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
