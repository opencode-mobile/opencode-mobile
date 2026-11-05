@file:Suppress("ktlint:standard:filename")

package dev.opencode.hexcode.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Hex Code's colours (hex-design-system.md, Colours). Two palettes, [Dark] and [Light]; Appearance picks one, and the
 * camera view always uses [Dark]. Colour carries state only: text greys for content, green, amber and red for status.
 */
@Immutable
class HexColors(
    /** Screen background, inset fields. */
    val background: Color,
    /** Sheets, cards, toasts. */
    val panel: Color,
    /** Buttons, options, raised rows. */
    val panel2: Color,
    /** Selected segment, menus. */
    val panel3: Color,
    /** Borders, dividers. */
    val line: Color,
    /** Stronger borders, the sheet grab. */
    val line2: Color,
    /** A pressed row. */
    val hover: Color,
    /** Primary text. */
    val text1: Color,
    /** Secondary text, icons. */
    val text2: Color,
    /** Labels, hints, captions. */
    val text3: Color,
    /** Faint marks, empty progress. */
    val text4: Color,
    /** Connected, working, success. */
    val green: Color,
    /** Needs you, warnings, Now. */
    val amber: Color,
    /** Needs-you background. */
    val amberSoft: Color,
    /** Amber at 30 %: the Needs-you border. */
    val amberLine: Color,
    /** Stop, errors, drop a take. */
    val red: Color,
    /** The stop hint. */
    val redSoft: Color,
    /** Accent, rarely. */
    val indigo: Color,
    /** The primary button and talk button fill. */
    val solid: Color,
    /** Text on [solid]. */
    val onSolid: Color,
    /** Behind sheets. */
    val scrim: Color,
    /** The stop warning card. */
    val stopBackground: Color,
    /** Under menus and toasts. */
    val shadow: Color,
    /** The foreground [tint] mixes from: white in the dark palette, black in the light one. */
    private val foreground: Color,
    val isDark: Boolean,
) {
    /** The prototype's `rgba(var(--fg-rgb), n)`: the foreground at [percent] % (chunk fills, round buttons, the search field). */
    fun tint(percent: Float): Color = foreground.copy(alpha = percent / 100f)

    companion object {
        private val Amber = Color(0xFFF2994A)
        private val Red = Color(0xFFEB5757)
        private val AmberLight = Color(0xFFD9771C)
        private val RedLight = Color(0xFFD94848)

        val Dark =
            HexColors(
                background = Color(0xFF08090A),
                panel = Color(0xFF0F1011),
                panel2 = Color(0xFF151618),
                panel3 = Color(0xFF1C1D1F),
                line = Color.White.copy(alpha = 0.08f),
                line2 = Color.White.copy(alpha = 0.13f),
                hover = Color.White.copy(alpha = 0.045f),
                text1 = Color(0xFFF7F8F8),
                text2 = Color(0xFF8A8F98),
                text3 = Color(0xFF62666D),
                text4 = Color(0xFF3E4146),
                green = Color(0xFF4CB782),
                amber = Amber,
                amberSoft = Amber.copy(alpha = 0.10f),
                amberLine = Amber.copy(alpha = 0.30f),
                red = Red,
                redSoft = Red.copy(alpha = 0.12f),
                indigo = Color(0xFF5E6AD2),
                solid = Color(0xFFE6E7E9),
                onSolid = Color(0xFF08090A),
                scrim = Color.Black.copy(alpha = 0.60f),
                stopBackground = Color(0xFF1B1112),
                shadow = Color.Black.copy(alpha = 0.55f),
                foreground = Color.White,
                isDark = true,
            )

        val Light =
            HexColors(
                background = Color(0xFFFBFBFC),
                panel = Color(0xFFFFFFFF),
                panel2 = Color(0xFFF3F3F5),
                panel3 = Color(0xFFFFFFFF),
                line = Color.Black.copy(alpha = 0.08f),
                line2 = Color.Black.copy(alpha = 0.14f),
                hover = Color.Black.copy(alpha = 0.04f),
                text1 = Color(0xFF17181A),
                text2 = Color(0xFF5F636B),
                text3 = Color(0xFF8B8F97),
                text4 = Color(0xFFC9CCD1),
                green = Color(0xFF2C9A66),
                amber = AmberLight,
                amberSoft = AmberLight.copy(alpha = 0.09f),
                amberLine = AmberLight.copy(alpha = 0.30f),
                red = RedLight,
                redSoft = RedLight.copy(alpha = 0.09f),
                indigo = Color(0xFF5E6AD2),
                solid = Color(0xFF17181A),
                onSolid = Color(0xFFFFFFFF),
                scrim = Color.Black.copy(alpha = 0.28f),
                stopBackground = Color(0xFFFFF4F3),
                shadow = Color.Black.copy(alpha = 0.14f),
                foreground = Color.Black,
                isDark = false,
            )
    }
}
