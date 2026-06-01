package dev.opencode.mobile.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Resolve a named semantic token from the current theme at composition time.
 * Tokens match the OpenCode desktop theme schema (e.g. "text-strong", "surface-base").
 */
@Composable
fun openCodeColor(token: String): Color {
    val isDark = isSystemInDarkTheme()
    val hex = if (isDark) ThemeManager.resolvedDark[token] else ThemeManager.resolvedLight[token]
    return ThemeResolver.parseHexColor(hex)
}

/**
 * Resolve a named semantic token for a specific mode without composition.
 */
fun openCodeColor(
    token: String,
    isDark: Boolean,
): Color {
    val hex = if (isDark) ThemeManager.resolvedDark[token] else ThemeManager.resolvedLight[token]
    return ThemeResolver.parseHexColor(hex)
}

// --- Letter badge colors (no desktop equivalent, hardcoded component tokens) ---

internal val LightListItemLetterVioletFill = Color(0xFF4C3D5C)
internal val LightListItemLetterVioletTint = Color(0xFFE2D5F5)
internal val DarkListItemLetterVioletFill = Color(0xFF3A2F47)
internal val DarkListItemLetterVioletTint = Color(0xFFD8C8EE)

internal val LightListItemLetterTealFill = Color(0xFF2D4A42)
internal val LightListItemLetterTealTint = Color(0xFFB8F5E0)
internal val DarkListItemLetterTealFill = Color(0xFF243832)
internal val DarkListItemLetterTealTint = Color(0xFFA8EDD8)

internal val LightListItemLetterAmberFill = Color(0xFF5C3D28)
internal val LightListItemLetterAmberTint = Color(0xFFFFB86C)
internal val DarkListItemLetterAmberFill = Color(0xFF4A3224)
internal val DarkListItemLetterAmberTint = Color(0xFFFFAA55)

internal val LightListItemLetterBlueFill = Color(0xFF2A3A55)
internal val LightListItemLetterBlueTint = Color(0xFFA8D4FF)
internal val DarkListItemLetterBlueFill = Color(0xFF1E2D42)
internal val DarkListItemLetterBlueTint = Color(0xFF9EC8FF)

internal val LightListItemLetterRoseFill = Color(0xFF5C2D3D)
internal val LightListItemLetterRoseTint = Color(0xFFFFB8D4)
internal val DarkListItemLetterRoseFill = Color(0xFF4A2533)
internal val DarkListItemLetterRoseTint = Color(0xFFFFA8CC)

internal val LightListItemLetterRedFill = Color(0xFF5C2D2D)
internal val LightListItemLetterRedTint = Color(0xFFFFC8C8)
internal val DarkListItemLetterRedFill = Color(0xFF4A2424)
internal val DarkListItemLetterRedTint = Color(0xFFFFB8B8)

internal val LightListItemLetterGreenFill = Color(0xFF2D4A3D)
internal val LightListItemLetterGreenTint = Color(0xFFB8F5D8)
internal val DarkListItemLetterGreenFill = Color(0xFF243832)
internal val DarkListItemLetterGreenTint = Color(0xFFA8EDC8)

internal val LightListItemLetterIndigoFill = Color(0xFF2D3D5C)
internal val LightListItemLetterIndigoTint = Color(0xFFB8C8FF)
internal val DarkListItemLetterIndigoFill = Color(0xFF24304A)
internal val DarkListItemLetterIndigoTint = Color(0xFFA8B8FF)
