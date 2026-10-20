package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeActionBar
import dev.opencode.mobile.ui.components.OpenCodeActionBarTarget

@Composable
fun ActionBarGalleryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GallerySectionLabel("Enabled")
        OpenCodeActionBar(
            actionTitle = "Add this folder",
            onAction = {},
            target =
                OpenCodeActionBarTarget(
                    title = "opencode-mobile",
                    subtitle = "/Users/me/Projects/opencode-mobile",
                    tag = "git",
                ),
        )

        GallerySectionLabel("Disabled reason")
        OpenCodeActionBar(
            actionTitle = "Already on Home",
            onAction = {},
            enabled = false,
            target = OpenCodeActionBarTarget(title = "notes", subtitle = "/Users/me/notes"),
        )

        GallerySectionLabel("Compact (keyboard up)")
        OpenCodeActionBar(
            actionTitle = "Add this folder",
            onAction = {},
            compact = true,
            target = OpenCodeActionBarTarget(title = "notes", subtitle = "/Users/me/notes"),
        )

        GallerySectionLabel("Long name and path")
        OpenCodeActionBar(
            actionTitle = "Add this folder",
            onAction = {},
            target =
                OpenCodeActionBarTarget(
                    title = "a-very-long-folder-name-that-does-not-fit-on-one-line",
                    subtitle =
                        "/Users/me/Projects/clients/acme/monorepo/packages/" +
                            "a-very-long-folder-name-that-does-not-fit-on-one-line",
                    tag = "git",
                ),
        )
    }
}

@Composable
private fun GallerySectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
}
