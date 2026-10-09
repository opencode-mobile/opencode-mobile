package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeEmptyStateAction
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun EmptyStateGalleryScreen(modifier: Modifier = Modifier) {
    var showsIcon by rememberSaveable { mutableStateOf(true) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OpenCodeChoiceBadge(
            label = "Icon",
            selected = showsIcon,
            onClick = { showsIcon = !showsIcon },
        )
        OpenCodeEmptyState(
            title = "No projects yet",
            message = "Tap the Add project icon below to choose a folder on your server.",
            icon = if (showsIcon) OpenCodeIcons.CreateNewFolder else null,
            modifier = Modifier.height(280.dp),
        )
        OpenCodeEmptyState(
            title = "Unable to connect",
            message = "The server did not answer.",
            primaryAction = OpenCodeEmptyStateAction(title = "Retry", onClick = {}),
            secondaryAction = OpenCodeEmptyStateAction(title = "Edit connection", onClick = {}),
            modifier = Modifier.height(320.dp),
        )
    }
}
