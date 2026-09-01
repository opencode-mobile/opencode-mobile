package dev.opencode.mobile.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val ActionTileShape = RoundedCornerShape(14.dp)
private val ActionTileMinHeight = 88.dp
private val ActionTilePaddingTop = 18.dp
private val ActionTilePaddingHorizontal = 14.dp
private val ActionTilePaddingBottom = 16.dp
private val ActionTileIconSize = 24.dp
private val ActionTileContentGap = 12.dp

@Composable
fun OpenCodeActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accessibilityLabel: String = label,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (enabled && isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium),
        label = "actionTilePressScale",
    )
    val fill =
        if (enabled && isPressed) {
            openCodeColor("surface-raised-base-hover")
        } else {
            openCodeColor("surface-raised-base")
        }
    val iconColor =
        if (enabled) {
            openCodeColor("icon-strong-base")
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val labelColor =
        if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .scale(pressScale)
                .clip(ActionTileShape)
                .background(fill)
                .defaultMinSize(minHeight = ActionTileMinHeight)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true),
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(
                    start = ActionTilePaddingHorizontal,
                    top = ActionTilePaddingTop,
                    end = ActionTilePaddingHorizontal,
                    bottom = ActionTilePaddingBottom,
                )
                .semantics { contentDescription = accessibilityLabel }
                .then(if (enabled) Modifier else Modifier.alpha(0.7f)),
        verticalArrangement = Arrangement.spacedBy(ActionTileContentGap),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(ActionTileIconSize),
            tint = iconColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeActionTilePreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeActionTile(
            icon = OpenCodeIcons.Add,
            label = "New",
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeActionTileDarkPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeActionTile(
            icon = OpenCodeIcons.Add,
            label = "New",
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeActionTileDisabledPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeActionTile(
            icon = OpenCodeIcons.Add,
            label = "New",
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
