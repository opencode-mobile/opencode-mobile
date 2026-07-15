package dev.opencode.mobile.ui.changes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.opencode.mobile.backwardRouteTransition
import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.forwardRouteTransition
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeHunkNavigationPill
import dev.opencode.mobile.ui.components.OpenCodeListGroup
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.OpenCodeTopBarOverflowHost
import dev.opencode.mobile.ui.components.OpenCodeUnifiedDiff
import dev.opencode.mobile.ui.components.UnifiedDiffContextMode
import dev.opencode.mobile.ui.icons.OpenCodeIcons
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
    val exitChanges = {
        viewModel.clearFilter()
        onNavigateBack()
    }

    LaunchedEffect(state.phase, initialFilePath, didAttemptDeepLink) {
        if (didAttemptDeepLink || initialFilePath.isNullOrEmpty()) return@LaunchedEffect
        val content = (state.phase as? ChangesPhase.Content)?.changeSet ?: return@LaunchedEffect
        if (content.files.any { it.path == initialFilePath }) {
            backStack.add(FileViewerRoute(initialFilePath))
        }
        didAttemptDeepLink = true
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

@Composable
private fun ChangedFilesScreen(
    state: ChangesUiState,
    viewModel: ChangesViewModel,
    onNavigateBack: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var listResetToken by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        OpenCodeTopBarOverflowHost(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            entries =
                state.availableSources.map { source ->
                    val selected = source == state.selectedSource
                    OpenCodeOverflowMenuEntry(
                        title = source.displayName,
                        icon = if (selected) OpenCodeIcons.Check else null,
                        accessibilityLabel = if (selected) "${source.displayName}, selected" else source.displayName,
                        onClick = {
                            if (source != state.selectedSource) listResetToken += 1
                            viewModel.selectSource(source)
                        },
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
        when (val phase = state.phase) {
            ChangesPhase.Loading -> Centered { CircularProgressIndicator() }
            ChangesPhase.NoTurn ->
                RefreshableMessage(
                    state.isRefreshing,
                    viewModel::refresh,
                    "No completed turn",
                    "Send a prompt and wait for it to complete, then try again.",
                )
            ChangesPhase.NoChanges ->
                RefreshableMessage(
                    state.isRefreshing,
                    viewModel::refresh,
                    state.selectedSource.emptyTitle,
                    state.selectedSource.emptyDetail,
                )
            is ChangesPhase.Content -> ChangedFilesContent(phase.changeSet, state, listResetToken, viewModel, onOpenFile)
            is ChangesPhase.Error ->
                Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        EmptyMessage("Unable to load changes", phase.message)
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
@OptIn(ExperimentalMaterial3Api::class)
private fun ChangedFilesContent(
    changeSet: ChangesContentState,
    state: ChangesUiState,
    listResetToken: Int,
    viewModel: ChangesViewModel,
    onOpenFile: (String) -> Unit,
) {
    val listState =
        rememberSaveable(state.selectedSource, listResetToken, saver = androidx.compose.foundation.lazy.LazyListState.Saver) {
            androidx.compose.foundation.lazy
                .LazyListState()
        }
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = viewModel::refresh,
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
                Text(
                    text =
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                append("${changeSet.summary}  ")
                            }
                            withStyle(SpanStyle(color = openCodeColor("diff-add"))) { append("+${changeSet.additions}") }
                            append("  ")
                            withStyle(SpanStyle(color = openCodeColor("diff-delete"))) { append("−${changeSet.deletions}") }
                        },
                    modifier = Modifier.semantics { contentDescription = changeSet.aggregateAccessibilityLabel },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            state.refreshError?.let { error ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Refresh failed: $error",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OpenCodeButton(
                            OpenCodeButtonVariant.CompactText("Retry"),
                            onClick = viewModel::refresh,
                            contrast = OpenCodeButtonContrast.Low,
                        )
                    }
                }
            }
            item {
                OpenCodeTextInput(
                    value = changeSet.filter,
                    onValueChange = viewModel::updateFilter,
                    placeholder = "Filter",
                    accessibilityLabel = "Filter changed files",
                )
            }
            if (changeSet.filteredFiles.isEmpty()) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyMessage("No matching files", "Try another path or clear the filter.")
                        OpenCodeButton(
                            OpenCodeButtonVariant.CompactText("Clear filter"),
                            onClick = viewModel::clearFilter,
                            contrast = OpenCodeButtonContrast.Low,
                        )
                    }
                }
            } else {
                item {
                    OpenCodeListGroup(itemCount = changeSet.filteredFiles.size) { index ->
                        FileRow(changeSet.filteredFiles[index], changeSet, onOpenFile)
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
) {
    OpenCodeListItem(
        title = file.path,
        onClick = { onOpenFile(file.path) },
        modifier = Modifier.semantics { contentDescription = changeSet.fileAccessibilityLabel(file) },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("+${file.additions}", style = MaterialTheme.typography.labelMedium, color = openCodeColor("diff-add"))
                Text("−${file.deletions}", style = MaterialTheme.typography.labelMedium, color = openCodeColor("diff-delete"))
                Icon(OpenCodeIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        },
    )
}

@Composable
private fun FileViewerScreen(
    file: FileDiff,
    onNavigateBack: () -> Unit,
) {
    var viewer by remember(file.path) { mutableStateOf(FileViewerState(file)) }
    val scrollState = rememberScrollState()
    var selectedHunkOffset by remember(file.path) { mutableIntStateOf(0) }
    LaunchedEffect(viewer.selectedHunkId, selectedHunkOffset) {
        scrollState.animateScrollTo(selectedHunkOffset)
    }
    Column(Modifier.fillMaxSize()) {
        OpenCodeTopBar(
            title = file.path,
            leading = OpenCodeTopBarLeading.Back(onNavigateBack),
            showDivider = true,
        )
        if (!viewer.hasPreview) {
            Centered { EmptyMessage("Preview unavailable", "A text preview is unavailable for this file.") }
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
                    )
                    Spacer(Modifier.height(trailingScrollSpace))
                }
                OpenCodeHunkNavigationPill(
                    previousEnabled = viewer.canSelectPreviousHunk,
                    nextEnabled = viewer.canSelectNextHunk,
                    contextEnabled = viewer.canExpandContext,
                    contextExpanded = viewer.contextMode == UnifiedDiffContextMode.Expanded,
                    onPrevious = { viewer = viewer.selectPreviousHunk() },
                    onToggleContext = { viewer = viewer.toggleContextMode() },
                    onNext = { viewer = viewer.selectNextHunk() },
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 12.dp),
                )
            }
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
        Centered { EmptyMessage(title, detail) }
    }
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
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        Text(
            detail,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
