package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.OpenCodeMediumShape
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeFileIcon
import dev.opencode.mobile.ui.icons.OpenCodeFileIconKind
import dev.opencode.mobile.ui.icons.OpenCodeIcons

data class OpenCodeComposerSuggestionItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val badge: String? = null,
    val leadingIcon: OpenCodeComposerSuggestionLeadingIcon? = null,
)

/** Leading mark before the title: a file-type icon for paths, or a catalog glyph. */
sealed interface OpenCodeComposerSuggestionLeadingIcon {
    data class File(
        val path: String,
        val kind: OpenCodeFileIconKind = OpenCodeFileIconKind.File,
    ) : OpenCodeComposerSuggestionLeadingIcon

    data class Icon(
        val icon: ImageVector,
    ) : OpenCodeComposerSuggestionLeadingIcon
}

private val ComposerSuggestionIconSize = 16.dp

/** bodyLarge line height; the icon centres on the title line so rows keep their height. */
private val ComposerSuggestionTitleLineHeight = 24.dp

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
    val shape = OpenCodeMediumShape
    OpenCodeFloatingChrome(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (items.isEmpty()) {
            Text(
                text = emptyMessage ?: "No commands",
                style = MaterialTheme.typography.bodyMedium,
                color = openCodeColor("text-base"),
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
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    role = Role.Button
                    contentDescription = accessibilityLabel
                }.clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item.leadingIcon?.let { icon ->
            Box(
                modifier = Modifier.height(ComposerSuggestionTitleLineHeight),
                contentAlignment = Alignment.Center,
            ) {
                ComposerSuggestionLeadingIcon(icon)
            }
        }
        ComposerSuggestionText(item)
    }
}

@Composable
private fun ComposerSuggestionLeadingIcon(icon: OpenCodeComposerSuggestionLeadingIcon) {
    when (icon) {
        is OpenCodeComposerSuggestionLeadingIcon.File ->
            OpenCodeFileIcon(path = icon.path, kind = icon.kind, size = ComposerSuggestionIconSize)
        is OpenCodeComposerSuggestionLeadingIcon.Icon ->
            Icon(
                imageVector = icon.icon,
                contentDescription = null,
                tint = openCodeColor("icon-base"),
                modifier = Modifier.size(ComposerSuggestionIconSize),
            )
    }
}

@Composable
private fun ComposerSuggestionText(item: OpenCodeComposerSuggestionItem) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item.badge?.takeIf { it.isNotBlank() }?.let { label ->
                Text(
                    text = "[$label]",
                    style = MaterialTheme.typography.bodyLarge,
                    color = openCodeColor("text-base"),
                    maxLines = 1,
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = openCodeColor("text-strong"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        item.subtitle?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = openCodeColor("text-base"),
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
private fun OpenCodeComposerSuggestionOverlayMentionsPreview() {
    OpenCodeTheme {
        OpenCodeComposerSuggestionOverlay(
            items =
                listOf(
                    OpenCodeComposerSuggestionItem(
                        id = "build",
                        title = "@build",
                        subtitle = "Default agent",
                        leadingIcon = OpenCodeComposerSuggestionLeadingIcon.Icon(OpenCodeIcons.Agent),
                    ),
                    OpenCodeComposerSuggestionItem(
                        id = "file:app/build.gradle.kts",
                        title = "app/build.gradle.kts",
                        subtitle = "File",
                        leadingIcon = OpenCodeComposerSuggestionLeadingIcon.File("app/build.gradle.kts"),
                    ),
                    OpenCodeComposerSuggestionItem(
                        id = "file:Main.kt",
                        title = "Main.kt",
                        subtitle = "File",
                        leadingIcon = OpenCodeComposerSuggestionLeadingIcon.File("Main.kt"),
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
