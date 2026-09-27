package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeSessionBranchPill

@Composable
fun SessionBranchPillGalleryScreen(modifier: Modifier = Modifier) {
    var enabled by rememberSaveable { mutableStateOf(true) }
    var sample by rememberSaveable { mutableStateOf(SessionBranchPillGallerySample.Mixed) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Interactive", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Review mixed, additions-only, deletions-only, clean, long-branch, and disabled states.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeChoiceBadge(
                "Mixed",
                sample == SessionBranchPillGallerySample.Mixed,
                onClick = { sample = SessionBranchPillGallerySample.Mixed },
            )
            OpenCodeChoiceBadge(
                "+ only",
                sample == SessionBranchPillGallerySample.AdditionsOnly,
                onClick = { sample = SessionBranchPillGallerySample.AdditionsOnly },
            )
            OpenCodeChoiceBadge(
                "− only",
                sample == SessionBranchPillGallerySample.DeletionsOnly,
                onClick = { sample = SessionBranchPillGallerySample.DeletionsOnly },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeChoiceBadge(
                "Clean",
                sample == SessionBranchPillGallerySample.Clean,
                onClick = { sample = SessionBranchPillGallerySample.Clean },
            )
            OpenCodeChoiceBadge(
                "Long",
                sample == SessionBranchPillGallerySample.LongBranch,
                onClick = { sample = SessionBranchPillGallerySample.LongBranch },
            )
            OpenCodeChoiceBadge("Enabled", enabled, onClick = { enabled = !enabled })
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            OpenCodeSessionBranchPill(
                branch = sample.branch,
                additions = sample.additions,
                deletions = sample.deletions,
                enabled = enabled,
                onClick = {},
            )
        }
    }
}

private enum class SessionBranchPillGallerySample(
    val branch: String,
    val additions: Int,
    val deletions: Int,
) {
    Mixed("feature/session-branch", 12, 3),
    AdditionsOnly("feature/session-branch", 12, 0),
    DeletionsOnly("feature/session-branch", 0, 3),
    Clean("main", 0, 0),
    LongBranch("feature/very-long-worktree-branch-name-that-must-ellipsis", 48, 2),
}
