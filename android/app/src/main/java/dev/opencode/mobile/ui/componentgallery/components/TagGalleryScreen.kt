package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeTag
import dev.opencode.mobile.ui.components.OpenCodeTagSize
import dev.opencode.mobile.ui.components.OpenCodeTagTone

@Composable
fun TagGalleryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Regular", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        OpenCodeTag(title = "Unofficial")
        OpenCodeTag(title = "Beta")
        Text(text = "Small", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = "opencode-mobile", style = MaterialTheme.typography.bodyLarge)
            OpenCodeTag(title = "git", size = OpenCodeTagSize.Small)
        }
        OpenCodeTag(title = "new", size = OpenCodeTagSize.Small)
        Text(text = "Accent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        OpenCodeTag(title = "Recommended", tone = OpenCodeTagTone.Accent)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = "Activity Card", style = MaterialTheme.typography.bodyLarge)
            OpenCodeTag(title = "Recommended", size = OpenCodeTagSize.Small, tone = OpenCodeTagTone.Accent)
        }
    }
}
