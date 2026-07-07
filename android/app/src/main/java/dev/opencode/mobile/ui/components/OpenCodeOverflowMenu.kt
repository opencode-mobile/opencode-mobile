package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlin.math.roundToInt

private val OverflowMenuCornerRadius = 8.dp
private val OverflowMenuMinWidth = 200.dp
private val OverflowMenuPaddingVertical = 4.dp
private val OverflowMenuEntryMinHeight = 48.dp
private val OverflowMenuEntryPaddingHorizontal = 12.dp
private val OverflowMenuEntryIconSize = 20.dp
private val OverflowMenuEntryIconSpacing = 12.dp
private val OverflowMenuAnchorEndInset = 4.dp
private val OverflowMenuAnchorTopOffset = 8.dp

data class OpenCodeOverflowMenuEntry(
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val contentDescription: String? = null,
)

@Composable
fun OpenCodeTopBarOverflowHost(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    entries: List<OpenCodeOverflowMenuEntry>,
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val endInsetPx = with(density) { OverflowMenuAnchorEndInset.roundToPx() }
    val topOffsetPx = with(density) { OverflowMenuAnchorTopOffset.roundToPx() }
    var topBarBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }

    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier =
                Modifier.onGloballyPositioned { coordinates ->
                    topBarBounds = coordinates.boundsInWindow()
                },
        ) {
            topBar()
        }

        if (expanded) {
            val positionProvider =
                remember(topBarBounds, endInsetPx, topOffsetPx) {
                    OverflowMenuPopupPositionProvider(
                        topBarBounds = topBarBounds,
                        endInsetPx = endInsetPx,
                        topOffsetPx = topOffsetPx,
                    )
                }
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = onDismissRequest,
                properties =
                    PopupProperties(
                        focusable = true,
                        dismissOnBackPress = true,
                        dismissOnClickOutside = true,
                        usePlatformDefaultWidth = false,
                    ),
            ) {
                OpenCodeOverflowMenuPanel(
                    entries = entries,
                    onDismissRequest = onDismissRequest,
                )
            }
        }
    }
}

private class OverflowMenuPopupPositionProvider(
    private val topBarBounds: androidx.compose.ui.geometry.Rect,
    private val endInsetPx: Int,
    private val topOffsetPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): androidx.compose.ui.unit.IntOffset {
        return androidx.compose.ui.unit.IntOffset(
            x = topBarBounds.right.roundToInt() - popupContentSize.width - endInsetPx,
            y = topBarBounds.bottom.roundToInt() + topOffsetPx,
        )
    }
}

@Composable
private fun OpenCodeOverflowMenuPanel(
    entries: List<OpenCodeOverflowMenuEntry>,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .wrapContentWidth(Alignment.End)
                .widthIn(min = OverflowMenuMinWidth),
        shape = RoundedCornerShape(OverflowMenuCornerRadius),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .wrapContentWidth(Alignment.Start)
                    .widthIn(min = OverflowMenuMinWidth)
                    .padding(vertical = OverflowMenuPaddingVertical),
        ) {
            entries.forEach { entry ->
                OverflowMenuEntryRow(
                    entry = entry,
                    onDismissRequest = onDismissRequest,
                )
            }
        }
    }
}

@Composable
private fun OverflowMenuEntryRow(
    entry: OpenCodeOverflowMenuEntry,
    onDismissRequest: () -> Unit,
) {
    val contentColor =
        if (entry.enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val iconColor =
        if (entry.enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        }
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier =
            Modifier
                .widthIn(min = OverflowMenuMinWidth)
                .defaultMinSize(minHeight = OverflowMenuEntryMinHeight)
                .clickable(
                    enabled = entry.enabled,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        onDismissRequest()
                        entry.onClick()
                    },
                )
                .padding(
                    horizontal = OverflowMenuEntryPaddingHorizontal,
                    vertical = 12.dp,
                )
                .then(if (entry.enabled) Modifier else Modifier.alpha(0.7f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OverflowMenuEntryIconSpacing),
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = entry.contentDescription,
            modifier = Modifier.size(OverflowMenuEntryIconSize),
            tint = iconColor,
        )
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeOverflowMenuLightPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeTopBarOverflowHost(
            expanded = true,
            onDismissRequest = {},
            entries = previewOverflowMenuEntries(),
        ) {
            OpenCodeTopBar(
                title = "Session",
                leading = OpenCodeTopBarLeading.Back(onClick = {}),
                actions =
                    listOf(
                        OpenCodeTopBarAction.Icon(
                            imageVector = OpenCodeIcons.MoreVert,
                            contentDescription = "More options",
                            onClick = {},
                        ),
                    ),
                showDivider = true,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeOverflowMenuDarkPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeTopBarOverflowHost(
            expanded = true,
            onDismissRequest = {},
            entries = previewOverflowMenuEntries(),
        ) {
            OpenCodeTopBar(
                title = "Session",
                leading = OpenCodeTopBarLeading.Back(onClick = {}),
                showDivider = true,
            )
        }
    }
}

private fun previewOverflowMenuEntries(): List<OpenCodeOverflowMenuEntry> =
    listOf(
        OpenCodeOverflowMenuEntry(
            title = "Session context",
            icon = OpenCodeIcons.Architecture,
            onClick = {},
        ),
        OpenCodeOverflowMenuEntry(
            title = "Copy session ID",
            icon = OpenCodeIcons.ContentCopy,
            onClick = {},
        ),
        OpenCodeOverflowMenuEntry(
            title = "Disabled action",
            icon = OpenCodeIcons.Settings,
            enabled = false,
            onClick = {},
        ),
    )
