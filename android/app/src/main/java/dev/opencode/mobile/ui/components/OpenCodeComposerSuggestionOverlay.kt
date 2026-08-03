package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeTheme

data class OpenCodeComposerSuggestionItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val badge: String? = null,
)

/** Visible suggestion rows before the overlay list scrolls. */
private const val ComposerSuggestionVisibleItemLimit = 4

/**
 * Subtitle-budgeted row height: vertical padding 20 + bodyLarge 24 + spacing 2 +
 * bodyMedium 20.
 */
private val ComposerSuggestionEstimatedRowHeight = 66.dp

private val ComposerSuggestionMaxListHeight =
    ComposerSuggestionEstimatedRowHeight * ComposerSuggestionVisibleItemLimit

@Composable
fun OpenCodeComposerSuggestionOverlay(
    items: List<OpenCodeComposerSuggestionItem>,
    onSelect: (OpenCodeComposerSuggestionItem) -> Unit,
    modifier: Modifier = Modifier,
    emptyMessage: String? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    OpenCodeFloatingChrome(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                        .heightIn(max = ComposerSuggestionMaxListHeight)
                        .verticalScroll(rememberScrollState()),
            ) {
                items.forEach { item ->
                    ComposerSuggestionRow(
                        item = item,
                        onClick = { onSelect(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerSuggestionRow(
    item: OpenCodeComposerSuggestionItem,
    onClick: () -> Unit,
) {
    val accessibilityLabel =
        buildString {
            item.badge?.takeIf { it.isNotBlank() }?.let { append("[$it], ") }
            append(item.title)
            item.subtitle?.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    role = Role.Button
                    contentDescription = accessibilityLabel
                }.clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item.badge?.takeIf { it.isNotBlank() }?.let { label ->
                Text(
                    text = "[$label]",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        item.subtitle?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
private fun OpenCodeComposerSuggestionOverlayScrollablePreview() {
    OpenCodeTheme {
        OpenCodeComposerSuggestionOverlay(
            items =
                (1..8).map { index ->
                    OpenCodeComposerSuggestionItem(
                        id = "cmd-$index",
                        title = "/command-$index",
                        subtitle = "Description $index",
                        badge = if (index % 3 == 0) "skill" else null,
                    )
                },
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
