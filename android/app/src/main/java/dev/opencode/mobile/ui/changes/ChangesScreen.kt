package dev.opencode.mobile.ui.changes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductRouteScreenViewEffect
import dev.opencode.mobile.R
import dev.opencode.mobile.backwardRouteTransition
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.forwardRouteTransition
import dev.opencode.mobile.ui.components.OpenCodeDiffStat
import dev.opencode.mobile.ui.components.OpenCodeDiffStatSeparator
import dev.opencode.mobile.ui.components.OpenCodeDiffStatStyle
import dev.opencode.mobile.ui.components.OpenCodeHunkNavigationPill
import dev.opencode.mobile.ui.components.OpenCodeListGroup
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeEmptyStateAction
import dev.opencode.mobile.ui.components.OpenCodeInlineNotice
import dev.opencode.mobile.ui.components.OpenCodeInlineNoticeAction
import dev.opencode.mobile.ui.components.openCodeDiffStatAnnotatedString
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemAccessory
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeSectionHeaderInset
import dev.opencode.mobile.ui.components.OpenCodeSectionHeaderTrailing
import dev.opencode.mobile.ui.components.OpenCodeSectionHeaderVariant
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeSplitPane
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneDetailControl
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneScreen
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.OpenCodeTopBarOverflowHost
import dev.opencode.mobile.ui.components.currentOpenCodeUsesSplitLayout
import dev.opencode.mobile.ui.components.openCodeDetailLeading
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.components.OpenCodeUnifiedDiff
import dev.opencode.mobile.ui.components.UnifiedDiffContextMode
import dev.opencode.mobile.ui.text.resolve
import kotlinx.serialization.Serializable

@Serializable
private data object ChangedFilesRoute : NavKey

@Serializable
private data class FileViewerRoute(
    val path: String,
) : NavKey

@Composable
fun ChangesScreen(
    sessionId: String,
    worktree: String,
    messageId: String?,
    initialSource: ChangeSource = ChangeSource.LastTurn,
    initialFilePath: String? = null,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChangesViewModel = hiltViewModel(key = "$sessionId-$worktree-$messageId-$initialSource-$initialFilePath"),
) {
    LaunchedEffect(sessionId, worktree, messageId, initialSource) {
        viewModel.bind(sessionId, worktree, messageId, initialSource)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack(ChangedFilesRoute)
    var didAttemptDeepLink by rememberSaveable(initialFilePath) { mutableStateOf(false) }
    var selectedPath by rememberSaveable { mutableStateOf<String?>(null) }
    val useSplitLayout = currentOpenCodeUsesSplitLayout()
    val exitChanges = {
        viewModel.clearFilter()
        onNavigateBack()
    }

    LaunchedEffect(state.phase, initialFilePath, didAttemptDeepLink, useSplitLayout) {
        if (didAttemptDeepLink || initialFilePath.isNullOrEmpty()) return@LaunchedEffect
        val content = (state.phase as? ChangesPhase.Content)?.changeSet ?: return@LaunchedEffect
        if (content.files.any { it.path == initialFilePath }) {
            if (useSplitLayout) selectedPath = initialFilePath else backStack.add(FileViewerRoute(initialFilePath))
        }
        didAttemptDeepLink = true
    }
    LaunchedEffect(useSplitLayout) {
        if (useSplitLayout) {
            val viewer = backStack.lastOrNull() as? FileViewerRoute
            if (viewer != null) {
                selectedPath = viewer.path
                backStack.removeLastOrNull()
            }
        } else {
            val path = selectedPath
            if (path != null) {
                selectedPath = null
                backStack.add(FileViewerRoute(path))
            }
        }
    }

    if (useSplitLayout) {
        OpenCodeSplitPane(
            screen = OpenCodeSplitPaneScreen.Changes,
            modifier = modifier,
            leading = {
                ChangedFilesScreen(
                    state = state,
                    viewModel = viewModel,
                    onNavigateBack = exitChanges,
                    onOpenFile = { selectedPath = it },
                    onSourceChanged = { selectedPath = null },
                    selectedPath = selectedPath,
                    constrainToReadableColumn = false,
                )
            },
            trailing = { control ->
                val file = state.content?.files?.firstOrNull { it.path == selectedPath }
                if (file != null) {
                    FileViewerScreen(
                        file = file,
                        onNavigateBack = { selectedPath = null },
                        detailControl = control,
                        usesReadableColumn = false,
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        OpenCodeTopBar(leading = openCodeDetailLeading(control) {})
                        OpenCodeEmptyState(
                            title = stringResource(R.string.changes_select_file_title),
                            message = stringResource(R.string.changes_select_file_message),
                            icon = OpenCodeIcons.List,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
        )
        return
    }

    NavDisplay(
        modifier = modifier.fillMaxSize(),
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() else exitChanges() },
        transitionSpec = { forwardRouteTransition() },
        popTransitionSpec = { backwardRouteTransition() },
        predictivePopTransitionSpec = { backwardRouteTransition() },
        entryProvider =
            entryProvider {
                entry<ChangedFilesRoute> {
                    ChangedFilesScreen(
                        state = state,
                        viewModel = viewModel,
                        onNavigateBack = exitChanges,
                        onOpenFile = { backStack.add(FileViewerRoute(it)) },
                        onSourceChanged = { if (backStack.size > 1) backStack.removeLastOrNull() },
                    )
                }
                entry<FileViewerRoute> { route ->
                    state.content?.files?.firstOrNull { it.path == route.path }?.let { file ->
                        FileViewerScreen(file = file, onNavigateBack = { backStack.removeLastOrNull() })
                    }
                }
            },
    )
}

private val ChangesUiState.content: ChangesContentState?
    get() = (phase as? ChangesPhase.Content)?.changeSet

private fun logChangesAction(actionId: String) {
    ProductAnalytics.logUserAction(
        screenId = ProductAnalytics.Screen.CHANGES,
        actionId = actionId,
    )
}

private fun logFileViewerAction(actionId: String) {
    ProductAnalytics.logUserAction(
        screenId = ProductAnalytics.Screen.CHANGES_FILE_VIEWER,
        actionId = actionId,
    )
}

@Composable
private fun ChangedFilesScreen(
    state: ChangesUiState,
    viewModel: ChangesViewModel,
    onNavigateBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    onSourceChanged: () -> Unit = {},
    selectedPath: String? = null,
    constrainToReadableColumn: Boolean = true,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var listResetToken by rememberSaveable { mutableIntStateOf(0) }
    ProductRouteScreenViewEffect(screenId = ProductAnalytics.Screen.CHANGES)
    Column(Modifier.fillMaxSize()) {
        OpenCodeTopBarOverflowHost(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            entries =
                state.availableSources.map { source ->
                    val selected = source == state.selectedSource
                    val sourceName = source.displayName.resolve()
                    OpenCodeOverflowMenuEntry(
                        title = sourceName,
                        icon = if (selected) OpenCodeIcons.Check else null,
                        accessibilityLabel =
                            if (selected) stringResource(R.string.changes_source_menu_selected, sourceName) else sourceName,
                        onClick = {
                            if (source != state.selectedSource) {
                                listResetToken += 1
                                onSourceChanged()
                            }
                            viewModel.selectSource(source)
                        },
                    )
                },
        ) {
            OpenCodeTopBar(
                title = stringResource(R.string.changes_title),
                subtitle = state.selectedSource.displayName.resolve(),
                leading =
                    OpenCodeTopBarLeading.Back(
                        onClick = {
                            logChangesAction(ProductAnalytics.Action.CHANGES_BACK)
                            onNavigateBack()
                        },
                    ),
                actions =
                    listOf(
                        OpenCodeTopBarAction.Icon(
                            imageVector = OpenCodeIcons.MoreVert,
                            contentDescription = stringResource(R.string.changes_source_menu_label),
                            onClick = {
                                logChangesAction(ProductAnalytics.Action.CHANGES_OPEN_SOURCE_MENU)
                                menuExpanded = true
                            },
                        ),
                    ),
                showDivider = true,
            )
        }
        val phaseContent: @Composable () -> Unit = {
            when (val phase = state.phase) {
                ChangesPhase.Loading ->
                    OpenCodeStatusStack(
                        title = stringResource(R.string.changes_loading_title),
                        subtitle = stringResource(R.string.changes_loading_message),
                        modifier = Modifier.fillMaxSize(),
                    )
                ChangesPhase.NoTurn ->
                    RefreshableMessage(
                        state.isRefreshing,
                        {
                            logChangesAction(ProductAnalytics.Action.CHANGES_REFRESH)
                            viewModel.refresh()
                        },
                        stringResource(R.string.changes_no_turn_title),
                        stringResource(R.string.changes_no_turn_message),
                    )
                ChangesPhase.NoChanges ->
                    RefreshableMessage(
                        state.isRefreshing,
                        {
                            logChangesAction(ProductAnalytics.Action.CHANGES_REFRESH)
                            viewModel.refresh()
                        },
                        state.selectedSource.emptyTitle.resolve(),
                        state.selectedSource.emptyDetail.resolve(),
                    )
                is ChangesPhase.Content ->
                    ChangedFilesContent(phase.changeSet, state, listResetToken, viewModel, onOpenFile, selectedPath)
                is ChangesPhase.Error ->
                    OpenCodeEmptyState(
                        title = stringResource(R.string.changes_error_title),
                        message = phase.message.resolve().ifBlank { stringResource(R.string.changes_error_fallback) },
                        primaryAction =
                            OpenCodeEmptyStateAction(title = stringResource(R.string.common_retry)) {
                                logChangesAction(ProductAnalytics.Action.CHANGES_RETRY)
                                viewModel.retry()
                            },
                        modifier = Modifier.fillMaxSize(),
                    )
            }
        }
        if (constrainToReadableColumn) {
            OpenCodeReadableColumn(modifier = Modifier.weight(1f)) { phaseContent() }
        } else {
            Box(Modifier.weight(1f)) { phaseContent() }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChangedFilesContent(
    changeSet: ChangesContentState,
    state: ChangesUiState,
    listResetToken: Int,
    viewModel: ChangesViewModel,
    onOpenFile: (String) -> Unit,
    selectedPath: String? = null,
) {
    val listState =
        rememberSaveable(state.selectedSource, listResetToken, saver = androidx.compose.foundation.lazy.LazyListState.Saver) {
            androidx.compose.foundation.lazy
                .LazyListState()
        }
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = {
            logChangesAction(ProductAnalytics.Action.CHANGES_REFRESH)
            viewModel.refresh()
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(16.dp),
        ) {
            item {
                val summary = changeSet.summary.resolve()
                val aggregateLabel = changeSet.aggregateAccessibilityLabel.resolve()
                Text(
                    text =
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                append("$summary  ")
                            }
                            append(
                                openCodeDiffStatAnnotatedString(
                                    additions = changeSet.additions,
                                    deletions = changeSet.deletions,
                                    separator = OpenCodeDiffStatSeparator.Wide,
                                ),
                            )
                        },
                    modifier = Modifier.semantics { contentDescription = aggregateLabel },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            state.refreshError?.let { error ->
                item {
                    OpenCodeInlineNotice(
                        message = stringResource(R.string.changes_refresh_failed, error.resolve()),
                        action =
                            OpenCodeInlineNoticeAction(title = stringResource(R.string.common_retry)) {
                                logChangesAction(ProductAnalytics.Action.CHANGES_RETRY)
                                viewModel.refresh()
                            },
                    )
                }
            }
            item {
                OpenCodeTextInput(
                    value = changeSet.filter,
                    onValueChange = viewModel::updateFilter,
                    hint = stringResource(R.string.changes_filter_hint),
                    accessibilityLabel = stringResource(R.string.changes_filter_accessibility_label),
                )
            }
            if (changeSet.filteredFiles.isEmpty()) {
                item {
                    OpenCodeEmptyState(
                        title = stringResource(R.string.changes_no_matches_title),
                        message = stringResource(R.string.changes_no_matches_message),
                        primaryAction =
                            OpenCodeEmptyStateAction(title = stringResource(R.string.changes_no_matches_clear_filter)) {
                                // clearFilter() also runs when leaving Changes, so log the tap here.
                                logChangesAction(ProductAnalytics.Action.CHANGES_CLEAR_FILTER)
                                viewModel.clearFilter()
                            },
                    )
                }
            } else {
                changeSet.fileGroups.forEach { group ->
                    item(key = group.directory) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OpenCodeSectionHeader(
                                title = group.directory,
                                variant = OpenCodeSectionHeaderVariant.Plain,
                                trailing = OpenCodeSectionHeaderTrailing.Count(group.files.size),
                                inset = OpenCodeSectionHeaderInset.Flush,
                            )
                            OpenCodeListGroup(itemCount = group.files.size) { index ->
                                FileRow(group.files[index], changeSet, onOpenFile, selectedPath)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: FileDiff,
    changeSet: ChangesContentState,
    onOpenFile: (String) -> Unit,
    selectedPath: String? = null,
) {
    val accessibilityLabel = changeSet.fileAccessibilityLabel(file).resolve()
    OpenCodeListItem(
        title = changedFileName(file.path),
        selected = file.path == selectedPath,
        onClick = {
            logChangesAction(ProductAnalytics.Action.CHANGES_OPEN_FILE)
            onOpenFile(file.path)
        },
        modifier = Modifier.semantics { contentDescription = accessibilityLabel },
        accessory = OpenCodeListItemAccessory.Disclosure,
        trailing = {
            OpenCodeDiffStat(
                additions = file.additions,
                deletions = file.deletions,
                style = OpenCodeDiffStatStyle.Label,
                separator = OpenCodeDiffStatSeparator.Split,
            )
        },
    )
}

@Composable
private fun FileViewerScreen(
    file: FileDiff,
    onNavigateBack: () -> Unit,
    detailControl: OpenCodeSplitPaneDetailControl? = null,
    usesReadableColumn: Boolean = true,
) {
    var viewer by remember(file.path) { mutableStateOf(FileViewerState(file)) }
    val scrollState = rememberScrollState()
    var selectedHunkOffset by remember(file.path) { mutableIntStateOf(0) }
    // Open at the top (diff header visible, like iOS); follow the hunk only once the user navigates.
    var hunkNavigated by remember(file.path) { mutableStateOf(false) }
    LaunchedEffect(viewer.selectedHunkId, selectedHunkOffset, hunkNavigated) {
        if (hunkNavigated) scrollState.animateScrollTo(selectedHunkOffset)
    }
    ProductRouteScreenViewEffect(screenId = ProductAnalytics.Screen.CHANGES_FILE_VIEWER)
    Column(Modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = file.path,
            leading =
                openCodeDetailLeading(detailControl) {
                    logFileViewerAction(ProductAnalytics.Action.FILE_VIEWER_BACK)
                    onNavigateBack()
                },
            showDivider = true,
        )
        val viewerBody: @Composable () -> Unit = {
            if (!viewer.hasPreview) {
                Centered {
                    OpenCodeEmptyState(
                        title = stringResource(R.string.changes_preview_unavailable_title),
                        message = stringResource(R.string.changes_preview_unavailable_message),
                    )
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val trailingScrollSpace = maxOf(84.dp, maxHeight - 20.dp)
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(start = 16.dp, top = 16.dp, end = 16.dp),
                    ) {
                        OpenCodeUnifiedDiff(
                            file = file,
                            document = viewer.document,
                            contextMode = viewer.contextMode,
                            selectedHunkId = viewer.selectedHunkId,
                            onSelectedHunkOffset = { selectedHunkOffset = it },
                            revealSelectedHunk = hunkNavigated,
                        )
                        Spacer(Modifier.height(trailingScrollSpace))
                    }
                    OpenCodeHunkNavigationPill(
                        previousEnabled = viewer.canSelectPreviousHunk,
                        nextEnabled = viewer.canSelectNextHunk,
                        contextEnabled = viewer.canExpandContext,
                        contextExpanded = viewer.contextMode == UnifiedDiffContextMode.Expanded,
                        onPrevious = {
                            logFileViewerAction(ProductAnalytics.Action.FILE_VIEWER_HUNK_PREVIOUS)
                            hunkNavigated = true
                            viewer = viewer.selectPreviousHunk()
                        },
                        onToggleContext = {
                            logFileViewerAction(ProductAnalytics.Action.FILE_VIEWER_TOGGLE_CONTEXT)
                            viewer = viewer.toggleContextMode()
                        },
                        onNext = {
                            logFileViewerAction(ProductAnalytics.Action.FILE_VIEWER_HUNK_NEXT)
                            hunkNavigated = true
                            viewer = viewer.selectNextHunk()
                        },
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 12.dp),
                    )
                }
            }
        }
        if (usesReadableColumn) {
            OpenCodeReadableColumn(modifier = Modifier.weight(1f)) { viewerBody() }
        } else {
            Box(Modifier.weight(1f)) { viewerBody() }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RefreshableMessage(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    title: String,
    detail: String,
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        Centered {
            OpenCodeEmptyState(title = title, message = detail)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) =
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }

