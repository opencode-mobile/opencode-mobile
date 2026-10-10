package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.CodeTextStyle
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

enum class OpenCodeCodeSnippetSurface {
    /** Raised fill for a snippet sitting on the page background. */
    Raised,

    /** Base fill for a snippet sitting on a raised card. */
    Base,
}

/** Monospace command or code block. Span colors on [code] stay. */
@Composable
fun OpenCodeCodeSnippet(
    code: String,
    modifier: Modifier = Modifier,
    surface: OpenCodeCodeSnippetSurface = OpenCodeCodeSnippetSurface.Raised,
) {
    OpenCodeCodeSnippet(
        code = AnnotatedString(code),
        modifier = modifier,
        surface = surface,
        defaultColor = openCodeColor("text-strong"),
    )
}

/** Monospace block for syntax-colored text. Unspanned runs use text-strong. */
@Composable
fun OpenCodeCodeSnippet(
    code: AnnotatedString,
    modifier: Modifier = Modifier,
    surface: OpenCodeCodeSnippetSurface = OpenCodeCodeSnippetSurface.Base,
) {
    OpenCodeCodeSnippet(
        code = code,
        modifier = modifier,
        surface = surface,
        defaultColor = openCodeColor("text-strong"),
    )
}

@Composable
private fun OpenCodeCodeSnippet(
    code: AnnotatedString,
    modifier: Modifier,
    surface: OpenCodeCodeSnippetSurface,
    defaultColor: Color,
) {
    require(code.text.isNotBlank()) { "Code Snippet requires non-blank text" }
    val shape = RoundedCornerShape(10.dp)
    Text(
        text = code,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(surface.fill(), shape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        style = CodeTextStyle,
        color = defaultColor,
    )
}

@Composable
private fun OpenCodeCodeSnippetSurface.fill(): Color =
    when (this) {
        OpenCodeCodeSnippetSurface.Raised -> openCodeColor("surface-raised-base")
        OpenCodeCodeSnippetSurface.Base -> openCodeColor("surface-base")
    }

@Preview(showBackground = true)
@Composable
private fun OpenCodeCodeSnippetPreview() {
    OpenCodeTheme {
        OpenCodeCodeSnippet(code = "opencode serve")
    }
}
