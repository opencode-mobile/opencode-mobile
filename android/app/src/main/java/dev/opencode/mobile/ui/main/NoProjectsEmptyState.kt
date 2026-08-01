package dev.opencode.mobile.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.icons.OpenCodeIcons

internal const val NoProjectsEmptyTitle = "No projects yet"
internal const val NoProjectsEmptyMessage =
    "Tap the Add project icon below to choose a folder on your server."

@Composable
internal fun NoProjectsEmptyState(modifier: Modifier = Modifier) {
    OpenCodeEmptyState(
        title = NoProjectsEmptyTitle,
        message = NoProjectsEmptyMessage,
        icon = OpenCodeIcons.CreateNewFolder,
        modifier = modifier,
    )
}
