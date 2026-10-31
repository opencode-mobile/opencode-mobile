package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val ActionPillShape = RoundedCornerShape(percent = 50)
private val ActionPillPaddingHorizontal = 14.dp
private val ActionPillPaddingVertical = 8.dp
private val ActionPillMinHeight = 36.dp
private val ActionPillIconSize = 16.dp
private val ActionPillIconSpacing = 6.dp
private val ActionPillShadowElevation = 4.dp

enum class OpenCodeActionPillVariant {
    ScrollToEnd,

    /** A hidden permission dock waiting above the composer. */
    ReviewPermission,

    /** A collapsed question panel waiting above the composer. */
    AnswerQuestion,
}

@Composable
fun OpenCodeActionPill(
    variant: OpenCodeActionPillVariant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val label =
        when (variant) {
            OpenCodeActionPillVariant.ScrollToEnd -> "Scroll to end"
            OpenCodeActionPillVariant.ReviewPermission -> "Review permission request"
            OpenCodeActionPillVariant.AnswerQuestion -> "Answer question"
        }
    val icon: ImageVector? =
        when (variant) {
            OpenCodeActionPillVariant.ScrollToEnd -> OpenCodeIcons.ArrowDownward
            OpenCodeActionPillVariant.ReviewPermission,
            OpenCodeActionPillVariant.AnswerQuestion,
            -> OpenCodeIcons.ArrowUpward
        }
    val accessibilityLabel = label

    val surfaceRaised = openCodeColor("surface-raised-base")
    val borderColor = openCodeColor("border-weaker-base")
    val contentColor =
        if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier =
            modifier
                .defaultMinSize(minHeight = ActionPillMinHeight)
                .semantics { contentDescription = accessibilityLabel },
        shape = ActionPillShape,
        color = surfaceRaised.copy(alpha = if (enabled) 0.95f else 0.7f),
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 2.dp,
        shadowElevation = if (enabled) ActionPillShadowElevation else 0.dp,
    ) {
        Row(
            modifier =
                Modifier.padding(
                    horizontal = ActionPillPaddingHorizontal,
                    vertical = ActionPillPaddingVertical,
                ),
            horizontalArrangement = Arrangement.spacedBy(ActionPillIconSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(ActionPillIconSize),
                    tint = contentColor,
                )
            }
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeActionPillScrollToEndPreview() {
    OpenCodeTheme(darkTheme = false) {
        OpenCodeActionPill(variant = OpenCodeActionPillVariant.ScrollToEnd, onClick = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeActionPillScrollToEndDarkPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeActionPill(variant = OpenCodeActionPillVariant.ScrollToEnd, onClick = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeActionPillDisabledPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeActionPill(
            variant = OpenCodeActionPillVariant.ScrollToEnd,
            enabled = false,
            onClick = {},
        )
    }
}
