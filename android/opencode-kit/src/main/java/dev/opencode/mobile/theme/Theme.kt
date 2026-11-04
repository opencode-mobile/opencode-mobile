package dev.opencode.mobile.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val LocalOpenCodeDarkTheme = staticCompositionLocalOf { false }

@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            else -> {
                if (darkTheme) {
                    darkColorScheme(
                        primary = parse("text-interactive-base", dark = true),
                        onPrimary = Color.White,
                        primaryContainer = parse("surface-interactive-weak", dark = true),
                        onPrimaryContainer = parse("text-strong", dark = true),
                        inversePrimary = parse("text-interactive-base", dark = false),
                        secondary = parse("info", dark = true),
                        onSecondary = Color.White,
                        secondaryContainer = parse("surface-raised-base", dark = true),
                        onSecondaryContainer = parse("text-strong", dark = true),
                        tertiary = parse("success", dark = true),
                        onTertiary = Color.White,
                        tertiaryContainer = parse("surface-success-base", dark = true),
                        onTertiaryContainer = parse("text-strong", dark = true),
                        background = parse("background-base", dark = true),
                        onBackground = parse("text-strong", dark = true),
                        surface = parse("surface-base", dark = true),
                        onSurface = parse("text-strong", dark = true),
                        surfaceVariant = parse("surface-raised-base", dark = true),
                        onSurfaceVariant = parse("text-base", dark = true),
                        surfaceTint = parse("interactive", dark = true),
                        inverseSurface = parse("surface-base", dark = false),
                        inverseOnSurface = parse("text-strong", dark = false),
                        error = parse("error", dark = true),
                        onError = Color.White,
                        errorContainer = parse("surface-critical-base", dark = true),
                        onErrorContainer = parse("error", dark = true),
                        outline = parse("border-weak-base", dark = true),
                        outlineVariant = parse("border-weaker-base", dark = true),
                        scrim = Color.Black,
                    )
                } else {
                    lightColorScheme(
                        primary = parse("text-interactive-base", dark = false),
                        onPrimary = Color.White,
                        primaryContainer = parse("surface-interactive-weak", dark = false),
                        onPrimaryContainer = parse("text-strong", dark = false),
                        inversePrimary = parse("text-interactive-base", dark = true),
                        secondary = parse("info", dark = false),
                        onSecondary = Color.White,
                        secondaryContainer = parse("surface-raised-base", dark = false),
                        onSecondaryContainer = parse("text-strong", dark = false),
                        tertiary = parse("success", dark = false),
                        onTertiary = Color.White,
                        tertiaryContainer = parse("surface-success-base", dark = false),
                        onTertiaryContainer = parse("text-strong", dark = false),
                        background = parse("background-base", dark = false),
                        onBackground = parse("text-strong", dark = false),
                        surface = parse("surface-base", dark = false),
                        onSurface = parse("text-strong", dark = false),
                        surfaceVariant = parse("surface-raised-base", dark = false),
                        onSurfaceVariant = parse("text-base", dark = false),
                        surfaceTint = parse("interactive", dark = false),
                        inverseSurface = parse("surface-base", dark = true),
                        inverseOnSurface = parse("text-strong", dark = true),
                        error = parse("error", dark = false),
                        onError = Color.White,
                        errorContainer = parse("surface-critical-base", dark = false),
                        onErrorContainer = parse("error", dark = false),
                        outline = parse("border-weak-base", dark = false),
                        outlineVariant = parse("border-weaker-base", dark = false),
                        scrim = Color.Black,
                    )
                }
            }
        }

    CompositionLocalProvider(LocalOpenCodeDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
    }
}

private fun parse(
    token: String,
    dark: Boolean,
): Color = openCodeColor(token, dark)
