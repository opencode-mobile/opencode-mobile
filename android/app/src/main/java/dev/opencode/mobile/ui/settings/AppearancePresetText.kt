package dev.opencode.mobile.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.opencode.mobile.R
import dev.opencode.mobile.theme.SyntaxHighlightPreset
import dev.opencode.mobile.theme.ThemePreset

// Preset names such as Porcelain or Nocturne are proper names from the preset files and show
// as is. Match theme and the built-in presets' descriptions are app text, keyed by preset id;
// a preset the app does not know keeps the text from its file.

/** The syntax preset's name: "Match theme" is keyed, other names are preset data. */
@Composable
internal fun SyntaxHighlightPreset.displayName(): String =
    if (isMatch) stringResource(R.string.appearance_syntax_name_match) else name

/** The syntax preset's description, keyed for the built-in presets. */
@Composable
internal fun SyntaxHighlightPreset.displayBlurb(): String =
    when (id) {
        SyntaxHighlightPreset.MATCH_SYNTAX_PRESET_ID -> stringResource(R.string.appearance_syntax_blurb_match)
        "nocturne" -> stringResource(R.string.appearance_syntax_blurb_nocturne)
        "citrus" -> stringResource(R.string.appearance_syntax_blurb_citrus)
        "paper" -> stringResource(R.string.appearance_syntax_blurb_paper)
        "neon" -> stringResource(R.string.appearance_syntax_blurb_neon)
        else -> blurb
    }

/** The theme preset's description, keyed for the built-in presets. */
@Composable
internal fun ThemePreset.displayBlurb(): String = builtInThemeBlurb(id) ?: blurb.ifBlank { id }

/** A built-in theme's description by id, or [id] itself for a theme the app does not know. */
@Composable
internal fun themeBlurb(id: String): String = builtInThemeBlurb(id) ?: id

@Composable
private fun builtInThemeBlurb(id: String): String? =
    when (id) {
        "porcelain" -> stringResource(R.string.appearance_theme_blurb_porcelain)
        "linen" -> stringResource(R.string.appearance_theme_blurb_linen)
        "signal" -> stringResource(R.string.appearance_theme_blurb_signal)
        "hotline" -> stringResource(R.string.appearance_theme_blurb_hotline)
        "phosphor" -> stringResource(R.string.appearance_theme_blurb_phosphor)
        else -> null
    }
