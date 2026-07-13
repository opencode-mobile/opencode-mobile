package dev.opencode.mobile.ui.componentgallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading

@Composable
fun ComponentGalleryDetailScreen(
    entry: ComponentGalleryEntry,
    themeMode: ComponentGalleryThemeMode,
    onThemeModeChange: (ComponentGalleryThemeMode) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val horizontalPadding = 16.dp

    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = entry.title,
            leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
            showDivider = true,
        )

        if (entry.id == ComponentGalleryEntryId.SessionComposer) {
            Column(
                modifier = Modifier.fillMaxSize().padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                )
                ComponentGalleryControls(
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
                )
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    content()
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = horizontalPadding),
                    )
                }
                item {
                    ComponentGalleryControls(
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
                    )
                }
                item {
                    if (entry.id == ComponentGalleryEntryId.TopBar ||
                        entry.id == ComponentGalleryEntryId.OverflowMenu ||
                        entry.id == ComponentGalleryEntryId.SessionStatusBar
                    ) {
                        content()
                    } else {
                        Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
                            content()
                        }
                    }
                }
            }
        }
    }
}
