package dev.opencode.mobile.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
    darkColorScheme(
        primary = DarkInteractiveAccent,
        onPrimary = Color.White,
        primaryContainer = DarkTextInteractive,
        onPrimaryContainer = DarkBackgroundPrimary,
        inversePrimary = LightTextInteractive,
        secondary = DarkStatusInfo,
        onSecondary = DarkBackgroundPrimary,
        secondaryContainer = DarkSurfaceSecondary,
        onSecondaryContainer = DarkTextPrimary,
        tertiary = DarkStatusSuccess,
        onTertiary = DarkBackgroundPrimary,
        tertiaryContainer = DarkSurfaceInset,
        onTertiaryContainer = DarkTextPrimary,
        background = DarkBackgroundPrimary,
        onBackground = DarkTextPrimary,
        surface = DarkSurfacePrimary,
        onSurface = DarkTextPrimary,
        surfaceVariant = DarkSurfaceSecondary,
        onSurfaceVariant = DarkTextSecondary,
        surfaceTint = DarkInteractiveAccent,
        inverseSurface = LightSurfacePrimary,
        inverseOnSurface = LightTextPrimary,
        error = DarkStatusError,
        onError = Color.White,
        errorContainer = DarkSurfaceInset,
        onErrorContainer = DarkStatusError,
        outline = DarkBorderDefault,
        outlineVariant = DarkBorderSubtle,
        scrim = Color.Black,
    )

private val LightColorScheme =
    lightColorScheme(
        primary = LightTextInteractive,
        onPrimary = Color.White,
        primaryContainer = LightBackgroundSecondary,
        onPrimaryContainer = LightTextPrimary,
        inversePrimary = DarkTextInteractive,
        secondary = LightStatusInfo,
        onSecondary = Color.White,
        secondaryContainer = LightSurfaceSecondary,
        onSecondaryContainer = LightTextPrimary,
        tertiary = LightStatusSuccess,
        onTertiary = Color.White,
        tertiaryContainer = LightSurfaceInset,
        onTertiaryContainer = LightTextPrimary,
        background = LightBackgroundPrimary,
        onBackground = LightTextPrimary,
        surface = LightSurfacePrimary,
        onSurface = LightTextPrimary,
        surfaceVariant = LightSurfaceSecondary,
        onSurfaceVariant = LightTextSecondary,
        surfaceTint = LightTextInteractive,
        inverseSurface = DarkSurfacePrimary,
        inverseOnSurface = DarkTextPrimary,
        error = LightStatusError,
        onError = Color.White,
        errorContainer = LightSurfaceInset,
        onErrorContainer = LightStatusError,
        outline = LightBorderDefault,
        outlineVariant = LightBorderSubtle,
        scrim = Color.Black,
    )

@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
