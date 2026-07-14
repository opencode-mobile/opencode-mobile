package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.OpenCodeTopBarOverflowHost
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun OverflowMenuGalleryScreen(modifier: Modifier = Modifier) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var entriesEnabled by rememberSaveable { mutableStateOf(true) }
    var selectionRowsEnabled by rememberSaveable { mutableStateOf(false) }
    var itemCount by rememberSaveable { mutableStateOf(2) }

    val entries =
        buildList {
            add(
                OpenCodeOverflowMenuEntry(
                    title = "Session context",
                    icon = if (selectionRowsEnabled) OpenCodeIcons.Check else OpenCodeIcons.Architecture,
                    enabled = entriesEnabled,
                    accessibilityLabel = if (selectionRowsEnabled) "Session context, selected" else null,
                    onClick = { menuExpanded = false },
                ),
            )
            add(
                OpenCodeOverflowMenuEntry(
                    title = "Copy session ID",
                    icon = if (selectionRowsEnabled) null else OpenCodeIcons.ContentCopy,
                    enabled = entriesEnabled,
                    onClick = { menuExpanded = false },
                ),
            )
            if (itemCount >= 3) {
                add(
                    OpenCodeOverflowMenuEntry(
                        title = "Settings",
                        icon = OpenCodeIcons.Settings,
                        enabled = entriesEnabled,
                        onClick = { menuExpanded = false },
                    ),
                )
            }
            if (itemCount >= 4) {
                add(
                    OpenCodeOverflowMenuEntry(
                        title = "Search",
                        icon = OpenCodeIcons.Search,
                        enabled = entriesEnabled,
                        onClick = { menuExpanded = false },
                    ),
                )
            }
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Overflow menu",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            ControlGroup(title = "State") {
                OpenCodeChoiceBadge(
                    label = "Entries enabled",
                    selected = entriesEnabled,
                    onClick = { entriesEnabled = !entriesEnabled },
                )
                OpenCodeChoiceBadge(
                    label = "Selection rows",
                    selected = selectionRowsEnabled,
                    onClick = { selectionRowsEnabled = !selectionRowsEnabled },
                )
            }

            ControlGroup(title = "Entry count") {
                listOf(2, 3, 4).forEach { count ->
                    OpenCodeChoiceBadge(
                        label = count.toString(),
                        selected = itemCount == count,
                        onClick = { itemCount = count },
                    )
                }
            }

            Text(
                text =
                    "Use the overflow icon in the live top bar preview to open the menu. " +
                        "Switch controls to review enabled, disabled, and aligned selection states.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OpenCodeTopBarOverflowHost(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            entries = entries,
        ) {
            OpenCodeTopBar(
                title = "Session",
                leading = OpenCodeTopBarLeading.Back(onClick = {}),
                actions =
                    listOf(
                        OpenCodeTopBarAction.Icon(
                            imageVector = OpenCodeIcons.MoreVert,
                            contentDescription = "More options",
                            onClick = { menuExpanded = true },
                        ),
                    ),
                showDivider = true,
            )
        }
    }
}

@Composable
private fun ControlGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}
