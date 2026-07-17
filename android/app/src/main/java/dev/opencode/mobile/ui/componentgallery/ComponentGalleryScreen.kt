package dev.opencode.mobile.ui.componentgallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading

@Composable
fun ComponentGalleryScreen(
    onEntryClick: (ComponentGalleryEntryId) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    entries: List<ComponentGalleryEntry> = ComponentGalleryEntries,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = "Component Gallery",
            leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
            showDivider = true,
        )

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Explicit category order (matches iOS enum); entries A–Z by title within each group.
            ComponentGalleryCategory.entries.forEach { category ->
                val categoryEntries =
                    entries
                        .filter { it.category == category }
                        .sortedBy { it.title.lowercase() }
                if (categoryEntries.isEmpty()) return@forEach
                item {
                    Text(
                        text = category.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                items(categoryEntries, key = { it.id.name }) { entry ->
                    ComponentGalleryEntryCard(entry = entry, onClick = { onEntryClick(entry.id) })
                }
            }
        }
    }
}

@Composable
private fun ComponentGalleryEntryCard(
    entry: ComponentGalleryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
