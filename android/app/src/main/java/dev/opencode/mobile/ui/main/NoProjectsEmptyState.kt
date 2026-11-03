package dev.opencode.mobile.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.opencode.mobile.R
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
internal fun NoProjectsEmptyState(modifier: Modifier = Modifier) {
    OpenCodeEmptyState(
        title = stringResource(R.string.home_empty_title),
        message = stringResource(R.string.home_empty_message),
        icon = OpenCodeIcons.CreateNewFolder,
        modifier = modifier,
    )
}
