package dev.opencode.mobile.domain.preferences

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneLayout
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneScreen
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneState

/** Pure key naming and value resolution for [SplitPanePreferences]. */
internal object SplitPanePreferencesCodec {
    fun leadingWidthKey(screen: OpenCodeSplitPaneScreen): String = "${screen.storagePrefix}.leadingWidthDp"

    fun isCollapsedKey(screen: OpenCodeSplitPaneScreen): String = "${screen.storagePrefix}.isCollapsed"

    /** Resolves stored values; a missing or invalid width falls back to the default, then clamps. */
    fun decode(
        storedLeadingWidthDp: Float?,
        storedIsCollapsed: Boolean?,
        screen: OpenCodeSplitPaneScreen = OpenCodeSplitPaneScreen.Home,
    ): OpenCodeSplitPaneState {
        val stored = storedLeadingWidthDp?.takeIf { it.isFinite() && it > 0f }?.dp
        val width =
            if (screen.usesContentMetrics) {
                stored ?: OpenCodeSplitPaneLayout.ContentDefaultLeadingWidth
            } else {
                stored?.let { OpenCodeSplitPaneLayout.clamp(it) } ?: OpenCodeSplitPaneLayout.DefaultLeadingWidth
            }
        return OpenCodeSplitPaneState(leadingWidth = width, isCollapsed = storedIsCollapsed ?: false)
    }

    fun sanitize(
        state: OpenCodeSplitPaneState,
        screen: OpenCodeSplitPaneScreen = OpenCodeSplitPaneScreen.Home,
    ): OpenCodeSplitPaneState {
        val width =
            if (screen.usesContentMetrics) {
                state.leadingWidth.takeIf { it.value.isFinite() && it.value > 0f }
                    ?: OpenCodeSplitPaneLayout.ContentDefaultLeadingWidth
            } else {
                OpenCodeSplitPaneLayout.clamp(state.leadingWidth)
            }
        return state.copy(leadingWidth = width)
    }

    private val OpenCodeSplitPaneScreen.storagePrefix: String
        get() =
            when (this) {
                OpenCodeSplitPaneScreen.Home -> "home"
                OpenCodeSplitPaneScreen.Settings -> "settings"
                OpenCodeSplitPaneScreen.Changes -> "changes"
                OpenCodeSplitPaneScreen.Terminals -> "terminals"
            }
}

/** Persists the tablet split-view leading pane width and collapsed state per screen. */
object SplitPanePreferences {
    private const val PREFS_NAME = "opencode_split_pane"

    private val states = mutableStateMapOf<OpenCodeSplitPaneScreen, OpenCodeSplitPaneState>()
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs() ?: return
        OpenCodeSplitPaneScreen.entries.forEach { screen ->
            val widthKey = SplitPanePreferencesCodec.leadingWidthKey(screen)
            val collapsedKey = SplitPanePreferencesCodec.isCollapsedKey(screen)
            states[screen] =
                SplitPanePreferencesCodec.decode(
                    storedLeadingWidthDp = if (prefs.contains(widthKey)) prefs.getFloat(widthKey, 0f) else null,
                    storedIsCollapsed = if (prefs.contains(collapsedKey)) prefs.getBoolean(collapsedKey, false) else null,
                    screen = screen,
                )
        }
    }

    fun state(screen: OpenCodeSplitPaneScreen): OpenCodeSplitPaneState =
        states[screen] ?: SplitPanePreferencesCodec.decode(null, null, screen)

    fun update(
        screen: OpenCodeSplitPaneScreen,
        state: OpenCodeSplitPaneState,
    ) {
        val sanitized = SplitPanePreferencesCodec.sanitize(state, screen)
        states[screen] = sanitized
        prefs()
            ?.edit()
            ?.putFloat(SplitPanePreferencesCodec.leadingWidthKey(screen), sanitized.leadingWidth.value)
            ?.putBoolean(SplitPanePreferencesCodec.isCollapsedKey(screen), sanitized.isCollapsed)
            ?.apply()
    }

    fun clear() {
        val editor = prefs()?.edit()
        OpenCodeSplitPaneScreen.entries.forEach { screen ->
            editor?.remove(SplitPanePreferencesCodec.leadingWidthKey(screen))
            editor?.remove(SplitPanePreferencesCodec.isCollapsedKey(screen))
        }
        editor?.apply()
        states.clear()
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
