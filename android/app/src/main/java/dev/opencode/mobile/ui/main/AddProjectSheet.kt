package dev.opencode.mobile.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.project.ServerProjectSuggestions
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.BottomSheetWindowOverlay
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeight
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonSize
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

/**
 * Browse folders first, then server projects in a fixed five-row frame. The card opens at
 * its final height and does not resize when the list arrives or comes back short.
 */
@Composable
private fun AddProjectSuggestions(
    sheet: AddProjectSheetUi,
    callbacks: AddProjectSheetCallbacks,
) {
    val isListError = sheet.listError != null && sheet.directory == null && !sheet.isValidating
    val isLoadingSuggestions = !sheet.isValidating && !isListError && !sheet.suggestionsResolved && !sheet.showSuggestions
    val showsEmptySuggestions = !sheet.isValidating && !isListError && sheet.suggestionsResolved && !sheet.showSuggestions
    Column(modifier = Modifier.fillMaxWidth()) {
        OpenCodeListItem(
            title = AddProjectSheetUi.BROWSE_FOLDERS,
            subtitle = "Pick any folder on this server",
            enabled = !sheet.isValidating,
            startDecorator = StartDecorator.Icon(OpenCodeIcons.Folder, contentDescription = null),
            accessory = OpenCodeListItemAccessory.Disclosure,
            onClick = callbacks.onBrowse,
        )
        sheet.fieldError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = OpenCodeLayout.SheetContentHorizontalInset)
                        .padding(bottom = 12.dp),
            )
        }
        OpenCodeSectionHeader(
            title = "On this server",
            modifier =
                Modifier
                    .alpha(if (isListError) 0f else 1f)
                    .then(if (isListError) Modifier.clearAndSetSemantics {} else Modifier),
        )
        SuggestionFrame(
            sheet = sheet,
            callbacks = callbacks,
            isListError = isListError,
            isLoadingSuggestions = isLoadingSuggestions,
            showsEmptySuggestions = showsEmptySuggestions,
        )
    }
}

@Composable
private fun SuggestionFrame(
    sheet: AddProjectSheetUi,
    callbacks: AddProjectSheetCallbacks,
    isListError: Boolean,
    isLoadingSuggestions: Boolean,
    showsEmptySuggestions: Boolean,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (isLoadingSuggestions) {
                        Modifier.semantics(mergeDescendants = true) {
                            contentDescription = "Loading projects"
                        }
                    } else {
                        Modifier
                    },
                ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            repeat(ServerProjectSuggestions.MAX_SUGGESTIONS) { index ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    AddProjectSuggestionRuler()
                    if (isLoadingSuggestions) {
                        AddProjectSuggestionSkeleton(Modifier.align(Alignment.CenterStart))
                    } else if (
                        !sheet.isValidating &&
                        !isListError &&
                        sheet.showSuggestions &&
                        index < sheet.suggestions.size
                    ) {
                        val suggestion = sheet.suggestions[index]
                        OpenCodeListItem(
                            title = suggestion.name,
                            subtitle = suggestion.path,
                            caption = suggestion.activityLabel,
                            startDecorator = StartDecorator.Icon(OpenCodeIcons.Folder, contentDescription = null),
                            onClick = { callbacks.onSelectSuggestion(suggestion.path) },
                        )
                    }
                }
            }
        }
        when {
            sheet.isValidating ->
                OpenCodeStatusStack(
                    title = "Checking folder",
                    style = OpenCodeStatusStackStyle.Spinner,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            isListError ->
                OpenCodeEmptyState(
                    title = AddProjectSheetUi.LIST_ERROR_TITLE,
                    message = sheet.listError ?: AddProjectSheetUi.LIST_ERROR_MESSAGE,
                    primaryAction = OpenCodeEmptyStateAction(title = "Retry", onClick = callbacks.onRetry),
                    modifier = Modifier.align(Alignment.TopCenter).padding(20.dp),
                )
            showsEmptySuggestions ->
                Text(
                    text = AddProjectSheetUi.NO_OTHER_PROJECTS,
                    style = MaterialTheme.typography.labelMedium,
                    color = openCodeColor("text-weak"),
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(horizontal = OpenCodeLayout.SheetContentHorizontalInset)
                            .padding(top = 8.dp),
                )
        }
    }
}

/** Hidden suggestion row. Every slot stays the height of a real row, loaded or not. */
@Composable
private fun AddProjectSuggestionRuler() {
    OpenCodeListItem(
        title = "Project",
        subtitle = "/Users/placeholder",
        caption = "1d ago",
        enabled = false,
        startDecorator = StartDecorator.Icon(OpenCodeIcons.Folder, contentDescription = null),
        modifier = Modifier.alpha(0f).clearAndSetSemantics {},
    )
}

@Composable
private fun AddProjectSuggestionSkeleton(modifier: Modifier = Modifier) {
    val bar = openCodeColor("text-weaker").copy(alpha = 0.35f)
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(bar),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.42f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(bar),
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.62f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(bar),
            )
        }
    }
}

/**
 * Full-screen folder browser. Back returns to the suggestion sheet; it does not dismiss Add project.
 */
@Composable
private fun AddProjectBrowser(
    sheet: AddProjectSheetUi,
    callbacks: AddProjectSheetCallbacks,
) {
    val title = sheet.directory?.let(ServerPathPolicy::name)?.takeIf { it.isNotEmpty() } ?: "Add project"
    BottomSheetWindowOverlay(paneTitle = title) {
        BackHandler(onBack = callbacks.onCloseBrowser)
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .navigationBarsPadding(),
        ) {
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
            // Match the 48dp path field. Medium (40dp) sits short of it; iOS uses .large.
            size = OpenCodeButtonSize.Large,
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
