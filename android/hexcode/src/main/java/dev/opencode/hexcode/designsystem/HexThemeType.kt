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
    /** 30 / semibold, line 1.18, −0.028 em: the status sentence, the welcome title. */
    val display: TextStyle,
    /** 26 / semibold, −0.025 em: first-run headings. */
    val title: TextStyle,
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
                title = style(26f, FontWeight.SemiBold, (-0.025).em),
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
