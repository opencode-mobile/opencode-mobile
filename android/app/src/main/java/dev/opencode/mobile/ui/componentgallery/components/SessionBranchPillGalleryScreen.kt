package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeAnchoredCardLayout
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeFloatingPill
import dev.opencode.mobile.ui.components.OpenCodeFloatingPillLayout
import dev.opencode.mobile.ui.components.OpenCodeFloatingPillState
import dev.opencode.mobile.ui.components.OpenCodeSessionBranchPill
import dev.opencode.mobile.ui.components.OpenCodeSessionBranchPopoverContent
import dev.opencode.mobile.ui.components.SessionBranchPopoverWidth
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.components.SessionBranchPillPreviewCase

@Composable
fun SessionBranchPillGalleryScreen(modifier: Modifier = Modifier) {
    var sample by rememberSaveable { mutableStateOf(SessionBranchPillPreviewCase.Changes) }
    var copiedCount by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Interactive", style = MaterialTheme.typography.titleMedium)
        Text(
            text =
                "Tap the pill to open its popover. With changes it shows the line counts; without, " +
                    "the icon only. Worktree sessions use the worktree icon.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SessionBranchPillPreviewCase.entries.forEach { option ->
                OpenCodeChoiceBadge(option.galleryLabel, sample == option, onClick = { sample = option })
            }
        }
        // As in the Session Chat top-left corner: the branch first, then todos.
        Row(horizontalArrangement = Arrangement.spacedBy(OpenCodeAnchoredCardLayout.Gap)) {
            OpenCodeSessionBranchPill(
                summary = sample.summary,
                onViewAllChanges = {},
                onBranchNameCopied = { copiedCount += 1 },
            )
            OpenCodeFloatingPill(
                layout = OpenCodeFloatingPillLayout.IconLabel(OpenCodeIcons.List, "2/5"),
                state = OpenCodeFloatingPillState.Ready,
                accessibilityLabel = "Todos, 2 of 5 done",
                onClick = {},
            )
        }
        if (copiedCount > 0) {
            Text(
                text = "Branch name copied ($copiedCount)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(text = "All pills", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SessionBranchPillPreviewCase.entries.forEach { option ->
                OpenCodeSessionBranchPill(summary = option.summary, onViewAllChanges = {})
            }
        }

        Text(text = "Popover open", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Surface(
            modifier = Modifier.widthIn(max = SessionBranchPopoverWidth),
            shape = OpenCodeAnchoredCardLayout.Shape,
            color = openCodeColor("surface-raised-base"),
            border = BorderStroke(1.dp, openCodeColor("border-weak-base")),
        ) {
            OpenCodeSessionBranchPopoverContent(
                summary = sample.summary,
                onViewAllChanges = {},
                onCopyBranchName = {},
            )
        }
    }
}
