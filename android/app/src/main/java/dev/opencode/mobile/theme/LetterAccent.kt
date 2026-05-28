package dev.opencode.mobile.theme

import androidx.compose.ui.graphics.Color

/** Semantic accent for the list-item letter badge; pairs map to component tokens in the design docs. */
enum class LetterAccent {
    Violet,
    Teal,
    Amber,
    Blue,
    Rose,
}

/** Returns [fill, tint] for the letter badge for the current theme. */
fun LetterAccent.badgeColors(useDarkTheme: Boolean): Pair<Color, Color> =
    when (this) {
        LetterAccent.Violet ->
            if (useDarkTheme) {
                DarkListItemLetterVioletFill to DarkListItemLetterVioletTint
            } else {
                LightListItemLetterVioletFill to LightListItemLetterVioletTint
            }
        LetterAccent.Teal ->
            if (useDarkTheme) {
                DarkListItemLetterTealFill to DarkListItemLetterTealTint
            } else {
                LightListItemLetterTealFill to LightListItemLetterTealTint
            }
        LetterAccent.Amber ->
            if (useDarkTheme) {
                DarkListItemLetterAmberFill to DarkListItemLetterAmberTint
            } else {
                LightListItemLetterAmberFill to LightListItemLetterAmberTint
            }
        LetterAccent.Blue ->
            if (useDarkTheme) {
                DarkListItemLetterBlueFill to DarkListItemLetterBlueTint
            } else {
                LightListItemLetterBlueFill to LightListItemLetterBlueTint
            }
        LetterAccent.Rose ->
            if (useDarkTheme) {
                DarkListItemLetterRoseFill to DarkListItemLetterRoseTint
            } else {
                LightListItemLetterRoseFill to LightListItemLetterRoseTint
            }
    }
