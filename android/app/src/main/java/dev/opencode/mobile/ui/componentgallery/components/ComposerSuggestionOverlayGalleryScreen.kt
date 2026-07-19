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
import dev.opencode.mobile.ui.components.OpenCodeComposerSuggestionOverlay

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
                listOf(
                    OpenCodeComposerSuggestionItem(
                        id = "init",
                        title = "/init",
                        subtitle = "Create AGENTS.md",
                    ),
                    OpenCodeComposerSuggestionItem(
                        id = "skill",
                        title = "/my-skill",
                        subtitle = "A skill",
                        badge = "skill",
                    ),
                    OpenCodeComposerSuggestionItem(
                        id = "mcp",
                        title = "/mcp-prompt",
                        subtitle = "From MCP",
                        badge = "mcp",
                    ),
                ),
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
