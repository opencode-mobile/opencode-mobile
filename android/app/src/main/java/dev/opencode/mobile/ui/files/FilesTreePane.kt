package dev.opencode.mobile.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductRouteScreenViewEffect
import dev.opencode.mobile.domain.filesystem.ProjectEntry
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeEmptyStateAction
import dev.opencode.mobile.ui.components.OpenCodeInlineNotice
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeAction
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeTone
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeStatusStackStyle
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTextInputKind
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.OpenCodeTopBarOverflowHost
import dev.opencode.mobile.ui.components.OpenCodeTreeRow
import dev.opencode.mobile.ui.components.OpenCodeTreeRowDisclosure
import dev.opencode.mobile.ui.components.OpenCodeTreeRowIndent
import dev.opencode.mobile.ui.components.OpenCodeTreeRowState
import dev.opencode.mobile.ui.icons.OpenCodeIcons

internal class FilesTreeActions(
    val onNavigateBack: () -> Unit,
    val onToggleHidden: () -> Unit,
    val onRefresh: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val onToggleDirectory: (String) -> Unit,
    val onRetryFolder: (String) -> Unit,
    val onRetryFind: () -> Unit,
)

/** Leading pane (split) or whole screen (compact): top bar, find field, then tree or find results. */
@Composable
internal fun FilesTreePane(
    state: FilesUiState,
    actions: FilesTreeActions,
    selectedPath: String?,
    onOpenFile: (String) -> Unit,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    // Hoisted so the tree keeps its scroll position while find results are shown.
    val treeListState = rememberLazyListState()
    ProductRouteScreenViewEffect(screenId = ProductAnalytics.Screen.FILES)
    Column(Modifier.fillMaxSize()) {
        OpenCodeTopBarOverflowHost(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            entries =
                listOf(
                    OpenCodeOverflowMenuEntry(
                        title = "Show hidden files",
                        icon = if (state.showHidden) OpenCodeIcons.Check else null,
                        accessibilityLabel = if (state.showHidden) "Show hidden files, on" else "Show hidden files, off",
                        onClick = {
                            menuExpanded = false
                            actions.onToggleHidden()
                        },
                    ),
                    OpenCodeOverflowMenuEntry(
                        title = "Refresh",
                        icon = null,
                        onClick = {
                            menuExpanded = false
                            actions.onRefresh()
                        },
                    ),
                ),
        ) {
            OpenCodeTopBar(
                title = "Files",
                subtitle = state.projectName,
                leading = OpenCodeTopBarLeading.Back(onClick = actions.onNavigateBack),
                actions =
                    listOf(
                        OpenCodeTopBarAction.Icon(
                            imageVector = OpenCodeIcons.MoreVert,
                            contentDescription = "More options",
                            onClick = { menuExpanded = true },
                        ),
                    ),
                showDivider = true,
            )
        }
        OpenCodeTextInput(
            value = state.query,
            onValueChange = actions.onQueryChange,
            hint = "Find file",
            kind = OpenCodeTextInputKind.Filter,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Box(Modifier.weight(1f)) {
            if (state.isFinding) {
                FindResults(state, actions, selectedPath, onOpenFile)
            } else {
                Tree(state, actions, selectedPath, onOpenFile, treeListState)
            }
        }
    }
}

@Composable
private fun Tree(
    state: FilesUiState,
    actions: FilesTreeActions,
    selectedPath: String?,
    onOpenFile: (String) -> Unit,
    listState: LazyListState,
) {
    when (val root = state.root) {
        FilesFolderState.Loading ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                OpenCodeStatusStack(title = "Loading files", subtitle = "Fetching the folder…")
            }
        is FilesFolderState.Failed ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                OpenCodeEmptyState(
                    title = "Couldn't load folder",
                    message = root.message,
                    primaryAction =
                        OpenCodeEmptyStateAction(title = "Retry") {
                            actions.onRetryFolder(FilesViewModel.ROOT)
                        },
                )
            }
        is FilesFolderState.Loaded ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(state.treeRows, key = { it.key }) { row ->
                    when (row) {
                        is FilesTreeRow.Entry ->
                            EntryRow(
                                row = row,
                                selected = row.entry.relativePath == selectedPath,
                                onClick = {
                                    if (row.entry.isDirectory) {
                                        actions.onToggleDirectory(row.entry.relativePath)
                                    } else {
                                        onOpenFile(row.entry.relativePath)
                                    }
                                },
                            )
                        is FilesTreeRow.EmptyFolder ->
                            Text(
                                text = "Empty folder",
                                style = MaterialTheme.typography.bodySmall,
                                color = openCodeColor("text-weak"),
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = childContentInset(row.depth), top = 6.dp, bottom = 6.dp),
                            )
                        is FilesTreeRow.FolderError ->
                            OpenCodeInlineNotice(
                                message = "Couldn't load folder",
                                tone = OpenCodeInlineNoticeTone.Error,
                                action =
                                    OpenCodeInlineNoticeAction(title = "Retry") {
                                        actions.onRetryFolder(row.folderPath)
                                    },
                                modifier = Modifier.padding(start = childContentInset(row.depth), end = 16.dp),
                            )
                    }
                }
            }
    }
}

@Composable
private fun EntryRow(
    row: FilesTreeRow.Entry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val entry = row.entry
    OpenCodeTreeRow(
        title = entry.name,
        icon = entry.icon(),
        depth = row.depth,
        disclosure =
            when {
                !entry.isDirectory -> OpenCodeTreeRowDisclosure.None
                row.isExpanded -> OpenCodeTreeRowDisclosure.Expanded
                else -> OpenCodeTreeRowDisclosure.Collapsed
            },
        state =
            when {
                row.isLoading -> OpenCodeTreeRowState.Loading
                selected -> OpenCodeTreeRowState.Selected
                entry.isIgnored -> OpenCodeTreeRowState.Dimmed
                else -> OpenCodeTreeRowState.Normal
            },
        tag = if (entry.isIgnored) "ignored" else null,
        onClick = onClick,
    )
}

@Composable
private fun FindResults(
    state: FilesUiState,
    actions: FilesTreeActions,
    selectedPath: String?,
    onOpenFile: (String) -> Unit,
) {
    when (val find = state.find) {
        FilesFindState.Idle, FilesFindState.Loading ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                OpenCodeStatusStack(title = "Finding files", style = OpenCodeStatusStackStyle.Spinner)
            }
        is FilesFindState.Failed ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                OpenCodeEmptyState(
                    title = "Couldn't search files",
                    message = find.message,
                    primaryAction = OpenCodeEmptyStateAction(title = "Retry", onClick = actions.onRetryFind),
                )
            }
        is FilesFindState.Results ->
            if (find.entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    OpenCodeEmptyState(
                        title = "No files match “${state.query.trim()}”",
                        message = "Server search skips ignored folders.",
                        icon = OpenCodeIcons.Search,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(find.entries, key = { it.relativePath }) { entry ->
                        OpenCodeTreeRow(
                            title = entry.name,
                            icon = entry.icon(),
                            subtitle = entry.parentPath.ifEmpty { "/" },
                            state = if (entry.relativePath == selectedPath) OpenCodeTreeRowState.Selected else OpenCodeTreeRowState.Normal,
                            onClick = { onOpenFile(entry.relativePath) },
                        )
                    }
                }
            }
    }
}

private fun ProjectEntry.icon() = if (isDirectory) OpenCodeIcons.Folder else OpenCodeIcons.Description

/** Aligns inline child rows with the titles of entries one level deeper than their folder. */
private fun childContentInset(depth: Int): Dp = 8.dp + OpenCodeTreeRowIndent * depth + 20.dp + 6.dp
