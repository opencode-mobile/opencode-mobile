package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun OpenCodeHunkNavigationPill(
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    contextEnabled: Boolean,
    contextExpanded: Boolean,
    onPrevious: () -> Unit,
    onToggleContext: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = openCodeColor("surface-raised-base"),
        border = BorderStroke(1.dp, openCodeColor("border-weaker-base")),
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.width(132.dp).defaultMinSize(minHeight = 44.dp)) {
            HunkNavigationButton(
                icon = OpenCodeIcons.ArrowUpward,
                label = "Previous hunk",
                enabled = previousEnabled,
                onClick = onPrevious,
            )
            HunkNavigationButton(
                icon = if (contextExpanded) OpenCodeIcons.CollapseContent else OpenCodeIcons.ExpandContent,
                label = if (contextExpanded) "Collapse context" else "Expand context",
                enabled = contextEnabled,
                onClick = onToggleContext,
            )
            HunkNavigationButton(
                icon = OpenCodeIcons.ArrowDownward,
                label = "Next hunk",
                enabled = nextEnabled,
                onClick = onNext,
            )
        }
    }
}

@Composable
private fun HunkNavigationButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        enabled = enabled,
        interactionSource = interactionSource,
        colors =
            IconButtonDefaults.iconButtonColors(
                containerColor =
                    if (pressed && enabled) {
                        openCodeColor("surface-raised-base-hover")
                    } else {
                        androidx.compose.ui.graphics.Color.Transparent
                    },
                contentColor = openCodeColor("icon-strong-base"),
                disabledContentColor = openCodeColor("icon-weak-base"),
            ),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun HunkNavigationPillLightPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeHunkNavigationPill(false, true, true, false, {}, {}, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun HunkNavigationPillDarkPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeHunkNavigationPill(true, false, true, true, {}, {}, {})
    }
}
