package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeListItem

@Composable
fun ListItemGalleryScreen(modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(text = "Default", style = MaterialTheme.typography.titleMedium)
    OpenCodeListItem(
      title = "Material Design Icons integration plan",
      subtitle = "http://127.0.0.1:4096/",
      trailing = { Text("...", style = MaterialTheme.typography.titleMedium) },
    )

    Text(text = "Selected", style = MaterialTheme.typography.titleMedium)
    OpenCodeListItem(
      title = "iOS color palette and typography screens",
      subtitle = "http://127.0.0.1:4096/",
      selected = true,
      trailing = { Text("...", style = MaterialTheme.typography.titleMedium) },
    )

    Text(text = "Disabled", style = MaterialTheme.typography.titleMedium)
    OpenCodeListItem(
      title = "Update agents.md with notes on screenshot",
      subtitle = "Unavailable",
      enabled = false,
    )
  }
}
