package dev.opencode.mobile.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
private val ActionTileBadgeInset = 12.dp
private val ActionTileBadgeDotSize = 10.dp
private val ActionTileBadgeCountHeight = 18.dp

/** Semantic tone for the corner badge on an action tile. */
enum class OpenCodeActionTileBadgeTone {
    Success,
    Warning,
    Error,
    Neutral,
}

/**
 * Corner status badge for [OpenCodeActionTile]: a dot when [count] is null,
 * otherwise a small count pill. Tone conveys health, never raw colour.
 */
data class OpenCodeActionTileBadge(
    val tone: OpenCodeActionTileBadgeTone,
    val count: Int? = null,
)

@Composable
fun OpenCodeActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accessibilityLabel: String = label,
    badge: OpenCodeActionTileBadge? = null,
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

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .scale(pressScale)
                .clip(ActionTileShape)
                .background(fill)
                .defaultMinSize(minHeight = ActionTileMinHeight)
                .openCodeFocusRing(ActionTileShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true),
                    enabled = enabled,
                    onClick = onClick,
                ).semantics { contentDescription = accessibilityLabel }
                .then(if (enabled) Modifier else Modifier.alpha(0.7f)),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = ActionTilePaddingHorizontal,
                        top = ActionTilePaddingTop,
                        end = ActionTilePaddingHorizontal,
                        bottom = ActionTilePaddingBottom,
                    ),
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
        if (badge != null) {
            ActionTileBadge(
                badge = badge,
                modifier = Modifier.align(Alignment.TopEnd).padding(ActionTileBadgeInset),
            )
        }
    }
}

@Composable
private fun ActionTileBadge(
    badge: OpenCodeActionTileBadge,
    modifier: Modifier = Modifier,
) {
    val color =
        when (badge.tone) {
            OpenCodeActionTileBadgeTone.Success -> openCodeColor("success")
            OpenCodeActionTileBadgeTone.Warning -> openCodeColor("warning")
            OpenCodeActionTileBadgeTone.Error -> openCodeColor("error")
            OpenCodeActionTileBadgeTone.Neutral -> openCodeColor("text-weak")
        }
    val count = badge.count
    if (count == null) {
        Box(
            modifier =
                modifier
                    .size(ActionTileBadgeDotSize)
                    .clip(CircleShape)
                    .background(color),
        )
    } else {
        Box(
            modifier =
                modifier
                    .defaultMinSize(minWidth = ActionTileBadgeCountHeight, minHeight = ActionTileBadgeCountHeight)
                    .clip(RoundedCornerShape(ActionTileBadgeCountHeight / 2))
                    .background(color)
                    .padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.surface,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun OpenCodeActionTileGrid(
    itemCount: Int,
    modifier: Modifier = Modifier,
    item: @Composable (index: Int) -> Unit,
) {
    val columns =
        if (currentOpenCodeLayoutWidth() == OpenCodeLayoutWidth.Regular) {
            OpenCodeLayout.actionTileColumnCount(OpenCodeLayout.SheetMaxWidth)
        } else {
            OpenCodeLayout.ACTION_TILE_GRID_MIN_COLUMNS
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = OpenCodeLayout.ActionTileGridPaddingHorizontal),
        verticalArrangement = Arrangement.spacedBy(OpenCodeLayout.ActionTileGridGap),
    ) {
        val rows = if (itemCount == 0) 0 else (itemCount + columns - 1) / columns
        repeat(rows) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OpenCodeLayout.ActionTileGridGap),
            ) {
                repeat(columns) { column ->
                    val index = row * columns + column
                    if (index < itemCount) {
                        Box(modifier = Modifier.weight(1f)) {
                            item(index)
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
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
private fun OpenCodeActionTileBadgePreview() {
    OpenCodeTheme(darkTheme = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OpenCodeActionTile(
                icon = OpenCodeIcons.Plug,
                label = "MCP servers",
                onClick = {},
                badge = OpenCodeActionTileBadge(OpenCodeActionTileBadgeTone.Success),
                modifier = Modifier.weight(1f),
            )
            OpenCodeActionTile(
                icon = OpenCodeIcons.Plug,
                label = "MCP servers",
                onClick = {},
                badge = OpenCodeActionTileBadge(OpenCodeActionTileBadgeTone.Error, count = 2),
                modifier = Modifier.weight(1f),
            )
        }
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
