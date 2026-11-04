package dev.opencode.mobile.ui.archived

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.session.ArchivedSessionPeriod
import dev.opencode.mobile.domain.time.RelativeTimeFormatting
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeEmptyStateAction
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeSnackbarHost
import dev.opencode.mobile.ui.components.OpenCodeSnackbarMessage
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.SessionListRow
import dev.opencode.mobile.ui.components.openCodeBottomSheetContentInset
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.text.resolve
import dev.opencode.kit.R as KitR

/** Test tags of the Archived sessions screen. */
internal object ArchivedSessionsTags {
    fun row(sessionId: String): String = "archivedSessionRow:$sessionId"

    fun unarchiveButton(sessionId: String): String = "archivedSessionUnarchive:$sessionId"
}

/**
 * Archived sessions of Home scope [scopeId], pushed from Home's ⋯ menu. [onOpenSession] opens
 * a session in Session Chat (directory, then id and title).
 */
@Composable
fun ArchivedSessionsScreen(
    scopeId: String,
    onNavigateBack: () -> Unit,
    onOpenSession: (directory: String, sessionId: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArchivedSessionsViewModel = hiltViewModel(),
) {
    LaunchedEffect(scopeId) { viewModel.bind(scopeId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scopeTitle = uiState.scopeTitle.resolve()

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OpenCodeTopBar(
                title = stringResource(R.string.archived_sessions_title),
                subtitle = scopeTitle,
                leading = OpenCodeTopBarLeading.Back(onClick = onNavigateBack),
                showDivider = true,
            )
            OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
                when (val content = uiState.content) {
                    ArchivedSessionsContent.Loading ->
                        OpenCodeStatusStack(
                            title = stringResource(R.string.common_loading_sessions),
                            subtitle = stringResource(R.string.common_checking_server),
                            modifier = Modifier.fillMaxSize(),
                        )
                    is ArchivedSessionsContent.Failed ->
                        CenteredEmptyState {
                            OpenCodeEmptyState(
                                title = stringResource(R.string.archived_sessions_title),
                                message = content.message.resolve(),
                                icon = OpenCodeIcons.Archive,
                                primaryAction =
                                    OpenCodeEmptyStateAction(stringResource(KitR.string.common_retry), viewModel::retry),
                            )
                        }
                    is ArchivedSessionsContent.Loaded ->
                        if (content.isEmpty) {
                            CenteredEmptyState {
                                OpenCodeEmptyState(
                                    title = stringResource(R.string.archived_sessions_empty_title),
                                    message = stringResource(R.string.archived_sessions_empty_message, scopeTitle),
                                    icon = OpenCodeIcons.Archive,
                                    primaryAction =
                                        if (content.canShowAllProjects) {
                                            OpenCodeEmptyStateAction(
                                                stringResource(R.string.archived_sessions_show_all_projects),
                                                viewModel::showAllProjects,
                                            )
                                        } else {
                                            null
                                        },
                                )
                            }
                        } else {
                            ArchivedSessionsList(
                                sections = content.sections,
                                onRowClick = viewModel::openActions,
                                onUnarchive = { viewModel.unarchive(it.id) },
                            )
                        }
                }
            }
        }

        val snackbar = uiState.snackbar
        val undoLabel = stringResource(R.string.archived_sessions_undo)
        OpenCodeSnackbarHost(
            message =
                snackbar?.let {
                    OpenCodeSnackbarMessage(
                        text = it.text.resolve(),
                        variant = it.variant,
                        actionLabel = if (it.undoSessionId != null) undoLabel else null,
                        id = it.id,
                    )
                },
            onDismissed = viewModel::consumeSnackbar,
            onAction = { message ->
                val undoId = snackbar?.takeIf { it.id == message.id }?.undoSessionId
                if (undoId != null) viewModel.undoUnarchive(undoId)
            },
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
        )
    }

    ArchivedSessionActions(
        uiState = uiState,
        onDismiss = viewModel::dismissActions,
        onUnarchive = viewModel::chooseUnarchive,
        onOpen = {
            viewModel.chooseOpen()?.let { row -> onOpenSession(row.directory, row.id, row.title) }
        },
        onDelete = viewModel::chooseDelete,
        onConfirmDelete = viewModel::confirmDelete,
        onDismissDelete = viewModel::dismissDeleteConfirm,
    )
}

@Composable
private fun CenteredEmptyState(content: @Composable () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(bottom = 88.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ArchivedSessionsList(
    sections: List<ArchivedSessionSection>,
    onRowClick: (ArchivedSessionRow) -> Unit,
    onUnarchive: (ArchivedSessionRow) -> Unit,
) {
    val rowStyle = HomeDisplayPreferences.sessionRowStyle
    val unarchiveLabel = stringResource(R.string.archived_sessions_unarchive)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        sections.forEach { section ->
            item(key = "header-${section.period}") {
                OpenCodeSectionHeader(
                    title = stringResource(section.period.titleRes),
                    modifier = Modifier.animateItem(),
                )
            }
            items(section.rows, key = { it.id }) { row ->
                Box(
                    modifier =
                        Modifier
                            .animateItem()
                            .testTag(ArchivedSessionsTags.row(row.id)),
                ) {
                    SessionListRow(
                        session = row.row,
                        showStatusDot = false,
                        selected = false,
                        onSessionSelected = { onRowClick(row) },
                        onSessionLongPress = { onRowClick(row) },
                        rowStyle = rowStyle,
                        showsStatus = false,
                        trailing = {
                            OpenCodeButton(
                                variant = OpenCodeButtonVariant.IconCircle(OpenCodeIcons.Unarchive, unarchiveLabel),
                                contrast = OpenCodeButtonContrast.Low,
                                onClick = { onUnarchive(row) },
                                modifier = Modifier.testTag(ArchivedSessionsTags.unarchiveButton(row.id)),
                            )
                        },
                    )
                }
            }
        }
        item(key = "footer") {
            Text(
                text = stringResource(R.string.archived_sessions_footer),
                color = openCodeColor("text-weak"),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .animateItem()
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun ArchivedSessionActions(
    uiState: ArchivedSessionsUiState,
    onDismiss: () -> Unit,
    onUnarchive: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
) {
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.ARCHIVED_SESSION_ACTIONS,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = uiState.sheetVisible,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.ARCHIVED_DELETE_SESSION_CONFIRM,
        presentation = ProductAnalytics.Presentation.DIALOG,
        visible = uiState.deleteConfirmVisible,
    )
    val target = uiState.actionTarget
    OpenCodeBottomSheet(
        visible = uiState.sheetVisible,
        title = target?.title.orEmpty(),
        onDismissRequest = onDismiss,
    ) {
        if (target != null) {
            Text(
                text = stringResource(R.string.archived_sessions_archived_at, RelativeTimeFormatting.format(target.archivedAtEpochMs).resolve()),
                color = openCodeColor("text-weak"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.openCodeBottomSheetContentInset().padding(bottom = 8.dp),
            )
        }
        OpenCodeBottomSheetEntry(
            title = stringResource(R.string.archived_sessions_unarchive),
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Unarchive),
            onClick = onUnarchive,
        )
        OpenCodeBottomSheetEntry(
            title = stringResource(R.string.common_open),
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.ChevronRight),
            onClick = onOpen,
        )
        OpenCodeBottomSheetEntry(
            title = stringResource(R.string.archived_sessions_delete),
            destructive = true,
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Close),
            onClick = onDelete,
        )
    }
    OpenCodeDialog(
        visible = uiState.deleteConfirmVisible,
        title = stringResource(R.string.home_delete_title),
        subtitle = stringResource(R.string.archived_sessions_delete_dialog_message, uiState.deleteTarget?.title.orEmpty()),
        primaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.common_delete),
                destructive = true,
                onClick = onConfirmDelete,
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = stringResource(KitR.string.common_cancel),
                onClick = onDismissDelete,
            ),
        onDismissRequest = onDismissDelete,
    )
}

private val ArchivedSessionPeriod.titleRes: Int
    get() =
        when (this) {
            ArchivedSessionPeriod.ThisWeek -> R.string.archived_sessions_section_this_week
            ArchivedSessionPeriod.Earlier -> R.string.archived_sessions_section_earlier
        }
