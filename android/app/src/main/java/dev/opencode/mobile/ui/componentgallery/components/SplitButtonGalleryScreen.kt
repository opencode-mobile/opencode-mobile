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
import dev.opencode.mobile.ui.components.OpenCodeAnchoredOverflowHost
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.components.OpenCodeSplitButton
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun SplitButtonGalleryScreen(modifier: Modifier = Modifier) {
    var contrastName by rememberSaveable { mutableStateOf(OpenCodeButtonContrast.Low.name) }
    var enabled by rememberSaveable { mutableStateOf(true) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var lastAction by rememberSaveable { mutableStateOf("None") }
    val contrast =
        runCatching { OpenCodeButtonContrast.valueOf(contrastName) }.getOrDefault(OpenCodeButtonContrast.Low)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenCodeChoiceBadge(label = "Enabled", selected = enabled, onClick = { enabled = !enabled })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(OpenCodeButtonContrast.Low, OpenCodeButtonContrast.High).forEach { option ->
                OpenCodeChoiceBadge(
                    label = if (option == OpenCodeButtonContrast.Low) "Low contrast" else "High contrast",
                    selected = option == contrast,
                    onClick = { contrastName = option.name },
                )
            }
        }
        Text(
            "Last action: $lastAction",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OpenCodeAnchoredOverflowHost(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            entries =
                listOf(
                    OpenCodeOverflowMenuEntry(
                        title = "Deny",
                        icon = OpenCodeIcons.Close,
                        onClick = { lastAction = "Menu: Deny" },
                    ),
                    OpenCodeOverflowMenuEntry(
                        title = "Deny with a note…",
                        subtitle = "Tell the agent what to do instead",
                        icon = OpenCodeIcons.Edit,
                        onClick = { lastAction = "Menu: Deny with a note" },
                    ),
                ),
        ) {
            OpenCodeSplitButton(
                title = "Deny",
                menuContentDescription = "More deny options",
                onClick = { lastAction = "Deny" },
                onMenu = {
                    lastAction = "Menu"
                    menuOpen = true
                },
                enabled = enabled,
                contrast = contrast,
            )
        }
        Text("All states", style = MaterialTheme.typography.labelLarge)
        listOf(true, false).forEach { rowEnabled ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(OpenCodeButtonContrast.Low, OpenCodeButtonContrast.High).forEach { rowContrast ->
                    OpenCodeSplitButton(
                        title = "Deny",
                        menuContentDescription = "More deny options",
                        onClick = {},
                        onMenu = {},
                        enabled = rowEnabled,
                        contrast = rowContrast,
                    )
                }
            }
        }
    }
}
