package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.theme.OpenCodeRadius
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val TaskRowCornerRadius = OpenCodeRadius.small
private val TaskRowPaddingHorizontal = 12.dp
private val TaskRowPaddingVertical = 10.dp
private val TaskRowGlyphFrameSize = 32.dp
private val TaskRowSpinnerSize = 14.dp
private val TaskRowIconSize = 16.dp
private val TaskRowDotSize = 8.dp
private val TaskRowContentSpacing = 12.dp
private val TaskRowTitleRowSpacing = 6.dp
private val TaskRowTextStackSpacing = 2.dp
private val TaskRowChevronSize = 16.dp

/** Where a background task (a subagent) is; drives the leading glyph and the detail tone. */
enum class OpenCodeTaskRowState(
    val label: String,
) {
    /** Spinner. */
    Running("running"),

    /** Amber dot and amber detail: the task waits on a permission or question. */
    WaitingForInput("waiting for input"),

    /** Check mark. */
    Done("done"),

    /** `!` and the detail (the reason) in the error tone. */
    Failed("failed"),

    /** `!` and the detail (the reason) in the error tone; the run was stopped first. */
    Stopped("stopped"),
}

/**
 * A background task in a list, such as a subagent in the background work panel. The title
 * leads, an optional [tag] marks a non-default agent, [detail] is one line under it (the
 * current step, or the result), and the trailing column holds [time] (a live timer or the
 * run time) over [detailCaption] ("12 tools", "4m ago"). Lines up with `OpenCodeShellRow`.
 */
@Composable
fun OpenCodeTaskRow(
    title: String,
    state: OpenCodeTaskRowState,
    modifier: Modifier = Modifier,
    tag: String? = null,
    detail: String? = null,
    detailCaption: String? = null,
    time: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val accessibilityLabel =
        buildTaskRowAccessibilityLabel(
            title = title,
            tag = tag,
            state = state,
            detail = detail,
            detailCaption = detailCaption,
            time = time,
        )
    val shape = RoundedCornerShape(TaskRowCornerRadius)
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = accessibilityLabel
                    if (onClick != null) role = Role.Button
                }.then(if (onClick != null) Modifier.clip(shape).openCodeControl().clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TaskRowPaddingHorizontal, vertical = TaskRowPaddingVertical),
            horizontalArrangement = Arrangement.spacedBy(TaskRowContentSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskRowGlyph(state)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(TaskRowTextStackSpacing),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TaskRowTitleRowSpacing),
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
                    if (!tag.isNullOrBlank()) {
                        OpenCodeTag(title = tag, size = OpenCodeTagSize.Small)
                    }
                }
                if (!detail.isNullOrEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = detailColor(state),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!time.isNullOrEmpty() || !detailCaption.isNullOrEmpty()) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(TaskRowTextStackSpacing),
                ) {
                    if (!time.isNullOrEmpty()) {
                        Text(
                            text = time,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            color = openCodeColor("text-weaker"),
                            maxLines = 1,
                        )
                    }
                    if (!detailCaption.isNullOrEmpty()) {
                        Text(
                            text = detailCaption,
                            style = MaterialTheme.typography.bodySmall,
                            color = openCodeColor("text-weaker"),
                            maxLines = 1,
                        )
                    }
                }
            }
            if (onClick != null) {
                Icon(
                    imageVector = OpenCodeIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(TaskRowChevronSize),
                    tint = openCodeColor("icon-strong-base"),
                )
            }
        }
    }
}

@Composable
private fun detailColor(state: OpenCodeTaskRowState): Color =
    when (state) {
        OpenCodeTaskRowState.Failed, OpenCodeTaskRowState.Stopped -> MaterialTheme.colorScheme.error
        OpenCodeTaskRowState.WaitingForInput -> openCodeColor("warning")
        OpenCodeTaskRowState.Running, OpenCodeTaskRowState.Done -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun TaskRowGlyph(state: OpenCodeTaskRowState) {
    Box(
        modifier =
            Modifier
                .size(TaskRowGlyphFrameSize)
                .background(openCodeColor("surface-raised-base"), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            OpenCodeTaskRowState.Running -> {
                val color = openCodeColor("text-strong")
                CircularProgressIndicator(
                    modifier = Modifier.size(TaskRowSpinnerSize),
                    strokeWidth = 2.dp,
                    color = color,
                    trackColor = color.copy(alpha = 0.25f),
                )
            }
            OpenCodeTaskRowState.WaitingForInput ->
                OpenCodePulsingDot(color = openCodeColor("warning"), size = TaskRowDotSize)
            OpenCodeTaskRowState.Done ->
                Icon(
                    imageVector = OpenCodeIcons.Check,
                    contentDescription = null,
                    modifier = Modifier.size(TaskRowIconSize),
                    tint = openCodeColor("success"),
                )
            OpenCodeTaskRowState.Failed, OpenCodeTaskRowState.Stopped -> {
                val color = MaterialTheme.colorScheme.error
                Box(
                    modifier = Modifier.size(TaskRowIconSize).border(1.5.dp, color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "!", color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

/** "Find float money math, explore, running, Reading format.ts, 12 tools, 1:04". */
internal fun buildTaskRowAccessibilityLabel(
    title: String,
    tag: String?,
    state: OpenCodeTaskRowState,
    detail: String?,
    detailCaption: String?,
    time: String?,
): String =
    listOf(title, tag, state.label, detail, detailCaption, time)
        .filterNot { it.isNullOrBlank() }
        .joinToString(", ")

@Preview(showBackground = true, name = "Task Row Light")
@Composable
private fun OpenCodeTaskRowPreviewLight() {
    OpenCodeTheme(darkTheme = false) { TaskRowPreviewColumn() }
}

@Preview(showBackground = true, name = "Task Row Dark")
@Composable
private fun OpenCodeTaskRowPreviewDark() {
    OpenCodeTheme(darkTheme = true) { TaskRowPreviewColumn() }
}

@Composable
private fun TaskRowPreviewColumn() {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OpenCodeTaskRow(
            title = "Find float money math",
            state = OpenCodeTaskRowState.Running,
            detail = "Searching toFixed",
            detailCaption = "12 tools",
            time = "1:04",
            onClick = {},
        )
        OpenCodeTaskRow(
            title = "Run the tests",
            state = OpenCodeTaskRowState.WaitingForInput,
            tag = "build",
            detail = "Waiting for input",
            time = "0:41",
            onClick = {},
        )
        OpenCodeTaskRow(
            title = "Map invoice flow",
            state = OpenCodeTaskRowState.Done,
            tag = "explore",
            detail = "parse → validate → totalCents → render",
            detailCaption = "4m ago",
            time = "2:31",
            onClick = {},
        )
        OpenCodeTaskRow(
            title = "Benchmark formatMoney",
            state = OpenCodeTaskRowState.Failed,
            detail = "Rate limited",
            detailCaption = "now",
            onClick = {},
        )
    }
}
