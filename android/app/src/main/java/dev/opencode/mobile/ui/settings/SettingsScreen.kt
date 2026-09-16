package dev.opencode.mobile.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.PrivacyPolicy
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.domain.home.HomeSessionLadder
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.preferences.DeveloperToolsPreferences
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.ThemeManager
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
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
    onAppearanceClick: () -> Unit,
    onClearData: () -> Unit,
    onClearSessionCache: () -> Unit,
    sessionCacheSizeLabel: String,
    onRefreshSessionCacheSize: () -> Unit,
    version: String,
    build: String,
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
    followUpDelivery: FollowUpDelivery,
    onFollowUpDeliveryChange: (FollowUpDelivery) -> Unit,
    supportsFollowUpDelivery: Boolean,
    textMateEnabled: Boolean,
    onTextMateEnabledChange: (Boolean) -> Unit,
    productAnalyticsConsented: Boolean,
    onProductAnalyticsConsentedChange: (Boolean) -> Unit,
    serversSummary: ServersSummary = ServersSummary(),
    modifier: Modifier = Modifier,
) {
    var showClearConfirmation by remember { mutableStateOf(false) }
    var showClearSessionCacheConfirmation by remember { mutableStateOf(false) }
    var showRecentWindowPicker by remember { mutableStateOf(false) }
    var showFollowUpPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val currentTheme = ThemeManager.currentTheme
    val currentSyntax = ThemeManager.currentSyntaxPreset
    val themeLoadError = ThemeManager.loadError

    LaunchedEffect(Unit) {
        onRefreshSessionCacheSize()
    }

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

        OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
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
                    title = "Appearance",
                    subtitle = "${currentTheme.name} · ${currentSyntax.name}",
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Widgets, "Appearance"),
                    onClick = {
                        logSettingsAction(ProductAnalytics.Action.SETTINGS_OPEN_APPEARANCE)
                        onAppearanceClick()
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
                    title = "Follow-up send",
                    subtitle =
                        if (supportsFollowUpDelivery) {
                            val label = if (followUpDelivery == FollowUpDelivery.Steer) "Steer" else "Queue"
                            "$label · V2 servers only. V1 does not support this."
                        } else {
                            "Not supported on V1. This server always sends immediately."
                        },
                    style = OpenCodeListItemStyle.Card,
                    enabled = supportsFollowUpDelivery,
                    startDecorator =
                        StartDecorator.Icon(
                            if (followUpDelivery == FollowUpDelivery.Steer && supportsFollowUpDelivery) {
                                OpenCodeIcons.ArrowWarmUp
                            } else {
                                OpenCodeIcons.ArrowUpward
                            },
                            "Follow-up send",
                        ),
                    onClick =
                        if (supportsFollowUpDelivery) {
                            { showFollowUpPicker = true }
                        } else {
                            null
                        },
                    trailing =
                        if (supportsFollowUpDelivery) {
                            {
                                Icon(
                                    imageVector = OpenCodeIcons.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            null
                        },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                themeLoadError?.let { error ->
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
                    title = "Privacy Policy",
                    subtitle = "How the apps handle data",
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Description, "Privacy Policy"),
                    onClick = {
                        logSettingsAction(ProductAnalytics.Action.SETTINGS_OPEN_PRIVACY_POLICY)
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(PrivacyPolicy.URL)),
                        )
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
                    subtitle = "Remove connection and cache",
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Close, "Clear All Data"),
                    onClick = {
                        logSettingsAction(ProductAnalytics.Action.SETTINGS_CLEAR_ALL_DATA)
                        showClearConfirmation = true
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                SectionHeader(title = "About")

                OpenCodeListItem(
                    title = "Version",
                    subtitle = version,
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Description, "Version"),
                    onClick = { copyAboutValueToClipboard(context, label = "Version", value = version) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                OpenCodeListItem(
                    title = "Build",
                    subtitle = build,
                    style = OpenCodeListItemStyle.Card,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Settings, "Build"),
                    onClick = { copyAboutValueToClipboard(context, label = "Build", value = build) },
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

    OpenCodeBottomSheet(
        visible = showFollowUpPicker,
        title = "Follow-up send",
        onDismissRequest = { showFollowUpPicker = false },
    ) {
        FollowUpDelivery.entries.forEach { mode ->
            OpenCodeBottomSheetEntry(
                title = if (mode == FollowUpDelivery.Steer) "Steer" else "Queue",
                startDecorator =
                    BottomSheetEntryDecorator.Icon(
                        if (mode == FollowUpDelivery.Steer) OpenCodeIcons.ArrowWarmUp else OpenCodeIcons.ArrowUpward,
                        null,
                    ),
                endDecorator =
                    if (mode == followUpDelivery) {
                        BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check, "Selected")
                    } else {
                        BottomSheetEntryDecorator.None
                    },
                onClick = {
                    onFollowUpDeliveryChange(mode)
                    showFollowUpPicker = false
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

private fun copyAboutValueToClipboard(
    context: Context,
    label: String,
    value: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
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
            onAppearanceClick = {},
            onClearData = {},
            onClearSessionCache = {},
            sessionCacheSizeLabel = "Empty",
            onRefreshSessionCacheSize = {},
            version = "1.0",
            build = "1",
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
            followUpDelivery = FollowUpDelivery.Steer,
            onFollowUpDeliveryChange = {},
            supportsFollowUpDelivery = true,
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
            onAppearanceClick = {},
            onClearData = {},
            onClearSessionCache = {},
            sessionCacheSizeLabel = "Empty",
            onRefreshSessionCacheSize = {},
            version = "1.0",
            build = "1",
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
            followUpDelivery = FollowUpDelivery.Steer,
            onFollowUpDeliveryChange = {},
            supportsFollowUpDelivery = true,
            textMateEnabled = false,
            onTextMateEnabledChange = {},
            productAnalyticsConsented = false,
            onProductAnalyticsConsentedChange = {},
        )
    }
}
