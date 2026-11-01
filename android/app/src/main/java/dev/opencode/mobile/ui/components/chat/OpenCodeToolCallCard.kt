package dev.opencode.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

private val ToolCallCardCornerRadius = OpenCodeRadius.small
private val ToolCallCardPaddingHorizontal = 12.dp
private val ToolCallCardPaddingVertical = 10.dp
private val ToolCallCardStatusDotSize = 6.dp
private val ToolCallCardStatusDotSpacing = 10.dp
private val ToolCallCardContentSpacing = 4.dp
private val ToolCallCardStatusDotTopPadding = 6.dp
private val ToolCallCardMetaRowSpacing = 8.dp

enum class OpenCodeToolCallCardStatus {
    Pending,
    Running,
    Completed,
    Error,

    /** Stopped by the user (Stop, Deny, Dismiss): neutral, not a failure. */
    Interrupted,
}

@Composable
fun OpenCodeToolCallCard(
    status: OpenCodeToolCallCardStatus,
    title: String,
    subtitle: String? = null,
    additions: Int? = null,
    deletions: Int? = null,
    errorText: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    require(additions == null || additions >= 0) { "Tool Call Card additions must be nonnegative" }
    require(deletions == null || deletions >= 0) { "Tool Call Card deletions must be nonnegative" }

    val accessibilityLabel =
        buildToolCallCardAccessibilityLabel(
            status = status,
            title = title,
            subtitle = subtitle,
            additions = additions,
            deletions = deletions,
            errorText = errorText,
        )
    val shape = RoundedCornerShape(ToolCallCardCornerRadius)

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = accessibilityLabel
                    if (onClick != null) {
                        role = Role.Button
                    }
                }.then(
                    if (onClick != null) {
                        Modifier
                            .clip(shape)
                            .openCodeFocusRing(shape)
                            .clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                ),
        shape = shape,
        color = openCodeColor("surface-raised-base"),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = ToolCallCardPaddingHorizontal,
                        vertical = ToolCallCardPaddingVertical,
                    ),
            horizontalArrangement = Arrangement.spacedBy(ToolCallCardStatusDotSpacing),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier =
                    Modifier
                        .padding(top = ToolCallCardStatusDotTopPadding)
                        .size(ToolCallCardStatusDotSize)
                        .background(status.dotColor(), CircleShape),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ToolCallCardContentSpacing),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                val hasSubtitle = !subtitle.isNullOrEmpty()
                val additionCount = additions ?: 0
                val deletionCount = deletions ?: 0
                val hasDiffCounts = additionCount > 0 || deletionCount > 0
                if (hasSubtitle || hasDiffCounts) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ToolCallCardMetaRowSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (hasSubtitle) {
                            Text(
                                text = subtitle.orEmpty(),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        if (hasDiffCounts) {
                            OpenCodeDiffStat(
                                additions = additionCount.takeIf { it > 0 },
                                deletions = deletionCount.takeIf { it > 0 },
                            )
                        }
                    }
                }

                if (!errorText.isNullOrEmpty()) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (status == OpenCodeToolCallCardStatus.Interrupted) {
                                openCodeColor("text-weak")
                            } else {
                                openCodeColor("error")
                            },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun OpenCodeToolCallCardStatus.dotColor(): Color =
    when (this) {
        OpenCodeToolCallCardStatus.Pending,
        OpenCodeToolCallCardStatus.Running,
        -> openCodeColor("warning")
        OpenCodeToolCallCardStatus.Completed -> openCodeColor("success")
        OpenCodeToolCallCardStatus.Error -> openCodeColor("error")
        OpenCodeToolCallCardStatus.Interrupted -> openCodeColor("text-weak")
    }

internal fun buildToolCallCardAccessibilityLabel(
    status: OpenCodeToolCallCardStatus,
    title: String,
    subtitle: String?,
    additions: Int?,
    deletions: Int?,
    errorText: String?,
): String {
    val parts = mutableListOf(status.name.lowercase(), title)
    if (!subtitle.isNullOrEmpty()) {
        parts += subtitle
    }
    if ((additions ?: 0) > 0) {
        parts += "+$additions"
    }
    if ((deletions ?: 0) > 0) {
        parts += "−$deletions"
    }
    if (!errorText.isNullOrEmpty()) {
        parts += errorText
    }
    return parts.joinToString(", ")
}

@Preview(showBackground = true, name = "Tool Call Card Light")
@Composable
private fun OpenCodeToolCallCardPreviewLight() {
    OpenCodeTheme(darkTheme = false) {
        ToolCallCardPreviewColumn()
    }
}

@Preview(showBackground = true, name = "Tool Call Card Dark")
@Composable
private fun OpenCodeToolCallCardPreviewDark() {
    OpenCodeTheme(darkTheme = true) {
        ToolCallCardPreviewColumn()
    }
}

@Composable
private fun ToolCallCardPreviewColumn() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OpenCodeToolCallCard(
            status = OpenCodeToolCallCardStatus.Pending,
            title = "Called bash",
            subtitle = "npm test",
        )
        OpenCodeToolCallCard(
            status = OpenCodeToolCallCardStatus.Running,
            title = "Called task",
            subtitle = "Explore codebase",
        )
        OpenCodeToolCallCard(
            status = OpenCodeToolCallCardStatus.Completed,
            title = "Called edit",
            subtitle = "OpenCodeListItem.swift",
            additions = 12,
            deletions = 3,
            onClick = {},
        )
        OpenCodeToolCallCard(
            status = OpenCodeToolCallCardStatus.Error,
            title = "Called webfetch",
            errorText = "Request failed with status 403",
        )
        OpenCodeToolCallCard(
            status = OpenCodeToolCallCardStatus.Interrupted,
            title = "Called shell",
            subtitle = "./deploy.sh",
            errorText = "Stopped",
        )
        OpenCodeToolCallCard(
            status = OpenCodeToolCallCardStatus.Completed,
            title = "Called skill",
        )
    }
}
