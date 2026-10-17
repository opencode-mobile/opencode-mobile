package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeTimelineNotice
import dev.opencode.mobile.ui.components.OpenCodeTimelineNoticeState
import dev.opencode.mobile.ui.components.OpenCodeTimelineNoticeVariant

private const val GALLERY_MOVE_PATH = "/Users/dev/Projects/opencode-mobile/.claude/worktrees/feature-702-v2-timeline-notices"

@Composable
fun TimelineNoticeGalleryScreen(modifier: Modifier = Modifier) {
    var moveExpanded by rememberSaveable { mutableStateOf(false) }
    var instructionsExpanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Variants", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Quiet line where a session change happened. Tap a row with a value to expand it in place.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OpenCodeTimelineNotice(
            label = "Moved to",
            value = GALLERY_MOVE_PATH,
            variant = OpenCodeTimelineNoticeVariant.Move,
            state = if (moveExpanded) OpenCodeTimelineNoticeState.Expanded else OpenCodeTimelineNoticeState.Collapsed,
            onToggle = { moveExpanded = !moveExpanded },
        )
        OpenCodeTimelineNotice(label = "Agent changed", value = "Build → Plan", onToggle = {})
        OpenCodeTimelineNotice(label = "Switched to", value = "Claude Opus 5.5", onToggle = {})
        OpenCodeTimelineNotice(label = "Switched variant to", value = "high", onToggle = {})
        OpenCodeTimelineNotice(label = "Switched variant to Default")
        OpenCodeTimelineNotice(
            label = "Instructions updated",
            value = "AGENTS.md, CLAUDE.md, packages/app/AGENTS.md, docs/STYLE.md",
            state = if (instructionsExpanded) OpenCodeTimelineNoticeState.Expanded else OpenCodeTimelineNoticeState.Collapsed,
            onToggle = { instructionsExpanded = !instructionsExpanded },
        )
        OpenCodeTimelineNotice(label = "Continuing after restart")
        Text(text = "Expanded", style = MaterialTheme.typography.titleMedium)
        OpenCodeTimelineNotice(
            label = "Moved to",
            value = GALLERY_MOVE_PATH,
            variant = OpenCodeTimelineNoticeVariant.Move,
            state = OpenCodeTimelineNoticeState.Expanded,
            onToggle = {},
        )
    }
}
