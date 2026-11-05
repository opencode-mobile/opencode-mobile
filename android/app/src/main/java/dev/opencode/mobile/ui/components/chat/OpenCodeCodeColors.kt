package dev.opencode.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.opencode.mobile.markdown.CodeTokenRole
import dev.opencode.mobile.theme.LocalOpenCodeDarkTheme
import dev.opencode.mobile.theme.ThemeManager
import dev.opencode.mobile.theme.ThemeResolver

/**
 * The one role-to-token table for highlighted code. Every surface that shows highlighted code
 * (chat code fences, tool results, the File Viewer, the Highlighting Lab, settings previews, and
 * the Component Gallery) colors roles through it.
 */
object CodeRoleTokens {
    /** Editor background of the active syntax preset; Match theme and some presets leave it out. */
    const val BACKGROUND = "syntax-background"

    /** Editor plain-text color of the active syntax preset; left out together with [BACKGROUND]. */
    const val FOREGROUND = "syntax-foreground"

    /** Code text color when the syntax preset has no [FOREGROUND]. */
    const val FALLBACK_TEXT = "text-strong"

    /** The token that colors [role], or null when the role draws in the plain code text color. */
    fun tokenFor(role: CodeTokenRole): String? =
        when (role) {
            CodeTokenRole.Keyword -> "syntax-keyword"
            CodeTokenRole.String -> "syntax-string"
            CodeTokenRole.Number -> "syntax-number"
            CodeTokenRole.Constant -> "syntax-constant"
            CodeTokenRole.Comment -> "syntax-comment"
            CodeTokenRole.Type -> "syntax-type"
            CodeTokenRole.Function -> "syntax-function"
            CodeTokenRole.Property -> "syntax-property"
            CodeTokenRole.Attribute -> "syntax-attribute"
            CodeTokenRole.Tag -> "syntax-keyword"
            CodeTokenRole.Inserted -> "diff-add"
            CodeTokenRole.Deleted -> "diff-delete"
            CodeTokenRole.Warning -> "syntax-diff-unknown"
            CodeTokenRole.Critical -> "syntax-critical"
            CodeTokenRole.Variable,
            CodeTokenRole.Operator,
            CodeTokenRole.Punctuation,
            CodeTokenRole.Plain,
            -> null
        }
}

/**
 * Resolved colors for one code surface: a color per role, the plain code text color, and the
 * syntax preset's editor background when it has one.
 */
@Immutable
internal data class CodeColors(
    /** Plain code text: the preset's [CodeRoleTokens.FOREGROUND], else [CodeRoleTokens.FALLBACK_TEXT]. */
    val plain: Color,
    /** The preset's [CodeRoleTokens.BACKGROUND]; null keeps the surface's own UI-theme background. */
    val background: Color?,
    private val roles: Map<CodeTokenRole, Color>,
) {
    fun colorFor(role: CodeTokenRole): Color = roles[role] ?: plain

    /** The code surface background: the preset's editor background, else [fallback]. */
    fun surface(fallback: Color): Color = background ?: fallback

    companion object {
        /** Colors from resolved theme [tokens], such as the active theme with its syntax preset. */
        fun from(tokens: Map<String, String>): CodeColors {
            fun color(token: String) = tokens[token]?.let(ThemeResolver::parseHexColor)
            val plain = color(CodeRoleTokens.FOREGROUND) ?: color(CodeRoleTokens.FALLBACK_TEXT) ?: Color.Unspecified
            return CodeColors(
                plain = plain,
                background = color(CodeRoleTokens.BACKGROUND),
                roles =
                    CodeTokenRole.entries
                        .mapNotNull { role ->
                            CodeRoleTokens.tokenFor(role)?.let { token -> color(token)?.let { role to it } }
                        }.toMap(),
            )
        }
    }
}

/** Code colors of the active theme and syntax preset. */
@Composable
internal fun codeColors(): CodeColors {
    val tokens = if (LocalOpenCodeDarkTheme.current) ThemeManager.resolvedDark else ThemeManager.resolvedLight
    return remember(tokens) { CodeColors.from(tokens.tokens) }
}
