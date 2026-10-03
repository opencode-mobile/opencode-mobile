package dev.opencode.mobile.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.domain.time.RelativeTimeFormatting
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetContentMode
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeDialogTextField
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeSessionConnectionStatus
import dev.opencode.mobile.ui.components.OpenCodeSnackbarHost
import dev.opencode.mobile.ui.components.OpenCodeSnackbarMessage
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarSubtitleAccessory
import dev.opencode.mobile.ui.components.SessionList
import dev.opencode.mobile.ui.components.SessionListFilterTab
import dev.opencode.mobile.ui.components.SessionListProjectPickerRows
import dev.opencode.mobile.ui.components.SessionListSession
import dev.opencode.mobile.ui.components.SessionListStatus
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.connection.ConnectionOnboardingScreen
import dev.opencode.mobile.ui.connection.listCaption
import dev.opencode.mobile.ui.connection.listStatus
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.sessionchat.SessionConnectionStatus

/** Long-press session actions on home: sheet, rename/fork dialogs, delete confirm. */
data class HomeSessionActionsCallbacks(
    val onSessionLongPress: (SessionListSession) -> Unit = {},
    val onDismissSheet: () -> Unit = {},
    val onChooseRename: () -> Unit = {},
    val onRenameDraftChange: (String) -> Unit = {},
    val onCancelRename: () -> Unit = {},
    val onConfirmRename: () -> Unit = {},
    val onChooseFork: () -> Unit = {},
    val onForkDraftChange: (String) -> Unit = {},
    val onCancelFork: () -> Unit = {},
    val onConfirmFork: () -> Unit = {},
    val onForkOpen: () -> Unit = {},
    val onForkStay: () -> Unit = {},
    val onChooseArchive: () -> Unit = {},
    val onChooseDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onDismissDeleteConfirm: () -> Unit = {},
)

@Composable
fun MainScreen(
    onSettingsClick: () -> Unit,
    onTerminalClick: () -> Unit = {},
    onSessionClick: (SessionOpenTarget) -> Unit,
    onEditConnectionClick: (dev.opencode.mobile.domain.connection.ConnectionProfile?) -> Unit,
    onConnectClick: () -> Unit,
    onAddServerClick: () -> Unit = onConnectClick,
    onManageServersClick: () -> Unit = onSettingsClick,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val liveUpdatesConnectionStatus by viewModel.liveUpdatesConnectionStatus.collectAsStateWithLifecycle()
    val removalSnackbar by viewModel.removalSnackbar.collectAsStateWithLifecycle()
    val sessionActions by viewModel.sessionActions.collectAsStateWithLifecycle()
    val revealNewestToken by viewModel.revealNewestToken.collectAsStateWithLifecycle()
    val pendingSelectedFilterId by viewModel.pendingSelectedFilterId.collectAsStateWithLifecycle()
    val serversChrome by viewModel.serversChrome.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.onHomeRevealed()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.markHomeHidden()
    }
    MainScreen(
        uiState = state,
        isRefreshing = isRefreshing,
        liveUpdatesConnectionStatus = liveUpdatesConnectionStatus,
        removalSnackbar = removalSnackbar,
        sessionActions = sessionActions,
        revealNewestToken = revealNewestToken,
        pendingSelectedFilterId = pendingSelectedFilterId,
        onConsumePendingSelectedFilter = viewModel::consumePendingSelectedFilter,
        serversChrome = serversChrome,
        sessionActionsCallbacks =
            HomeSessionActionsCallbacks(
                onSessionLongPress = { session ->
                    viewModel.onSessionLongPress(
                        sessionId = session.id,
                        title = session.title,
                        worktree = session.worktree,
                    )
                },
                onDismissSheet = viewModel::dismissSessionActionsSheet,
                onChooseRename = viewModel::chooseRename,
                onRenameDraftChange = viewModel::updateRenameDraft,
                onCancelRename = viewModel::cancelRename,
                onConfirmRename = viewModel::confirmRename,
                onChooseFork = viewModel::chooseFork,
                onForkDraftChange = viewModel::updateForkDraft,
                onCancelFork = viewModel::cancelFork,
                onConfirmFork = viewModel::confirmFork,
                onForkOpen = viewModel::chooseForkOpen,
                onForkStay = viewModel::chooseForkStay,
                onChooseArchive = viewModel::chooseArchive,
                onChooseDelete = viewModel::chooseDelete,
                onConfirmDelete = viewModel::confirmDelete,
                onDismissDeleteConfirm = viewModel::dismissDeleteConfirm,
            ),
        onSettingsClick = onSettingsClick,
        onTerminalClick = onTerminalClick,
        onSessionClick = onSessionClick,
        onRetryProbe = viewModel::retryProbe,
        onEditConnection = { viewModel.requestEditConnection(onEditConnectionClick) },
        onConnectClick = onConnectClick,
        onRefresh = viewModel::pullToRefresh,
        onAddProjectClick = viewModel::showAddProjectSheet,
        onDismissAddProject = viewModel::dismissAddProjectSheet,
        onNavigateAddProjectUp = viewModel::navigateAddProjectUp,
        onNavigateAddProjectInto = viewModel::navigateAddProjectInto,
        onRetryAddProjectNavigator = viewModel::retryAddProjectNavigator,
        onSelectCurrentDirectory = viewModel::selectCurrentDirectory,
        onNewSession = viewModel::createSession,
        onConsumePendingOpenSession = viewModel::consumePendingOpenSession,
        onRemoveProject = viewModel::requestRemoveProject,
        onReorderProjects = viewModel::reorderProjects,
        onConfirmRemoveProject = viewModel::confirmRemoveProject,
        onDismissProjectRemoval = viewModel::dismissProjectRemoval,
        onConsumeProjectRemovalSnackbar = viewModel::consumeProjectRemovalSnackbar,
        onTitleClick = viewModel::showServersSheet,
        onDismissServersSheet = viewModel::dismissServersSheet,
        onSwitchProfile = viewModel::switchProfile,
        onAddServerClick = {
            viewModel.dismissServersSheet()
            onAddServerClick()
        },
        onManageServersClick = {
            viewModel.dismissServersSheet()
            onManageServersClick()
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreen(
    uiState: MainScreenUiState,
    liveUpdatesConnectionStatus: SessionConnectionStatus,
    removalSnackbar: OpenCodeSnackbarMessage?,
    onSettingsClick: () -> Unit,
    onTerminalClick: () -> Unit = {},
    onSessionClick: (SessionOpenTarget) -> Unit,
    onEditConnection: () -> Unit,
    onConnectClick: () -> Unit,
    onRetryProbe: () -> Unit,
    onRefresh: () -> Unit,
    onAddProjectClick: () -> Unit,
    onDismissAddProject: () -> Unit,
    onNavigateAddProjectUp: () -> Unit,
    onNavigateAddProjectInto: (String) -> Unit,
    onRetryAddProjectNavigator: () -> Unit,
    onSelectCurrentDirectory: () -> Unit,
    onNewSession: (String) -> Unit,
    onConsumePendingOpenSession: () -> Unit,
    onRemoveProject: (String) -> Unit,
    onConfirmRemoveProject: () -> Unit,
    onDismissProjectRemoval: () -> Unit,
    onConsumeProjectRemovalSnackbar: () -> Unit,
    modifier: Modifier = Modifier,
    onReorderProjects: (List<String>) -> Unit = {},
    isRefreshing: Boolean = false,
    sessionActions: HomeSessionActionsUiState = HomeSessionActionsUiState(),
    sessionActionsCallbacks: HomeSessionActionsCallbacks = HomeSessionActionsCallbacks(),
    revealNewestToken: Int = 0,
    pendingSelectedFilterId: String? = null,
    onConsumePendingSelectedFilter: () -> Unit = {},
    serversChrome: HomeServersChrome = HomeServersChrome(),
    onTitleClick: () -> Unit = {},
    onDismissServersSheet: () -> Unit = {},
    onSwitchProfile: (String) -> Unit = {},
    onAddServerClick: () -> Unit = {},
    onManageServersClick: () -> Unit = {},
) {
    val pendingOpenSession = (uiState as? MainScreenUiState.Loaded)?.pendingOpenSession
    LaunchedEffect(pendingOpenSession) {
        pendingOpenSession?.let { target ->
            onSessionClick(target)
            onConsumePendingOpenSession()
        }
    }
    val showAddSheet =
        when (uiState) {
            is MainScreenUiState.Empty -> uiState.showAddProjectSheet
            is MainScreenUiState.Loaded -> uiState.showAddProjectSheet
            else -> false
        }
    val addNavigator =
        when (uiState) {
            is MainScreenUiState.Empty -> uiState.addProjectNavigator
            is MainScreenUiState.Loaded -> uiState.addProjectNavigator
            else -> null
        }

    var showNewSessionProjectPicker by rememberSaveable { mutableStateOf(false) }
    var selectedFilterId by rememberSaveable { mutableStateOf(SessionListFilterTab.ALL_ID) }
    val loadedSections = (uiState as? MainScreenUiState.Loaded)?.sections.orEmpty()
    val chromeProjects = loadedSections.toHomeChromeProjects()
    val showTerminalAction =
        SessionListHomeChrome.terminalAction(chromeProjects) is SessionListHomeChrome.TerminalAction.OpenCatalog
    val newSessionPickerProjects =
        when (uiState) {
            is MainScreenUiState.Loaded -> uiState.sections
            else -> emptyList()
        }

    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.HOME_CONNECTION_EMPTY,
        presentation = ProductAnalytics.Presentation.EMBEDDED,
        visible = uiState is MainScreenUiState.Disconnected,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.HOME_PROBE_FAILED,
        presentation = ProductAnalytics.Presentation.EMBEDDED,
        visible = uiState is MainScreenUiState.ProbeFailed,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.HOME_ADD_PROJECT,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = showAddSheet,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.HOME_NEW_SESSION_PROJECT_PICKER,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = showNewSessionProjectPicker,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.HOME_REMOVE_PROJECT_CONFIRM,
        presentation = ProductAnalytics.Presentation.DIALOG,
        visible = (uiState as? MainScreenUiState.Loaded)?.pendingProjectRemoval != null,
    )

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OpenCodeTopBar(
                title = serversChrome.title,
                onTitleClick = if (serversChrome.isSwitcher) onTitleClick else null,
                titleMenuExpanded = serversChrome.sheetVisible,
                subtitleAccessory =
                    if (showsLiveUpdatesStatus(uiState)) {
                        OpenCodeTopBarSubtitleAccessory.StatusDot(
                            liveUpdatesConnectionStatus.toOpenCodeSessionConnectionStatus(),
                        )
                    } else {
                        null
                    },
                actions =
                    buildList {
                        if (showTerminalAction) {
                            add(
                                OpenCodeTopBarAction.Icon(
                                    imageVector = OpenCodeIcons.Terminal,
                                    contentDescription = "Terminal",
                                    onClick = {
                                        ProductAnalytics.logUserAction(
                                            screenId = ProductAnalytics.Screen.HOME,
                                            actionId = ProductAnalytics.Action.HOME_OPEN_TERMINAL,
                                        )
                                        onTerminalClick()
                                    },
                                ),
                            )
                        }
                        add(
                            OpenCodeTopBarAction.Icon(
                                imageVector = OpenCodeIcons.Settings,
                                contentDescription = "Settings",
                                onClick = {
                                    ProductAnalytics.logUserAction(
                                        screenId = ProductAnalytics.Screen.HOME,
                                        actionId = ProductAnalytics.Action.HOME_OPEN_SETTINGS,
                                    )
                                    onSettingsClick()
                                },
                            ),
                        )
                    },
            )

            when (uiState) {
                is MainScreenUiState.Disconnected -> {
                    Box(
                        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                        contentAlignment = Alignment.Center,
                    ) {
                        ConnectionOnboardingScreen(
                            onConnectClick = {
                                ProductAnalytics.logUserAction(
                                    screenId = ProductAnalytics.Screen.HOME_CONNECTION_EMPTY,
                                    actionId = ProductAnalytics.Action.HOME_CONNECT,
                                )
                                onConnectClick()
                            },
                        )
                    }
                }
                MainScreenUiState.Probing,
                MainScreenUiState.Loading,
                -> {
                    OpenCodeStatusStack(
                        title = "Loading sessions",
                        subtitle = "Checking server…",
                        testTag = "homeLoading",
                        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    )
                }
                is MainScreenUiState.ProbeFailed -> {
                    ProbeFailedContent(
                        message = uiState.message,
                        onRetry = onRetryProbe,
                        onEditConnection = onEditConnection,
                        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    )
                }
                is MainScreenUiState.Empty,
                is MainScreenUiState.Loaded,
                -> {
                    val sections =
                        when (uiState) {
                            is MainScreenUiState.Loaded -> uiState.sections
                            else -> emptyList()
                        }
                    val projectSessionCreateError =
                        when (uiState) {
                            is MainScreenUiState.Empty -> uiState.projectSessionCreateError
                            is MainScreenUiState.Loaded -> uiState.projectSessionCreateError
                            else -> null
                        }
                    HomeProjectsContent(
                        sections = sections,
                        projectSessionCreateError = projectSessionCreateError,
                        isRefreshing = isRefreshing,
                        onRefresh = onRefresh,
                        onBrowseProjectsClick = onAddProjectClick,
                        onSessionClick = { target ->
                            ProductAnalytics.logUserAction(
                                screenId = ProductAnalytics.Screen.HOME,
                                actionId = ProductAnalytics.Action.HOME_OPEN_SESSION,
                            )
                            onSessionClick(target)
                        },
                        onRequestNewSessionProjectPicker = {
                            ProductAnalytics.logUserAction(
                                screenId = ProductAnalytics.Screen.HOME,
                                actionId = ProductAnalytics.Action.HOME_OPEN_NEW_SESSION_PROJECT_PICKER,
                            )
                            showNewSessionProjectPicker = true
                        },
                        onCreateSession = onNewSession,
                        onRemoveProject = onRemoveProject,
                        onReorderProjects = onReorderProjects,
                        removalSnackbar = removalSnackbar,
                        onSessionLongPress = sessionActionsCallbacks.onSessionLongPress,
                        revealNewestToken = revealNewestToken,
                        pendingSelectedFilterId = pendingSelectedFilterId,
                        onConsumePendingSelectedFilter = onConsumePendingSelectedFilter,
                        selectedFilterId = selectedFilterId,
                        onSelectedFilterIdChange = { selectedFilterId = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        OpenCodeSnackbarHost(
            message = removalSnackbar,
            onDismissed = {
                onConsumeProjectRemovalSnackbar()
            },
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
        )
    }

    OpenCodeBottomSheet(
        visible = serversChrome.sheetVisible,
        title = "Servers",
        onDismissRequest = onDismissServersSheet,
    ) {
        val active = serversChrome.active
        if (active != null) {
            OpenCodeSectionHeader(title = "Using now")
            OpenCodeListItem(
                title = active.displayName,
                subtitle = active.baseUrl.trimEnd('/'),
                caption = active.listCaption(isActive = true),
                status = active.listStatus(isActive = true),
                style = OpenCodeListItemStyle.Card,
                startDecorator =
                    StartDecorator.Letter(
                        letterCharacterForProject(active.displayName),
                        letterAccentForWorktree(active.id.ifBlank { active.displayName }),
                    ),
                onClick = onDismissServersSheet,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (serversChrome.others.isNotEmpty()) {
            OpenCodeSectionHeader(title = "Switch to")
            serversChrome.others.forEach { profile ->
                OpenCodeListItem(
                    title = profile.displayName,
                    subtitle = ConnectionProfileIdentity.hostLabel(profile.baseUrl),
                    caption = profile.listCaption(isActive = false),
                    status = profile.listStatus(isActive = false),
                    startDecorator =
                        StartDecorator.Letter(
                            letterCharacterForProject(profile.displayName),
                            letterAccentForWorktree(profile.id.ifBlank { profile.displayName }),
                        ),
                    onClick = { onSwitchProfile(profile.id) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        OpenCodeListItem(
            title = "Add server",
            subtitle = "New Connection Profile",
            startDecorator = StartDecorator.Icon(OpenCodeIcons.Add, "Add server"),
            onClick = onAddServerClick,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        OpenCodeListItem(
            title = "Manage in Settings",
            onClick = onManageServersClick,
            modifier =
                Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
        )
    }

    val pendingProjectRemoval = (uiState as? MainScreenUiState.Loaded)?.pendingProjectRemoval
    OpenCodeDialog(
        visible = pendingProjectRemoval != null,
        title = "Remove from home?",
        subtitle =
            pendingProjectRemoval?.let { removal ->
                "Remove \"${removal.displayName}\" from home? Its sessions stay on the server."
            },
        primaryOption =
            OpenCodeDialogOption(
                title = "Remove",
                destructive = true,
                onClick = {
                    onConfirmRemoveProject()
                },
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = "Cancel",
                onClick = onDismissProjectRemoval,
            ),
        onDismissRequest = onDismissProjectRemoval,
    )

    HomeSessionActions(state = sessionActions, callbacks = sessionActionsCallbacks)

    if (showAddSheet) {
        val navigator = addNavigator ?: AddProjectNavigatorUi.empty()
        OpenCodeBottomSheet(
            visible = true,
            title = "Add project",
            onDismissRequest = {
                ProductAnalytics.logUserAction(
                    screenId = ProductAnalytics.Screen.HOME_ADD_PROJECT,
                    actionId = ProductAnalytics.Action.ADD_PROJECT_DISMISS,
                )
                onDismissAddProject()
            },
            alwaysFixedHeight = true,
            pinnedContent = {
                OpenCodeBottomSheetEntry(
                    title = "Go up",
                    enabled = navigator.canGoUp && !navigator.isLoading && !navigator.isValidating,
                    startDecorator =
                        BottomSheetEntryDecorator.Icon(
                            imageVector = OpenCodeIcons.ArrowUpward,
                            contentDescription = null,
                        ),
                    onClick = onNavigateAddProjectUp,
                )
                val currentTitle =
                    when {
                        navigator.currentPath.isBlank() -> "Current directory"
                        else -> navigator.currentPath
                    }
                OpenCodeBottomSheetEntry(
                    title = currentTitle,
                    enabled = navigator.currentPath.isNotBlank() && !navigator.isLoading && !navigator.isValidating,
                    startDecorator =
                        BottomSheetEntryDecorator.Icon(
                            imageVector = OpenCodeIcons.FolderCheck,
                            contentDescription = null,
                        ),
                    onClick = onSelectCurrentDirectory,
                )
            },
        ) {
            when {
                navigator.isLoading || navigator.isValidating -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                navigator.errorMessage != null -> {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = navigator.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        OpenCodeButton(
                            variant = OpenCodeButtonVariant.FullWidthText("Retry"),
                            onClick = onRetryAddProjectNavigator,
                        )
                    }
                }
                navigator.entries.isEmpty() -> {
                    OpenCodeBottomSheetEntry(title = "No subdirectories", enabled = false, onClick = {})
                }
                else -> {
                    navigator.entries.forEach { entry ->
                        OpenCodeBottomSheetEntry(
                            title = entry.name,
                            startDecorator =
                                BottomSheetEntryDecorator.Icon(
                                    imageVector = OpenCodeIcons.SubdirectoryArrowRight,
                                    contentDescription = null,
                                ),
                            onClick = { onNavigateAddProjectInto(entry.absolutePath) },
                        )
                    }
                }
            }
        }
    }

    if (showNewSessionProjectPicker) {
        OpenCodeBottomSheet(
            visible = true,
            title = "New session",
            onDismissRequest = {
                ProductAnalytics.logUserAction(
                    screenId = ProductAnalytics.Screen.HOME_NEW_SESSION_PROJECT_PICKER,
                    actionId = ProductAnalytics.Action.NEW_SESSION_PICKER_DISMISS,
                )
                showNewSessionProjectPicker = false
            },
        ) {
            if (newSessionPickerProjects.isEmpty()) {
                OpenCodeBottomSheetEntry(title = "No projects", enabled = false, onClick = {})
            } else {
                newSessionPickerProjects.forEach { project ->
                    OpenCodeBottomSheetEntry(
                        title = project.displayName,
                        startDecorator =
                            BottomSheetEntryDecorator.Icon(
                                imageVector = OpenCodeIcons.Folder,
                                contentDescription = null,
                            ),
                        onClick = {
                            ProductAnalytics.logUserAction(
                                screenId = ProductAnalytics.Screen.HOME_NEW_SESSION_PROJECT_PICKER,
                                actionId = ProductAnalytics.Action.NEW_SESSION_PICKER_SELECT_PROJECT,
                            )
                            showNewSessionProjectPicker = false
                            onNewSession(project.worktree)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSessionActions(
    state: HomeSessionActionsUiState,
    callbacks: HomeSessionActionsCallbacks,
) {
    val target = state.target ?: return

    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.HOME_SESSION_ACTIONS,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = state.sheetVisible,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.HOME_RENAME_SESSION,
        presentation = ProductAnalytics.Presentation.DIALOG,
        visible = state.renameVisible,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.HOME_FORK_SESSION,
        presentation = ProductAnalytics.Presentation.DIALOG,
        visible = state.forkVisible,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.HOME_FORK_RESULT,
        presentation = ProductAnalytics.Presentation.DIALOG,
        visible = state.forkResultVisible,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.HOME_DELETE_SESSION_CONFIRM,
        presentation = ProductAnalytics.Presentation.DIALOG,
        visible = state.deleteConfirmVisible,
    )

    OpenCodeBottomSheet(
        visible = state.sheetVisible,
        title = "Session actions",
        onDismissRequest = callbacks.onDismissSheet,
    ) {
        OpenCodeBottomSheetEntry(
            title = "Rename session",
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Edit, null),
            onClick = callbacks.onChooseRename,
        )
        if (state.canFork) {
            OpenCodeBottomSheetEntry(
                title = "Fork session",
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.ContentCopy, null),
                onClick = callbacks.onChooseFork,
            )
        }
        if (state.canArchive) {
            OpenCodeBottomSheetEntry(
                title = "Archive",
                enabled = !state.lifecycleInFlight,
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Archive, null),
                onClick = callbacks.onChooseArchive,
            )
        }
        OpenCodeBottomSheetEntry(
            title = "Delete",
            enabled = !state.lifecycleInFlight,
            destructive = true,
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Close, null),
            onClick = callbacks.onChooseDelete,
        )
    }

    OpenCodeDialog(
        visible = state.renameVisible,
        title = "Rename session",
        primaryOption =
            OpenCodeDialogOption(
                title = "Save",
                enabled = !state.renameSubmitting,
                onClick = callbacks.onConfirmRename,
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = "Cancel",
                enabled = !state.renameSubmitting,
                onClick = callbacks.onCancelRename,
            ),
        textField =
            OpenCodeDialogTextField(
                value = state.renameDraft,
                onValueChange = callbacks.onRenameDraftChange,
                hint = "Title",
                enabled = !state.renameSubmitting,
                errorMessage = state.renameError,
            ),
        onDismissRequest = {
            if (!state.renameSubmitting) {
                callbacks.onCancelRename()
            }
        },
    )

    OpenCodeDialog(
        visible = state.forkVisible,
        title = "Fork session",
        primaryOption =
            OpenCodeDialogOption(
                title = "Fork",
                enabled = !state.forkSubmitting,
                onClick = callbacks.onConfirmFork,
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = "Cancel",
                enabled = !state.forkSubmitting,
                onClick = callbacks.onCancelFork,
            ),
        textField =
            OpenCodeDialogTextField(
                value = state.forkDraft,
                onValueChange = callbacks.onForkDraftChange,
                hint = "Title",
                enabled = !state.forkSubmitting,
                errorMessage = state.forkError,
            ),
        onDismissRequest = {
            if (!state.forkSubmitting) {
                callbacks.onCancelFork()
            }
        },
    )

    OpenCodeDialog(
        visible = state.forkResultVisible,
        title = "Session forked",
        subtitle = "“${state.forkedTitle}” is ready.",
        primaryOption = OpenCodeDialogOption(title = "Open", onClick = callbacks.onForkOpen),
        secondaryOption = OpenCodeDialogOption(title = "Stay here", onClick = callbacks.onForkStay),
        onDismissRequest = callbacks.onForkStay,
    )

    OpenCodeDialog(
        visible = state.deleteConfirmVisible,
        title = "Delete session",
        subtitle = "Delete session “${target.title}”?",
        primaryOption =
            OpenCodeDialogOption(
                title = "Delete",
                destructive = true,
                enabled = !state.lifecycleInFlight,
                onClick = callbacks.onConfirmDelete,
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = "Cancel",
                enabled = !state.lifecycleInFlight,
                onClick = callbacks.onDismissDeleteConfirm,
            ),
        onDismissRequest = callbacks.onDismissDeleteConfirm,
    )
}

@Composable
private fun ProbeFailedContent(
    message: String,
    onRetry: () -> Unit,
    onEditConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Unable to connect", style = MaterialTheme.typography.titleMedium)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OpenCodeButton(
            variant = OpenCodeButtonVariant.FullWidthText("Retry"),
            onClick = onRetry,
        )
        OpenCodeButton(
            variant = OpenCodeButtonVariant.FullWidthText("Edit connection"),
            onClick = onEditConnection,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeProjectsContent(
    sections: List<ProjectSectionUi>,
    projectSessionCreateError: String?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onBrowseProjectsClick: () -> Unit,
    onSessionClick: (SessionOpenTarget) -> Unit,
    onRequestNewSessionProjectPicker: () -> Unit,
    onCreateSession: (String) -> Unit,
    onRemoveProject: (String) -> Unit,
    onReorderProjects: (List<String>) -> Unit,
    removalSnackbar: OpenCodeSnackbarMessage?,
    onSessionLongPress: (SessionListSession) -> Unit,
    revealNewestToken: Int = 0,
    pendingSelectedFilterId: String? = null,
    onConsumePendingSelectedFilter: () -> Unit = {},
    selectedFilterId: String,
    onSelectedFilterIdChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        val sessions = sections.toCrossProjectSessionListSessions()
        val chromeProjects = sections.toHomeChromeProjects()
        val filterTabs =
            SessionListHomeChrome.filterTabs(
                projects = chromeProjects,
                sessionCount = sessions.size,
            )
        var showProjectPicker by rememberSaveable { mutableStateOf(false) }
        val resolvedFilterId =
            SessionListHomeChrome.resolvedFilterId(
                selectedId = selectedFilterId,
                tabs = filterTabs,
            )
        LaunchedEffect(resolvedFilterId) {
            if (selectedFilterId != resolvedFilterId) {
                onSelectedFilterIdChange(resolvedFilterId)
            }
        }
        LaunchedEffect(pendingSelectedFilterId) {
            pendingSelectedFilterId?.let { filterId ->
                if (resolvedFilterId != SessionListFilterTab.ALL_ID) {
                    onSelectedFilterIdChange(filterId)
                }
                onConsumePendingSelectedFilter()
            }
        }
        LaunchedEffect(removalSnackbar) {
            if (removalSnackbar != null) {
                showProjectPicker = false
            }
        }
        ProductPresentedScreenViewEffect(
            screenId = ProductAnalytics.Screen.HOME_FILTER_PROJECT_PICKER,
            presentation = ProductAnalytics.Presentation.SHEET,
            visible = showProjectPicker,
        )
        SessionList(
            sessions = sessions,
            filterTabs = filterTabs,
            selectedFilterId = resolvedFilterId,
            createError = projectSessionCreateError,
            onOpenProjectPicker = {
                ProductAnalytics.logUserAction(
                    screenId = ProductAnalytics.Screen.HOME,
                    actionId = ProductAnalytics.Action.HOME_OPEN_PROJECT_PICKER,
                )
                showProjectPicker = true
            },
            onFilterSelected = { filterId ->
                ProductAnalytics.logUserAction(
                    screenId = ProductAnalytics.Screen.HOME,
                    actionId = ProductAnalytics.Action.HOME_SELECT_SESSION_FILTER,
                )
                onSelectedFilterIdChange(filterId)
            },
            onSessionSelected = { session ->
                ProductAnalytics.logUserAction(
                    screenId = ProductAnalytics.Screen.HOME,
                    actionId = ProductAnalytics.Action.HOME_OPEN_SESSION,
                )
                onSessionClick(
                    SessionOpenTarget(
                        sessionId = session.id,
                        title = session.title,
                        worktree = session.worktree,
                    ),
                )
            },
            onAddProject = onBrowseProjectsClick,
            onNewSession = {
                when (
                    val action =
                        SessionListHomeChrome.newSessionAction(
                            projects = chromeProjects,
                            selectedFilterId = resolvedFilterId,
                        )
                ) {
                    SessionListHomeChrome.NewSessionAction.None -> Unit
                    SessionListHomeChrome.NewSessionAction.PresentPicker ->
                        onRequestNewSessionProjectPicker()
                    is SessionListHomeChrome.NewSessionAction.Create ->
                        onCreateSession(action.worktree)
                }
            },
            onNewSessionLongPress =
                if (
                    SessionListHomeChrome.newSessionLongPressAction(
                        projects = chromeProjects,
                    ) is SessionListHomeChrome.NewSessionAction.PresentPicker
                ) {
                    onRequestNewSessionProjectPicker
                } else {
                    null
                },
            onSessionLongPress = onSessionLongPress,
            revealNewestToken = revealNewestToken,
            modifier = Modifier.fillMaxSize(),
            emptyContent =
                if (sections.isEmpty()) {
                    { NoProjectsEmptyState() }
                } else {
                    null
                },
        )
        OpenCodeBottomSheet(
            visible = showProjectPicker,
            title = "Filter by project",
            contentMode = OpenCodeBottomSheetContentMode.Form,
            onDismissRequest = {
                ProductAnalytics.logUserAction(
                    screenId = ProductAnalytics.Screen.HOME_FILTER_PROJECT_PICKER,
                    actionId = ProductAnalytics.Action.PROJECT_PICKER_DISMISS,
                )
                showProjectPicker = false
            },
        ) {
            SessionListProjectPickerRows(
                tabs = filterTabs,
                onSelect = { filterId ->
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.HOME_FILTER_PROJECT_PICKER,
                        actionId = ProductAnalytics.Action.HOME_SELECT_SESSION_FILTER,
                    )
                    onSelectedFilterIdChange(filterId)
                    showProjectPicker = false
                },
                onRemoveProject = onRemoveProject,
                onReorderProjects = onReorderProjects,
            )
        }
    }
}

private fun List<ProjectSectionUi>.toCrossProjectSessionListSessions(): List<SessionListSession> =
    flatMap { section ->
        section.allSessions.map { session ->
            section to session
        }
    }.sortedByDescending { (_, session) -> session.updatedAtEpochMs ?: 0L }
        .map { (section, session) ->
            SessionListSession(
                id = session.id,
                title = session.title,
                worktree = section.worktree,
                letter = section.letter,
                letterAccent = section.letterAccent,
                subtitle = section.pathSubtitle,
                status = session.toSessionListStatus(),
                caption = session.updatedAtEpochMs?.let(RelativeTimeFormatting::format).orEmpty(),
                updatedAtEpochMs = session.updatedAtEpochMs,
                retryAttempt = session.retryAttempt(),
                statusStale = session.statusStale,
            )
        }

private fun List<ProjectSectionUi>.toHomeChromeProjects(): List<SessionListHomeChrome.Project> =
    map { section ->
        SessionListHomeChrome.Project(
            worktree = section.worktree,
            title = section.displayName,
            sessionCount = section.allSessions.size,
        )
    }

@Suppress("UNUSED_PARAMETER")
private fun SessionSummary.toSessionListStatus(hasPendingPermission: Boolean = false): SessionListStatus =
    when {
        statusLabel == "Working" -> SessionListStatus.Running
        statusLabel?.startsWith("Retrying") == true -> SessionListStatus.Retrying
        else -> if (isReady) SessionListStatus.Ready else SessionListStatus.Idle
    }

private fun SessionSummary.retryAttempt(): Int? =
    statusLabel
        ?.removePrefix("Retrying · ")
        ?.takeIf { it != statusLabel }
        ?.toIntOrNull()

private fun showsLiveUpdatesStatus(uiState: MainScreenUiState): Boolean =
    when (uiState) {
        MainScreenUiState.Disconnected, MainScreenUiState.Probing, MainScreenUiState.Loading -> false
        else -> true
    }

private fun SessionConnectionStatus.toOpenCodeSessionConnectionStatus(): OpenCodeSessionConnectionStatus =
    when (this) {
        SessionConnectionStatus.Live -> OpenCodeSessionConnectionStatus.Live
        SessionConnectionStatus.Connecting -> OpenCodeSessionConnectionStatus.Connecting
        SessionConnectionStatus.Offline -> OpenCodeSessionConnectionStatus.Offline
        SessionConnectionStatus.AuthFailed -> OpenCodeSessionConnectionStatus.AuthFailed
    }

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    OpenCodeTheme(darkTheme = false) {
        MainScreen(
            uiState =
                MainScreenUiState.Loaded(
                    sections =
                        listOf(
                            ProjectSectionUi(
                                worktree = "/Users/demo/opencode-mobile",
                                displayName = "opencode-mobile",
                                pathSubtitle = "/Users/demo/opencode-mobile",
                                letter = 'O',
                                letterAccent = LetterAccent.Violet,
                                allSessions =
                                    listOf(
                                        SessionSummary(id = "1", title = "Importing icons explanation"),
                                        SessionSummary(id = "2", title = "Material Design Icons integration plan"),
                                    ),
                            ),
                        ),
                ),
            liveUpdatesConnectionStatus = SessionConnectionStatus.Live,
            isRefreshing = false,
            removalSnackbar = null,
            onSettingsClick = {},
            onSessionClick = {},
            onEditConnection = {},
            onConnectClick = {},
            onRetryProbe = {},
            onRefresh = {},
            onAddProjectClick = {},
            onDismissAddProject = {},
            onNavigateAddProjectUp = {},
            onNavigateAddProjectInto = {},
            onRetryAddProjectNavigator = {},
            onSelectCurrentDirectory = {},
            onNewSession = {},
            onConsumePendingOpenSession = {},
            onRemoveProject = {},
            onConfirmRemoveProject = {},
            onDismissProjectRemoval = {},
            onConsumeProjectRemovalSnackbar = {},
        )
    }
}
