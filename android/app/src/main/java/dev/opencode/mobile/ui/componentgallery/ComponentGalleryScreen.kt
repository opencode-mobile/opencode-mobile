package dev.opencode.mobile.ui.componentgallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ComponentGalleryScreen(
  onEntryClick: (ComponentGalleryEntryId) -> Unit,
  modifier: Modifier = Modifier,
  entries: List<ComponentGalleryEntry> = ComponentGalleryEntries,
) {
  LazyColumn(modifier = modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Component Gallery", style = MaterialTheme.typography.displaySmall)
        Text(
          text = "Developer-only review surface for design-system foundations and reusable components.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    // Explicit category order (matches iOS enum / declaration order), not map iteration order.
    ComponentGalleryCategory.entries.forEach { category ->
      val categoryEntries = entries.filter { it.category == category }
      if (categoryEntries.isEmpty()) return@forEach
      item {
        Text(
          text = category.label,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
      items(categoryEntries, key = { it.id.name }) { entry ->
        ComponentGalleryEntryCard(entry = entry, onClick = { onEntryClick(entry.id) })
      }
    }
  }
}

@Composable
private fun ComponentGalleryEntryCard(entry: ComponentGalleryEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Card(
    modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(text = entry.title, style = MaterialTheme.typography.titleMedium)
      Text(text = entry.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
