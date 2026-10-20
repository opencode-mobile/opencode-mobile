package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeComposerSuggestionItem
import dev.opencode.mobile.ui.components.OpenCodeComposerSuggestionLeadingIcon
import dev.opencode.mobile.ui.components.OpenCodeComposerSuggestionOverlay
import dev.opencode.mobile.ui.icons.OpenCodeFileIconKind
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun ComposerSuggestionOverlayGalleryScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Populated",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OpenCodeComposerSuggestionOverlay(
            items =
                (1..8).map { index ->
                    OpenCodeComposerSuggestionItem(
                        id = "cmd-$index",
                        title = "/command-$index",
                        subtitle = "Description $index",
                        badge =
                            when {
                                index % 3 == 0 -> "skill"
                                index % 2 == 0 -> "mcp"
                                else -> null
                            },
                    )
                },
            onSelect = {},
        )

        Text(
            text = "Mentions",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OpenCodeComposerSuggestionOverlay(
            items =
                listOf(
                    OpenCodeComposerSuggestionItem(
                        id = "build",
                        title = "@build",
                        subtitle = "Default agent",
                        leadingIcon = OpenCodeComposerSuggestionLeadingIcon.Icon(OpenCodeIcons.Agent),
                    ),
                ) +
                    listOf("Package.swift", "app/build.gradle", "src/main.kt", "web/App.tsx", "README.md", "src").map { path ->
                        OpenCodeComposerSuggestionItem(
                            id = "file:$path",
                            title = path,
                            subtitle = "File",
                            leadingIcon =
                                OpenCodeComposerSuggestionLeadingIcon.File(
                                    path = path,
                                    kind = if ('.' in path) OpenCodeFileIconKind.File else OpenCodeFileIconKind.Directory,
                                ),
                        )
                    },
            onSelect = {},
        )

        Text(
            text = "Empty",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OpenCodeComposerSuggestionOverlay(
            items = emptyList(),
            onSelect = {},
            emptyMessage = "No commands",
        )
    }
}
