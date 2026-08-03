package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadgeVariant

@Composable
fun ChoiceBadgeGalleryScreen(modifier: Modifier = Modifier) {
    var selection by rememberSaveable { mutableStateOf("Alpha") }
    var glassSelection by rememberSaveable { mutableStateOf("Build") }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Standard", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Tap a badge to select it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Alpha", "Beta", "Gamma").forEach { option ->
                OpenCodeChoiceBadge(
                    label = option,
                    selected = selection == option,
                    onClick = { selection = option },
                )
            }
        }

        Text(text = "Glass", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Composer picker chrome (home dock glass language).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Grok 4.5", "Build", "Default").forEach { option ->
                OpenCodeChoiceBadge(
                    label = option,
                    selected = glassSelection == option,
                    onClick = { glassSelection = option },
                    variant = OpenCodeChoiceBadgeVariant.Glass,
                )
            }
        }

        Text(text = "Disabled", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeChoiceBadge(label = "Unavailable", selected = false, enabled = false, onClick = {})
            OpenCodeChoiceBadge(
                label = "Glass unavailable",
                selected = true,
                enabled = false,
                onClick = {},
                variant = OpenCodeChoiceBadgeVariant.Glass,
            )
        }
    }
}
