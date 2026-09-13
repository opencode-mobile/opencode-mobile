package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeQueuedPromptsDock

private enum class QueuedPromptsDockSample(
    val label: String,
    val count: Int,
    val preview: String?,
) {
    Single(label = "Single", count = 1, preview = "Also check the compact path."),
    Multiple(
        label = "Multiple",
        count = 3,
        preview = "First queued prompt\nwith a second line",
    ),
}

@Composable
fun QueuedPromptsDockGalleryScreen(modifier: Modifier = Modifier) {
    var sample by rememberSaveable { mutableStateOf(QueuedPromptsDockSample.Single) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Interactive", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Switch samples to review a single queued prompt and a multi-item dock label.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QueuedPromptsDockSample.entries.forEach { option ->
                OpenCodeChoiceBadge(
                    label = option.label,
                    selected = sample == option,
                    onClick = { sample = option },
                )
            }
        }

        OpenCodeQueuedPromptsDock(
            count = sample.count,
            preview = sample.preview,
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
