package dev.opencode.mobile.ui.main

import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/**
 * Home's top-bar ⋯ menu. Feature entries come first, in the order given; Settings always closes
 * the menu, after a divider when anything sits above it.
 */
object HomeMenu {
    const val SETTINGS_TEST_TAG = "homeMenuSettings"
    const val ARCHIVED_SESSIONS_TEST_TAG = "homeMenuArchivedSessions"

    fun entries(
        features: List<OpenCodeOverflowMenuEntry>,
        settingsTitle: String,
        onSettingsClick: () -> Unit,
    ): List<OpenCodeOverflowMenuEntry> =
        features +
            OpenCodeOverflowMenuEntry(
                title = settingsTitle,
                icon = OpenCodeIcons.Settings,
                onClick = onSettingsClick,
                testTag = SETTINGS_TEST_TAG,
                dividerAbove = features.isNotEmpty(),
            )
}
