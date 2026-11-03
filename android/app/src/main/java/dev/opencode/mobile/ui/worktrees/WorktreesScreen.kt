package dev.opencode.mobile.ui.worktrees

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.R
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogNote
import dev.opencode.mobile.ui.components.OpenCodeDialogNoteTone
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStatus
import dev.opencode.mobile.ui.components.OpenCodeListItemStatusTone
import dev.opencode.mobile.ui.components.OpenCodeListItemSubtitleSegment
import dev.opencode.mobile.ui.components.OpenCodeListItemSubtitleTone
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeSectionHeaderTrailing
import dev.opencode.mobile.ui.components.OpenCodeSectionHeaderVariant
import dev.opencode.mobile.ui.components.OpenCodeSnackbarHost
import dev.opencode.mobile.ui.components.OpenCodeSnackbarMessage
import dev.opencode.mobile.ui.components.OpenCodeSnackbarVariant
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset
import dev.opencode.mobile.ui.icons.OpenCodeIcons

object WorktreesTags {
    const val LIST = "worktreesList"
    const val MENU_ENTRY = "homeMenuWorktrees"

    fun row(directory: String) = "worktreeRow:$directory"

    fun more(directory: String) = "worktreeMore:$directory"
}

/** Every worktree of Home's projects, grouped by project, and removing one. */
@Composable
fun WorktreesScreen(
    onNavigateBack: () -> Unit,
    onOpenSession: (sessionId: String, title: String, directory: String) -> Unit,
    homeShowsWorktrees: Boolean,
    modifier: Modifier = Modifier,
    viewModel: WorktreesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    viewModel.homeShowsWorktrees = homeShowsWorktrees
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                WorktreesEvent.Close -> onNavigateBack()
                is WorktreesEvent.OpenSession -> onOpenSession(event.sessionId, event.title, event.directory)
            }
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OpenCodeTopBar(
                title = stringResource(R.string.worktrees_title),
                subtitle =
                    stringResource(
                        R.string.worktrees_subtitle,
                        pluralStringResource(R.plurals.worktrees_count, state.worktreeCount, state.worktreeCount),
                        pluralStringResource(R.plurals.home_project_count, state.projectCount, state.projectCount),
                    ),
                leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
                showDivider = true,
            )
            OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
                WorktreesList(
                    groups = state.groups,
                    onOpenActions = viewModel::openActions,
                )
            }
        }
        WorktreesSnackbar(
            snackbar = state.snackbar,
            onShown = viewModel::snackbarShown,
            onRetry = viewModel::retry,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
        )
    }
    WorktreeActionsSheet(state.actions, viewModel)
    WorktreeRemoveDialog(state.dialog, viewModel)
}

@Composable
private fun WorktreesList(
    groups: List<WorktreesGroupUi>,
    onOpenActions: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(WorktreesTags.LIST),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        groups.forEach { group ->
            item(key = "header:${group.projectKey}") {
                OpenCodeSectionHeader(
                    title = group.displayName,
                    variant = OpenCodeSectionHeaderVariant.Plain,
                    trailing = group.worktrees.size.takeIf { it > 0 }?.let { OpenCodeSectionHeaderTrailing.Count(it) },
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "main:${group.projectKey}") {
                OpenCodeListItem(
                    title = stringResource(R.string.worktrees_main_checkout),
                    subtitle = group.mainCheckout,
                    caption = stringResource(R.string.worktrees_cannot_remove),
                    startDecorator =
                        StartDecorator.Letter(
                            letterCharacterForProject(group.displayName),
                            letterAccentForWorktree(group.primaryPath),
                        ),
                    modifier = Modifier.animateItem(),
                )
            }
            items(group.worktrees, key = { "worktree:${it.directory}" }) { row ->
                WorktreeRow(row, onOpenActions, Modifier.animateItem())
            }
            if (group.worktrees.isEmpty()) {
                item(key = "empty:${group.projectKey}") {
                    WorktreesNote(stringResource(R.string.worktrees_none_in_project), Modifier.animateItem())
                }
            }
        }
        item(key = "footer") {
            WorktreesNote(stringResource(R.string.worktrees_footer), Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun WorktreeRow(
    row: WorktreeRowUi,
    onOpenActions: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sessions =
        if (row.sessionCount > 0) {
            pluralStringResource(R.plurals.worktrees_session_count, row.sessionCount, row.sessionCount)
        } else {
            stringResource(R.string.worktrees_no_sessions)
        }
    val changed =
        row.changeCount?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.worktrees_changed, it, it) }
    OpenCodeListItem(
        title = row.name,
        subtitle = sessions,
        detailSegments = listOf(OpenCodeListItemSubtitleSegment(row.directory, OpenCodeListItemSubtitleTone.Tertiary)),
        titleTag = changed.takeIf { !row.isRemoving },
        status =
            when {
                row.isRemoving -> OpenCodeListItemStatus(stringResource(R.string.worktrees_removing), OpenCodeListItemStatusTone.Stale)
                row.isRunning -> OpenCodeListItemStatus(stringResource(R.string.session_list_status_running), OpenCodeListItemStatusTone.Interactive)
                else -> null
            },
        enabled = !row.isRemoving,
        startDecorator = StartDecorator.Icon(OpenCodeIcons.CallSplit, null),
        onClick = { onOpenActions(row.directory) },
        trailing = {
            if (row.isRemoving) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 12.dp).size(18.dp),
                    strokeWidth = 2.dp,
                    color = openCodeColor("text-weak"),
                )
            } else {
                OpenCodeButton(
                    variant =
                        OpenCodeButtonVariant.IconCircle(
                            OpenCodeIcons.MoreVert,
                            stringResource(R.string.worktrees_more, row.name),
                        ),
                    contrast = OpenCodeButtonContrast.Low,
                    onClick = { onOpenActions(row.directory) },
                    modifier = Modifier.testTag(WorktreesTags.more(row.directory)),
                )
            }
        },
        modifier = modifier.testTag(WorktreesTags.row(row.directory)),
    )
}

@Composable
private fun WorktreesNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = openCodeColor("text-weak"),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun WorktreeActionsSheet(
    actions: WorktreeActionsUi?,
    viewModel: WorktreesViewModel,
) {
    // The sheet keeps its last target while it plays its exit motion.
    val shown = remember { arrayOfNulls<WorktreeActionsUi>(1) }
    if (actions != null) shown[0] = actions
    val target = actions ?: shown[0]
    OpenCodeBottomSheet(
        visible = actions != null,
        title = target?.name.orEmpty(),
        onDismissRequest = viewModel::dismissActions,
        pinnedTop = {
            if (target != null) {
                Text(
                    text =
                        stringResource(
                            R.string.worktrees_actions_subtitle,
                            target.projectName,
                            pluralStringResource(R.plurals.worktrees_session_count, target.sessionCount, target.sessionCount),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = openCodeColor("text-weak"),
                    modifier = Modifier.openCodeBottomSheetContentInset(),
                )
            }
        },
    ) {
        if (target == null) return@OpenCodeBottomSheet
        if (target.canShowOnHome) {
            OpenCodeBottomSheetEntry(
                title = stringResource(R.string.worktrees_actions_show_on_home),
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.List),
                onClick = { viewModel.showOnHome(target.directory) },
            )
        }
        OpenCodeBottomSheetEntry(
            title = stringResource(R.string.worktrees_actions_new_session),
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Add),
            onClick = { viewModel.newSessionHere(target.directory) },
        )
        OpenCodeBottomSheetEntry(
            title = stringResource(R.string.worktrees_actions_remove),
            destructive = true,
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Delete),
            onClick = { viewModel.requestRemove(target.directory) },
        )
    }
}

@Composable
private fun WorktreeRemoveDialog(
    dialog: WorktreeRemoveDialogUi?,
    viewModel: WorktreesViewModel,
) {
    when (dialog) {
        null -> Unit
        is WorktreeRemoveDialogUi.Blocked ->
            OpenCodeDialog(
                visible = true,
                title = stringResource(R.string.worktrees_blocked_title, dialog.name),
                subtitle = stringResource(R.string.worktrees_blocked_message),
                primaryOption =
                    OpenCodeDialogOption(
                        title = stringResource(R.string.worktrees_blocked_show_session),
                        onClick = viewModel::showRunningSession,
                    ),
                secondaryOption =
                    OpenCodeDialogOption(
                        title = stringResource(R.string.worktrees_blocked_ok),
                        onClick = viewModel::dismissDialog,
                    ),
                onDismissRequest = viewModel::dismissDialog,
            )
        is WorktreeRemoveDialogUi.Confirm -> {
            val changes = dialog.changeCount?.takeIf { it > 0 }
            val notes =
                buildList {
                    when {
                        changes != null ->
                            add(
                                OpenCodeDialogNote(
                                    pluralStringResource(R.plurals.worktrees_remove_changes, changes, changes),
                                    OpenCodeDialogNoteTone.Warning,
                                ),
                            )
                        dialog.changesUnknown ->
                            add(OpenCodeDialogNote(stringResource(R.string.worktrees_remove_changes_unknown), OpenCodeDialogNoteTone.Warning))
                    }
                    if (dialog.sessionCount > 0) {
                        add(
                            OpenCodeDialogNote(
                                pluralStringResource(R.plurals.worktrees_remove_sessions, dialog.sessionCount, dialog.sessionCount),
                            ),
                        )
                    }
                }
            OpenCodeDialog(
                visible = true,
                title = stringResource(R.string.worktrees_remove_title, dialog.name),
                subtitle = stringResource(R.string.worktrees_remove_message, dialog.directory),
                notes = notes,
                primaryOption =
                    OpenCodeDialogOption(
                        title = stringResource(if (dialog.losesChanges) R.string.worktrees_remove_confirm_anyway else R.string.common_remove),
                        destructive = true,
                        onClick = viewModel::confirmRemove,
                    ),
                secondaryOption =
                    OpenCodeDialogOption(
                        title = stringResource(R.string.common_cancel),
                        onClick = viewModel::dismissDialog,
                    ),
                onDismissRequest = viewModel::dismissDialog,
            )
        }
    }
}

@Composable
private fun WorktreesSnackbar(
    snackbar: WorktreesSnackbarUi?,
    onShown: (Long) -> Unit,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val message =
        when (snackbar) {
            null -> null
            is WorktreesSnackbarUi.Removed ->
                OpenCodeSnackbarMessage(
                    text = resources.getString(R.string.worktrees_removed, snackbar.name),
                    id = snackbar.id,
                )
            is WorktreesSnackbarUi.Failed ->
                OpenCodeSnackbarMessage(
                    text = resources.getString(R.string.worktrees_remove_failed, snackbar.name, snackbar.message),
                    variant = OpenCodeSnackbarVariant.Error,
                    actionLabel = resources.getString(R.string.common_retry),
                    id = snackbar.id,
                )
        }
    OpenCodeSnackbarHost(
        message = message,
        onDismissed = { snackbar?.let { onShown(it.id) } },
        onAction = {
            (snackbar as? WorktreesSnackbarUi.Failed)?.let { failed -> onRetry(failed.directory) }
        },
        modifier = modifier,
    )
}
