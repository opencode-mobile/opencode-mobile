package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeFloatingPill
import dev.opencode.mobile.ui.components.OpenCodeFloatingPillLayout
import dev.opencode.mobile.ui.components.OpenCodeFloatingPillState
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val floatingPillGalleryLayouts =
    listOf(
        "Icon + label" to OpenCodeFloatingPillLayout.IconLabel(OpenCodeIcons.CallSplit, "+128 −14"),
        "Icon only" to OpenCodeFloatingPillLayout.IconOnly(OpenCodeIcons.ArrowDownward, "Scroll to end"),
        "Live counter" to OpenCodeFloatingPillLayout.Counter("1 subagent · 2 shells"),
        "Live counter, waiting item" to OpenCodeFloatingPillLayout.Counter("general needs input · 2 running", waiting = true),
        "Label + close" to OpenCodeFloatingPillLayout.Closable("Request failed"),
        "Label only" to OpenCodeFloatingPillLayout.Label("+3"),
    )

@Composable
fun FloatingPillGalleryScreen(modifier: Modifier = Modifier) {
    var log by remember { mutableStateOf("Tap a pill") }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            text =
                "Every layout in every state, then compact. Needs attention adds an amber ring; a waiting counter swaps its " +
                    "spinner for a pulsing dot; done shows a green check.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        floatingPillGalleryLayouts.forEach { (title, layout) ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                OpenCodeFloatingPillState.entries.forEach { state ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = state.spokenName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(104.dp),
                        )
                        OpenCodeFloatingPill(
                            layout = layout,
                            state = state,
                            onClick = { log = "Tapped ${layout.label}, ${state.spokenName}" },
                            onClose = { log = "Dismissed ${layout.label}" },
                        )
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = "Compact (keyboard up)", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Each layout above, left to right, shrunk to its mark. Layouts without one show a dot in the state's colour.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OpenCodeFloatingPillState.entries.forEach { state ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = state.spokenName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(96.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        floatingPillGalleryLayouts.forEach { (_, layout) ->
                            OpenCodeFloatingPill(
                                layout = layout,
                                state = state,
                                compact = true,
                                onClick = { log = "Tapped compact ${layout.label}, ${state.spokenName}" },
                            )
                        }
                    }
                }
            }
        }
        Text(text = log, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
