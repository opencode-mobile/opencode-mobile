package dev.opencode.hexcode.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalHexColors = staticCompositionLocalOf { HexColors.Dark }
val LocalHexTypography = staticCompositionLocalOf { HexTypography.Default }
val LocalHexRadii = staticCompositionLocalOf { HexRadii() }
val LocalHexSpacing = staticCompositionLocalOf { HexSpacing() }
val LocalHexSizes = staticCompositionLocalOf { HexSizes() }
val LocalHexMotion = staticCompositionLocalOf { HexMotion() }

/**
 * Hex Code's theme: its own tokens, not OpenCode Mobile's. [dark] picks the palette (Appearance: Auto follows the
 * system, Light and Dark pin one); wrap the camera view in `HexTheme(dark = true)`. Material's own controls inside a
 * Hex component get a colour scheme made from the same palette.
 */
@Composable
fun HexTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) HexColors.Dark else HexColors.Light
    val type = HexTypography.Default
    val material =
        if (dark) {
            darkColorScheme(
                primary = colors.solid,
                onPrimary = colors.onSolid,
                background = colors.background,
                onBackground = colors.text1,
                surface = colors.background,
                onSurface = colors.text1,
                surfaceVariant = colors.panel2,
                onSurfaceVariant = colors.text2,
                surfaceContainer = colors.panel,
                outline = colors.line2,
                outlineVariant = colors.line,
                error = colors.red,
                scrim = colors.scrim,
            )
        } else {
            lightColorScheme(
                primary = colors.solid,
                onPrimary = colors.onSolid,
                background = colors.background,
                onBackground = colors.text1,
                surface = colors.background,
                onSurface = colors.text1,
                surfaceVariant = colors.panel2,
                onSurfaceVariant = colors.text2,
                surfaceContainer = colors.panel,
                outline = colors.line2,
                outlineVariant = colors.line,
                error = colors.red,
                scrim = colors.scrim,
            )
        }
    MaterialTheme(colorScheme = material) {
        CompositionLocalProvider(
            LocalHexColors provides colors,
            LocalHexTypography provides type,
            LocalContentColor provides colors.text1,
            LocalTextStyle provides type.body,
            LocalTextSelectionColors provides TextSelectionColors(handleColor = colors.text1, backgroundColor = colors.tint(20f)),
            content = content,
        )
    }
}

/** The current Hex tokens, as `HexTheme.colors.text1`, `HexTheme.type.body`, `HexTheme.radius.l`. */
object HexTheme {
    val colors: HexColors
        @Composable @ReadOnlyComposable
        get() = LocalHexColors.current

    val type: HexTypography
        @Composable @ReadOnlyComposable
        get() = LocalHexTypography.current

    val radius: HexRadii
        @Composable @ReadOnlyComposable
        get() = LocalHexRadii.current

    val space: HexSpacing
        @Composable @ReadOnlyComposable
        get() = LocalHexSpacing.current

    val size: HexSizes
        @Composable @ReadOnlyComposable
        get() = LocalHexSizes.current

    val motion: HexMotion
        @Composable @ReadOnlyComposable
        get() = LocalHexMotion.current

    /** The dark palette is showing (for the system bar icons). */
    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalHexColors.current.isDark
}
