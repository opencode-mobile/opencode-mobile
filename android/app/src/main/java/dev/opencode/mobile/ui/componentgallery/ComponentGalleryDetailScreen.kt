package dev.opencode.mobile.ui.componentgallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ComponentGalleryDetailScreen(
  entry: ComponentGalleryEntry,
  themeMode: ComponentGalleryThemeMode,
  onThemeModeChange: (ComponentGalleryThemeMode) -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  LazyColumn(modifier = modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = entry.title, style = MaterialTheme.typography.displaySmall)
        Text(text = entry.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    item {
      ComponentGalleryControls(
        themeMode = themeMode,
        onThemeModeChange = onThemeModeChange,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    item { content() }
  }
}
