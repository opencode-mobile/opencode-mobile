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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeReorderableList
import dev.opencode.mobile.ui.components.ReorderableListMath
import dev.opencode.mobile.ui.components.SessionListScopeCountBadge
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private data class ReorderableGalleryProject(
    val id: String,
    val title: String,
    val path: String,
    val accent: LetterAccent,
    val count: Int,
) {
    val isAll: Boolean get() = id == "all"
}

@Composable
fun ReorderableListGalleryScreen(modifier: Modifier = Modifier) {
    var reorderEnabled by rememberSaveable { mutableStateOf(true) }
    var contextActionEnabled by rememberSaveable { mutableStateOf(true) }
    var lastEvent by rememberSaveable { mutableStateOf("Long-press a project row, then drag.") }
    var items by remember {
        mutableStateOf(
            listOf(
                ReorderableGalleryProject("all", "All projects", "4 projects", LetterAccent.Violet, 12),
                ReorderableGalleryProject(
                    "/Users/me/Projects/opencode-mobile",
                    "opencode-mobile",
                    "~/Projects/opencode-mobile",
                    LetterAccent.Violet,
                    6,
                ),
                ReorderableGalleryProject("/Users/me/Projects/playground", "playground", "~/Projects/playground", LetterAccent.Teal, 3),
                ReorderableGalleryProject("/Users/me/Projects/website", "website", "~/Projects/website", LetterAccent.Amber, 2),
                ReorderableGalleryProject("/Users/me/Projects/docs", "docs", "~/Projects/docs", LetterAccent.Blue, 1),
            ),
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Behavior", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenCodeChoiceBadge(
                    label = "Reorder",
                    selected = reorderEnabled,
                    onClick = { reorderEnabled = !reorderEnabled },
                )
                OpenCodeChoiceBadge(
                    label = "Context action",
                    selected = contextActionEnabled,
                    onClick = { contextActionEnabled = !contextActionEnabled },
                )
            }
        }

        Text(lastEvent, style = MaterialTheme.typography.labelMedium)

        OpenCodeReorderableList(
            items = items,
            key = { it.id },
            onSelect = { lastEvent = "Selected ${it.title}." },
            canMove = { !it.isAll },
            onMove =
                if (reorderEnabled) {
                    { from, to ->
                        items = ReorderableListMath.move(items, from, to)
                        lastEvent = "Moved row $from to $to."
                    }
                } else {
                    null
                },
            onContextAction =
                if (contextActionEnabled) {
                    { lastEvent = "Context action on ${it.title}." }
                } else {
                    null
                },
            supportsContextAction = { !it.isAll },
        ) { item ->
            OpenCodeListItem(
                title = item.title,
                subtitle = item.path,
                startDecorator =
                    if (item.isAll) {
                        StartDecorator.IconCircle(OpenCodeIcons.Widgets, null)
                    } else {
                        StartDecorator.Letter(item.title.first(), item.accent)
                    },
                trailing = { SessionListScopeCountBadge(count = item.count) },
            )
        }
    }
}
