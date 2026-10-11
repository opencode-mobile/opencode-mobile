package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeFileTab
import dev.opencode.mobile.ui.components.OpenCodeFileTabs

private val fileTabsGallerySamples =
    listOf(
        "README.md",
        "src/main.kt",
        "src/ui/components/OpenCodeButton.kt",
        "app/build.gradle.kts",
        "docs/a-very-long-file-name-that-truncates-in-the-tab.md",
    )

@Composable
fun FileTabsGalleryScreen(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(fileTabsGallerySamples) }
    var selected by rememberSaveable { mutableStateOf<String?>("src/main.kt") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Interactive", style = MaterialTheme.typography.titleMedium)
        OpenCodeFileTabs(
            tabs = open.map { OpenCodeFileTab(id = it, title = it.substringAfterLast('/')) },
            selectedTabId = selected,
            onSelect = { selected = it },
            onClose = { path ->
                open = open - path
                if (selected == path) selected = open.lastOrNull()
            },
        )
        if (open.size < fileTabsGallerySamples.size) {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.CompactText("Reopen all"),
                onClick = { open = fileTabsGallerySamples },
            )
        }
        Text("Single tab, no divider", style = MaterialTheme.typography.titleMedium)
        OpenCodeFileTabs(
            tabs = listOf(OpenCodeFileTab(id = "notes.txt", title = "notes.txt")),
            selectedTabId = "notes.txt",
            onSelect = {},
            onClose = {},
            showDivider = false,
        )
    }
}
