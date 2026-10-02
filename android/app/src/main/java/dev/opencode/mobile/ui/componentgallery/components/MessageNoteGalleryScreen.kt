package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeMessageNote
import dev.opencode.mobile.ui.components.OpenCodeMessageNoteVariant

@Composable
fun MessageNoteGalleryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Variants", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Persistent note under an assistant reply that ended early.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OpenCodeMessageNoteVariant.entries.forEach { variant ->
            OpenCodeMessageNote(variant = variant)
        }
    }
}
