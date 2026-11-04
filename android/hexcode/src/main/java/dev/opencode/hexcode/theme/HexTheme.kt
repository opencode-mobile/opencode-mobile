package dev.opencode.hexcode.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.ThemeManager

/** Hex Code's look: OpenCode's shared theme system with the Hex preset (the prototype's palette). */
object HexTheme {
    const val PRESET_ID = "hex"

    /** Loads the Hex preset into the shared theme once per process. */
    fun install(context: Context) {
        ThemeManager.initialize(context)
        if (ThemeManager.currentTheme.id != PRESET_ID) ThemeManager.applyPreset(PRESET_ID)
    }
}

/** Light and dark follow the system unless [dark] pins one. */
@Composable
fun HexTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    OpenCodeTheme(darkTheme = dark, content = content)
}
