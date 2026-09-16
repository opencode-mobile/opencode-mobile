package dev.opencode.mobile.ui.componentgallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun ComponentGalleryScreen(
    onEntryClick: (ComponentGalleryEntryId) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    entries: List<ComponentGalleryEntry> = ComponentGalleryEntries,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filteredEntries = entries.filter { it.matchesGallerySearch(searchQuery) }
    val hasQuery = searchQuery.isNotBlank()

    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = "Component Gallery",
            leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
            showDivider = true,
        )

        OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxSize()) {
                OpenCodeTextInput(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    hint = "Search components",
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                )

                if (filteredEntries.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        OpenCodeEmptyState(
                            title = if (hasQuery) "No components found" else "No components",
                            message =
                                if (hasQuery) {
                                    "Try a different search."
                                } else {
                                    "Component gallery entries will appear here."
                                },
                            icon = OpenCodeIcons.Search,
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Explicit category order (matches iOS enum); entries A–Z by title within each group.
                        ComponentGalleryCategory.entries.forEach { category ->
                            val categoryEntries =
                                filteredEntries
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
        }
    }
}

internal fun ComponentGalleryEntry.matchesGallerySearch(query: String): Boolean {
    val normalized = query.trim()
    if (normalized.isEmpty()) return true
    return title.contains(normalized, ignoreCase = true) ||
        description.contains(normalized, ignoreCase = true) ||
        category.label.contains(normalized, ignoreCase = true)
}
