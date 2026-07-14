package dev.opencode.mobile.ui.changes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.OpenCodeTopBarOverflowHost
import dev.opencode.mobile.ui.components.OpenCodeUnifiedDiff
import dev.opencode.mobile.ui.components.UnifiedDiffContextMode
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun ChangesScreen(
    sessionId: String,
    worktree: String,
    messageId: String?,
    initialSource: ChangeSource = ChangeSource.LastTurn,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChangesViewModel = hiltViewModel(key = "$sessionId-$worktree-$messageId-$initialSource"),
) {
    LaunchedEffect(sessionId, worktree, messageId, initialSource) {
        viewModel.bind(sessionId, worktree, messageId, initialSource)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        OpenCodeTopBarOverflowHost(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            entries =
                state.availableSources.map { source ->
                    OpenCodeOverflowMenuEntry(
                        title = source.displayName + if (source == state.selectedSource) " (selected)" else "",
                        icon = OpenCodeIcons.Description,
                        onClick = { viewModel.selectSource(source) },
                    )
                },
        ) {
            OpenCodeTopBar(
                title = "Changes",
                subtitle = state.selectedSource.displayName,
                leading = OpenCodeTopBarLeading.Back(onNavigateBack),
                actions =
                    listOf(
                        OpenCodeTopBarAction.Icon(
                            imageVector = OpenCodeIcons.MoreVert,
                            contentDescription = "Choose change source",
                            onClick = { menuExpanded = true },
                        ),
                    ),
                showDivider = true,
            )
        }
        when (val current = state.phase) {
            ChangesPhase.Loading -> Centered { CircularProgressIndicator() }
            ChangesPhase.NoTurn -> EmptyMessage("No completed turn", "Send a prompt and wait for it to complete, then try again.")
            ChangesPhase.NoChanges -> EmptyMessage(state.selectedSource.emptyTitle, state.selectedSource.emptyDetail)
            is ChangesPhase.Content -> ChangeSetContent(current.changeSet, viewModel)
            is ChangesPhase.Error ->
                Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        EmptyMessage("Unable to load changes", current.message)
                        OpenCodeButton(
                            OpenCodeButtonVariant.CompactText("Retry"),
                            onClick = viewModel::retry,
                            contrast = OpenCodeButtonContrast.Low,
                        )
                    }
                }
        }
    }
}

@Composable
private fun ChangeSetContent(
    changeSet: ChangesContentState,
    viewModel: ChangesViewModel,
) {
    var pickerVisible by remember { mutableStateOf(false) }
    val selectedFile = changeSet.selectedFile

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${changeSet.files.size} files  ·  +${changeSet.additions}  −${changeSet.deletions}",
                modifier = Modifier.semantics { contentDescription = changeSet.aggregateAccessibilityLabel },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OpenCodeButton(
                variant = OpenCodeButtonVariant.CompactText("Refresh"),
                onClick = viewModel::refresh,
                contrast = OpenCodeButtonContrast.Low,
            )
        }

        OpenCodeListItem(
            title = selectedFile.path,
            subtitle = changeSet.selectedFileSummary,
            style = OpenCodeListItemStyle.Card,
            onClick = { pickerVisible = true },
            modifier =
                Modifier
                    .padding(horizontal = 16.dp)
                    .semantics { contentDescription = "Choose file. ${changeSet.selectedFileAccessibilityLabel}" },
            trailing = {
                Icon(OpenCodeIcons.ChevronRight, contentDescription = null)
            },
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.CompactText("Previous file"),
                onClick = viewModel::selectPreviousFile,
                enabled = changeSet.canSelectPrevious,
                contrast = OpenCodeButtonContrast.Low,
            )
            OpenCodeButton(
                variant = OpenCodeButtonVariant.CompactText("Next file"),
                onClick = viewModel::selectNextFile,
                enabled = changeSet.canSelectNext,
                contrast = OpenCodeButtonContrast.Low,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OpenCodeButton(
                variant =
                    OpenCodeButtonVariant.CompactText(
                        if (changeSet.contextMode == UnifiedDiffContextMode.Collapsed) "Expand context" else "Collapse context",
                    ),
                onClick = viewModel::toggleContextMode,
                contrast = OpenCodeButtonContrast.Low,
            )
            OpenCodeButton(
                variant =
                    OpenCodeButtonVariant.CompactText(
                        if (changeSet.selectedFileReviewed) "Mark unreviewed" else "Mark reviewed",
                    ),
                onClick = viewModel::toggleSelectedFileReviewed,
                contrast = OpenCodeButtonContrast.Low,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.CompactText("Previous hunk"),
                onClick = viewModel::selectPreviousHunk,
                enabled = changeSet.canSelectPreviousHunk,
                contrast = OpenCodeButtonContrast.Low,
            )
            Text(
                changeSet.selectedHunkPositionLabel,
                modifier = Modifier.semantics { contentDescription = changeSet.selectedHunkPositionLabel },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OpenCodeButton(
                variant = OpenCodeButtonVariant.CompactText("Next hunk"),
                onClick = viewModel::selectNextHunk,
                enabled = changeSet.canSelectNextHunk,
                contrast = OpenCodeButtonContrast.Low,
            )
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (!changeSet.selectedFileHasPreview) {
                EmptyMessage(selectedFile.path, "A text preview is unavailable for this file.")
            } else {
                OpenCodeUnifiedDiff(
                    file = selectedFile,
                    document = changeSet.selectedDocument,
                    contextMode = changeSet.contextMode,
                    reviewed = changeSet.selectedFileReviewed,
                    selectedHunkId = changeSet.selectedHunkId,
                )
            }
        }
    }

    OpenCodeBottomSheet(
        visible = pickerVisible,
        title = "Changed files",
        onDismissRequest = { pickerVisible = false },
        pinnedContent = {
            FileFilter(changeSet, viewModel)
        },
    ) {
        if (changeSet.filteredFiles.isEmpty()) {
            EmptyMessage("No matching files", "Try another path or clear the filter.")
        } else {
            changeSet.filteredFiles.forEach { file ->
                FilePickerEntry(file, changeSet, selectedFile.path == file.path) {
                    viewModel.selectFile(file.path)
                    pickerVisible = false
                }
            }
        }
    }
}

@Composable
private fun FileFilter(
    changeSet: ChangesContentState,
    viewModel: ChangesViewModel,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OpenCodeTextInput(
            label = "Filter paths",
            value = changeSet.filter,
            onValueChange = viewModel::updateFilter,
            placeholder = "Search changed paths",
        )
        if (changeSet.filter.isNotEmpty()) {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.CompactText("Clear filter"),
                onClick = viewModel::clearFilter,
                contrast = OpenCodeButtonContrast.Low,
            )
        }
    }
}

@Composable
private fun FilePickerEntry(
    file: FileDiff,
    changeSet: ChangesContentState,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OpenCodeListItem(
        title = file.path,
        subtitle = changeSet.filePickerSummary(file),
        selected = selected,
        onClick = onClick,
        modifier =
            Modifier
                .padding(horizontal = 12.dp)
                .semantics { contentDescription = changeSet.fileAccessibilityLabel(file) },
        trailing = {
            if (file.path in changeSet.reviewedPaths) {
                Text("Reviewed", style = MaterialTheme.typography.labelMedium)
            } else if (selected) {
                Text("Selected", style = MaterialTheme.typography.labelMedium)
            }
        },
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) =
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }

@Composable
private fun EmptyMessage(
    title: String,
    detail: String,
) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
