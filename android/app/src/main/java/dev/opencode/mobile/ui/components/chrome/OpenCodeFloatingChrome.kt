package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor

/** Default corner radius for floating chrome surfaces (dock, composer shell). */
val OpenCodeFloatingChromeCornerRadius = 26.dp

/**
 * Shared floating raised-surface chrome used by Floating Dock, Session Composer,
 * and composer-anchored suggestion overlays. Callers own insets, content
 * padding, and whether to draw a border.
 */
@Composable
fun OpenCodeFloatingChrome(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(OpenCodeFloatingChromeCornerRadius),
    border: BorderStroke? = null,
    tonalElevation: Dp = 2.dp,
    shadowElevation: Dp = 4.dp,
    /**
     * Fully opaque fill for large surfaces over the timeline (Session Composer and its
     * docks): the 95% translucent fill lets timeline rows show through as faint shapes.
     */
    opaque: Boolean = false,
    content: @Composable () -> Unit,
) {
    val surfaceRaised = openCodeColor("surface-raised-base")
    Surface(
        modifier = modifier,
        shape = shape,
        color = if (opaque) surfaceRaised else surfaceRaised.copy(alpha = 0.95f),
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
    ) {
        Box { content() }
    }
}
