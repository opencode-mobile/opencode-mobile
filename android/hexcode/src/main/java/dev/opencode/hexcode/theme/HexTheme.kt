package dev.opencode.hexcode.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Hex Code's palette, the prototype's tokens (`--bg`, `--t1`, …) for one theme. */
@Immutable
data class HexColors(
    val isDark: Boolean,
    val background: Color,
    val panel: Color,
    val panel2: Color,
    val panel3: Color,
    /** The foreground at low alpha: hairlines, washes, pressed rows. */
    val ink: Color,
    val line: Color,
    val line2: Color,
    val text1: Color,
    val text2: Color,
    val text3: Color,
    val text4: Color,
    val green: Color,
    val amber: Color,
    val red: Color,
    val solid: Color,
    val onSolid: Color,
) {
    fun wash(alpha: Float): Color = ink.copy(alpha = alpha)

    companion object {
        val Dark =
            HexColors(
                isDark = true,
                background = Color(0xFF08090A),
                panel = Color(0xFF0F1011),
                panel2 = Color(0xFF151618),
                panel3 = Color(0xFF1C1D1F),
                ink = Color.White,
                line = Color.White.copy(alpha = 0.08f),
                line2 = Color.White.copy(alpha = 0.13f),
                text1 = Color(0xFFF7F8F8),
                text2 = Color(0xFF8A8F98),
                text3 = Color(0xFF62666D),
                text4 = Color(0xFF3E4146),
                green = Color(0xFF4CB782),
                amber = Color(0xFFF2994A),
                red = Color(0xFFEB5757),
                solid = Color(0xFFE6E7E9),
                onSolid = Color(0xFF08090A),
            )

        val Light =
            HexColors(
                isDark = false,
                background = Color(0xFFFBFBFC),
                panel = Color(0xFFFFFFFF),
                panel2 = Color(0xFFF3F3F5),
                panel3 = Color(0xFFFFFFFF),
                ink = Color.Black,
                line = Color.Black.copy(alpha = 0.08f),
                line2 = Color.Black.copy(alpha = 0.14f),
                text1 = Color(0xFF17181A),
                text2 = Color(0xFF5F636B),
                text3 = Color(0xFF8B8F97),
                text4 = Color(0xFFC9CCD1),
                green = Color(0xFF2C9A66),
                amber = Color(0xFFD9771C),
                red = Color(0xFFD94848),
                solid = Color(0xFF17181A),
                onSolid = Color(0xFFFFFFFF),
            )
    }
}

/** The prototype's type scale: titles tighten their tracking, captions stay small and quiet. */
@Immutable
data class HexType(
    val display: TextStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em),
    val title: TextStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
    val rule: TextStyle = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
    val lead: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    val body: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    val rowTitle: TextStyle = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
    val rowSubtitle: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    val caption: TextStyle = TextStyle(fontSize = 12.5.sp),
    val section: TextStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em),
    val button: TextStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    val mono: TextStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
    val tag: TextStyle = TextStyle(fontSize = 10.5.sp, fontFamily = FontFamily.Monospace),
)

private val LocalHexColors = staticCompositionLocalOf { HexColors.Dark }
private val LocalHexType = staticCompositionLocalOf { HexType() }

object HexTheme {
    val colors: HexColors
        @Composable @ReadOnlyComposable
        get() = LocalHexColors.current

    val type: HexType
        @Composable @ReadOnlyComposable
        get() = LocalHexType.current
}

/** Light and dark follow the system. Material components read the same palette. */
@Composable
fun HexTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) HexColors.Dark else HexColors.Light
    val scheme =
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
                outline = colors.line2,
                error = colors.red,
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
                outline = colors.line2,
                error = colors.red,
            )
        }
    CompositionLocalProvider(LocalHexColors provides colors, LocalHexType provides HexType()) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
