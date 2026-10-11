package dev.opencode.mobile.ui.files

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.backwardRouteTransition
import dev.opencode.mobile.forwardRouteTransition
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeSnackbarHost
import dev.opencode.mobile.ui.components.OpenCodeSnackbarMessage
import dev.opencode.mobile.ui.components.OpenCodeSplitPane
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneScreen
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.currentOpenCodeUsesSplitLayout
import dev.opencode.mobile.ui.components.openCodeDetailLeading
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlinx.serialization.Serializable

@Serializable
private data object FilesTreeRoute : NavKey

@Serializable
private data class FilesViewerRoute(
    val path: String,
) : NavKey

/**
 * Read-only File Browser for the Session Chat [worktree]. Phones push the File Viewer over the
 * tree; split layouts show the tree beside a tabbed viewer.
 */
@Composable
fun FilesScreen(
    worktree: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FilesViewModel = hiltViewModel(key = "files-$worktree"),
) {
    LaunchedEffect(worktree) { viewModel.bind(worktree) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack(FilesTreeRoute)
    val useSplitLayout = currentOpenCodeUsesSplitLayout()
    val context = LocalContext.current
    var snackbar by remember { mutableStateOf<OpenCodeSnackbarMessage?>(null) }
    val copyPath: (String) -> Unit = { path ->
        logFilesAction(ProductAnalytics.Action.FILES_VIEWER_COPY_PATH, viewer = true)
        copyFilePathToClipboard(context, path)
        snackbar = OpenCodeSnackbarMessage("Path copied")
    }
    val treeActions =
        FilesTreeActions(
            onNavigateBack = {
                logFilesAction(ProductAnalytics.Action.FILES_BACK)
                onNavigateBack()
            },
            onToggleHidden = {
                logFilesAction(ProductAnalytics.Action.FILES_TOGGLE_HIDDEN)
                viewModel.toggleHidden()
            },
            onRefresh = {
                logFilesAction(ProductAnalytics.Action.FILES_REFRESH)
                viewModel.refresh()
            },
            onQueryChange = { query ->
                if (state.query.isBlank() && query.isNotBlank()) logFilesAction(ProductAnalytics.Action.FILES_UPDATE_QUERY)
                viewModel.setQuery(query)
            },
            onToggleDirectory = { path ->
                logFilesAction(ProductAnalytics.Action.FILES_TOGGLE_FOLDER)
                viewModel.toggleDirectory(path)
            },
            onRetryFolder = { path ->
                logFilesAction(ProductAnalytics.Action.FILES_RETRY)
                viewModel.retryFolder(path)
            },
            onRetryFind = {
                logFilesAction(ProductAnalytics.Action.FILES_RETRY)
                viewModel.retryFind()
            },
        )
    val openFile: (String) -> Unit = { path ->
        logFilesAction(ProductAnalytics.Action.FILES_OPEN_FILE)
        viewModel.openFile(path)
    }
    val toggleWrap = {
        logFilesAction(ProductAnalytics.Action.FILES_VIEWER_TOGGLE_WRAP, viewer = true)
        viewModel.toggleWrap()
    }
    val retryFile: (String) -> Unit = { path ->
        logFilesAction(ProductAnalytics.Action.FILES_VIEWER_RETRY, viewer = true)
        viewModel.retryFile(path)
    }
    val toggleMarkdownSource: (String) -> Unit = { path ->
        logFilesAction(ProductAnalytics.Action.FILES_VIEWER_TOGGLE_MARKDOWN_SOURCE, viewer = true)
        viewModel.toggleMarkdownSource(path)
    }

    // Hand the open file between the pushed viewer (compact) and the active tab (split).
    LaunchedEffect(useSplitLayout) {
        if (useSplitLayout) {
            if (backStack.lastOrNull() is FilesViewerRoute) backStack.removeLastOrNull()
        } else {
            val active = viewModel.uiState.value.activePath
            if (active != null && backStack.lastOrNull() !is FilesViewerRoute) {
                backStack.add(FilesViewerRoute(active))
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (useSplitLayout) {
            OpenCodeSplitPane(
                screen = OpenCodeSplitPaneScreen.Files,
                leading = {
                    FilesTreePane(
                        state = state,
                        actions = treeActions,
                        selectedPath = state.activePath,
                        onOpenFile = openFile,
                    )
                },
                trailing = { control ->
                    val viewer = state.activeViewer
                    if (viewer == null) {
                        Column(Modifier.fillMaxSize()) {
                            OpenCodeTopBar(leading = openCodeDetailLeading(control) {})
                            OpenCodeEmptyState(
                                title = "Open a file from the tree",
                                message = "Opened files stay as tabs",
                                icon = OpenCodeIcons.Description,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        FilesViewerPane(
                            viewer = viewer,
                            wrapLines = state.wrapLines,
                            leading = openCodeDetailLeading(control) {},
                            onCopyPath = { copyPath(viewer.path) },
                            onToggleWrap = toggleWrap,
                            onRetry = { retryFile(viewer.path) },
                            onToggleMarkdownSource = { toggleMarkdownSource(viewer.path) },
                            tabs = {
                                FilesTabStrip(
                                    tabs = state.tabs,
                                    activePath = state.activePath,
                                    onSelect = { path ->
                                        logFilesAction(ProductAnalytics.Action.FILES_SELECT_TAB)
                                        viewModel.selectTab(path)
                                    },
                                    onClose = { path ->
                                        logFilesAction(ProductAnalytics.Action.FILES_CLOSE_TAB)
                                        viewModel.closeTab(path)
                                    },
                                )
                            },
                        )
                    }
                },
            )
        } else {
            val closeViewer: (String) -> Unit = { path ->
                backStack.removeLastOrNull()
                viewModel.closeTab(path)
            }
            NavDisplay(
                modifier = Modifier.fillMaxSize(),
                backStack = backStack,
                onBack = {
                    val top = backStack.lastOrNull()
                    if (top is FilesViewerRoute) closeViewer(top.path) else onNavigateBack()
                },
                transitionSpec = { forwardRouteTransition() },
                popTransitionSpec = { backwardRouteTransition() },
                predictivePopTransitionSpec = { backwardRouteTransition() },
                entryProvider =
                    entryProvider {
                        entry<FilesTreeRoute> {
                            FilesTreePane(
                                state = state,
                                actions = treeActions,
                                selectedPath = null,
                                onOpenFile = { path ->
                                    openFile(path)
                                    backStack.add(FilesViewerRoute(path))
                                },
                            )
                        }
                        entry<FilesViewerRoute> { route ->
                            LaunchedEffect(route.path) {
                                // Restored after process death: the Screen Model starts empty.
                                if (viewModel.uiState.value.viewers[route.path] == null) viewModel.openFile(route.path)
                            }
                            // Keep the last content while the pop transition runs after the tab closes.
                            val current = state.viewers[route.path]
                            var shown by remember(route.path) { mutableStateOf(current) }
                            if (current != null) shown = current
                            shown?.let { viewer ->
                                FilesViewerPane(
                                    viewer = viewer,
                                    wrapLines = state.wrapLines,
                                    leading =
                                        OpenCodeTopBarLeading.Back(
                                            onClick = {
                                                logFilesAction(ProductAnalytics.Action.FILES_VIEWER_BACK, viewer = true)
                                                closeViewer(viewer.path)
                                            },
                                        ),
                                    onCopyPath = { copyPath(viewer.path) },
                                    onToggleWrap = toggleWrap,
                                    onRetry = { retryFile(viewer.path) },
                                    onToggleMarkdownSource = { toggleMarkdownSource(viewer.path) },
                                )
                            }
                        }
                    },
            )
        }
        OpenCodeSnackbarHost(
            message = snackbar,
            onDismissed = { snackbar = null },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private fun logFilesAction(
    actionId: String,
    viewer: Boolean = false,
) {
    ProductAnalytics.logUserAction(
        screenId = if (viewer) ProductAnalytics.Screen.FILES_VIEWER else ProductAnalytics.Screen.FILES,
        actionId = actionId,
    )
}

private fun copyFilePathToClipboard(
    context: Context,
    path: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("File path", path))
}
