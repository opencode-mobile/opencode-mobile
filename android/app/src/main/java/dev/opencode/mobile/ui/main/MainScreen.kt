package dev.opencode.mobile.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.backwardRouteTransition
import dev.opencode.mobile.forwardRouteTransition
import dev.opencode.mobile.popRouteUnlessRoot
import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import dev.opencode.mobile.domain.home.HomeCompactRowPath
import dev.opencode.mobile.domain.home.HomeSettleFeed
import dev.opencode.mobile.domain.home.HomeSettleGesture
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeProjectGrouping
import dev.opencode.mobile.domain.home.HomeProjectPickerState
import dev.opencode.mobile.domain.home.HomeScope
import dev.opencode.mobile.domain.home.HomeSessionLocation
import dev.opencode.mobile.domain.home.HomeWorktreeCopy
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.session.SessionIdleOutcome
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.domain.time.RelativeTimeFormatting
import dev.opencode.mobile.domain.worktree.WorktreeCreateCopy
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeaderTextAction
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetHeight
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetKeyboard
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeDialog
import dev.opencode.mobile.ui.components.OpenCodeDialogOption
import dev.opencode.mobile.ui.components.OpenCodeDialogTextField
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeEmptyStateAction
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeListItemStyle
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeSessionConnectionStatus
import dev.opencode.mobile.ui.components.OpenCodeSnackbarHost
import dev.opencode.mobile.ui.components.OpenCodeSnackbarMessage
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeTextInput
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.OpenCodeTopBarSubtitleAccessory
import dev.opencode.mobile.ui.components.SessionList
import dev.opencode.mobile.ui.components.SessionListFilterTab
import dev.opencode.mobile.ui.components.SessionListLocationPart
import dev.opencode.mobile.ui.components.SessionListProjectPickerRows
import dev.opencode.mobile.ui.components.SessionListProjectPickerTags
import dev.opencode.mobile.ui.components.SessionListScopeLoad
import dev.opencode.mobile.ui.components.SessionListSession
import dev.opencode.mobile.ui.components.SessionListStatus
import dev.opencode.mobile.ui.components.visibleForFilter
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.connection.ConnectionOnboardingScreen
import dev.opencode.mobile.ui.connection.listCaption
import dev.opencode.mobile.ui.connection.listStatus
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.keyboard.KeyboardShortcutAction
import dev.opencode.mobile.ui.keyboard.KeyboardShortcutHandler
import dev.opencode.mobile.ui.keyboard.KeyboardShortcutRules
import dev.opencode.mobile.ui.keyboard.SessionStepDirection
import dev.opencode.mobile.ui.sessionchat.SessionConnectionStatus
import dev.opencode.mobile.ui.worktree.NewWorktreeAvailability
import dev.opencode.mobile.ui.worktree.NewWorktreeRow
import dev.opencode.mobile.ui.worktree.WorktreeCreateFormActions
import dev.opencode.mobile.ui.worktree.WorktreeCreateFormBody
import dev.opencode.mobile.ui.worktree.WorktreeCreateFormCallbacks
import dev.opencode.mobile.ui.worktree.WorktreeCreateFormState
import dev.opencode.mobile.ui.worktree.WorktreeCreateOrigin
import dev.opencode.mobile.ui.worktree.WorktreeCreateTags
import dev.opencode.mobile.ui.worktree.worktreeCreateBackAction
import kotlinx.serialization.Serializable

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
    val onChooseUnarchive: () -> Unit = {},
    val onChooseUnsettle: () -> Unit = {},
    val onChooseSettle: () -> Unit = {},
    val onChooseWake: () -> Unit = {},
    val onChooseSnooze: () -> Unit = {},
    val onDismissSnooze: () -> Unit = {},
    val onSnoozeAmountChange: (String) -> Unit = {},
    val onCycleSnoozeUnit: () -> Unit = {},
    val onSnoozePreset: (Long) -> Unit = {},
    val onConfirmCustomSnooze: () -> Unit = {},
    val onSettleGesture: (SessionListSession, HomeSettleGesture) -> Unit = { _, _ -> },
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
    selectedSessionId: String? = null,
    sessionKeyboardNavigationEnabled: Boolean = false,
    viewModel: MainScreenViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val liveUpdatesConnectionStatus by viewModel.liveUpdatesConnectionStatus.collectAsStateWithLifecycle()
    val removalSnackbar by viewModel.removalSnackbar.collectAsStateWithLifecycle()
    val sessionActions by viewModel.sessionActions.collectAsStateWithLifecycle()
    val revealNewestToken by viewModel.revealNewestToken.collectAsStateWithLifecycle()
    val pendingScopeRequest by viewModel.pendingScopeRequest.collectAsStateWithLifecycle()
    val serversChrome by viewModel.serversChrome.collectAsStateWithLifecycle()
    val worktreeCreateForm by viewModel.worktreeCreateForm.collectAsStateWithLifecycle()
    val worktreeServerCanCreate by viewModel.worktreeServerCanCreate.collectAsStateWithLifecycle()
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
        pendingScopeRequest = pendingScopeRequest,
        onConsumePendingScopeRequest = viewModel::consumePendingScopeRequest,
        onRetryProjectLoad = viewModel::retryProjectLoad,
        serversChrome = serversChrome,
        sessionActionsCallbacks =
            HomeSessionActionsCallbacks(
                onSessionLongPress = { session ->
                    viewModel.onSessionLongPress(
                        sessionId = session.id,
                        title = session.title,
                        worktree = session.directory,
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
                onChooseUnarchive = viewModel::chooseUnarchive,
                onChooseUnsettle = viewModel::chooseUnsettle,
                onChooseSettle = viewModel::chooseSettle,
                onChooseWake = viewModel::chooseWake,
                onChooseSnooze = viewModel::openSnooze,
                onDismissSnooze = viewModel::dismissSnooze,
                onSnoozeAmountChange = viewModel::updateSnoozeAmount,
                onCycleSnoozeUnit = viewModel::cycleSnoozeUnit,
                onSnoozePreset = viewModel::chooseSnoozePreset,
                onConfirmCustomSnooze = viewModel::confirmCustomSnooze,
                onSettleGesture = { session, gesture ->
                    viewModel.onSettleGesture(session.id, session.title, session.directory, gesture)
                },
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
        addProjectCallbacks =
            AddProjectSheetCallbacks(
                onFieldChange = viewModel::updateAddProjectField,
                onUp = viewModel::addProjectUp,
                onSelectRoot = viewModel::selectAddProjectRoot,
                onOpenFolder = viewModel::openAddProjectFolder,
                onSelectSuggestion = viewModel::selectAddProjectSuggestion,
                onRetry = viewModel::retryAddProjectListing,
                onConfirm = viewModel::confirmAddProject,
                onBrowse = viewModel::browseAddProjectFolders,
                onCloseBrowser = viewModel::closeAddProjectBrowser,
                onSubmitField = viewModel::submitAddProjectField,
                onFolderRowAppeared = viewModel::addProjectFolderRowAppeared,
                onFolderRowDisappeared = viewModel::addProjectFolderRowDisappeared,
            ),
        onNewSession = viewModel::createSession,
        worktreeCreateForm = worktreeCreateForm,
        worktreeServerCanCreate = worktreeServerCanCreate,
        onOpenRunInNewWorktree = viewModel::openRunInNewWorktree,
        worktreeCreateCallbacks =
            WorktreeCreateFormCallbacks(
                onNameChange = viewModel::updateNewWorktreeName,
                onShowBasePicker = viewModel::showNewWorktreeBasePicker,
                onHideBasePicker = viewModel::hideNewWorktreeBasePicker,
                onSelectBase = viewModel::selectNewWorktreeBase,
                onSubmit = viewModel::submitNewWorktree,
                onBack = viewModel::closeNewWorktreeForm,
            ),
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
        showsHomeSettle = viewModel.showsHomeSettle(),
        showsTerminal = viewModel.showsTerminal(),
        modifier = modifier,
        selectedSessionId = selectedSessionId,
        sessionKeyboardNavigationEnabled = sessionKeyboardNavigationEnabled,
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
    onNewSession: (String) -> Unit,
    onConsumePendingOpenSession: () -> Unit,
    onRemoveProject: (String) -> Unit,
    onConfirmRemoveProject: () -> Unit,
    onDismissProjectRemoval: () -> Unit,
    onConsumeProjectRemovalSnackbar: () -> Unit,
    modifier: Modifier = Modifier,
    onReorderProjects: (List<String>) -> Unit = {},
    addProjectCallbacks: AddProjectSheetCallbacks = AddProjectSheetCallbacks(),
    isRefreshing: Boolean = false,
    sessionActions: HomeSessionActionsUiState = HomeSessionActionsUiState(),
    sessionActionsCallbacks: HomeSessionActionsCallbacks = HomeSessionActionsCallbacks(),
    revealNewestToken: Int = 0,
    pendingScopeRequest: HomeScopeRequest? = null,
    onConsumePendingScopeRequest: () -> Unit = {},
    onRetryProjectLoad: (String) -> Unit = {},
    serversChrome: HomeServersChrome = HomeServersChrome(),
    onTitleClick: () -> Unit = {},
    onDismissServersSheet: () -> Unit = {},
    onSwitchProfile: (String) -> Unit = {},
    onAddServerClick: () -> Unit = {},
    onManageServersClick: () -> Unit = {},
    selectedSessionId: String? = null,
    sessionKeyboardNavigationEnabled: Boolean = false,
    worktreeCreateForm: WorktreeCreateFormState? = null,
    worktreeServerCanCreate: Boolean = true,
    onOpenRunInNewWorktree: (String) -> Unit = {},
    worktreeCreateCallbacks: WorktreeCreateFormCallbacks = WorktreeCreateFormCallbacks(),
    showsHomeSettle: Boolean = false,
    showsTerminal: Boolean = true,
) {
    var newSessionSheetMode by rememberSaveable(stateSaver = NewSessionSheetModeSaver) {
        mutableStateOf<SessionListHomeChrome.NewSessionSheetMode?>(null)
    }
    val pendingOpenSession = (uiState as? MainScreenUiState.Loaded)?.pendingOpenSession
    LaunchedEffect(pendingOpenSession) {
        pendingOpenSession?.let { target ->
            // A session made from the New worktree form closes the New session sheet too.
            newSessionSheetMode = null
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
    val addProjectSheet =
        when (uiState) {
            is MainScreenUiState.Empty -> uiState.addProjectSheet
            is MainScreenUiState.Loaded -> uiState.addProjectSheet
            else -> null
        }

    var selectedFilterId by rememberSaveable { mutableStateOf(SessionListFilterTab.ALL_ID) }
    val worktreesEnabled = HomeDisplayPreferences.showsWorktrees
    val loaded = uiState as? MainScreenUiState.Loaded
    val nowEpochMs = System.currentTimeMillis()
    val homeSessions =
        loaded?.homeSessions(worktreesEnabled, showsSettle = showsHomeSettle, nowEpochMs = nowEpochMs).orEmpty()
    val settledSessions =
        loaded?.homeSettledSessions(worktreesEnabled, showsSettle = showsHomeSettle, nowEpochMs = nowEpochMs).orEmpty()
    val chromeProjects =
        remember(loaded, homeSessions) {
            loaded?.let { state -> homeChromeProjects(state.projects, homeSessions, state.projectLoads) }.orEmpty()
        }
    val showTerminalAction =
        SessionListHomeChrome.terminalAction(chromeProjects, showsTerminal = showsTerminal) is
            SessionListHomeChrome.TerminalAction.OpenCatalog
    val startNewSession: (SessionListHomeChrome.NewSessionAction) -> Unit = { action ->
        when (action) {
            SessionListHomeChrome.NewSessionAction.None -> Unit
            is SessionListHomeChrome.NewSessionAction.PresentSheet -> {
                if (newSessionSheetMode == null) {
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.HOME,
                        actionId = ProductAnalytics.Action.HOME_OPEN_NEW_SESSION_PROJECT_PICKER,
                    )
                }
                newSessionSheetMode = action.mode
            }
            is SessionListHomeChrome.NewSessionAction.Create -> {
                newSessionSheetMode = null
                onNewSession(action.worktree)
            }
        }
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
        visible = newSessionSheetMode != null,
    )
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.HOME_REMOVE_PROJECT_CONFIRM,
        presentation = ProductAnalytics.Presentation.DIALOG,
        visible = (uiState as? MainScreenUiState.Loaded)?.pendingProjectRemoval != null,
    )

    val columnStack = rememberNavBackStack(HomeColumnFeed)
    LaunchedEffect(showsHomeSettle) {
        if (!showsHomeSettle) {
            while (columnStack.lastOrNull() is SettledSessionsRoute) {
                columnStack.popRouteUnlessRoot()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        NavDisplay(
            modifier = Modifier.fillMaxSize(),
            backStack = columnStack,
            onBack = { columnStack.popRouteUnlessRoot() },
            transitionSpec = { forwardRouteTransition() },
            popTransitionSpec = { backwardRouteTransition() },
            predictivePopTransitionSpec = { backwardRouteTransition() },
            entryProvider =
                entryProvider {
                    entry<HomeColumnFeed> {
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
                        sessions = homeSessions,
                        chromeProjects = chromeProjects,
                        onRetryProjectLoad = onRetryProjectLoad,
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
                        onNewSessionAction = startNewSession,
                        worktreesEnabled = worktreesEnabled,
                        onRemoveProject = onRemoveProject,
                        onReorderProjects = onReorderProjects,
                        removalSnackbar = removalSnackbar,
                        onSessionLongPress = sessionActionsCallbacks.onSessionLongPress,
                        projects = loaded?.projects.orEmpty(),
                        revealNewestToken = revealNewestToken,
                        pendingScopeRequest = pendingScopeRequest,
                        onConsumePendingScopeRequest = onConsumePendingScopeRequest,
                        selectedFilterId = selectedFilterId,
                        onSelectedFilterIdChange = { selectedFilterId = it },
                        selectedSessionId = selectedSessionId,
                        sessionKeyboardNavigationEnabled = sessionKeyboardNavigationEnabled,
                        showsSettle = showsHomeSettle,
                        settledSessions = settledSessions,
                        onOpenSettled = {
                            if (columnStack.lastOrNull() != SettledSessionsRoute) {
                                columnStack.add(SettledSessionsRoute)
                            }
                        },
                        onSettleGesture = sessionActionsCallbacks.onSettleGesture,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
                    }
                    entry<SettledSessionsRoute> {
                        SettledSessionsColumn(
                            sessions = settledSessions,
                            selectedFilterId = selectedFilterId,
                            isRefreshing = isRefreshing,
                            onRefresh = onRefresh,
                            onBack = { columnStack.popRouteUnlessRoot() },
                            onSessionClick = onSessionClick,
                            onSessionLongPress = sessionActionsCallbacks.onSessionLongPress,
                            onSettleGesture = sessionActionsCallbacks.onSettleGesture,
                        )
                    }
                },
        )

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
        pinnedBottom = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.FullWidthText("Add server"),
                    onClick = onAddServerClick,
                )
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.FullWidthText("Manage in Settings"),
                    contrast = OpenCodeButtonContrast.Low,
                    onClick = onManageServersClick,
                )
            }
        },
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

    AddProjectSheet(
        visible = showAddSheet,
        sheet = addProjectSheet ?: AddProjectSheetUi.empty(),
        callbacks = addProjectCallbacks,
        onDismiss = onDismissAddProject,
    )

    // One New session sheet: the project list, "Run in" for a project, or the New worktree form
    // in place of Run in. Keeps the shown content while the sheet plays its exit motion.
    var heldSheetMode by remember { mutableStateOf(newSessionSheetMode) }
    newSessionSheetMode?.let { heldSheetMode = it }
    val pickProject: (SessionListHomeChrome.Project, Boolean) -> Unit = { project, holdPick ->
        ProductAnalytics.logUserAction(
            screenId = ProductAnalytics.Screen.HOME_NEW_SESSION_PROJECT_PICKER,
            actionId = ProductAnalytics.Action.NEW_SESSION_PICKER_SELECT_PROJECT,
        )
        // A hold-pick with Worktrees on stays open and asks where to run.
        startNewSession(SessionListHomeChrome.projectPickAction(project, holdPick, worktreesEnabled))
    }
    val heldRunInKey = (heldSheetMode as? SessionListHomeChrome.NewSessionSheetMode.RunIn)?.projectId
    val runInForm =
        worktreeCreateForm?.takeIf { form ->
            heldRunInKey != null && (form.origin as? WorktreeCreateOrigin.RunIn)?.projectKey == heldRunInKey
        }
    OpenCodeBottomSheet(
        visible = newSessionSheetMode != null,
        title = if (runInForm != null) WorktreeCreateCopy.FORM_TITLE else "New session",
        onDismissRequest = {
            ProductAnalytics.logUserAction(
                screenId = ProductAnalytics.Screen.HOME_NEW_SESSION_PROJECT_PICKER,
                actionId = ProductAnalytics.Action.NEW_SESSION_PICKER_DISMISS,
            )
            newSessionSheetMode = null
            worktreeCreateCallbacks.onBack()
        },
        headerTrailingAction = runInForm?.let { worktreeCreateBackAction(it, worktreeCreateCallbacks) },
        keyboard =
            if (runInForm != null && !runInForm.basePickerVisible) {
                OpenCodeBottomSheetKeyboard.Keep
            } else {
                OpenCodeBottomSheetKeyboard.Dismiss
            },
        pinnedBottom = if (runInForm != null) ({ WorktreeCreateFormActions(runInForm, worktreeCreateCallbacks) }) else null,
    ) {
        val mode = heldSheetMode
        val runIn =
            (mode as? SessionListHomeChrome.NewSessionSheetMode.RunIn)?.let { runInMode ->
                chromeProjects.firstOrNull { it.id == runInMode.projectId }
            }
        if (runInForm != null) {
            WorktreeCreateFormBody(runInForm, worktreeCreateCallbacks)
        } else if (mode is SessionListHomeChrome.NewSessionSheetMode.RunIn && runIn != null) {
            OpenCodeSectionHeader(title = HomeWorktreeCopy.RUN_IN)
            runIn.worktrees.forEach { worktree ->
                OpenCodeListItem(
                    title = worktree.title,
                    subtitle = worktree.subtitle,
                    caption = worktree.runningCount.takeIf { it > 0 }?.let(HomeWorktreeCopy::runningCount),
                    startDecorator = StartDecorator.Icon(OpenCodeIcons.CallSplit, null),
                    onClick = {
                        ProductAnalytics.logUserAction(
                            screenId = ProductAnalytics.Screen.HOME_NEW_SESSION_PROJECT_PICKER,
                            actionId = ProductAnalytics.Action.NEW_SESSION_PICKER_SELECT_PROJECT,
                        )
                        startNewSession(SessionListHomeChrome.NewSessionAction.Create(worktree.directory))
                    },
                    modifier = Modifier.testTag(HomeNewSessionSheetTags.runInRow(worktree.title)),
                )
            }
            NewWorktreeRow(
                availability =
                    NewWorktreeAvailability.runIn(
                        project = loaded?.projects?.firstOrNull { it.key == runIn.id },
                        worktreesEnabled = worktreesEnabled,
                        serverCanCreate = worktreeServerCanCreate,
                    ),
                testTag = WorktreeCreateTags.RUN_IN_ROW,
                onClick = { onOpenRunInNewWorktree(runIn.id) },
            )
            val others = chromeProjects.filter { it.id != runIn.id }
            if (mode.showsOtherProjects && others.isNotEmpty()) {
                OpenCodeSectionHeader(title = HomeWorktreeCopy.OTHER_PROJECTS)
                others.forEach { project ->
                    OpenCodeBottomSheetEntry(
                        title = project.title,
                        startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Folder),
                        onClick = { pickProject(project, true) },
                    )
                }
            }
        } else if (chromeProjects.isEmpty()) {
            OpenCodeBottomSheetEntry(title = "No projects", enabled = false, onClick = {})
        } else {
            val holdPick = (mode as? SessionListHomeChrome.NewSessionSheetMode.Projects)?.holdPick ?: true
            chromeProjects.forEach { project ->
                OpenCodeBottomSheetEntry(
                    title = project.title,
                    startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Folder),
                    onClick = { pickProject(project, holdPick) },
                )
            }
        }
    }
}

/** Test tags of the New session sheet. */
internal object HomeNewSessionSheetTags {
    fun runInRow(title: String): String = "newSessionRunInRow:$title"
}

/** Saves the New session sheet mode across configuration changes. */
private val NewSessionSheetModeSaver: Saver<SessionListHomeChrome.NewSessionSheetMode?, String> =
    Saver(
        save = { mode ->
            when (mode) {
                null -> ""
                is SessionListHomeChrome.NewSessionSheetMode.Projects -> "p:${mode.holdPick}"
                is SessionListHomeChrome.NewSessionSheetMode.RunIn -> "r:${mode.showsOtherProjects}:${mode.projectId}"
            }
        },
        restore = { saved ->
            when {
                saved.startsWith("p:") -> SessionListHomeChrome.NewSessionSheetMode.Projects(holdPick = saved == "p:true")
                saved.startsWith("r:") -> {
                    val body = saved.removePrefix("r:")
                    SessionListHomeChrome.NewSessionSheetMode.RunIn(
                        projectId = body.substringAfter(':'),
                        showsOtherProjects = body.substringBefore(':') == "true",
                    )
                }
                else -> null
            }
        },
    )

@Composable
private fun HomeSessionActions(
    state: HomeSessionActionsUiState,
    callbacks: HomeSessionActionsCallbacks,
) {
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
        when (state.lifecycle) {
            HomeSessionLifecycle.Archived ->
                OpenCodeBottomSheetEntry(
                    title = "Unarchive",
                    enabled = !state.lifecycleInFlight,
                    startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Archive),
                    onClick = callbacks.onChooseUnarchive,
                )
            HomeSessionLifecycle.Settled ->
                OpenCodeBottomSheetEntry(
                    title = "Unsettle",
                    enabled = !state.lifecycleInFlight,
                    startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check),
                    onClick = callbacks.onChooseUnsettle,
                )
            HomeSessionLifecycle.Snoozed -> {
                OpenCodeBottomSheetEntry(
                    title = "Wake",
                    enabled = !state.lifecycleInFlight,
                    startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check),
                    onClick = callbacks.onChooseWake,
                )
                OpenCodeBottomSheetEntry(
                    title = "Rename session",
                    startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Edit),
                    onClick = callbacks.onChooseRename,
                )
            }
            HomeSessionLifecycle.Active -> {
                if (state.showsSettle) {
                    OpenCodeBottomSheetEntry(
                        title =
                            if (state.snoozeBlockReason != null) {
                                "Settle · ${state.snoozeBlockReason}"
                            } else {
                                "Settle"
                            },
                        enabled = state.snoozeBlockReason == null && !state.lifecycleInFlight,
                        startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check),
                        onClick = callbacks.onChooseSettle,
                    )
                    OpenCodeBottomSheetEntry(
                        title =
                            if (state.snoozeBlockReason != null) {
                                "Snooze · ${state.snoozeBlockReason}"
                            } else {
                                "Snooze"
                            },
                        enabled = state.snoozeBlockReason == null && !state.lifecycleInFlight,
                        startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Check),
                        onClick = callbacks.onChooseSnooze,
                    )
                }
                OpenCodeBottomSheetEntry(
                    title = "Rename session",
                    startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Edit),
                    onClick = callbacks.onChooseRename,
                )
                OpenCodeBottomSheetEntry(
                    title = "Fork session",
                    startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.ContentCopy),
                    onClick = callbacks.onChooseFork,
                )
                if (state.canArchive) {
                    OpenCodeBottomSheetEntry(
                        title = "Archive",
                        enabled = !state.lifecycleInFlight,
                        startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Archive),
                        onClick = callbacks.onChooseArchive,
                    )
                }
            }
        }
        OpenCodeBottomSheetEntry(
            title = "Delete",
            enabled = !state.lifecycleInFlight,
            destructive = true,
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Close),
            onClick = callbacks.onChooseDelete,
        )
    }

    OpenCodeBottomSheet(
        visible = state.snoozeVisible,
        title = "Snooze until",
        onDismissRequest = callbacks.onDismissSnooze,
    ) {
        HomeSettleFeed.snoozePresets(System.currentTimeMillis()).forEach { preset ->
            OpenCodeBottomSheetEntry(
                title = "${preset.label} · ${preset.whenLabel}",
                onClick = { callbacks.onSnoozePreset(preset.atEpochMs) },
            )
        }
        OpenCodeTextInput(
            value = state.snoozeAmount,
            onValueChange = callbacks.onSnoozeAmountChange,
            hint = "Amount",
            accessibilityLabel = "Snooze amount",
            errorMessage = state.snoozeError,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        OpenCodeBottomSheetEntry(
            title = state.snoozeUnit.label,
            onClick = callbacks.onCycleSnoozeUnit,
        )
        OpenCodeButton(
            variant = OpenCodeButtonVariant.CompactText("Snooze"),
            onClick = callbacks.onConfirmCustomSnooze,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }

    // The sheet stays composed without a target so it can play its exit motion.
    val target = state.target ?: return

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
                enabled = !state.forkSubmitting && state.forkBlockedMessage == null,
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
                errorMessage = state.forkError ?: state.forkBlockedMessage,
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

@Serializable
private data object HomeColumnFeed : NavKey

@Serializable
private data object SettledSessionsRoute : NavKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettledSessionsColumn(
    sessions: List<SessionListSession>,
    selectedFilterId: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onSessionClick: (SessionOpenTarget) -> Unit,
    onSessionLongPress: (SessionListSession) -> Unit,
    onSettleGesture: (SessionListSession, HomeSettleGesture) -> Unit,
) {
    val visible = sessions.visibleForFilter(selectedFilterId)
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        OpenCodeTopBar(
            title = "Settled",
            subtitle = if (visible.size == 1) "1 thread" else "${visible.size} threads",
            leading = OpenCodeTopBarLeading.Back(onClick = onBack),
        )
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        ) {
            SettledSessionsPage(
                sessions = visible,
                onSessionSelected = { session ->
                    ProductAnalytics.logUserAction(
                        screenId = ProductAnalytics.Screen.HOME,
                        actionId = ProductAnalytics.Action.HOME_OPEN_SESSION,
                    )
                    onSessionClick(
                        SessionOpenTarget(
                            sessionId = session.id,
                            title = session.title,
                            worktree = session.directory,
                        ),
                    )
                },
                onSessionLongPress = onSessionLongPress,
                onSettleGesture = onSettleGesture,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ProbeFailedContent(
    message: String,
    onRetry: () -> Unit,
    onEditConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpenCodeEmptyState(
        title = "Unable to connect",
        message = message.ifBlank { "Unable to connect." },
        primaryAction = OpenCodeEmptyStateAction(title = "Retry", onClick = onRetry),
        secondaryAction = OpenCodeEmptyStateAction(title = "Edit connection", onClick = onEditConnection),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeProjectsContent(
    sections: List<ProjectSectionUi>,
    sessions: List<SessionListSession>,
    chromeProjects: List<SessionListHomeChrome.Project>,
    onRetryProjectLoad: (String) -> Unit,
    projectSessionCreateError: String?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onBrowseProjectsClick: () -> Unit,
    onSessionClick: (SessionOpenTarget) -> Unit,
    onNewSessionAction: (SessionListHomeChrome.NewSessionAction) -> Unit,
    onRemoveProject: (String) -> Unit,
    onReorderProjects: (List<String>) -> Unit,
    removalSnackbar: OpenCodeSnackbarMessage?,
    onSessionLongPress: (SessionListSession) -> Unit,
    projects: List<HomeProject> = emptyList(),
    revealNewestToken: Int = 0,
    pendingScopeRequest: HomeScopeRequest? = null,
    onConsumePendingScopeRequest: () -> Unit = {},
    selectedFilterId: String,
    onSelectedFilterIdChange: (String) -> Unit,
    selectedSessionId: String? = null,
    sessionKeyboardNavigationEnabled: Boolean = false,
    worktreesEnabled: Boolean = true,
    showsSettle: Boolean = false,
    settledSessions: List<SessionListSession> = emptyList(),
    onOpenSettled: () -> Unit = {},
    onSettleGesture: (SessionListSession, HomeSettleGesture) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    // Rows exactly as the list renders them for the selected filter; read by Ctrl+Tab stepping.
    val displayedSessions = remember { DisplayedSessions() }
    val openSession: (SessionListSession) -> Unit = { session ->
        ProductAnalytics.logUserAction(
            screenId = ProductAnalytics.Screen.HOME,
            actionId = ProductAnalytics.Action.HOME_OPEN_SESSION,
        )
        onSessionClick(
            SessionOpenTarget(
                sessionId = session.id,
                title = session.title,
                worktree = session.directory,
            ),
        )
    }

    fun stepSession(direction: SessionStepDirection) {
        val sessions = displayedSessions.value
        val targetId =
            KeyboardShortcutRules.adjacentSessionId(
                orderedIds = sessions.map { it.id },
                selectedId = selectedSessionId,
                direction = direction,
            ) ?: return
        sessions.firstOrNull { it.id == targetId }?.let(openSession)
    }
    KeyboardShortcutHandler(KeyboardShortcutAction.NextSession, enabled = sessionKeyboardNavigationEnabled) {
        stepSession(SessionStepDirection.Next)
    }
    KeyboardShortcutHandler(KeyboardShortcutAction.PreviousSession, enabled = sessionKeyboardNavigationEnabled) {
        stepSession(SessionStepDirection.Previous)
    }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        val filterTabs =
            SessionListHomeChrome.filterTabs(
                projects = chromeProjects,
                sessionCount = sessions.size,
                worktreesEnabled = worktreesEnabled,
            )
        val worktreeTabs = SessionListHomeChrome.worktreeTabs(chromeProjects, worktreesEnabled)
        var showProjectPicker by rememberSaveable { mutableStateOf(false) }
        var pickerState by remember { mutableStateOf(HomeProjectPickerState()) }
        // An older scope id (an added path) reads as the project that path now belongs to, and
        // so does a worktree scope while worktrees are off.
        val resolvedFilterId =
            SessionListHomeChrome.resolvedFilterId(
                selectedId = homeScopeId(selectedFilterId, projects, worktreesEnabled),
                tabs = filterTabs,
                worktreeTabs = worktreeTabs,
            )
        LaunchedEffect(resolvedFilterId) {
            if (selectedFilterId != resolvedFilterId) {
                onSelectedFilterIdChange(resolvedFilterId)
            }
        }
        LaunchedEffect(pendingScopeRequest) {
            pendingScopeRequest?.let { request ->
                if (request.evenFromAll || resolvedFilterId != SessionListFilterTab.ALL_ID) {
                    onSelectedFilterIdChange(homeScopeId(request.filterId, projects, worktreesEnabled))
                }
                onConsumePendingScopeRequest()
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
                // Fresh each time: a worktree scope opens with its project expanded.
                pickerState =
                    HomeProjectPickerState.initial(
                        selectedScope = HomeScope.resolve(resolvedFilterId, projects, worktreesEnabled),
                        projects = projects,
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
            onSessionSelected = openSession,
            onDisplayedSessionsChange = { displayedSessions.value = it },
            onAddProject = onBrowseProjectsClick,
            onNewSession = {
                onNewSessionAction(
                    SessionListHomeChrome.newSessionAction(
                        projects = chromeProjects,
                        selectedFilterId = resolvedFilterId,
                        worktreesEnabled = worktreesEnabled,
                    ),
                )
            },
            onNewSessionLongPress =
                SessionListHomeChrome
                    .newSessionLongPressAction(
                        projects = chromeProjects,
                        selectedFilterId = resolvedFilterId,
                        worktreesEnabled = worktreesEnabled,
                    ).takeIf { it != SessionListHomeChrome.NewSessionAction.None }
                    ?.let { action -> { onNewSessionAction(action) } },
            onSessionLongPress = onSessionLongPress,
            settledSessions = if (showsSettle) settledSessions else emptyList(),
            onOpenSettled = if (showsSettle) onOpenSettled else null,
            onSettleGesture = if (showsSettle) onSettleGesture else null,
            revealNewestToken = revealNewestToken,
            selectedSessionId = selectedSessionId,
            onRetryScopeLoad = onRetryProjectLoad,
            scopeTabs = worktreeTabs,
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
            // Half + Keep keeps today's fixed height and keyboard policy; Dismiss would be more
            // natural for this list (#680).
            height = OpenCodeBottomSheetHeight.Half,
            keyboard = OpenCodeBottomSheetKeyboard.Keep,
            headerTrailingAction =
                OpenCodeBottomSheetHeaderTextAction(
                    title = if (pickerState.isEditing) HomeWorktreeCopy.DONE else HomeWorktreeCopy.EDIT,
                    // Reordering needs two projects; the control stays visible either way.
                    enabled = chromeProjects.size >= 2 || pickerState.isEditing,
                    onClick = { pickerState = pickerState.setEditing(!pickerState.isEditing) },
                    testTag = SessionListProjectPickerTags.EDIT,
                ),
            onDismissRequest = {
                ProductAnalytics.logUserAction(
                    screenId = ProductAnalytics.Screen.HOME_FILTER_PROJECT_PICKER,
                    actionId = ProductAnalytics.Action.PROJECT_PICKER_DISMISS,
                )
                showProjectPicker = false
            },
        ) {
            SessionListProjectPickerRows(
                rows =
                    SessionListHomeChrome.pickerRows(
                        projects = chromeProjects,
                        sessionCount = sessions.size,
                        state = pickerState,
                        worktreesEnabled = worktreesEnabled,
                    ),
                isEditing = pickerState.isEditing,
                onToggleWorktrees = { projectId -> pickerState = pickerState.toggleExpanded(projectId) },
                onToggleShowAll = { projectId -> pickerState = pickerState.toggleShowAll(projectId) },
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

/** Plain holder: the list reports its rows on every composition, and only key handling reads them. */
private class DisplayedSessions {
    var value: List<SessionListSession> = emptyList()
}

/**
 * Scope id for [id] against [projects]: an added path maps to its project, and so does a
 * worktree while [worktreesEnabled] is off; anything else is kept.
 */
internal fun homeScopeId(
    id: String,
    projects: List<HomeProject>,
    worktreesEnabled: Boolean = true,
): String = HomeScope.resolve(id, projects, worktreesEnabled)?.let(HomeScope::id) ?: id

/** Rows for every Home project, one per session id. Settled rows stay off this list. */
internal fun MainScreenUiState.Loaded.homeSessions(
    worktreesEnabled: Boolean = true,
    showsSettle: Boolean = false,
    nowEpochMs: Long = System.currentTimeMillis(),
): List<SessionListSession> =
    homeSessionListSessions(
        projects,
        sections,
        home.extraSessions,
        worktreesEnabled,
        showsSettle = showsSettle,
        nowEpochMs = nowEpochMs,
        settledOnly = false,
    )

/** Settled rows for the settled page. Empty unless settle is on. */
internal fun MainScreenUiState.Loaded.homeSettledSessions(
    worktreesEnabled: Boolean = true,
    showsSettle: Boolean = false,
    nowEpochMs: Long = System.currentTimeMillis(),
): List<SessionListSession> {
    if (!showsSettle) return emptyList()
    return homeSessionListSessions(
        projects,
        sections,
        home.extraSessions,
        worktreesEnabled,
        showsSettle = true,
        nowEpochMs = nowEpochMs,
        settledOnly = true,
    )
}

/** Without project data, each section is its own project (tests and previews). */
internal fun List<ProjectSectionUi>.toCrossProjectSessionListSessions(): List<SessionListSession> =
    homeSessionListSessions(HomeProjectGrouping.group(map { it.worktree }, emptyMap()), this)

/**
 * Home rows: each project's sessions from its added paths' lists and (v1) its other
 * worktrees' lists. A session listed more than once keeps its newest copy (the earlier
 * source on a tie); rows are newest first. Settled rows are a separate list when settle is on.
 */
internal fun homeSessionListSessions(
    projects: List<HomeProject>,
    sections: List<ProjectSectionUi>,
    extraSessions: Map<String, List<SessionSummary>> = emptyMap(),
    worktreesEnabled: Boolean = true,
    showsSettle: Boolean = false,
    nowEpochMs: Long = 0L,
    settledOnly: Boolean = false,
): List<SessionListSession> {
    val sectionsByPath = sections.associateBy { it.worktree }
    val flattened =
        projects.flatMap { project ->
            project.memberPaths.flatMap { path ->
                val section = sectionsByPath[path]
                val summaries =
                    if (settledOnly) {
                        section?.archivedSessions.orEmpty().filter { it.isSettled }
                    } else {
                        section?.allSessions.orEmpty() +
                            section?.archivedSessions.orEmpty().filter { session ->
                                !showsSettle || !session.isSettled
                            }
                    }
                summaries.map { HomeRowSource(project, path, it) }
            } +
                if (settledOnly) {
                    emptyList()
                } else {
                    project.worktrees.flatMap { worktree ->
                        extraSessions[worktree.directory].orEmpty().map { HomeRowSource(project, worktree.directory, it) }
                    }
                }
        }
    val chosenIndexById = mutableMapOf<String, Int>()
    flattened.forEachIndexed { index, source ->
        val timestamp = source.session.rowTimestamp(settledOnly)
        val chosenIndex = chosenIndexById[source.session.id]
        val chosenTimestamp = chosenIndex?.let { flattened[it].session.rowTimestamp(settledOnly) }
        if (chosenTimestamp == null || timestamp > chosenTimestamp) {
            chosenIndexById[source.session.id] = index
        }
    }
    return chosenIndexById.values
        .sortedWith(
            compareByDescending<Int> { index ->
                flattened[index].session.rowTimestamp(settledOnly)
            }.thenBy { index -> index },
        ).map { index ->
            flattened[index].toSessionListSession(
                worktreesEnabled = worktreesEnabled,
                showsSettle = showsSettle,
                nowEpochMs = nowEpochMs,
                settledPage = settledOnly,
            )
        }
}

private fun SessionSummary.rowTimestamp(settledPage: Boolean): Long =
    if (settledPage) settledAtEpochMs ?: updatedAtEpochMs ?: 0L else updatedAtEpochMs ?: 0L

private data class HomeRowSource(
    val project: HomeProject,
    /** The added path or worktree whose list carried the session. */
    val listedIn: String,
    val session: SessionSummary,
) {
    fun toSessionListSession(
        worktreesEnabled: Boolean = true,
        showsSettle: Boolean = false,
        nowEpochMs: Long = 0L,
        settledPage: Boolean = false,
    ): SessionListSession {
        val directory = session.directory?.takeIf { it.isNotEmpty() } ?: listedIn
        val worktree = project.worktree(directory)?.takeIf { worktreesEnabled && project.hasManyWorktrees }
        val until = session.snoozedUntilEpochMs
        val snoozed =
            showsSettle &&
                !settledPage &&
                HomeSettleFeed.isSnoozed(until, nowEpochMs, session.isSettled)
        val gestures =
            when {
                !showsSettle -> emptyList()
                settledPage -> listOf(HomeSettleGesture.Unsettle)
                else ->
                    HomeSettleFeed.gestures(
                        showsSettle = true,
                        isSettled = false,
                        isSnoozed = snoozed,
                        snoozeBlocked = session.snoozeBlockReason != null,
                        isArchived = session.isArchived,
                    )
            }
        val hideLocation = settledPage || snoozed
        return SessionListSession(
            id = session.id,
            title = session.title,
            worktree = project.key,
            letter = letterCharacterForProject(project.displayName),
            letterAccent = letterAccentForWorktree(project.primaryPath),
            subtitle =
                when {
                    settledPage -> ""
                    snoozed && until != null -> "Until ${HomeSettleFeed.untilLabel(until, nowEpochMs)}"
                    else -> listedIn
                },
            directory = directory,
            status =
                if (snoozed && !session.needsApproval) {
                    SessionListStatus.Snoozed
                } else {
                    session.toSessionListStatus()
                },
            caption =
                when {
                    settledPage -> session.settledAtEpochMs?.let(RelativeTimeFormatting::format).orEmpty()
                    snoozed && until != null -> HomeSettleFeed.wakeInLabel(until, nowEpochMs)
                    else -> session.homeCaption()
                },
            updatedAtEpochMs = session.updatedAtEpochMs,
            retryAttempt = session.retryAttempt(),
            statusStale = session.statusStale,
            settleGestures = gestures,
            showStatus = !settledPage,
            worktreeScopeId = worktree?.let { HomeScope.worktreeId(project.key, it.directory) },
            location =
                if (hideLocation) {
                    null
                } else {
                    HomeSessionLocation
                        .parts(project, directory, includeProjectName = false, worktreesEnabled = worktreesEnabled)
                        ?.map { SessionListLocationPart(it.text, isBranch = it.isBranch) }
                },
            projectName = if (hideLocation) null else project.displayName,
            compactWorktree =
                if (hideLocation) {
                    null
                } else {
                    HomeCompactRowPath.worktreeLabel(project, directory, worktreesEnabled)
                },
        )
    }
}

/** Dock and picker projects: counts per project and per worktree, and unfinished first loads. */
internal fun homeChromeProjects(
    projects: List<HomeProject>,
    sessions: List<SessionListSession>,
    projectLoads: Map<String, ProjectFirstLoad> = emptyMap(),
): List<SessionListHomeChrome.Project> {
    val counts = sessions.groupingBy { it.worktree }.eachCount()
    val worktreeCounts = sessions.mapNotNull { it.worktreeScopeId }.groupingBy { it }.eachCount()
    val runningCounts =
        sessions
            .filter { it.status == SessionListStatus.Running || it.status == SessionListStatus.Retrying }
            .mapNotNull { it.worktreeScopeId }
            .groupingBy { it }
            .eachCount()
    return projects.map { project ->
        // A path that joined a project already on Home does not put the whole project back into loading.
        val loads = project.memberPaths.map { projectLoads[it] }
        SessionListHomeChrome.Project(
            worktree = project.primaryPath,
            title = project.displayName,
            sessionCount = counts[project.key] ?: 0,
            load = loads.takeIf { all -> all.all { it != null } }?.firstOrNull()?.toScopeLoad(),
            id = project.key,
            worktrees =
                project.worktrees.map { worktree ->
                    val scopeId = HomeScope.worktreeId(project.key, worktree.directory)
                    SessionListHomeChrome.Worktree(
                        worktree = worktree,
                        sessionCount = worktreeCounts[scopeId] ?: 0,
                        runningCount = runningCounts[scopeId] ?: 0,
                        scopeId = scopeId,
                    )
                },
        )
    }
}

internal fun ProjectFirstLoad.toScopeLoad(): SessionListScopeLoad =
    when (this) {
        is ProjectFirstLoad.Loading -> SessionListScopeLoad.Loading(isSlow = isSlow)
        is ProjectFirstLoad.Failed -> SessionListScopeLoad.Failed
    }

@Suppress("UNUSED_PARAMETER")
internal fun SessionSummary.toSessionListStatus(hasPendingPermission: Boolean = false): SessionListStatus =
    when {
        needsApproval || hasPendingPermission -> SessionListStatus.NeedsApproval
        statusLabel == "Working" -> SessionListStatus.Running
        statusLabel?.startsWith("Retrying") == true -> SessionListStatus.Retrying
        // v2: server read state. Only set on servers that track it, so v1 keeps Ready.
        unreadOutcome != null ->
            when (unreadOutcome) {
                SessionIdleOutcome.Succeeded -> SessionListStatus.Done
                SessionIdleOutcome.Failed -> SessionListStatus.Failed
                SessionIdleOutcome.Interrupted -> SessionListStatus.Stopped
            }
        isReady -> SessionListStatus.Ready
        else -> SessionListStatus.Idle
    }

/** `Archived · 2h ago` for restorable rows; the relative time alone for active ones. */
internal fun SessionSummary.homeCaption(): String {
    val relative = updatedAtEpochMs?.let(RelativeTimeFormatting::format).orEmpty()
    val lifecycle =
        when {
            isArchived -> "Archived"
            isSettled -> "Settled"
            else -> return relative
        }
    return if (relative.isEmpty()) lifecycle else "$lifecycle · $relative"
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
            onNewSession = {},
            onConsumePendingOpenSession = {},
            onRemoveProject = {},
            onConfirmRemoveProject = {},
            onDismissProjectRemoval = {},
            onConsumeProjectRemovalSnackbar = {},
        )
    }
}
