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
import dev.opencode.mobile.ui.components.OpenCodeActionTile
import dev.opencode.mobile.ui.components.OpenCodeActionTileBadge
import dev.opencode.mobile.ui.components.OpenCodeActionTileBadgeTone
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun ActionTileGalleryScreen(modifier: Modifier = Modifier) {
    var enabled by rememberSaveable { mutableStateOf(true) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Interactive", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Two-column labeled action tile for palettes such as Commands.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OpenCodeChoiceBadge(
            label = "Enabled",
            selected = enabled,
            onClick = { enabled = !enabled },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OpenCodeActionTile(
                icon = OpenCodeIcons.Add,
                label = "New",
                onClick = {},
                enabled = enabled,
                accessibilityLabel = "Start a new session",
                modifier = Modifier.weight(1f),
            )
            OpenCodeActionTile(
                icon = OpenCodeIcons.Terminal,
                label = "Terminal",
                onClick = {},
                enabled = enabled,
                accessibilityLabel = "Open project shell",
                modifier = Modifier.weight(1f),
            )
        }

        Text(text = "Badge", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Corner badge: tone-only dot, or a count pill when something needs attention.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OpenCodeActionTile(
                icon = OpenCodeIcons.Plug,
                label = "MCP servers",
                onClick = {},
                enabled = enabled,
                badge = OpenCodeActionTileBadge(OpenCodeActionTileBadgeTone.Success),
                modifier = Modifier.weight(1f),
            )
            OpenCodeActionTile(
                icon = OpenCodeIcons.Plug,
                label = "MCP servers",
                onClick = {},
                enabled = enabled,
                badge = OpenCodeActionTileBadge(OpenCodeActionTileBadgeTone.Warning),
                modifier = Modifier.weight(1f),
            )
            OpenCodeActionTile(
                icon = OpenCodeIcons.Plug,
                label = "MCP servers",
                onClick = {},
                enabled = enabled,
                badge = OpenCodeActionTileBadge(OpenCodeActionTileBadgeTone.Error, count = 2),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
