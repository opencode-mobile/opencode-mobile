package dev.opencode.mobile.ui.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeEmptyStateAction
import dev.opencode.mobile.ui.components.SessionListWorktreeEmptyCopy
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/**
 * Offline Home empty state for a worktree with no sessions, for on-demand snapshot evidence.
 * No ViewModel, no network. Renders the same [OpenCodeEmptyState] Session List shows.
 */
@Composable
fun SessionListWorktreeEmptySnapshotHost(
    modifier: Modifier = Modifier,
    message: String = SnapshotEvidenceMocks.WORKTREE_EMPTY_MESSAGE,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 420.dp)
                .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        OpenCodeEmptyState(
            title = SessionListWorktreeEmptyCopy.TITLE,
            message = message,
            icon = OpenCodeIcons.CallSplit,
            primaryAction = OpenCodeEmptyStateAction(title = SessionListWorktreeEmptyCopy.ACTION, onClick = {}),
        )
    }
}
