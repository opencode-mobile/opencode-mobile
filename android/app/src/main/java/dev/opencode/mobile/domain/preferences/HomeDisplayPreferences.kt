package dev.opencode.mobile.domain.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.opencode.mobile.domain.home.HomeSessionLadder

object HomeDisplayPreferences {
    private const val PREFS_NAME = "opencode_home_display"
    private const val KEY_SHOW_SESSION_LIST_ON_HOME = "show_session_list_on_home"
    private const val KEY_HIDE_USER_DIRECTORY_PREFIX = "hide_user_directory_prefix"
    private const val KEY_RECENT_WINDOW_HOURS = "recent_window_hours"

    /** When true, replace remote `/Users/…` or `/home/…` with `~` in path labels. */
    var hideUserDirectoryPrefix: Boolean by mutableStateOf(true)
        private set

    var recentWindowHours: Int by mutableIntStateOf(HomeSessionLadder.DEFAULT_WINDOW_HOURS)
        private set

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs() ?: return
        hideUserDirectoryPrefix =
            if (prefs.contains(KEY_HIDE_USER_DIRECTORY_PREFIX)) {
                prefs.getBoolean(KEY_HIDE_USER_DIRECTORY_PREFIX, true)
            } else {
                true
            }
        recentWindowHours =
            HomeSessionLadder.sanitizeWindowHours(
                prefs.getInt(KEY_RECENT_WINDOW_HOURS, HomeSessionLadder.DEFAULT_WINDOW_HOURS),
            )
    }

    fun updateHideUserDirectoryPrefix(value: Boolean) {
        hideUserDirectoryPrefix = value
        prefs()?.edit()?.putBoolean(KEY_HIDE_USER_DIRECTORY_PREFIX, value)?.apply()
    }

    fun updateRecentWindowHours(value: Int) {
        val sanitized = HomeSessionLadder.sanitizeWindowHours(value)
        recentWindowHours = sanitized
        prefs()?.edit()?.putInt(KEY_RECENT_WINDOW_HOURS, sanitized)?.apply()
    }

    fun clear() {
        prefs()
            ?.edit()
            ?.remove(KEY_SHOW_SESSION_LIST_ON_HOME)
            ?.remove(KEY_HIDE_USER_DIRECTORY_PREFIX)
            ?.remove(KEY_RECENT_WINDOW_HOURS)
            ?.apply()
        hideUserDirectoryPrefix = true
        recentWindowHours = HomeSessionLadder.DEFAULT_WINDOW_HOURS
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
