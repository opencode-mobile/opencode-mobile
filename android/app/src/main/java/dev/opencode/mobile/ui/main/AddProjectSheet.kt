package dev.opencode.mobile.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.project.ServerProjectSuggestions
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.BottomSheetWindowOverlay
import dev.opencode.mobile.ui.components.OpenCodeActionBar
import dev.opencode.mobile.ui.components.OpenCodeActionBarTarget
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
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
import dev.opencode.mobile.ui.components.OpenCodeTextInputSubmitAction
import dev.opencode.mobile.ui.components.OpenCodeTopBar
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
    /** Return / Go in the path field. */
    val onSubmitField: () -> Unit = {},
    val onFolderRowAppeared: (String) -> Unit = {},
    val onFolderRowDisappeared: (String) -> Unit = {},
)

@Composable
internal fun AddProjectSheet(
    visible: Boolean,
    sheet: AddProjectSheetUi,
    callbacks: AddProjectSheetCallbacks,
    onDismiss: () -> Unit,
) {
    if (visible && sheet.isBrowsing) {
        AddProjectBrowser(sheet = sheet, callbacks = callbacks)
    }
    OpenCodeBottomSheet(
        visible = visible && !sheet.isBrowsing,
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

// List Item rows pad their content 16dp; this brings rows to the shared screen gutter.
private val BrowserRowExtraInset = OpenCodeLayout.ScreenGutter - 16.dp

// Top Bar pads its back button 12dp; this brings it to the shared screen gutter.
private val BrowserTopBarExtraInset = OpenCodeLayout.ScreenGutter - 12.dp

/**
 * Full-screen folder browser. Rows only navigate; the pinned action bar adds the folder it
 * names. Back returns to the suggestion sheet; it does not dismiss Add project.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddProjectBrowser(
    sheet: AddProjectSheetUi,
    callbacks: AddProjectSheetCallbacks,
) {
    BottomSheetWindowOverlay(paneTitle = AddProjectSheetUi.TITLE) {
        BackHandler(onBack = callbacks.onCloseBrowser)
        val keyboardVisible = WindowInsets.isImeVisible
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
        ) {
            OpenCodeTopBar(
                title = AddProjectSheetUi.TITLE,
                leading = OpenCodeTopBarLeading.Back(onClick = callbacks.onCloseBrowser),
                modifier = Modifier.padding(horizontal = BrowserTopBarExtraInset),
            )
            AddProjectPathChrome(sheet = sheet, callbacks = callbacks)
            AddProjectFolderList(
                sheet = sheet,
                callbacks = callbacks,
                modifier = Modifier.weight(1f),
            )
            // Rides the keyboard and fills under the navigation bar itself.
            OpenCodeActionBar(
                actionTitle = sheet.addTitle,
                onAction = callbacks.onConfirm,
                target =
                    sheet.barTarget?.let { target ->
                        OpenCodeActionBarTarget(
                            title = target.name,
                            subtitle = target.path,
                            tag = AddProjectSheetUi.GIT_TAG.takeIf { target.isGitRepository },
                        )
                    },
                enabled = sheet.addEnabled,
                compact = keyboardVisible,
            )
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
                .padding(horizontal = OpenCodeLayout.ScreenGutter)
                .padding(top = 4.dp),
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
            submitAction = OpenCodeTextInputSubmitAction.Go,
            onSubmit = callbacks.onSubmitField,
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
                    .padding(horizontal = OpenCodeLayout.ScreenGutter, vertical = 8.dp),
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
    modifier: Modifier = Modifier,
) {
    when {
        sheet.isValidating -> {
            Box(modifier = modifier.fillMaxWidth()) {
                OpenCodeStatusStack(
                    title = "Checking folder",
                    style = OpenCodeStatusStackStyle.Spinner,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
        sheet.listError != null -> {
            Box(modifier = modifier.fillMaxWidth()) {
                OpenCodeEmptyState(
                    title = AddProjectSheetUi.LIST_ERROR_TITLE,
                    message = sheet.listError,
                    primaryAction = OpenCodeEmptyStateAction(title = "Retry", onClick = callbacks.onRetry),
                    modifier = Modifier.padding(OpenCodeLayout.ScreenGutter),
                )
            }
        }
        sheet.isLoading -> {
            Box(modifier = modifier.fillMaxWidth()) {
                OpenCodeStatusStack(
                    title = "Loading folders",
                    style = OpenCodeStatusStackStyle.Spinner,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
        sheet.emptyMessage != null -> {
            Box(modifier = modifier.fillMaxWidth()) {
                Text(
                    text = sheet.emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = openCodeColor("text-weak"),
                    modifier = Modifier.padding(horizontal = OpenCodeLayout.ScreenGutter, vertical = 24.dp),
                )
            }
        }
        else -> {
            LazyColumn(modifier = modifier.fillMaxWidth()) {
                items(sheet.entries, key = { it.path }) { folder ->
                    AddProjectFolderRow(folder = folder, callbacks = callbacks)
                }
                sheet.truncationHint?.let { hint ->
                    item(key = "truncation-hint") {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = openCodeColor("text-weak"),
                            modifier = Modifier.padding(horizontal = OpenCodeLayout.ScreenGutter, vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** One folder. Reports on-screen time so its count and git tag load lazily. */
@Composable
private fun AddProjectFolderRow(
    folder: AddProjectFolderUi,
    callbacks: AddProjectSheetCallbacks,
) {
    val onAppeared by rememberUpdatedState(callbacks.onFolderRowAppeared)
    val onDisappeared by rememberUpdatedState(callbacks.onFolderRowDisappeared)
    DisposableEffect(folder.path) {
        onAppeared(folder.path)
        onDispose { onDisappeared(folder.path) }
    }
    OpenCodeListItem(
        title = folder.name,
        subtitle = folder.subtitle,
        titleTag = AddProjectSheetUi.GIT_TAG.takeIf { folder.isGitRepository },
        startDecorator = StartDecorator.Icon(OpenCodeIcons.Folder, contentDescription = null),
        onClick = { callbacks.onOpenFolder(folder.path) },
        modifier = Modifier.padding(horizontal = BrowserRowExtraInset),
    )
}
