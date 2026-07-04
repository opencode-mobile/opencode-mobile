package dev.opencode.mobile.ui.componentgallery.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.ThemeManager
import dev.opencode.mobile.theme.ThemeResolver

private data class ColorToken(
    val name: String,
    val light: Color,
    val lightHex: String,
    val dark: Color,
    val darkHex: String,
)

private val TokenNames =
    listOf(
        "background-base",
        "surface-base",
        "surface-base-hover",
        "surface-raised-base",
        "surface-raised-base-hover",
        "surface-interactive-weak",
        "surface-success-base",
        "surface-critical-base",
        "text-strong",
        "text-base",
        "text-weak",
        "text-weaker",
        "text-interactive-base",
        "border-weak-base",
        "border-weaker-base",
        "icon-base",
        "icon-weak-base",
        "icon-strong-base",
        "button-primary-base",
        "button-secondary-base",
        "interactive",
        "success",
        "warning",
        "error",
        "info",
        "diff-add",
        "diff-delete",
        "syntax-comment",
        "syntax-keyword",
        "syntax-string",
        "syntax-primitive",
        "syntax-property",
        "syntax-type",
        "syntax-constant",
        "syntax-variable",
        "syntax-operator",
        "syntax-punctuation",
        "syntax-critical",
        "syntax-diff-delete",
        "syntax-diff-unknown",
    )

@Composable
fun ColorGalleryScreen(modifier: Modifier = Modifier) {
    val parse = { hex: String -> ThemeResolver.parseHexColor(hex) }
    val tokens =
        TokenNames.map { name ->
            val lightHex = ThemeManager.resolvedLight[name]
            val darkHex = ThemeManager.resolvedDark[name]
            ColorToken(name, parse(lightHex), lightHex, parse(darkHex), darkHex)
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "OpenCode Desktop Tokens (${ThemeManager.currentTheme.name})",
            style = MaterialTheme.typography.titleMedium,
        )
        tokens.forEach { token -> ColorTokenRow(token) }
    }
}

@Composable
private fun ColorTokenRow(
    token: ColorToken,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = token.name, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ColorSwatch(label = "Light", color = token.light, hex = token.lightHex, modifier = Modifier.weight(1f))
                ColorSwatch(label = "Dark", color = token.dark, hex = token.darkHex, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ColorSwatch(
    label: String,
    color: Color,
    hex: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(color = color, shape = RoundedCornerShape(8.dp))
                    .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(8.dp)),
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = hex, style = MaterialTheme.typography.bodySmall)
    }
}
