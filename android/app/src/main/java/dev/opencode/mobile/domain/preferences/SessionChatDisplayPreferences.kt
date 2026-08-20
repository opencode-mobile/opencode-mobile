package dev.opencode.mobile.domain.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Session Chat display preferences (OPE-144).
 * Device-local, default on, cleared by Clear All Data.
 */
object SessionChatDisplayPreferences {
    private const val PREFS_NAME = "opencode_session_chat_display"
    private const val KEY_COLLAPSE_CONSECUTIVE_TOOL_CALLS = "collapse_consecutive_tool_calls"
    private const val KEY_SMOOTH_STREAMING_UPDATES = "smooth_streaming_updates"
    const val DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS = true
    const val DEFAULT_SMOOTH_STREAMING_UPDATES = true

    /** When true, a run of two or more consecutive visible tool parts in one
     * assistant message collapses into one summary tool group. */
    var collapseConsecutiveToolCalls: Boolean by mutableStateOf(DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS)
        private set

    /** When true, live assistant tokens coalesce into 80ms / 160ms display frames.
     * Off paints every streaming snapshot immediately. */
    var smoothStreamingUpdates: Boolean by mutableStateOf(DEFAULT_SMOOTH_STREAMING_UPDATES)
        private set

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs() ?: return
        collapseConsecutiveToolCalls =
            if (prefs.contains(KEY_COLLAPSE_CONSECUTIVE_TOOL_CALLS)) {
                prefs.getBoolean(KEY_COLLAPSE_CONSECUTIVE_TOOL_CALLS, DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS)
            } else {
                DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS
            }
        smoothStreamingUpdates =
            if (prefs.contains(KEY_SMOOTH_STREAMING_UPDATES)) {
                prefs.getBoolean(KEY_SMOOTH_STREAMING_UPDATES, DEFAULT_SMOOTH_STREAMING_UPDATES)
            } else {
                DEFAULT_SMOOTH_STREAMING_UPDATES
            }
    }

    fun updateCollapseConsecutiveToolCalls(value: Boolean) {
        collapseConsecutiveToolCalls = value
        prefs()?.edit()?.putBoolean(KEY_COLLAPSE_CONSECUTIVE_TOOL_CALLS, value)?.apply()
    }

    fun updateSmoothStreamingUpdates(value: Boolean) {
        smoothStreamingUpdates = value
        prefs()?.edit()?.putBoolean(KEY_SMOOTH_STREAMING_UPDATES, value)?.apply()
    }

    fun clear() {
        prefs()
            ?.edit()
            ?.remove(KEY_COLLAPSE_CONSECUTIVE_TOOL_CALLS)
            ?.remove(KEY_SMOOTH_STREAMING_UPDATES)
            ?.apply()
        collapseConsecutiveToolCalls = DEFAULT_COLLAPSE_CONSECUTIVE_TOOL_CALLS
        smoothStreamingUpdates = DEFAULT_SMOOTH_STREAMING_UPDATES
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
