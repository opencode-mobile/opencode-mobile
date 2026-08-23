package dev.opencode.mobile.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.domain.home.HomeSessionLadder
import dev.opencode.mobile.domain.preferences.DeveloperToolsPreferences
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.ThemeManager
import dev.opencode.mobile.theme.ThemePreset
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun SettingsScreen(
    onComponentGalleryClick: () -> Unit,
    onTextMateLanguagesClick: () -> Unit,
    onTextMateLogbookClick: () -> Unit,
    onDiagnosticLogsClick: () -> Unit,
    onNavigateBack: () -> Unit,
    onServersClick: () -> Unit,
    onClearData: () -> Unit,
    onClearSessionCache: () -> Unit,
    sessionCacheSizeLabel: String,
    onRefreshSessionCacheSize: () -> Unit,
    onImportTheme: (android.net.Uri) -> Unit,
    onApplyPreset: (String) -> Unit,
    hideUserDirectoryPrefix: Boolean,
    onHideUserDirectoryPrefixChange: (Boolean) -> Unit,
    recentWindowHours: Int,
    onRecentWindowHoursChange: (Int) -> Unit,
    sessionListProjectPickerEnabled: Boolean,
    onSessionListProjectPickerEnabledChange: (Boolean) -> Unit,
    collapseConsecutiveToolCalls: Boolean,
    onCollapseConsecutiveToolCallsChange: (Boolean) -> Unit,
    smoothStreamingUpdates: Boolean,
    onSmoothStreamingUpdatesChange: (Boolean) -> Unit,
    textMateEnabled: Boolean,
    onTextMateEnabledChange: (Boolean) -> Unit,
    productAnalyticsConsented: Boolean,
    onProductAnalyticsConsentedChange: (Boolean) -> Unit,
    serversSummary: ServersSummary = ServersSummary(),
    modifier: Modifier = Modifier,
) {
    var showClearConfirmation by remember { mutableStateOf(false) }
    var showClearSessionCacheConfirmation by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showRecentWindowPicker by remember { mutableStateOf(false) }
    val currentTheme = ThemeManager.currentTheme
    val themeImportError = ThemeManager.loadError
    val presets =
        ThemeManager.availablePresets().ifEmpty {
            listOf(ThemePreset(id = "oc-2", name = "OC-2"))
        }

    LaunchedEffect(Unit) {
        onRefreshSessionCacheSize()
    }

    val themeImportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                onImportTheme(uri)
            }
        }

    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SETTINGS_THEME_PICKER,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = showThemePicker,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SETTINGS_RECENT_WINDOW_PICKER,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = showRecentWindowPicker,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SETTINGS_CLEAR_CACHE_CONFIRM,
        presentation = ProductAnalytics.Presentation.DIALOG,
        visible = showClearSessionCacheConfirmation,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SETTINGS_CLEAR_ALL_DATA_CONFIRM,
        presentation = ProductAnalytics.Presentation.DIALOG,
        visible = showClearConfirmation,
    )

    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = "Settings",
            leading =
                OpenCodeTopBarLeading.Back(
                    onClick = {
                        logSettingsAction(ProductAnalytics.Action.SETTINGS_BACK)
                        onNavigateBack()
                    },
                ),
            showDivider = true,
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(title = "Server")

            OpenCodeListItem(
                title = "Servers",
                subtitle = serversSummary.subtitle,
                style = OpenCodeListItemStyle.Card,
                startDecorator =
                    StartDecorator.Letter(
                        serversSummary.letter,
                        serversSummary.letterAccent,
                    ),
                onClick = {
                    logSettingsAction(ProductAnalytics.Action.SETTINGS_OPEN_CONNECTION)
                    onServersClick()
                },
                trailing = {
                    Icon(
                        imageVector = OpenCodeIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SectionHeader(title = "Appearance")

            OpenCodeListItem(
                title = "Theme",
                subtitle = currentTheme.name,
                style = OpenCodeListItemStyle.Card,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.Widgets, "Theme"),
                onClick = {
                    logSettingsAction(ProductAnalytics.Action.SETTINGS_OPEN_THEME_PICKER)
                    showThemePicker = true
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            OpenCodeListItem(
                title = "Hide User Directory",
                subtitle = "Show ~/Projects/… instead of /Users/you/Projects/…",
                style = OpenCodeListItemStyle.Card,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.Folder, "Hide User Directory"),
                trailing = {
                    Switch(
                        checked = hideUserDirectoryPrefix,
                        onCheckedChange = { enabled ->
                            logSettingsToggle(
                                ProductAnalytics.Action.SETTINGS_TOGGLE_HIDE_USER_DIRECTORY,
                                enabled,
                            )
                            onHideUserDirectoryPrefixChange(enabled)
                        },
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            OpenCodeListItem(
                title = "Recent Window",
                subtitle = HomeSessionLadder.formatWindowHoursLabel(recentWindowHours),
                style = OpenCodeListItemStyle.Card,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.List, "Recent Window"),
                onClick = {
                    logSettingsAction(ProductAnalytics.Action.SETTINGS_OPEN_RECENT_WINDOW_PICKER)
                    showRecentWindowPicker = true
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            OpenCodeListItem(
                title = "Project Picker",
                subtitle = "Replace home filter chips with a project picker",
                style = OpenCodeListItemStyle.Card,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.Widgets, "Project Picker"),
                trailing = {
                    Switch(
                        checked = sessionListProjectPickerEnabled,
                        onCheckedChange = { enabled ->
                            logSettingsToggle(
                                ProductAnalytics.Action.SETTINGS_TOGGLE_SESSION_LIST_PROJECT_PICKER,
                                enabled,
                            )
                            onSessionListProjectPickerEnabledChange(enabled)
                        },
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            OpenCodeListItem(
                title = "Collapse Consecutive Tool Calls",
                subtitle = "Group back-to-back tools into one summary card",
                style = OpenCodeListItemStyle.Card,
                startDecorator =
                    StartDecorator.Icon(OpenCodeIcons.CollapseContent, "Collapse Consecutive Tool Calls"),
                trailing = {
                    Switch(
                        checked = collapseConsecutiveToolCalls,
                        onCheckedChange = { enabled ->
                            logSettingsToggle(
                                ProductAnalytics.Action.SETTINGS_TOGGLE_COLLAPSE_CONSECUTIVE_TOOL_CALLS,
                                enabled,
                            )
                            onCollapseConsecutiveToolCallsChange(enabled)
                        },
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            OpenCodeListItem(
                title = "Smooth Streaming",
                subtitle = "Paint assistant text in short chunks instead of every token",
                style = OpenCodeListItemStyle.Card,
                startDecorator =
                    StartDecorator.Icon(OpenCodeIcons.ExpandContent, "Smooth Streaming"),
                trailing = {
                    Switch(
                        checked = smoothStreamingUpdates,
                        onCheckedChange = { enabled ->
                            logSettingsToggle(
                                ProductAnalytics.Action.SETTINGS_TOGGLE_SMOOTH_STREAMING,
                                enabled,
                            )
                            onSmoothStreamingUpdatesChange(enabled)
                        },
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            OpenCodeListItem(
                title = "Import Theme",
                subtitle = "Custom OpenCode OC-2 JSON file",
                style = OpenCodeListItemStyle.Card,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.Folder, "Import Theme"),
                onClick = {
                    logSettingsAction(ProductAnalytics.Action.SETTINGS_IMPORT_THEME)
                    themeImportLauncher.launch(arrayOf("application/json", "text/json", "*/*"))
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            themeImportError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = openCodeColor("error"),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            SectionHeader(title = "Session Cache")

            OpenCodeListItem(
                title = "Size",
                subtitle = sessionCacheSizeLabel,
                style = OpenCodeListItemStyle.Card,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.Archive, "Session Cache Size"),
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            OpenCodeListItem(
                title = "Clear Session Cache",
                subtitle = "Remove cached sessions and messages",
                style = OpenCodeListItemStyle.Card,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.Close, "Clear Session Cache"),
                onClick = {
                    logSettingsAction(ProductAnalytics.Action.SETTINGS_CLEAR_SESSION_CACHE)
                    showClearSessionCacheConfirmation = true
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SectionHeader(title = "Privacy")

            OpenCodeListItem(
                title = "Product Analytics",
                subtitle = "Allow optional product usage events. Crash reporting is unchanged.",
                style = OpenCodeListItemStyle.Card,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.Description, "Product Analytics"),
                trailing = {
                    Switch(
                        checked = productAnalyticsConsented,
                        onCheckedChange = { enabled ->
                            // Log after the consent change so a revoke is dropped by the
                            // consent gate instead of being recorded on the way out.
                            onProductAnalyticsConsentedChange(enabled)
                            logSettingsToggle(
                                ProductAnalytics.Action.SETTINGS_TOGGLE_PRODUCT_ANALYTICS,
                                enabled,
                            )
                        },
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            OpenCodeListItem(
                title = "Diagnostic Logs",
                subtitle = "Share a redacted rolling runtime log window",
                style = OpenCodeListItemStyle.Card,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.Description, "Diagnostic Logs"),
                onClick = onDiagnosticLogsClick,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            OpenCodeListItem(
                title = "Clear All Data",
                subtitle = "Remove connection, cache, and imported themes",
                style = OpenCodeListItemStyle.Card,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.Close, "Clear All Data"),
                onClick = {
                    logSettingsAction(ProductAnalytics.Action.SETTINGS_CLEAR_ALL_DATA)
                    showClearConfirmation = true
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            if (DeveloperToolsPreferences.unlocked) {
                SectionHeader(title = "Developer Tools")

                OpenCodeListItem(
                    title = "TextMate Highlighting",
                    subtitle = "On by default for all packaged languages; semantic fallback otherwise (can disable)",
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Architecture, "TextMate Highlighting"),
                    trailing = {
                        Switch(
                            checked = textMateEnabled,
                            onCheckedChange = onTextMateEnabledChange,
                        )
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                OpenCodeListItem(
                    title = "TextMate Languages",
                    subtitle = "Opt out of any packaged language (all default on)",
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Architecture, "TextMate Languages"),
                    onClick = onTextMateLanguagesClick,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                OpenCodeListItem(
                    title = "Component Gallery",
                    subtitle = "Design-system foundations and reusable components",
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Architecture, "Component Gallery"),
                    onClick = onComponentGalleryClick,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                OpenCodeListItem(
                    title = "TextMate Logbook",
                    subtitle = "Persisted code-fence parsing measurements",
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Description, "TextMate Logbook"),
                    onClick = onTextMateLogbookClick,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    OpenCodeBottomSheet(
        visible = showThemePicker,
        title = "Theme",
        onDismissRequest = {
            ProductAnalytics.logUserAction(
                screenId = ProductAnalytics.Screen.SETTINGS_THEME_PICKER,
                actionId = ProductAnalytics.Action.THEME_PICKER_DISMISS,
            )
            showThemePicker = false
        },
    ) {
        presets.forEach { preset ->
            OpenCodeBottomSheetEntry(
                title = preset.name,
                endDecorator =
                    if (preset.id == currentTheme.id) {
                        BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check, "Selected")
                    } else {
                        BottomSheetEntryDecorator.None
                    },
                onClick = {
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.SETTINGS_THEME_PICKER,
                        actionId = ProductAnalytics.Action.THEME_PICKER_SELECT_PRESET,
                        parameters =
                            mapOf(
                                ProductAnalytics.Param.THEME_PRESET to
                                    if (ThemeManager.isBundledPreset(preset.id)) preset.id else "imported",
                            ),
                    )
                    onApplyPreset(preset.id)
                    showThemePicker = false
                },
            )
        }
    }

    OpenCodeBottomSheet(
        visible = showRecentWindowPicker,
        title = "Recent Window",
        onDismissRequest = {
            ProductAnalytics.logUserAction(
                screenId = ProductAnalytics.Screen.SETTINGS_RECENT_WINDOW_PICKER,
                actionId = ProductAnalytics.Action.RECENT_WINDOW_DISMISS,
            )
            showRecentWindowPicker = false
        },
    ) {
        Text(
            text = "Sessions stay in Recent while Running or Ready, or when updated within this window.",
            style = MaterialTheme.typography.bodySmall,
            color = openCodeColor("text-base"),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        HomeSessionLadder.ALLOWED_WINDOW_HOURS.forEach { hours ->
            OpenCodeBottomSheetEntry(
                title = HomeSessionLadder.formatWindowHoursLabel(hours),
                endDecorator =
                    if (hours == recentWindowHours) {
                        BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check, "Selected")
                    } else {
                        BottomSheetEntryDecorator.None
                    },
                onClick = {
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.SETTINGS_RECENT_WINDOW_PICKER,
                        actionId = ProductAnalytics.Action.RECENT_WINDOW_SELECT_HOURS,
                        parameters = mapOf(ProductAnalytics.Param.HOURS to hours.toString()),
                    )
                    onRecentWindowHoursChange(hours)
                    showRecentWindowPicker = false
                },
            )
        }
    }

    OpenCodeDialog(
        visible = showClearSessionCacheConfirmation,
        title = "Clear session cache?",
        subtitle = "Removes cached sessions and messages. Your connection and credentials stay.",
        primaryOption =
            OpenCodeDialogOption(
                title = "Clear",
                onClick = {
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.SETTINGS_CLEAR_CACHE_CONFIRM,
                        actionId = ProductAnalytics.Action.CLEAR_CACHE_CONFIRM,
                    )
                    showClearSessionCacheConfirmation = false
                    onClearSessionCache()
                },
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = "Cancel",
                onClick = {
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.SETTINGS_CLEAR_CACHE_CONFIRM,
                        actionId = ProductAnalytics.Action.CLEAR_CACHE_CANCEL,
                    )
                    showClearSessionCacheConfirmation = false
                },
            ),
        onDismissRequest = { showClearSessionCacheConfirmation = false },
    )

    OpenCodeDialog(
        visible = showClearConfirmation,
        title = "Clear all data?",
        subtitle = "This cannot be undone.",
        primaryOption =
            OpenCodeDialogOption(
                title = "Clear",
                onClick = {
                    // Clearing all data also clears consent, so this must be logged first.
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.SETTINGS_CLEAR_ALL_DATA_CONFIRM,
                        actionId = ProductAnalytics.Action.CLEAR_ALL_DATA_CONFIRM,
                    )
                    showClearConfirmation = false
                    onClearData()
                },
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = "Cancel",
                onClick = {
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.SETTINGS_CLEAR_ALL_DATA_CONFIRM,
                        actionId = ProductAnalytics.Action.CLEAR_ALL_DATA_CANCEL,
                    )
                    showClearConfirmation = false
                },
            ),
        onDismissRequest = { showClearConfirmation = false },
    )
}

private fun logSettingsAction(actionId: String) {
    ProductAnalytics.logUserAction(
        screenId = ProductAnalytics.Screen.SETTINGS,
        actionId = actionId,
    )
}

private fun logSettingsToggle(
    actionId: String,
    enabled: Boolean,
) {
    ProductAnalytics.logUserAction(
        screenId = ProductAnalytics.Screen.SETTINGS,
        actionId = actionId,
        parameters = mapOf(ProductAnalytics.Param.OUTCOME to if (enabled) "enabled" else "disabled"),
    )
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenLightPreview() {
    OpenCodeTheme(darkTheme = false) {
        SettingsScreen(
            onComponentGalleryClick = {},
            onTextMateLanguagesClick = {},
            onTextMateLogbookClick = {},
            onDiagnosticLogsClick = {},
            onNavigateBack = {},
            onServersClick = {},
            onClearData = {},
            onClearSessionCache = {},
            sessionCacheSizeLabel = "Empty",
            onRefreshSessionCacheSize = {},
            onImportTheme = {},
            onApplyPreset = {},
            hideUserDirectoryPrefix = HomeDisplayPreferences.hideUserDirectoryPrefix,
            onHideUserDirectoryPrefixChange = {},
            recentWindowHours = HomeDisplayPreferences.recentWindowHours,
            onRecentWindowHoursChange = {},
            sessionListProjectPickerEnabled = HomeDisplayPreferences.sessionListProjectPickerEnabled,
            onSessionListProjectPickerEnabledChange = {},
            collapseConsecutiveToolCalls =
                SessionChatDisplayPreferences.collapseConsecutiveToolCalls,
            onCollapseConsecutiveToolCallsChange = {},
            smoothStreamingUpdates =
                SessionChatDisplayPreferences.smoothStreamingUpdates,
            onSmoothStreamingUpdatesChange = {},
            textMateEnabled = false,
            onTextMateEnabledChange = {},
            productAnalyticsConsented = false,
            onProductAnalyticsConsentedChange = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenDarkPreview() {
    OpenCodeTheme(darkTheme = true) {
        SettingsScreen(
            onComponentGalleryClick = {},
            onTextMateLanguagesClick = {},
            onTextMateLogbookClick = {},
            onDiagnosticLogsClick = {},
            onNavigateBack = {},
            onServersClick = {},
            onClearData = {},
            onClearSessionCache = {},
            sessionCacheSizeLabel = "Empty",
            onRefreshSessionCacheSize = {},
            onImportTheme = {},
            onApplyPreset = {},
            hideUserDirectoryPrefix = HomeDisplayPreferences.hideUserDirectoryPrefix,
            onHideUserDirectoryPrefixChange = {},
            recentWindowHours = HomeDisplayPreferences.recentWindowHours,
            onRecentWindowHoursChange = {},
            sessionListProjectPickerEnabled = HomeDisplayPreferences.sessionListProjectPickerEnabled,
            onSessionListProjectPickerEnabledChange = {},
            collapseConsecutiveToolCalls =
                SessionChatDisplayPreferences.collapseConsecutiveToolCalls,
            onCollapseConsecutiveToolCallsChange = {},
            smoothStreamingUpdates =
                SessionChatDisplayPreferences.smoothStreamingUpdates,
            onSmoothStreamingUpdatesChange = {},
            textMateEnabled = false,
            onTextMateEnabledChange = {},
            productAnalyticsConsented = false,
            onProductAnalyticsConsentedChange = {},
        )
    }
}
