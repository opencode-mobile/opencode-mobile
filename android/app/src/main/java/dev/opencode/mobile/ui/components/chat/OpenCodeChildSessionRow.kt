package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val ChildSessionRowCornerRadius = OpenCodeRadius.small
private val ChildSessionRowPaddingHorizontal = 12.dp
private val ChildSessionRowPaddingVertical = 10.dp
private val ChildSessionRowAvatarSize = 32.dp
private val ChildSessionRowAvatarGlyphSize = 14.sp
private val ChildSessionRowContentSpacing = 12.dp
private val ChildSessionRowTitleRowSpacing = 8.dp
private val ChildSessionRowTextStackSpacing = 2.dp
private val ChildSessionRowTrailingSpacing = 6.dp
private val ChildSessionRowChevronSize = 16.dp

/** Where a child session row's subagent is, shown beside the elapsed time. */
enum class OpenCodeChildSessionRowStatus(
    val label: String,
) {
    Running("running"),

    /** Amber: the subagent waits on a permission or question. */
    WaitingForInput("waiting for input"),
    Done("done"),

    /** Error token: the subagent's call failed. */
    Failed("failed"),

    /** The run was stopped before the subagent finished. */
    Stopped("stopped"),
}

@Composable
fun OpenCodeChildSessionRow(
    title: String,
    caption: String? = null,
    subtitle: String? = null,
    elapsed: String? = null,
    status: OpenCodeChildSessionRowStatus? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val accessibilityLabel =
        buildChildSessionRowAccessibilityLabel(
            title = title,
            caption = caption,
            subtitle = subtitle,
            elapsed = elapsed,
            status = status?.label,
        )
    val shape = RoundedCornerShape(ChildSessionRowCornerRadius)

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
                            .clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                ),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = ChildSessionRowPaddingHorizontal,
                        vertical = ChildSessionRowPaddingVertical,
                    ),
            horizontalArrangement = Arrangement.spacedBy(ChildSessionRowContentSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(ChildSessionRowAvatarSize)
                        .background(openCodeColor("surface-raised-base"), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = childSessionRowLetter(from = title),
                    color = openCodeColor("text-strong"),
                    fontSize = ChildSessionRowAvatarGlyphSize,
                    fontWeight = FontWeight.Bold,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ChildSessionRowTextStackSpacing),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ChildSessionRowTitleRowSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!caption.isNullOrEmpty()) {
                        Text(
                            text = caption,
                            style = MaterialTheme.typography.bodySmall,
                            color = openCodeColor("text-weaker"),
                            maxLines = 1,
                        )
                    }
                }
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (status != null) {
                Text(
                    text = status.label,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        when (status) {
                            OpenCodeChildSessionRowStatus.WaitingForInput -> openCodeColor("warning")
                            OpenCodeChildSessionRowStatus.Failed -> MaterialTheme.colorScheme.error
                            else -> openCodeColor("text-weaker")
                        },
                    maxLines = 1,
                )
            }
            if (!elapsed.isNullOrEmpty()) {
                Text(
                    text = elapsed,
                    style = MaterialTheme.typography.bodySmall,
                    color = openCodeColor("text-weaker"),
                    maxLines = 1,
                )
            }
            if (onClick != null) {
                Icon(
                    imageVector = OpenCodeIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(ChildSessionRowChevronSize),
                    tint = openCodeColor("icon-strong-base"),
                )
            }
        }
    }
}

internal fun childSessionRowLetter(from: String): String {
    val first = from.firstOrNull() ?: return "?"
    return first.uppercaseChar().toString()
}

internal fun buildChildSessionRowAccessibilityLabel(
    title: String,
    caption: String?,
    subtitle: String?,
    elapsed: String?,
    status: String? = null,
): String =
    buildList {
        add(title)
        if (!caption.isNullOrEmpty()) add(caption)
        if (!subtitle.isNullOrEmpty()) add(subtitle)
        if (!status.isNullOrEmpty()) add(status)
        if (!elapsed.isNullOrEmpty()) add(elapsed)
    }.joinToString(", ")

@Preview(showBackground = true, name = "Child Session Row Light")
@Composable
private fun OpenCodeChildSessionRowPreviewLight() {
    OpenCodeTheme(darkTheme = false) {
        ChildSessionRowPreviewColumn()
    }
}

@Preview(showBackground = true, name = "Child Session Row Dark")
@Composable
private fun OpenCodeChildSessionRowPreviewDark() {
    OpenCodeTheme(darkTheme = true) {
        ChildSessionRowPreviewColumn()
    }
}

@Composable
private fun ChildSessionRowPreviewColumn() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OpenCodeChildSessionRow(
            title = "explore",
            subtitle = "Searching the repo",
            elapsed = "1:04",
            onClick = {},
        )
        OpenCodeChildSessionRow(
            title = "explore",
            caption = "background",
            subtitle = "Find auth bugs",
            elapsed = "0:12",
            onClick = {},
        )
        OpenCodeChildSessionRow(
            title = "review",
            subtitle = "Standards review of OPE-144",
            onClick = {},
        )
        OpenCodeChildSessionRow(
            title = "Task",
            subtitle = "Investigate flaky tests",
        )
    }
}
