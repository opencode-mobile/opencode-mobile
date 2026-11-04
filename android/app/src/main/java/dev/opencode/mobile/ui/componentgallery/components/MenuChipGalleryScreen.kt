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
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.ui.components.OpenCodeAnchoredOverflowHost
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeMenuChip
import dev.opencode.mobile.ui.components.OpenCodeMenuChipLeading
import dev.opencode.mobile.ui.components.OpenCodeMenuChipVariant
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private data class GalleryScope(
    val title: String,
    val letter: Char?,
    val accent: LetterAccent?,
)

private val galleryScopes =
    listOf(
        GalleryScope("All projects", null, null),
        GalleryScope("acme-invoices", 'A', LetterAccent.Teal),
        GalleryScope("billing-service", 'B', LetterAccent.Violet),
    )

@Composable
fun MenuChipGalleryScreen(modifier: Modifier = Modifier) {
    var selectedIndex by rememberSaveable { mutableStateOf(0) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var enabled by rememberSaveable { mutableStateOf(true) }
    var toggled by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Interactive", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Menu shows the current choice (tinted when not the default) and opens a menu. Toggle turns a filter on and off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OpenCodeChoiceBadge(label = "Enabled", selected = enabled, onClick = { enabled = !enabled })

        val scope = galleryScopes[selectedIndex]
        OpenCodeAnchoredOverflowHost(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            entries =
                galleryScopes.mapIndexed { index, option ->
                    OpenCodeOverflowMenuEntry(
                        title = option.title,
                        icon = if (index == selectedIndex) OpenCodeIcons.Check else null,
                        accessibilityLabel = if (index == selectedIndex) "${option.title}, selected" else null,
                        onClick = { selectedIndex = index },
                    )
                },
        ) {
            OpenCodeMenuChip(
                title = scope.title,
                onClick = { expanded = true },
                leading = scope.leading(),
                selected = selectedIndex != 0,
                enabled = enabled,
            )
        }

        Text(text = "Toggle", style = MaterialTheme.typography.titleMedium)
        OpenCodeMenuChip(
            title = "Include archived · 3",
            onClick = { toggled = !toggled },
            leading = OpenCodeMenuChipLeading.Icon(OpenCodeIcons.Archive),
            selected = toggled,
            enabled = enabled,
            variant = OpenCodeMenuChipVariant.Toggle,
        )

        Text(text = "States", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            galleryScopes.take(2).forEachIndexed { index, option ->
                OpenCodeMenuChip(title = option.title, onClick = {}, leading = option.leading(), selected = index != 0)
            }
        }
    }
}

private fun GalleryScope.leading(): OpenCodeMenuChipLeading =
    if (letter != null && accent != null) {
        OpenCodeMenuChipLeading.Letter(letter, accent)
    } else {
        OpenCodeMenuChipLeading.Icon(OpenCodeIcons.Widgets)
    }
