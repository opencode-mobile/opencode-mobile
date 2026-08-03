package dev.opencode.mobile.domain.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object HomeDisplayPreferences {
    private const val PREFS_NAME = "opencode_home_display"
    private const val KEY_SHOW_SESSION_LIST_ON_HOME = "show_session_list_on_home"
    private const val KEY_HIDE_USER_DIRECTORY_PREFIX = "hide_user_directory_prefix"

    var showSessionListOnHome: Boolean by mutableStateOf(true)
        private set

    /** When true, replace remote `/Users/…` or `/home/…` with `~` in path labels. */
    var hideUserDirectoryPrefix: Boolean by mutableStateOf(true)
        private set

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs() ?: return
        showSessionListOnHome =
            if (prefs.contains(KEY_SHOW_SESSION_LIST_ON_HOME)) {
                prefs.getBoolean(KEY_SHOW_SESSION_LIST_ON_HOME, true)
            } else {
                true
            }
        hideUserDirectoryPrefix =
            if (prefs.contains(KEY_HIDE_USER_DIRECTORY_PREFIX)) {
                prefs.getBoolean(KEY_HIDE_USER_DIRECTORY_PREFIX, true)
            } else {
                true
            }
    }

    fun updateShowSessionListOnHome(value: Boolean) {
        showSessionListOnHome = value
        prefs()?.edit()?.putBoolean(KEY_SHOW_SESSION_LIST_ON_HOME, value)?.apply()
    }

    fun updateHideUserDirectoryPrefix(value: Boolean) {
        hideUserDirectoryPrefix = value
        prefs()?.edit()?.putBoolean(KEY_HIDE_USER_DIRECTORY_PREFIX, value)?.apply()
    }

    fun clear() {
        prefs()
            ?.edit()
            ?.remove(KEY_SHOW_SESSION_LIST_ON_HOME)
            ?.remove(KEY_HIDE_USER_DIRECTORY_PREFIX)
            ?.apply()
        showSessionListOnHome = true
        hideUserDirectoryPrefix = true
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
