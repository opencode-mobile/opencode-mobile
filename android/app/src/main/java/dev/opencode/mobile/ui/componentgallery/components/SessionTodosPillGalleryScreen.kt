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
import dev.opencode.mobile.ui.components.OpenCodeSessionTodosPill

@Composable
fun SessionTodosPillGalleryScreen(modifier: Modifier = Modifier) {
    var enabled by rememberSaveable { mutableStateOf(true) }
    var summary by rememberSaveable { mutableStateOf(SessionTodosPillGallerySummary.MidProgress) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Interactive", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Review mid-progress, near-complete, no-preview, and enabled/disabled states.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeChoiceBadge(
                "Mid",
                summary == SessionTodosPillGallerySummary.MidProgress,
                onClick = { summary = SessionTodosPillGallerySummary.MidProgress },
            )
            OpenCodeChoiceBadge(
                "Near done",
                summary == SessionTodosPillGallerySummary.NearComplete,
                onClick = { summary = SessionTodosPillGallerySummary.NearComplete },
            )
            OpenCodeChoiceBadge(
                "No preview",
                summary == SessionTodosPillGallerySummary.NoPreview,
                onClick = { summary = SessionTodosPillGallerySummary.NoPreview },
            )
            OpenCodeChoiceBadge("Enabled", enabled, onClick = { enabled = !enabled })
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            OpenCodeSessionTodosPill(
                completedCount = summary.completedCount,
                totalCount = summary.totalCount,
                activePreview = summary.activePreview,
                enabled = enabled,
                onClick = {},
            )
        }
    }
}

private enum class SessionTodosPillGallerySummary(
    val completedCount: Int,
    val totalCount: Int,
    val activePreview: String?,
) {
    MidProgress(2, 5, "Implement auth middleware"),
    NearComplete(4, 5, "Write integration tests"),
    NoPreview(1, 3, null),
}
