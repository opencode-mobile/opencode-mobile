package dev.opencode.mobile.ui.componentgallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge

enum class ComponentGalleryThemeMode(
    val label: String,
) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
}

@Composable
fun ComponentGalleryControls(
    themeMode: ComponentGalleryThemeMode,
    onThemeModeChange: (ComponentGalleryThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Theme", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ComponentGalleryThemeMode.entries.forEach { mode ->
                OpenCodeChoiceBadge(
                    label = mode.label,
                    selected = mode == themeMode,
                    onClick = { onThemeModeChange(mode) },
                )
            }
        }
    }
}
