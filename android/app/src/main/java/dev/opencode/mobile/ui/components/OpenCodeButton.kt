package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val OpenCodeButtonCornerRadius = RoundedCornerShape(8.dp)
private val OpenCodeButtonBorderWidth = 1.dp
private val OpenCodeButtonTextHorizontalPadding = 14.dp
private val OpenCodeButtonTextVerticalPadding = 10.dp
private val OpenCodeButtonFullMinHeight = 48.dp
private val OpenCodeButtonCompactMinHeight = 40.dp
private val OpenCodeButtonIconSquareSize = 40.dp
private val OpenCodeButtonIconCircleSize = 40.dp
private val OpenCodeButtonIconSize = 18.dp

sealed interface OpenCodeButtonVariant {
    data class FullWidthText(
        val title: String,
    ) : OpenCodeButtonVariant

    data class CompactText(
        val title: String,
    ) : OpenCodeButtonVariant

    data class IconSquare(
        val imageVector: ImageVector,
        val contentDescription: String? = null,
    ) : OpenCodeButtonVariant

    data class IconCircle(
        val imageVector: ImageVector,
        val contentDescription: String? = null,
    ) : OpenCodeButtonVariant
}

enum class OpenCodeButtonContrast {
    High,
    Low,
}

enum class OpenCodeButtonSize {
    Large,
    Medium,
}

@Composable
fun OpenCodeButton(
    variant: OpenCodeButtonVariant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contrast: OpenCodeButtonContrast = OpenCodeButtonContrast.High,
    size: OpenCodeButtonSize = OpenCodeButtonSize.Medium,
) {
    val containerColor =
        when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant
            contrast == OpenCodeButtonContrast.Low -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        }
    val borderColor =
        when {
            !enabled -> MaterialTheme.colorScheme.outlineVariant
            contrast == OpenCodeButtonContrast.Low -> MaterialTheme.colorScheme.outlineVariant
            else -> MaterialTheme.colorScheme.onSurface
        }
    val contentColor =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            contrast == OpenCodeButtonContrast.Low -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.surface
        }

    val variantMinHeight =
        when (size) {
            OpenCodeButtonSize.Large -> OpenCodeButtonFullMinHeight
            OpenCodeButtonSize.Medium -> OpenCodeButtonCompactMinHeight
        }
    val iconSize =
        when (size) {
            OpenCodeButtonSize.Large -> 48.dp
            OpenCodeButtonSize.Medium -> 40.dp
        }
    val variantModifier =
        when (variant) {
            is OpenCodeButtonVariant.FullWidthText -> Modifier.fillMaxWidth().defaultMinSize(minHeight = variantMinHeight)
            is OpenCodeButtonVariant.CompactText -> Modifier.defaultMinSize(minHeight = variantMinHeight)
            is OpenCodeButtonVariant.IconSquare -> Modifier.size(iconSize)
            is OpenCodeButtonVariant.IconCircle -> Modifier.size(iconSize)
        }
    val shape =
        when (variant) {
            is OpenCodeButtonVariant.IconCircle -> CircleShape
            else -> OpenCodeButtonCornerRadius
        }

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.then(variantModifier),
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(OpenCodeButtonBorderWidth, borderColor),
    ) {
        when (variant) {
            is OpenCodeButtonVariant.FullWidthText ->
                Box(
                    modifier =
                        Modifier.padding(
                            horizontal = OpenCodeButtonTextHorizontalPadding,
                            vertical = OpenCodeButtonTextVerticalPadding,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = variant.title, style = MaterialTheme.typography.labelLarge)
                }
            is OpenCodeButtonVariant.CompactText ->
                Box(
                    modifier =
                        Modifier.padding(
                            horizontal = OpenCodeButtonTextHorizontalPadding,
                            vertical = OpenCodeButtonTextVerticalPadding,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = variant.title, style = MaterialTheme.typography.labelLarge)
                }
            is OpenCodeButtonVariant.IconSquare ->
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = variant.imageVector,
                        contentDescription = variant.contentDescription,
                        modifier = Modifier.size(OpenCodeButtonIconSize),
                        tint = contentColor,
                    )
                }
            is OpenCodeButtonVariant.IconCircle ->
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = variant.imageVector,
                        contentDescription = variant.contentDescription,
                        modifier = Modifier.size(OpenCodeButtonIconSize),
                        tint = contentColor,
                    )
                }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeButtonFullWidthPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeButton(
            variant = OpenCodeButtonVariant.FullWidthText("Run"),
            onClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeButtonCompactPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeButton(
            variant = OpenCodeButtonVariant.CompactText("Build"),
            onClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeButtonIconPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeButton(
            variant = OpenCodeButtonVariant.IconSquare(OpenCodeIcons.ArrowUpward, "Send"),
            onClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeButtonIconCirclePreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeButton(
            variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.ArrowUpward, "Send"),
            onClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeButtonLowContrastPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeButton(
            variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.ArrowUpward, "Attach context"),
            contrast = OpenCodeButtonContrast.Low,
            onClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeButtonDisabledPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeButton(
            variant = OpenCodeButtonVariant.FullWidthText("Run"),
            enabled = false,
            onClick = {},
        )
    }
}
