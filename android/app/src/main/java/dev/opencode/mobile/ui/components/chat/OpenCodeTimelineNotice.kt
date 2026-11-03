package dev.opencode.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeSmallShape
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor

private val TimelineNoticeInsetHorizontal = 4.dp
private val TimelineNoticeInsetVertical = 2.dp
private val TimelineNoticeSpacing = 6.dp
private const val TIMELINE_NOTICE_MOVE_GLYPH = "↳"

/** What changed; the variant owns the leading glyph. */
enum class OpenCodeTimelineNoticeVariant {
    /** An agent, model, instructions, or restart notice: label and value only. */
    Change,

    /** The session moved to another working directory: led by `↳`. */
    Move,
}

/** Whether the value is end-ellipsized on one line or wraps in full. */
enum class OpenCodeTimelineNoticeState {
    Collapsed,
    Expanded,
}

/**
 * A quiet single-line timeline row for a session change (moved, agent, model,
 * instructions, restart): [label] in the weak text tone, then [value] in the
 * info tint. No card, fill, or border. Collapsed, the value ends in an
 * ellipsis; [onToggle] (when the row has a value) switches to [state]
 * Expanded, where the value wraps in full. Assistive tech reads the label and
 * the full value either way.
 */
@Composable
fun OpenCodeTimelineNotice(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    variant: OpenCodeTimelineNoticeVariant = OpenCodeTimelineNoticeVariant.Change,
    state: OpenCodeTimelineNoticeState = OpenCodeTimelineNoticeState.Collapsed,
    onToggle: (() -> Unit)? = null,
) {
    require(label.isNotBlank()) { "Timeline Notice requires a non-blank label" }
    val shownValue = value?.takeIf { it.isNotBlank() }
    val toggle = onToggle.takeIf { shownValue != null }
    val expanded = state == OpenCodeTimelineNoticeState.Expanded
    val weak = openCodeColor("text-weak")
    val style = MaterialTheme.typography.bodySmall
    val description = listOfNotNull(label, shownValue).joinToString(" ")
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (toggle != null) {
                        Modifier.openCodeFocusRing(OpenCodeSmallShape).clickable(role = Role.Button, onClick = toggle)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = TimelineNoticeInsetHorizontal, vertical = TimelineNoticeInsetVertical)
                .clearAndSetSemantics {
                    contentDescription = description
                    if (toggle != null) {
                        role = Role.Button
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                        onClick {
                            toggle()
                            true
                        }
                    }
                },
        horizontalArrangement = Arrangement.spacedBy(TimelineNoticeSpacing),
    ) {
        if (variant == OpenCodeTimelineNoticeVariant.Move) {
            Text(
                text = TIMELINE_NOTICE_MOVE_GLYPH,
                modifier = Modifier.alignByBaseline(),
                style = style,
                color = weak,
            )
        }
        Text(
            text = label,
            modifier =
                Modifier
                    .alignByBaseline()
                    .then(if (shownValue == null) Modifier.weight(1f, fill = false) else Modifier),
            style = style,
            color = weak,
            maxLines = if (shownValue == null) Int.MAX_VALUE else 1,
        )
        if (shownValue != null) {
            Text(
                text = shownValue,
                modifier = Modifier.alignByBaseline().weight(1f, fill = false),
                style = style,
                color = openCodeColor("info"),
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OpenCodeTimelineNoticePreviewContent() {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.width(360.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OpenCodeTimelineNotice(label = "Agent changed", value = "Build → Plan")
        OpenCodeTimelineNotice(label = "Switched to", value = "Claude Opus 5.5")
        OpenCodeTimelineNotice(label = "Switched variant to", value = "high")
        OpenCodeTimelineNotice(label = "Switched variant to Default")
        OpenCodeTimelineNotice(label = "Instructions updated", value = "AGENTS.md, CLAUDE.md")
        OpenCodeTimelineNotice(label = "Continuing after restart")
        OpenCodeTimelineNotice(
            label = "Moved to",
            value = "/Users/dev/Projects/opencode-mobile/.claude/worktrees/feature-702-v2-timeline-notices",
            variant = OpenCodeTimelineNoticeVariant.Move,
            state = if (expanded) OpenCodeTimelineNoticeState.Expanded else OpenCodeTimelineNoticeState.Collapsed,
            onToggle = { expanded = !expanded },
        )
        OpenCodeTimelineNotice(
            label = "Moved to",
            value = "/Users/dev/Projects/opencode-mobile/.claude/worktrees/feature-702-v2-timeline-notices",
            variant = OpenCodeTimelineNoticeVariant.Move,
            state = OpenCodeTimelineNoticeState.Expanded,
            onToggle = {},
        )
    }
}

@Preview(showBackground = true, name = "Timeline Notice Light")
@Composable
private fun OpenCodeTimelineNoticePreviewLight() {
    OpenCodeTheme(darkTheme = false) { OpenCodeTimelineNoticePreviewContent() }
}

@Preview(showBackground = true, name = "Timeline Notice Dark")
@Composable
private fun OpenCodeTimelineNoticePreviewDark() {
    OpenCodeTheme(darkTheme = true) { OpenCodeTimelineNoticePreviewContent() }
}
