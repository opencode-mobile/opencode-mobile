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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.home.HomeSessionLadder
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
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
    onConnectionSetup: () -> Unit,
    onClearData: () -> Unit,
    onImportTheme: (android.net.Uri) -> Unit,
    onApplyPreset: (String) -> Unit,
    showSessionListOnHome: Boolean,
    onShowSessionListOnHomeChange: (Boolean) -> Unit,
    hideUserDirectoryPrefix: Boolean,
    onHideUserDirectoryPrefixChange: (Boolean) -> Unit,
    recentWindowHours: Int,
    onRecentWindowHoursChange: (Int) -> Unit,
    textMateEnabled: Boolean,
    onTextMateEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showClearConfirmation by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showRecentWindowPicker by remember { mutableStateOf(false) }
    val currentTheme = ThemeManager.currentTheme
    val themeImportError = ThemeManager.loadError
    val presets =
        ThemeManager.availablePresets().ifEmpty {
            listOf(ThemePreset(id = "oc-2", name = "OC-2"))
        }

    val themeImportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                onImportTheme(uri)
            }
        }

    Column(modifier = modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = "Settings",
            leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
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
                title = "Server Connection",
                subtitle = "Configure server URL and credentials",
                style = OpenCodeListItemStyle.Card,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.Cloud, "Server Connection"),
                onClick = onConnectionSetup,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SectionHeader(title = "Appearance")

            OpenCodeListItem(
                title = "Theme",
                subtitle = currentTheme.name,
                style = OpenCodeListItemStyle.Card,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.Widgets, "Theme"),
                onClick = { showThemePicker = true },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            OpenCodeListItem(
                title = "Session List",
                subtitle = "Switch home between session list and project selector",
                style = OpenCodeListItemStyle.Card,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.List, "Session List"),
                trailing = {
                    Switch(
                        checked = showSessionListOnHome,
                        onCheckedChange = onShowSessionListOnHomeChange,
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
                        onCheckedChange = onHideUserDirectoryPrefixChange,
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            OpenCodeListItem(
                title = "Recent Window",
                subtitle = HomeSessionLadder.formatWindowHoursLabel(recentWindowHours),
                style = OpenCodeListItemStyle.Card,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.List, "Recent Window"),
                onClick = { showRecentWindowPicker = true },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            OpenCodeListItem(
                title = "Import Theme",
                subtitle = "Custom OpenCode OC-2 JSON file",
                style = OpenCodeListItemStyle.Card,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.Folder, "Import Theme"),
                onClick = {
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

            SectionHeader(title = "Developer Tools")

            OpenCodeListItem(
                title = "TextMate Highlighting",
                subtitle = "On by default for promoted languages; semantic fallback otherwise (can disable)",
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
                subtitle = "Opt out of promoted languages or force-enable packaged ones",
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
                onClick = { showClearConfirmation = true },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    OpenCodeBottomSheet(
        visible = showThemePicker,
        title = "Theme",
        onDismissRequest = { showThemePicker = false },
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
                    onApplyPreset(preset.id)
                    showThemePicker = false
                },
            )
        }
    }

    OpenCodeBottomSheet(
        visible = showRecentWindowPicker,
        title = "Recent Window",
        onDismissRequest = { showRecentWindowPicker = false },
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
                    onRecentWindowHoursChange(hours)
                    showRecentWindowPicker = false
                },
            )
        }
    }

    OpenCodeDialog(
        visible = showClearConfirmation,
        title = "Clear all data?",
        subtitle = "This cannot be undone.",
        primaryOption =
            OpenCodeDialogOption(
                title = "Clear",
                onClick = {
                    showClearConfirmation = false
                    onClearData()
                },
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = "Cancel",
                onClick = { showClearConfirmation = false },
            ),
        onDismissRequest = { showClearConfirmation = false },
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
            onConnectionSetup = {},
            onClearData = {},
            onImportTheme = {},
            onApplyPreset = {},
            showSessionListOnHome = HomeDisplayPreferences.showSessionListOnHome,
            onShowSessionListOnHomeChange = {},
            hideUserDirectoryPrefix = HomeDisplayPreferences.hideUserDirectoryPrefix,
            onHideUserDirectoryPrefixChange = {},
            recentWindowHours = HomeDisplayPreferences.recentWindowHours,
            onRecentWindowHoursChange = {},
            textMateEnabled = false,
            onTextMateEnabledChange = {},
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
            onConnectionSetup = {},
            onClearData = {},
            onImportTheme = {},
            onApplyPreset = {},
            showSessionListOnHome = HomeDisplayPreferences.showSessionListOnHome,
            onShowSessionListOnHomeChange = {},
            hideUserDirectoryPrefix = HomeDisplayPreferences.hideUserDirectoryPrefix,
            onHideUserDirectoryPrefixChange = {},
            recentWindowHours = HomeDisplayPreferences.recentWindowHours,
            onRecentWindowHoursChange = {},
            textMateEnabled = false,
            onTextMateEnabledChange = {},
        )
    }
}
