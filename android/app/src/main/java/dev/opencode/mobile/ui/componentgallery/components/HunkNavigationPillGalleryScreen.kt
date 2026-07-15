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
import dev.opencode.mobile.ui.components.OpenCodeHunkNavigationPill

@Composable
fun HunkNavigationPillGalleryScreen(modifier: Modifier = Modifier) {
    var position by rememberSaveable { mutableStateOf(HunkNavigationPosition.Middle) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var contextEnabled by rememberSaveable { mutableStateOf(true) }
    var lastAction by rememberSaveable { mutableStateOf("No action") }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Interactive", style = MaterialTheme.typography.titleMedium)
        Text(
            "Review boundaries, context mode, and unavailable context.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HunkNavigationPosition.entries.forEach { value ->
                OpenCodeChoiceBadge(value.label, position == value, onClick = { position = value })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeChoiceBadge("Expanded", expanded, onClick = { expanded = !expanded })
            OpenCodeChoiceBadge("No context", !contextEnabled, onClick = { contextEnabled = !contextEnabled })
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            OpenCodeHunkNavigationPill(
                previousEnabled = position != HunkNavigationPosition.First,
                nextEnabled = position != HunkNavigationPosition.Last,
                contextEnabled = contextEnabled,
                contextExpanded = expanded,
                onPrevious = { lastAction = "Previous hunk" },
                onToggleContext = {
                    expanded = !expanded
                    lastAction = if (expanded) "Expand context" else "Collapse context"
                },
                onNext = { lastAction = "Next hunk" },
            )
        }
        Text(lastAction, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium)
    }
}

private enum class HunkNavigationPosition(
    val label: String,
) {
    First("First"),
    Middle("Middle"),
    Last("Last"),
}
