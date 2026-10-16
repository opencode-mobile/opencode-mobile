package dev.opencode.mobile.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeight
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeEmptyStateAction
import dev.opencode.mobile.ui.components.OpenCodeLayout
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemAccessory
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeStatusStackStyle
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTextInputKind
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/** Add project actions; each maps to one Screen Model action. */
data class AddProjectSheetCallbacks(
    val onFieldChange: (String) -> Unit = {},
    val onUp: () -> Unit = {},
    val onSelectRoot: (String) -> Unit = {},
    val onOpenFolder: (String) -> Unit = {},
    val onSelectSuggestion: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onBrowse: () -> Unit = {},
    val onCloseBrowser: () -> Unit = {},
    val onAddFolder: (String) -> Unit = {},
)

@Composable
internal fun AddProjectSheet(
    sheet: AddProjectSheetUi,
    callbacks: AddProjectSheetCallbacks,
    onDismiss: () -> Unit,
) {
    if (sheet.isBrowsing) {
        AddProjectBrowser(sheet = sheet, callbacks = callbacks)
    }
    if (!sheet.isBrowsing) {
        OpenCodeBottomSheet(
            visible = true,
            title = "Add project",
            onDismissRequest = {
                ProductAnalytics.logUserAction(
                    screenId = ProductAnalytics.Screen.HOME_ADD_PROJECT,
                    actionId = ProductAnalytics.Action.ADD_PROJECT_DISMISS,
                )
                onDismiss()
            },
            height = OpenCodeBottomSheetHeight.FitContent,
        ) {
            AddProjectSuggestions(sheet = sheet, callbacks = callbacks)
        }
    }
}

/** Server projects the user can add in one tap, then Browse folders. */
@Composable
private fun AddProjectSuggestions(
    sheet: AddProjectSheetUi,
    callbacks: AddProjectSheetCallbacks,
) {
    if (sheet.isValidating) {
        OpenCodeStatusStack(
            title = "Checking folder",
            style = OpenCodeStatusStackStyle.Spinner,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        )
        return
    }
    if (sheet.showSuggestions) {
        OpenCodeSectionHeader(title = "On this server")
        sheet.suggestions.forEach { suggestion ->
            OpenCodeListItem(
                title = suggestion.name,
                subtitle = suggestion.path,
                caption = suggestion.activityLabel,
                startDecorator = StartDecorator.Icon(OpenCodeIcons.CallSplit, contentDescription = null),
                onClick = { callbacks.onSelectSuggestion(suggestion.path) },
            )
        }
    } else if (sheet.listError != null && sheet.directory == null) {
        OpenCodeEmptyState(
            title = AddProjectSheetUi.LIST_ERROR_TITLE,
            message = sheet.listError,
            primaryAction = OpenCodeEmptyStateAction(title = "Retry", onClick = callbacks.onRetry),
            modifier = Modifier.padding(20.dp),
        )
    } else if (sheet.isLoading && sheet.suggestions.isEmpty()) {
        OpenCodeStatusStack(
            title = "Loading folders",
            style = OpenCodeStatusStackStyle.Spinner,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        )
    }
    OpenCodeListItem(
        title = AddProjectSheetUi.BROWSE_FOLDERS,
        subtitle = "Pick any folder on this server",
        enabled = !sheet.isValidating,
        startDecorator = StartDecorator.Icon(OpenCodeIcons.Folder, contentDescription = null),
        accessory = OpenCodeListItemAccessory.Disclosure,
        onClick = callbacks.onBrowse,
    )
}

/**
 * Full-screen folder browser. Back returns to the suggestion sheet; it does not dismiss Add project.
 */
@Composable
private fun AddProjectBrowser(
    sheet: AddProjectSheetUi,
    callbacks: AddProjectSheetCallbacks,
) {
    BackHandler(onBack = callbacks.onCloseBrowser)
    val title = sheet.directory?.let(ServerPathPolicy::name)?.takeIf { it.isNotEmpty() } ?: "Add project"
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            OpenCodeTopBar(
                title = title,
                leading = OpenCodeTopBarLeading.Back(onClick = callbacks.onCloseBrowser),
                actions =
                    listOf(
                        OpenCodeTopBarAction.Label(
                            title = "Add",
                            enabled = sheet.addEnabled,
                            onClick = callbacks.onConfirm,
                        ),
                    ),
                showDivider = true,
            )
            if (!sheet.addEnabled && !sheet.isLoading && sheet.listError == null) {
                Text(
                    text = sheet.addTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = openCodeColor("text-weak"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = OpenCodeLayout.SheetContentHorizontalInset)
                            .padding(top = 8.dp),
                )
            }
            AddProjectPathChrome(sheet = sheet, callbacks = callbacks)
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
            ) {
                AddProjectFolderList(sheet = sheet, callbacks = callbacks)
            }
        }
    }
}

@Composable
private fun AddProjectPathChrome(
    sheet: AddProjectSheetUi,
    callbacks: AddProjectSheetCallbacks,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = OpenCodeLayout.SheetContentHorizontalInset)
                .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OpenCodeTextInput(
            value = sheet.fieldText,
            onValueChange = callbacks.onFieldChange,
            hint = "Folder path",
            kind = OpenCodeTextInputKind.Filter,
            enabled = !sheet.isValidating,
            errorMessage = sheet.fieldError,
            modifier = Modifier.weight(1f),
        )
        OpenCodeButton(
            variant = OpenCodeButtonVariant.IconSquare(OpenCodeIcons.ArrowUpward, "Up one folder"),
            onClick = callbacks.onUp,
            enabled = sheet.upTarget != null && !sheet.isValidating,
            contrast = OpenCodeButtonContrast.Low,
        )
    }
    if (sheet.roots.isNotEmpty()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = OpenCodeLayout.SheetContentHorizontalInset, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sheet.roots.forEach { root ->
                OpenCodeChoiceBadge(
                    label = root.label,
                    selected = root.selected,
                    enabled = !sheet.isValidating,
                    onClick = { callbacks.onSelectRoot(root.path) },
                )
            }
        }
    }
}

@Composable
private fun AddProjectFolderList(
    sheet: AddProjectSheetUi,
    callbacks: AddProjectSheetCallbacks,
) {
    when {
        sheet.isValidating -> {
            OpenCodeStatusStack(
                title = "Checking folder",
                style = OpenCodeStatusStackStyle.Spinner,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }
        sheet.listError != null -> {
            OpenCodeEmptyState(
                title = AddProjectSheetUi.LIST_ERROR_TITLE,
                message = sheet.listError,
                primaryAction = OpenCodeEmptyStateAction(title = "Retry", onClick = callbacks.onRetry),
                modifier = Modifier.padding(20.dp),
            )
        }
        sheet.isLoading -> {
            OpenCodeStatusStack(
                title = "Loading folders",
                style = OpenCodeStatusStackStyle.Spinner,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }
        sheet.emptyMessage != null -> {
            OpenCodeBottomSheetEntry(title = sheet.emptyMessage, enabled = false, onClick = {})
        }
        else -> {
            sheet.entries.forEach { folder ->
                OpenCodeListItem(
                    title = folder.name,
                    subtitle = if (folder.isOnHome) "On home" else null,
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.Folder, contentDescription = null),
                    accessory = OpenCodeListItemAccessory.Disclosure,
                    onClick = { callbacks.onOpenFolder(folder.path) },
                    trailing =
                        if (folder.isOnHome || sheet.isValidating) {
                            null
                        } else {
                            {
                                OpenCodeButton(
                                    variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Add, "Add ${folder.name}"),
                                    contrast = OpenCodeButtonContrast.Low,
                                    onClick = { callbacks.onAddFolder(folder.path) },
                                )
                            }
                        },
                )
            }
            sheet.truncationHint?.let { hint ->
                OpenCodeBottomSheetEntry(title = hint, enabled = false, onClick = {})
            }
        }
    }
}
