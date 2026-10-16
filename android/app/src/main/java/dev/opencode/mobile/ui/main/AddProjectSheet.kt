package dev.opencode.mobile.ui.main

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeight
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetKeyboard
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeEmptyStateAction
import dev.opencode.mobile.ui.components.OpenCodeLayout
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeStatusStackStyle
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTextInputKind
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons

/** Add project sheet actions; each maps to one Screen Model action. */
data class AddProjectSheetCallbacks(
    val onFieldChange: (String) -> Unit = {},
    val onUp: () -> Unit = {},
    val onSelectRoot: (String) -> Unit = {},
    val onOpenFolder: (String) -> Unit = {},
    val onSelectSuggestion: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onConfirm: () -> Unit = {},
)

@Composable
internal fun AddProjectSheet(
    sheet: AddProjectSheetUi,
    callbacks: AddProjectSheetCallbacks,
    onDismiss: () -> Unit,
) {
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
        height = OpenCodeBottomSheetHeight.Half,
        // The pinned field and footer are tall, so grow while typing to keep folder rows in view.
        keyboard = OpenCodeBottomSheetKeyboard.Expand,
        pinnedTop = { AddProjectSheetPinnedTop(sheet = sheet, callbacks = callbacks) },
        pinnedBottom = { AddProjectSheetFooter(sheet = sheet, callbacks = callbacks) },
    ) {
        AddProjectSheetBody(sheet = sheet, callbacks = callbacks)
    }
}

@Composable
private fun AddProjectSheetPinnedTop(
    sheet: AddProjectSheetUi,
    callbacks: AddProjectSheetCallbacks,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = OpenCodeLayout.SheetContentHorizontalInset),
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

/** The full target path over "Add <name>"; the sheet pads the footer. */
@Composable
private fun AddProjectSheetFooter(
    sheet: AddProjectSheetUi,
    callbacks: AddProjectSheetCallbacks,
) {
    sheet.addTarget?.let { target ->
        Text(
            text = target,
            style = MaterialTheme.typography.labelMedium,
            color = openCodeColor("text-base"),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
    }
    OpenCodeButton(
        variant = OpenCodeButtonVariant.FullWidthText(sheet.addTitle),
        onClick = callbacks.onConfirm,
        enabled = sheet.addEnabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AddProjectSheetBody(
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
        sheet.folderSectionTitle?.let { OpenCodeSectionHeader(title = it) }
    }
    when {
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
                    onClick = { callbacks.onOpenFolder(folder.path) },
                )
            }
        }
    }
}
