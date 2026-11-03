package dev.opencode.mobile.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.home.HomeCompactRowPath
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeProjectGrouping
import dev.opencode.mobile.domain.home.HomeProjectPickerState
import dev.opencode.mobile.domain.home.HomeScope
import dev.opencode.mobile.domain.home.HomeSessionLocation
import dev.opencode.mobile.domain.home.HomeWorktreeSession
import dev.opencode.mobile.domain.home.HomeWorktreesSnapshot
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.session.SessionIdleOutcome
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.domain.time.RelativeTimeFormatting
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
import dev.opencode.mobile.ui.components.OpenCodeOverflowMenuEntry
import dev.opencode.mobile.ui.components.OpenCodeSectionHeader
import dev.opencode.mobile.ui.components.OpenCodeSessionConnectionStatus
import dev.opencode.mobile.ui.components.OpenCodeSnackbarHost
import dev.opencode.mobile.ui.components.OpenCodeSnackbarMessage
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarOverflowHost
import dev.opencode.mobile.ui.components.OpenCodeTopBarSubtitleAccessory
import dev.opencode.mobile.ui.components.SessionList
import dev.opencode.mobile.ui.components.SessionListFilterTab
import dev.opencode.mobile.ui.components.SessionListLocationPart
import dev.opencode.mobile.ui.components.SessionListProjectPickerRows
import dev.opencode.mobile.ui.components.SessionListProjectPickerTags
import dev.opencode.mobile.ui.components.SessionListScopeLoad
import dev.opencode.mobile.ui.components.SessionListSession
import dev.opencode.mobile.ui.components.SessionListStatus
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
import dev.opencode.mobile.ui.text.ResourcesUiTextLookup
import dev.opencode.mobile.ui.text.UiText
import dev.opencode.mobile.ui.text.resolve
import dev.opencode.mobile.ui.worktree.NewWorktreeAvailability
import dev.opencode.mobile.ui.worktree.NewWorktreeRow
import dev.opencode.mobile.ui.worktree.WorktreeCreateFormActions
import dev.opencode.mobile.ui.worktree.WorktreeCreateFormBody
import dev.opencode.mobile.ui.worktree.WorktreeCreateFormCallbacks
import dev.opencode.mobile.ui.worktree.WorktreeCreateFormState
import dev.opencode.mobile.ui.worktree.WorktreeCreateOrigin
import dev.opencode.mobile.ui.worktree.WorktreeCreateTags
import dev.opencode.mobile.ui.worktree.worktreeCreateBackAction
import dev.opencode.mobile.ui.worktrees.WorktreesTags

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
    onWorktreesClick: () -> Unit = {},
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
        removalSnackbar = removalSnackbar?.toSnackbarMessage(),
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
                onChooseDelete = viewModel::chooseDelete,
                onConfirmDelete = viewModel::confirmDelete,
                onDismissDeleteConfirm = viewModel::dismissDeleteConfirm,
            ),
        onSettingsClick = onSettingsClick,
        onTerminalClick = onTerminalClick,
        onWorktreesClick = onWorktreesClick,
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
    onWorktreesClick: () -> Unit = {},
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
    var homeMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val loadedWorktrees = remember(uiState) { (uiState as? MainScreenUiState.Loaded)?.worktreesSnapshot() }
    // Feature entries go in `features`, top to bottom; HomeMenu adds Settings last.
    val homeMenuEntries =
        HomeMenu.entries(
            features =
                listOf(
                    OpenCodeOverflowMenuEntry(
                        title = stringResource(R.string.worktrees_title),
                        icon = OpenCodeIcons.CallSplit,
                        subtitle = loadedWorktrees?.let { worktreesMenuSubtitle(it) },
                        onClick = {
                            ProductAnalytics.logUserAction(
                                screenId = ProductAnalytics.Screen.HOME,
                                actionId = ProductAnalytics.Action.HOME_OPEN_WORKTREES,
                            )
                            onWorktreesClick()
                        },
                        testTag = WorktreesTags.MENU_ENTRY,
                    ),
                ),
            settingsTitle = stringResource(R.string.common_settings),
            onSettingsClick = {
                ProductAnalytics.logUserAction(
                    screenId = ProductAnalytics.Screen.HOME,
                    actionId = ProductAnalytics.Action.HOME_OPEN_SETTINGS,
                )
                onSettingsClick()
            },
        )
    val worktreesEnabled = HomeDisplayPreferences.showsWorktrees
    val loaded = uiState as? MainScreenUiState.Loaded
    val homeSessions = remember(loaded, worktreesEnabled) { loaded?.homeSessions(worktreesEnabled).orEmpty() }
    val chromeProjects =
        remember(loaded, homeSessions) {
            loaded?.let { state -> homeChromeProjects(state.projects, homeSessions, state.projectLoads) }.orEmpty()
        }
    val showTerminalAction =
        SessionListHomeChrome.terminalAction(chromeProjects) is SessionListHomeChrome.TerminalAction.OpenCatalog
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

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OpenCodeTopBarOverflowHost(
                expanded = homeMenuExpanded,
                onDismissRequest = { homeMenuExpanded = false },
                entries = homeMenuEntries,
            ) {
                OpenCodeTopBar(
                    title = serversChrome.title.resolve(),
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
                                        contentDescription = stringResource(R.string.common_terminal),
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
                                    imageVector = OpenCodeIcons.MoreVert,
                                    contentDescription = stringResource(R.string.home_more_options),
                                    onClick = {
                                        ProductAnalytics.logUserAction(
                                            screenId = ProductAnalytics.Screen.HOME,
                                            actionId = ProductAnalytics.Action.HOME_OPEN_MENU,
                                        )
                                        homeMenuExpanded = true
                                    },
                                ),
                            )
                        },
                )
            }

            val activeServerId = serversChrome.active?.id.orEmpty()
            AnimatedContent(
                targetState = activeServerId,
                transitionSpec = {
                    fadeIn(animationSpec = tween(180)) togetherWith ExitTransition.None
                },
                label = SERVER_SWITCH_ANIMATION_LABEL,
                modifier = Modifier.weight(1f).fillMaxSize(),
            ) { contentServerId ->
                // Only the new server's Home fades in. The outgoing one would draw the same new state.
                if (contentServerId != activeServerId) return@AnimatedContent
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
                        title = stringResource(R.string.common_loading_sessions),
                        subtitle = stringResource(R.string.common_checking_server),
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
                        modifier = Modifier.fillMaxSize(),
                    )
                }
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
        title = stringResource(R.string.servers_title),
        onDismissRequest = onDismissServersSheet,
        pinnedBottom = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.FullWidthText(stringResource(R.string.servers_add)),
                    onClick = onAddServerClick,
                )
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.FullWidthText(stringResource(R.string.home_servers_sheet_manage)),
                    contrast = OpenCodeButtonContrast.Low,
                    onClick = onManageServersClick,
                )
            }
        },
    ) {
        val active = serversChrome.active
        if (active != null) {
            OpenCodeSectionHeader(title = stringResource(R.string.home_servers_sheet_using_now))
            OpenCodeListItem(
                title = active.displayName,
                subtitle = active.baseUrl.trimEnd('/'),
                caption = active.listCaption(isActive = true),
                status = active.listStatus(isActive = true, activeLabel = stringResource(R.string.servers_status_active)),
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
            OpenCodeSectionHeader(title = stringResource(R.string.home_servers_sheet_switch_to))
            serversChrome.others.forEach { profile ->
                OpenCodeListItem(
                    title = profile.displayName,
                    subtitle = ConnectionProfileIdentity.hostLabel(profile.baseUrl),
                    caption = profile.listCaption(isActive = false),
                    status = profile.listStatus(isActive = false, activeLabel = stringResource(R.string.servers_status_active)),
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
        title = stringResource(R.string.home_remove_project_title),
        subtitle =
            pendingProjectRemoval?.let { removal ->
                stringResource(R.string.home_remove_project_message, removal.displayName)
            },
        primaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.common_remove),
                destructive = true,
                onClick = {
                    onConfirmRemoveProject()
                },
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.common_cancel),
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
        title = stringResource(if (runInForm != null) R.string.worktree_form_title else R.string.home_new_session),
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
            OpenCodeSectionHeader(title = stringResource(R.string.home_run_in))
            runIn.worktrees.forEach { worktree ->
                OpenCodeListItem(
                    title = worktree.title,
                    subtitle = worktree.subtitle.resolve(),
                    caption = worktree.runningCount.takeIf { it > 0 }?.let { HomeText.runningCount(it).resolve() },
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
                OpenCodeSectionHeader(title = stringResource(R.string.home_other_projects))
                others.forEach { project ->
                    OpenCodeBottomSheetEntry(
                        title = project.title,
                        startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Folder),
                        onClick = { pickProject(project, true) },
                    )
                }
            }
        } else if (chromeProjects.isEmpty()) {
            OpenCodeBottomSheetEntry(title = stringResource(R.string.home_no_projects), enabled = false, onClick = {})
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

/** The snackbar text in the current language. */
@Composable
private fun HomeSnackbar.toSnackbarMessage(): OpenCodeSnackbarMessage = OpenCodeSnackbarMessage(text = text.resolve(), variant = variant, id = id)

/** Animation inspector label, not user-facing. */
private const val SERVER_SWITCH_ANIMATION_LABEL = "serverSwitch"

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
        title = stringResource(R.string.home_session_actions_title),
        onDismissRequest = callbacks.onDismissSheet,
    ) {
        OpenCodeBottomSheetEntry(
            title = stringResource(R.string.home_session_actions_rename),
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Edit),
            onClick = callbacks.onChooseRename,
        )
        OpenCodeBottomSheetEntry(
            title = stringResource(R.string.home_session_actions_fork),
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.ContentCopy),
            onClick = callbacks.onChooseFork,
        )
        if (state.canArchive) {
            OpenCodeBottomSheetEntry(
                title = stringResource(R.string.home_session_actions_archive),
                enabled = !state.lifecycleInFlight,
                startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Archive),
                onClick = callbacks.onChooseArchive,
            )
        }
        OpenCodeBottomSheetEntry(
            title = stringResource(R.string.common_delete),
            enabled = !state.lifecycleInFlight,
            destructive = true,
            startDecorator = BottomSheetEntryDecorator.Icon(OpenCodeIcons.Close),
            onClick = callbacks.onChooseDelete,
        )
    }

    // The sheet stays composed without a target so it can play its exit motion.
    val target = state.target ?: return

    OpenCodeDialog(
        visible = state.renameVisible,
        title = stringResource(R.string.home_rename_title),
        primaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.common_save),
                enabled = !state.renameSubmitting,
                onClick = callbacks.onConfirmRename,
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.common_cancel),
                enabled = !state.renameSubmitting,
                onClick = callbacks.onCancelRename,
            ),
        textField =
            OpenCodeDialogTextField(
                value = state.renameDraft,
                onValueChange = callbacks.onRenameDraftChange,
                hint = stringResource(R.string.home_session_title_hint),
                enabled = !state.renameSubmitting,
                errorMessage = state.renameError?.resolve(),
            ),
        onDismissRequest = {
            if (!state.renameSubmitting) {
                callbacks.onCancelRename()
            }
        },
    )

    OpenCodeDialog(
        visible = state.forkVisible,
        title = stringResource(R.string.home_fork_title),
        primaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.home_fork_confirm),
                enabled = !state.forkSubmitting && state.forkBlockedMessage == null,
                onClick = callbacks.onConfirmFork,
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.common_cancel),
                enabled = !state.forkSubmitting,
                onClick = callbacks.onCancelFork,
            ),
        textField =
            OpenCodeDialogTextField(
                value = state.forkDraft,
                onValueChange = callbacks.onForkDraftChange,
                hint = stringResource(R.string.home_session_title_hint),
                enabled = !state.forkSubmitting,
                errorMessage = (state.forkError ?: state.forkBlockedMessage)?.resolve(),
            ),
        onDismissRequest = {
            if (!state.forkSubmitting) {
                callbacks.onCancelFork()
            }
        },
    )

    OpenCodeDialog(
        visible = state.forkResultVisible,
        title = stringResource(R.string.home_fork_result_title),
        subtitle = stringResource(R.string.home_fork_result_message, state.forkedTitle),
        primaryOption = OpenCodeDialogOption(title = stringResource(R.string.common_open), onClick = callbacks.onForkOpen),
        secondaryOption = OpenCodeDialogOption(title = stringResource(R.string.home_fork_result_stay), onClick = callbacks.onForkStay),
        onDismissRequest = callbacks.onForkStay,
    )

    OpenCodeDialog(
        visible = state.deleteConfirmVisible,
        title = stringResource(R.string.home_delete_title),
        subtitle = stringResource(R.string.home_delete_message, target.title),
        primaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.common_delete),
                destructive = true,
                enabled = !state.lifecycleInFlight,
                onClick = callbacks.onConfirmDelete,
            ),
        secondaryOption =
            OpenCodeDialogOption(
                title = stringResource(R.string.common_cancel),
                enabled = !state.lifecycleInFlight,
                onClick = callbacks.onDismissDeleteConfirm,
            ),
        onDismissRequest = callbacks.onDismissDeleteConfirm,
    )
}

@Composable
private fun ProbeFailedContent(
    message: UiText,
    onRetry: () -> Unit,
    onEditConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpenCodeEmptyState(
        title = stringResource(R.string.home_probe_failed_title),
        message = message.resolve().ifBlank { stringResource(R.string.home_probe_failed_message) },
        primaryAction = OpenCodeEmptyStateAction(title = stringResource(R.string.common_retry), onClick = onRetry),
        secondaryAction =
            OpenCodeEmptyStateAction(title = stringResource(R.string.home_probe_failed_edit_connection), onClick = onEditConnection),
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
    projectSessionCreateError: UiText?,
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
        val text = ResourcesUiTextLookup(LocalResources.current)
        val filterTabs =
            SessionListHomeChrome.filterTabs(
                projects = chromeProjects,
                sessionCount = sessions.size,
                text = text,
                worktreesEnabled = worktreesEnabled,
            )
        val worktreeTabs = SessionListHomeChrome.worktreeTabs(chromeProjects, text, worktreesEnabled)
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
        // Ctrl+N: the dock's New session for the current scope. A Session Chat open beside Home
        // registers its own Ctrl+N (its project) and, being newer, takes over.
        KeyboardShortcutHandler(
            KeyboardShortcutAction.NewSession,
            enabled = KeyboardShortcutRules.homeHandlesNewSession(sessionChatBeside = selectedSessionId != null),
        ) {
            onNewSessionAction(
                SessionListHomeChrome.newSessionAction(
                    projects = chromeProjects,
                    selectedFilterId = resolvedFilterId,
                    worktreesEnabled = worktreesEnabled,
                ),
            )
        }
        // Ctrl+1…Ctrl+9: the Nth project in the dock's order.
        val dockProjects = filterTabs.filterNot { it.isAll || it.isWorktree }
        val dockProjectIds = dockProjects.map { it.id }
        KeyboardShortcutAction.projects.forEachIndexed { index, action ->
            val projectId = KeyboardShortcutRules.projectForShortcut(dockProjectIds, index + 1)
            KeyboardShortcutHandler(
                action,
                enabled = projectId != null,
                title = dockProjects.getOrNull(index)?.let { KeyboardShortcutRules.projectShortcutTitle(it.title) } ?: action.title,
            ) {
                projectId?.let(onSelectedFilterIdChange)
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
            createError = projectSessionCreateError?.resolve(),
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
            title = stringResource(R.string.home_filter_sheet_title),
            // Half + Keep keeps today's fixed height and keyboard policy; Dismiss would be more
            // natural for this list (#680).
            height = OpenCodeBottomSheetHeight.Half,
            keyboard = OpenCodeBottomSheetKeyboard.Keep,
            headerTrailingAction =
                OpenCodeBottomSheetHeaderTextAction(
                    title = stringResource(if (pickerState.isEditing) R.string.common_done else R.string.common_edit),
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
                        text = text,
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

/** Rows for every Home project, one per session id. */
internal fun MainScreenUiState.Loaded.homeSessions(worktreesEnabled: Boolean = true): List<SessionListSession> =
    homeSessionListSessions(projects, sections, home.extraSessions, worktreesEnabled, home.removedWorktrees)

/** ⋯ menu subtitle of Worktrees: "N across M projects", or None. */
@Composable
private fun worktreesMenuSubtitle(snapshot: HomeWorktreesSnapshot): String {
    val count = snapshot.worktreeCount
    if (count == 0) return stringResource(R.string.worktrees_menu_none)
    val projects = snapshot.projectsWithWorktrees
    return stringResource(
        R.string.worktrees_menu_subtitle,
        count,
        pluralStringResource(R.plurals.home_project_count, projects, projects),
    )
}

/** Home's projects and sessions for the Worktrees screen. */
internal fun MainScreenUiState.Loaded.worktreesSnapshot(): HomeWorktreesSnapshot =
    HomeWorktreesSnapshot(
        projects = projects,
        sessions =
            homeSessions().map { session ->
                HomeWorktreeSession(
                    id = session.id,
                    title = session.title,
                    directory = ServerPathPolicy.canonical(session.directory.trim()),
                    isRunning = session.status == SessionListStatus.Running || session.status == SessionListStatus.Retrying,
                )
            },
    )

/** Without project data, each section is its own project (tests and previews). */
internal fun List<ProjectSectionUi>.toCrossProjectSessionListSessions(): List<SessionListSession> =
    homeSessionListSessions(HomeProjectGrouping.group(map { it.worktree }, emptyMap()), this)

/**
 * Home rows: each project's sessions from its added paths' lists and (v1) its other
 * worktrees' lists, including worktrees removed from this app ([removedWorktrees], directory to
 * project key). A session listed more than once keeps its newest copy (the earlier source on a
 * tie); rows are newest first.
 */
internal fun homeSessionListSessions(
    projects: List<HomeProject>,
    sections: List<ProjectSectionUi>,
    extraSessions: Map<String, List<SessionSummary>> = emptyMap(),
    worktreesEnabled: Boolean = true,
    removedWorktrees: Map<String, String> = emptyMap(),
): List<SessionListSession> {
    val sectionsByPath = sections.associateBy { it.worktree }
    val removed = removedWorktrees.keys
    val flattened =
        projects.flatMap { project ->
            val removedHere = removedWorktrees.filterValues { it == project.key }.keys
            project.memberPaths.flatMap { path ->
                sectionsByPath[path]?.allSessions.orEmpty().map { HomeRowSource(project, path, it, removed) }
            } +
                (project.worktrees.map { it.directory } + removedHere).distinct().flatMap { directory ->
                    extraSessions[directory].orEmpty().map { HomeRowSource(project, directory, it, removed) }
                }
        }
    val chosenIndexById = mutableMapOf<String, Int>()
    flattened.forEachIndexed { index, source ->
        val timestamp = source.session.updatedAtEpochMs ?: 0L
        val chosenIndex = chosenIndexById[source.session.id]
        val chosenTimestamp = chosenIndex?.let { flattened[it].session.updatedAtEpochMs ?: 0L }
        if (chosenTimestamp == null || timestamp > chosenTimestamp) {
            chosenIndexById[source.session.id] = index
        }
    }
    return chosenIndexById.values
        .sortedWith(
            compareByDescending<Int> { index ->
                flattened[index].session.updatedAtEpochMs ?: 0L
            }.thenBy { index -> index },
        ).map { index -> flattened[index].toSessionListSession(worktreesEnabled) }
}

private data class HomeRowSource(
    val project: HomeProject,
    /** The added path or worktree whose list carried the session. */
    val listedIn: String,
    val session: SessionSummary,
    /** Worktree directories removed from this app. */
    val removedDirectories: Set<String> = emptySet(),
) {
    fun toSessionListSession(worktreesEnabled: Boolean = true): SessionListSession {
        val directory = session.directory?.takeIf { it.isNotEmpty() } ?: listedIn
        val worktree = project.worktree(directory)?.takeIf { worktreesEnabled && project.hasManyWorktrees }
        return SessionListSession(
            id = session.id,
            title = session.title,
            worktree = project.key,
            letter = letterCharacterForProject(project.displayName),
            letterAccent = letterAccentForWorktree(project.primaryPath),
            subtitle = listedIn,
            directory = directory,
            status = session.toSessionListStatus(),
            caption = session.updatedAtEpochMs?.let(RelativeTimeFormatting::format) ?: UiText.verbatim(""),
            updatedAtEpochMs = session.updatedAtEpochMs,
            retryAttempt = session.retryAttempt(),
            statusStale = session.statusStale,
            worktreeScopeId = worktree?.let { HomeScope.worktreeId(project.key, it.directory) },
            location =
                HomeSessionLocation
                    .parts(project, directory, includeProjectName = false, worktreesEnabled, removedDirectories)
                    ?.map { SessionListLocationPart(it.text, isBranch = it.isBranch, isRemoved = it.isRemoved) },
            projectName = project.displayName,
            compactWorktree = HomeCompactRowPath.worktreeLabel(project, directory, worktreesEnabled),
            compactWorktreeRemoved = ServerPathPolicy.canonical(directory.trim()).let { it in removedDirectories && project.worktree(it) == null },
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
