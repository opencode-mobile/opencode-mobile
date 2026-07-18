package dev.opencode.mobile.ui.componentgallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
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
            contentPadding = PaddingValues(vertical = 16.dp),
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
                    OpenCodeListItem(
                        title = entry.title,
                        subtitle = entry.description,
                        style = OpenCodeListItemStyle.Card,
                        onClick = { onEntryClick(entry.id) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}
