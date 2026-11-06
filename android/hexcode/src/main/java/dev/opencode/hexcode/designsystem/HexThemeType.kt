package dev.opencode.hexcode.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Hex Code's type scale (hex-design-system.md, Type): the system font (Roboto) and the system monospace, in sp so it
 * follows the user's text size. [sectionLabel] is written in upper case by `HexSectionLabel`.
 */
@Immutable
class HexTypography(
    /** 30 / semibold, line 1.18, −0.028 em: the status sentence (Now up to 90 characters), the welcome title. */
    val display: TextStyle,
    /** 25 / semibold, line 1.22, −0.022 em: a Now sentence up to 150 characters (`.fx-now.n1`). */
    val nowLarge: TextStyle,
    /** 21 / medium, line 1.28, −0.016 em: a Now sentence up to 220 characters (`.fx-now.n2`). */
    val nowMedium: TextStyle,
    /** 19 / medium, line 1.32, −0.012 em: a Now sentence up to 300 characters (`.fx-now.n3`). */
    val nowSmall: TextStyle,
    /** 17 / medium, line 1.36, −0.008 em: a Now sentence only when the card doesn't fit otherwise (`.fx-now.n4`). */
    val nowFit: TextStyle,
    /** 26 / semibold, −0.025 em: first-run headings. */
    val title: TextStyle,
    /** 20 / semibold, −0.02 em: page titles (pickers, Settings), the welcome rule. */
    val pageTitle: TextStyle,
    /** 24 / medium, line 1.3, −0.015 em: the dictation draft. */
    val draft: TextStyle,
    /** 17 / semibold, −0.015 em: the top bar. */
    val headline: TextStyle,
    /** 15 / regular, line 1.45: questions, running text. */
    val body: TextStyle,
    /** 15 / semibold: the primary button, sheet titles. */
    val bodyStrong: TextStyle,
    /** 14.5 / regular: rows. */
    val row: TextStyle,
    /** 14.5 / medium: regular buttons. */
    val button: TextStyle,
    /** 13.5 / medium: toast titles, the goal line. */
    val callout: TextStyle,
    /** 13 / medium: small buttons, segments. */
    val small: TextStyle,
    /** 12.5 / regular: labels, hints. */
    val caption: TextStyle,
    /** 12 / regular: footers, fine print. */
    val footnote: TextStyle,
    /** 12 / semibold, +0.06 em, upper case: section headings. */
    val sectionLabel: TextStyle,
    /** 11 / medium: bordered badges. */
    val badge: TextStyle,
    /** 11.5 / regular, monospace: timers, counts. */
    val mono: TextStyle,
    /** 10.5 / regular, monospace: the `git` badge. */
    val monoSmall: TextStyle,
) {
    companion object {
        private val Even = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

        private fun style(
            size: Float,
            weight: FontWeight = FontWeight.Normal,
            tracking: TextUnit = 0.em,
            line: TextUnit = TextUnit.Unspecified,
            family: FontFamily = FontFamily.Default,
        ) = TextStyle(
            fontFamily = family,
            fontSize = size.sp,
            fontWeight = weight,
            letterSpacing = tracking,
            lineHeight = line,
            lineHeightStyle = if (line == TextUnit.Unspecified) null else Even,
        )

        val Default =
            HexTypography(
                display = style(30f, FontWeight.SemiBold, (-0.028).em, 1.18.em),
                nowLarge = style(25f, FontWeight.SemiBold, (-0.022).em, 1.22.em),
                nowMedium = style(21f, FontWeight.Medium, (-0.016).em, 1.28.em),
                nowSmall = style(19f, FontWeight.Medium, (-0.012).em, 1.32.em),
                nowFit = style(17f, FontWeight.Medium, (-0.008).em, 1.36.em),
                title = style(26f, FontWeight.SemiBold, (-0.025).em),
                pageTitle = style(20f, FontWeight.SemiBold, (-0.02).em),
                draft = style(24f, FontWeight.Medium, (-0.015).em, 1.3.em),
                headline = style(17f, FontWeight.SemiBold, (-0.015).em),
                body = style(15f, line = 1.45.em),
                bodyStrong = style(15f, FontWeight.SemiBold),
                row = style(14.5f),
                button = style(14.5f, FontWeight.Medium),
                callout = style(13.5f, FontWeight.Medium),
                small = style(13f, FontWeight.Medium),
                caption = style(12.5f),
                footnote = style(12f),
                sectionLabel = style(12f, FontWeight.SemiBold, 0.06.em),
                badge = style(11f, FontWeight.Medium),
                mono = style(11.5f, family = FontFamily.Monospace),
                monoSmall = style(10.5f, family = FontFamily.Monospace),
            )
    }
}

/** Text with its `backticked` commands set in the monospace, as the strings write them (and iOS renders them). */
fun hexCodeText(text: String): AnnotatedString =
    buildAnnotatedString {
        text.split('`').forEachIndexed { index, part ->
            if (index % 2 == 1) withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(part) } else append(part)
        }
    }
