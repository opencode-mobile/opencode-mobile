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
    private const val KEY_LAST_HOME_HIDDEN_AT_EPOCH_MS = "last_home_hidden_at_epoch_ms"
    private const val KEY_SESSION_LIST_PROJECT_PICKER_ENABLED = "session_list_project_picker_enabled"
    private const val KEY_SHOWS_WORKTREES = "shows_worktrees"

    /** When true, replace remote `/Users/…` or `/home/…` with `~` in path labels. */
    var hideUserDirectoryPrefix: Boolean by mutableStateOf(true)
        private set

    var recentWindowHours: Int by mutableIntStateOf(HomeSessionLadder.DEFAULT_WINDOW_HOURS)
        private set

    /**
     * When true, Home shows each project's worktrees and branches, and holding New session can
     * pick a worktree. Off changes presentation only: sessions of every worktree stay listed.
     */
    var showsWorktrees: Boolean by mutableStateOf(true)
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
        showsWorktrees = prefs.getBoolean(KEY_SHOWS_WORKTREES, true)
    }

    fun updateShowsWorktrees(value: Boolean) {
        showsWorktrees = value
        prefs()?.edit()?.putBoolean(KEY_SHOWS_WORKTREES, value)?.apply()
    }

    fun updateHideUserDirectoryPrefix(value: Boolean) {
        hideUserDirectoryPrefix = value
        prefs()?.edit()?.putBoolean(KEY_HIDE_USER_DIRECTORY_PREFIX, value)?.apply()
    }

    fun lastHomeHiddenAtEpochMs(): Long? {
        val prefs = prefs() ?: return null
        if (!prefs.contains(KEY_LAST_HOME_HIDDEN_AT_EPOCH_MS)) return null
        return prefs.getLong(KEY_LAST_HOME_HIDDEN_AT_EPOCH_MS, 0L)
    }

    fun setLastHomeHiddenAtEpochMs(value: Long?) {
        val editor = prefs()?.edit() ?: return
        if (value == null) {
            editor.remove(KEY_LAST_HOME_HIDDEN_AT_EPOCH_MS)
        } else {
            editor.putLong(KEY_LAST_HOME_HIDDEN_AT_EPOCH_MS, value)
        }
        editor.apply()
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
            ?.remove(KEY_LAST_HOME_HIDDEN_AT_EPOCH_MS)
            ?.remove(KEY_SESSION_LIST_PROJECT_PICKER_ENABLED)
            ?.remove(KEY_SHOWS_WORKTREES)
            ?.apply()
        hideUserDirectoryPrefix = true
        showsWorktrees = true
        recentWindowHours = HomeSessionLadder.DEFAULT_WINDOW_HOURS
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
