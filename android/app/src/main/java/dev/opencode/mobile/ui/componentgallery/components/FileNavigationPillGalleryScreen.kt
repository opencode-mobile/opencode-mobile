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
import dev.opencode.mobile.ui.components.OpenCodeFileNavigationPill

@Composable
fun FileNavigationPillGalleryScreen(modifier: Modifier = Modifier) {
    var position by rememberSaveable { mutableStateOf(FileNavigationPillGalleryPosition.Middle) }
    var zeroTotals by rememberSaveable { mutableStateOf(false) }
    var refreshing by rememberSaveable { mutableStateOf(false) }
    var lastAction by rememberSaveable { mutableStateOf("No action") }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Interactive", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Review first, middle, last, zero-total, and refresh-disabled states.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FileNavigationPillGalleryPosition.entries.forEach { value ->
                OpenCodeChoiceBadge(value.label, position == value, onClick = { position = value })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeChoiceBadge("Zero totals", zeroTotals, onClick = { zeroTotals = !zeroTotals })
            OpenCodeChoiceBadge("Refreshing", refreshing, onClick = { refreshing = !refreshing })
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            OpenCodeFileNavigationPill(
                additions = if (zeroTotals) 0 else 12,
                deletions = if (zeroTotals) 0 else 3,
                previousEnabled = position != FileNavigationPillGalleryPosition.First,
                nextEnabled = position != FileNavigationPillGalleryPosition.Last,
                enabled = !refreshing,
                onPrevious = { lastAction = "Previous file" },
                onNext = { lastAction = "Next file" },
            )
        }
        Text(
            text = lastAction,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private enum class FileNavigationPillGalleryPosition(
    val label: String,
) {
    First("First"),
    Middle("Middle"),
    Last("Last"),
}
