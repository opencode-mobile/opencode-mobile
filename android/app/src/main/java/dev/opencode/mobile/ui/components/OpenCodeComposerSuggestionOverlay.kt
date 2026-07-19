package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme

data class OpenCodeComposerSuggestionItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val badge: String? = null,
)

@Composable
fun OpenCodeComposerSuggestionOverlay(
    items: List<OpenCodeComposerSuggestionItem>,
    onSelect: (OpenCodeComposerSuggestionItem) -> Unit,
    modifier: Modifier = Modifier,
    emptyMessage: String? = null,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.4f).dp
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        if (items.isEmpty()) {
            Text(
                text = emptyMessage ?: "No commands",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        } else {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = maxHeight)
                        .verticalScroll(rememberScrollState()),
            ) {
                items.forEach { item ->
                    OpenCodeListItem(
                        title = item.title,
                        subtitle = item.subtitle,
                        caption = item.badge,
                        style = OpenCodeListItemStyle.Plain,
                        onClick = { onSelect(item) },
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeComposerSuggestionOverlayPreview() {
    OpenCodeTheme {
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
                ),
            onSelect = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenCodeComposerSuggestionOverlayEmptyPreview() {
    OpenCodeTheme(darkTheme = true) {
        OpenCodeComposerSuggestionOverlay(
            items = emptyList(),
            onSelect = {},
            emptyMessage = "No commands",
            modifier = Modifier.padding(16.dp),
        )
    }
}
