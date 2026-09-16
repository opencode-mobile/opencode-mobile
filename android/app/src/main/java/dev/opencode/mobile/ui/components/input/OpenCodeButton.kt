package dev.opencode.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
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

    data class IconCircleText(
        val text: String,
        val contentDescription: String? = null,
    ) : OpenCodeButtonVariant
}

enum class OpenCodeButtonContrast {
    High,
    Low,
    Accent,
    Destructive,
}

enum class OpenCodeButtonSize {
    Large,
    Medium,
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OpenCodeButton(
    variant: OpenCodeButtonVariant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contrast: OpenCodeButtonContrast = OpenCodeButtonContrast.High,
    size: OpenCodeButtonSize = OpenCodeButtonSize.Medium,
    onLongClick: (() -> Unit)? = null,
) {
    val targetContainer =
        when {
            !enabled && contrast == OpenCodeButtonContrast.Accent ->
                lerp(openCodeColor("surface-raised-base"), openCodeColor("interactive"), 0.10f)
            !enabled -> MaterialTheme.colorScheme.surfaceVariant
            contrast == OpenCodeButtonContrast.Low -> MaterialTheme.colorScheme.surfaceVariant
            contrast == OpenCodeButtonContrast.Accent -> openCodeColor("interactive")
            contrast == OpenCodeButtonContrast.Destructive -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        }
    val targetBorder =
        when {
            !enabled && contrast == OpenCodeButtonContrast.Accent ->
                lerp(openCodeColor("surface-raised-base"), openCodeColor("interactive"), 0.10f)
            !enabled -> MaterialTheme.colorScheme.outlineVariant
            contrast == OpenCodeButtonContrast.Low -> MaterialTheme.colorScheme.outlineVariant
            contrast == OpenCodeButtonContrast.Accent -> openCodeColor("interactive")
            contrast == OpenCodeButtonContrast.Destructive -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        }
    val targetContent =
        when {
            !enabled && contrast == OpenCodeButtonContrast.Accent -> openCodeColor("text-weak")
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            contrast == OpenCodeButtonContrast.Low -> MaterialTheme.colorScheme.onSurface
            contrast == OpenCodeButtonContrast.Accent -> openCodeColor("text-on-brand-base")
            contrast == OpenCodeButtonContrast.Destructive -> MaterialTheme.colorScheme.onError
            else -> MaterialTheme.colorScheme.surface
        }
    val isAccent = contrast == OpenCodeButtonContrast.Accent
    val accentColorSpring =
        spring<Color>(
            dampingRatio = 0.68f,
            stiffness = Spring.StiffnessMediumLow,
        )
    val animatedContainer by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = accentColorSpring,
        label = "OpenCodeButton container",
    )
    val animatedBorder by animateColorAsState(
        targetValue = targetBorder,
        animationSpec = accentColorSpring,
        label = "OpenCodeButton border",
    )
    val animatedContent by animateColorAsState(
        targetValue = targetContent,
        animationSpec = accentColorSpring,
        label = "OpenCodeButton content",
    )
    val containerColor = if (isAccent) animatedContainer else targetContainer
    val borderColor = if (isAccent) animatedBorder else targetBorder
    val contentColor = if (isAccent) animatedContent else targetContent
    val accentScale by animateFloatAsState(
        targetValue = if (isAccent && !enabled) 0.96f else 1f,
        animationSpec =
            spring(
                dampingRatio = 0.68f,
                stiffness = Spring.StiffnessMediumLow,
            ),
        label = "OpenCodeButton accent scale",
    )

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
            is OpenCodeButtonVariant.IconCircleText -> Modifier.size(iconSize)
        }
    val shape =
        when (variant) {
            is OpenCodeButtonVariant.IconCircle -> CircleShape
            is OpenCodeButtonVariant.IconCircleText -> CircleShape
            else -> OpenCodeButtonCornerRadius
        }

    val haptic = LocalHapticFeedback.current
    val interaction =
        if (onLongClick != null) {
            Modifier.combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
        } else {
            Modifier.clickable(enabled = enabled, onClick = onClick)
        }
    Surface(
        modifier =
            modifier
                .then(variantModifier)
                .graphicsLayer {
                    scaleX = accentScale
                    scaleY = accentScale
                }.clip(shape)
                .then(interaction),
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
                    Text(
                        text = variant.title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
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
            is OpenCodeButtonVariant.IconCircleText ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        variant.contentDescription?.let {
                            Modifier.semantics { contentDescription = it }
                        } ?: Modifier,
                ) {
                    Text(
                        text = variant.text,
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor,
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
private fun OpenCodeButtonIconCircleTextPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeButton(
            variant = OpenCodeButtonVariant.IconCircleText("/", "Open commands"),
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
