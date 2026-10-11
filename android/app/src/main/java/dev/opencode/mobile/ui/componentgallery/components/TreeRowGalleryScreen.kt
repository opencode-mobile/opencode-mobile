package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import dev.opencode.mobile.ui.components.OpenCodeTreeRow
import dev.opencode.mobile.ui.components.OpenCodeTreeRowDisclosure
import dev.opencode.mobile.ui.components.OpenCodeTreeRowState
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun TreeRowGalleryScreen(modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var selected by rememberSaveable { mutableStateOf("src/main.kt") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Interactive", style = MaterialTheme.typography.titleMedium)
        Column {
            OpenCodeTreeRow(
                title = "src",
                icon = OpenCodeIcons.Folder,
                disclosure = if (expanded) OpenCodeTreeRowDisclosure.Expanded else OpenCodeTreeRowDisclosure.Collapsed,
                onClick = { expanded = !expanded },
            )
            if (expanded) {
                listOf("src/main.kt", "src/Util.kt").forEach { path ->
                    OpenCodeTreeRow(
                        title = path.substringAfterLast('/'),
                        icon = OpenCodeIcons.Description,
                        depth = 1,
                        state = if (selected == path) OpenCodeTreeRowState.Selected else OpenCodeTreeRowState.Normal,
                        onClick = { selected = path },
                    )
                }
            }
        }
        Text("States", style = MaterialTheme.typography.titleMedium)
        Column {
            OpenCodeTreeRow(title = "README.md", icon = OpenCodeIcons.Description)
            OpenCodeTreeRow(title = "Selected.kt", icon = OpenCodeIcons.Description, state = OpenCodeTreeRowState.Selected)
            OpenCodeTreeRow(
                title = "node_modules",
                icon = OpenCodeIcons.Folder,
                disclosure = OpenCodeTreeRowDisclosure.Collapsed,
                state = OpenCodeTreeRowState.Dimmed,
                tag = "ignored",
            )
            OpenCodeTreeRow(
                title = "docs",
                icon = OpenCodeIcons.Folder,
                disclosure = OpenCodeTreeRowDisclosure.Expanded,
                state = OpenCodeTreeRowState.Loading,
            )
            OpenCodeTreeRow(title = "Button.kt", icon = OpenCodeIcons.Description, subtitle = "ui/components")
        }
    }
}
