package dev.opencode.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.opencode.mobile.domain.app.AppVersion
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.preferences.AnalyticsConsentPreferences
import dev.opencode.mobile.domain.preferences.DeveloperToolsPreferences
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.preferences.MarkdownHighlightPreferences
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryDetailScreen
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryEntries
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryEntryId
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryScreen
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryThemeMode
import dev.opencode.mobile.ui.componentgallery.components.ActionPillGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ActionTileGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.AssistantMessageGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.AttachmentGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.BottomFadeGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.BottomSheetGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ButtonGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ChildSessionRowGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ChoiceBadgeGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.CodeSnippetGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ComposerSuggestionOverlayGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.DialogGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.DiffStatGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.EmptyStateGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.FilterTabsGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.HunkNavigationPillGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.IconsGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.InlineNoticeGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.KeyValueRowGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ListGroupGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ListItemGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.MessageListGalleryDetailScreen
import dev.opencode.mobile.ui.componentgallery.components.MessageNoteGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ModalGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.OutgoingMessageGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.OverflowMenuGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.PageIndicatorGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.PendingPromptsStripGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ReasoningRowGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.RenderedMarkdownGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ReorderableListGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ReviewChangesPillGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SectionHeaderGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SelectableCardGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ServerAddressInputGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SessionBranchPillGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SessionComposerGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SessionListDockGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SessionListGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SessionStatusBarGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SessionTodoItemGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SessionTodosPillGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SkeletonIllustrationGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SnackbarGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SplitPaneGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.StatusStackGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SyncingPillGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.TagGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.TextInputGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ToolCallCardGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ToolChipGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.TopBarGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.UnifiedDiffGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.WorkingIndicatorGalleryScreen
import dev.opencode.mobile.ui.componentgallery.foundation.ColorGalleryScreen
import dev.opencode.mobile.ui.componentgallery.foundation.TypographyGalleryScreen
import dev.opencode.mobile.ui.components.OpenCodeEmptyState
import dev.opencode.mobile.ui.components.OpenCodeSplitPane
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneDetailControl
import dev.opencode.mobile.ui.components.OpenCodeSplitPaneScreen
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.currentOpenCodeUsesSplitLayout
import dev.opencode.mobile.ui.components.openCodeDetailLeading
import dev.opencode.mobile.ui.connection.ConnectionSetupMode
import dev.opencode.mobile.ui.connection.ConnectionSetupScreen
import dev.opencode.mobile.ui.connection.ConnectionSetupViewModel
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import dev.opencode.mobile.ui.keyboard.KeyboardShortcutAction
import dev.opencode.mobile.ui.keyboard.KeyboardShortcutHandler
import dev.opencode.mobile.ui.main.MainScreen
import dev.opencode.mobile.ui.main.SessionOpenTarget
import dev.opencode.mobile.ui.onboarding.AppOnboardingScreen
import dev.opencode.mobile.ui.root.AppRootState
import dev.opencode.mobile.ui.root.AppRootViewModel
import dev.opencode.mobile.ui.settings.AppearanceSettingsScreen
import dev.opencode.mobile.ui.settings.DiagnosticLogsScreen
import dev.opencode.mobile.ui.settings.DiagnosticLogsViewModel
import dev.opencode.mobile.ui.settings.FeatureFlagsRoute
import dev.opencode.mobile.ui.settings.ServersScreen
import dev.opencode.mobile.ui.settings.ServersViewModel
import dev.opencode.mobile.ui.settings.SettingsDetail
import dev.opencode.mobile.ui.settings.SettingsScreen
import dev.opencode.mobile.ui.settings.SettingsViewModel
import dev.opencode.mobile.ui.settings.SyntaxHighlightPickerScreen
import dev.opencode.mobile.ui.settings.TextMateLanguagesRoute
import dev.opencode.mobile.ui.settings.TextMateLogbookScreen
import dev.opencode.mobile.ui.settings.TextMateLogbookViewModel
import dev.opencode.mobile.ui.settings.ThemePickerScreen
import dev.opencode.mobile.ui.terminal.TerminalsCatalogScreen

/**
 * Entry decorators for the root [NavDisplay]. The ViewModelStore decorator scopes every
 * `hiltViewModel(key = …)` inside an entry to that entry, so screen ViewModels are cleared
 * (and `onCleared` runs) when the entry is popped instead of accumulating in the Activity store.
 */
internal fun <T> MutableList<T>.popRouteUnlessRoot() {
    if (size > 1) {
        removeAt(lastIndex)
    }
}

@Composable
internal fun <T : Any> rememberMainNavEntryDecorators(): List<NavEntryDecorator<T>> =
    listOf(
        rememberSaveableStateHolderNavEntryDecorator(),
        rememberViewModelStoreNavEntryDecorator(),
    )

@Composable
fun MainNavigation() {
    val rootViewModel: AppRootViewModel = hiltViewModel()
    val rootState by rootViewModel.state.collectAsStateWithLifecycle()
    val presentConnectionSetupAfterOnboarding by
        rootViewModel.presentConnectionSetup.collectAsStateWithLifecycle()
    val setupViewModel: ConnectionSetupViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val setupUiState by setupViewModel.uiState.collectAsStateWithLifecycle()
    val sessionCacheSizeLabel by settingsViewModel.sessionCacheSizeLabel.collectAsStateWithLifecycle()
    val serversSummary by settingsViewModel.serversSummary.collectAsStateWithLifecycle()
    val serversViewModel: ServersViewModel = hiltViewModel()
    val serversUiState by serversViewModel.uiState.collectAsStateWithLifecycle()

    val backStack = rememberNavBackStack(ProjectListHome)
    val rootChatFlowNavigator = remember(backStack) { backStack.rootChatFlowNavigator() }
    val homeResetGeneration by rootViewModel.homeResetGeneration.collectAsStateWithLifecycle()
    LaunchedEffect(homeResetGeneration) {
        if (homeResetGeneration == 0) return@LaunchedEffect
        while (backStack.size > 1) {
            when (backStack.lastOrNull()) {
                is SessionChat,
                is SessionContext,
                is Changes,
                is SessionToolGroup,
                is Terminal,
                is TerminalsCatalog,
                -> backStack.popRouteUnlessRoot()
                else -> break
            }
        }
    }

    fun presentConnectionSetup(
        initialProfile: ConnectionProfile?,
        mode: ConnectionSetupMode = if (initialProfile == null) ConnectionSetupMode.First else ConnectionSetupMode.Edit,
    ) {
        setupViewModel.configure(initialProfile, mode)
        if (backStack.lastOrNull() != ConnectionSetupHome) {
            backStack.add(ConnectionSetupHome)
        }
    }

    LaunchedEffect(rootState, presentConnectionSetupAfterOnboarding) {
        if (rootState == AppRootState.Home && presentConnectionSetupAfterOnboarding) {
            presentConnectionSetup(initialProfile = null)
            rootViewModel.consumePresentConnectionSetup()
        }
    }
    val settingsDetailContent: @Composable (SettingsDetail, OpenCodeSplitPaneDetailControl?, () -> Unit) -> Unit =
        { detail, detailControl, onNavigateBack ->
            when (detail) {
                SettingsDetail.Servers -> {
                    LaunchedEffect(Unit) {
                        serversViewModel.refresh()
                    }
                    ServersScreen(
                        uiState = serversUiState,
                        onNavigateBack = onNavigateBack,
                        detailControl = detailControl,
                        onAddServer = {
                            presentConnectionSetup(
                                initialProfile = null,
                                mode = ConnectionSetupMode.Add,
                            )
                        },
                        onEditProfile = { profile ->
                            presentConnectionSetup(
                                initialProfile = profile,
                                mode = ConnectionSetupMode.Edit,
                            )
                        },
                        onRequestRemove = serversViewModel::requestRemove,
                        onConfirmRemove = serversViewModel::confirmRemove,
                        onDismissRemove = serversViewModel::dismissRemove,
                    )
                }
                SettingsDetail.Appearance -> {
                    ProductRouteScreenViewEffect(
                        screenId = ProductAnalytics.Screen.SETTINGS_APPEARANCE,
                        presentation =
                            if (detailControl != null) {
                                ProductAnalytics.Presentation.EMBEDDED
                            } else {
                                ProductAnalytics.Presentation.ROUTE
                            },
                    )
                    AppearanceSettingsScreen(
                        onNavigateBack = onNavigateBack,
                        detailControl = detailControl,
                        onThemeClick = {
                            if (backStack.lastOrNull() != ThemePickerHome) {
                                backStack.add(ThemePickerHome)
                            }
                        },
                        onSyntaxClick = {
                            if (backStack.lastOrNull() != SyntaxPickerHome) {
                                backStack.add(SyntaxPickerHome)
                            }
                        },
                    )
                }
                SettingsDetail.ComponentGallery ->
                    ComponentGalleryScreen(
                        onEntryClick = { entryId -> backStack.add(ComponentGalleryDetail(entryId)) },
                        onNavigateBack = onNavigateBack,
                        detailControl = detailControl,
                    )
                SettingsDetail.TextMateLanguages ->
                    TextMateLanguagesRoute(
                        onNavigateBack = onNavigateBack,
                        detailControl = detailControl,
                    )
                SettingsDetail.FeatureFlags ->
                    FeatureFlagsRoute(
                        onNavigateBack = onNavigateBack,
                        detailControl = detailControl,
                    )
                SettingsDetail.TextMateLogbook -> {
                    val logbookViewModel: TextMateLogbookViewModel = hiltViewModel()
                    val logbookState by logbookViewModel.state.collectAsStateWithLifecycle()
                    TextMateLogbookScreen(
                        state = logbookState,
                        onNavigateBack = onNavigateBack,
                        detailControl = detailControl,
                        onRefresh = logbookViewModel::refresh,
                        onClear = logbookViewModel::clear,
                    )
                }
                SettingsDetail.DiagnosticLogs -> {
                    val diagnosticLogsViewModel: DiagnosticLogsViewModel = hiltViewModel()
                    val diagnosticLogsState by diagnosticLogsViewModel.state.collectAsStateWithLifecycle()
                    val context = LocalContext.current
                    DiagnosticLogsScreen(
                        state = diagnosticLogsState,
                        onNavigateBack = onNavigateBack,
                        detailControl = detailControl,
                        onShareViaApps = {
                            diagnosticLogsViewModel.shareViaApps { intent ->
                                context.startActivity(intent)
                            }
                        },
                        onSaveToUri = diagnosticLogsViewModel::saveToUri,
                        onClear = diagnosticLogsViewModel::clear,
                        onExportWindowChange = diagnosticLogsViewModel::setExportWindow,
                    )
                }
            }
        }
    var themeMode by rememberSaveable { mutableStateOf(ComponentGalleryThemeMode.System) }
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme =
        when (themeMode) {
            ComponentGalleryThemeMode.System -> systemDarkTheme
            ComponentGalleryThemeMode.Light -> false
            ComponentGalleryThemeMode.Dark -> true
        }

    OpenCodeTheme(darkTheme = darkTheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (rootState) {
                    AppRootState.Loading -> {
                        ProductRouteScreenViewEffect(screenId = ProductAnalytics.Screen.APP_LOADING)
                        OpenCodeStatusStack(
                            title = "Loading sessions",
                            subtitle = "Checking server…",
                            testTag = "appRootLoading",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    AppRootState.AppOnboarding ->
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .systemBarsPadding()
                                    .displayCutoutPadding(),
                        ) {
                            AppOnboardingScreen(
                                onSkip = { rootViewModel.completeAppOnboarding(openConnectionSetup = false) },
                                onConnect = { rootViewModel.completeAppOnboarding(openConnectionSetup = true) },
                            )
                        }
                    AppRootState.Home ->
                        NavDisplay(
                            modifier =
                                Modifier.windowInsetsPadding(
                                    WindowInsets.systemBars
                                        .union(WindowInsets.displayCutout)
                                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                                ),
                            backStack = backStack,
                            onBack = { backStack.popRouteUnlessRoot() },
                            entryDecorators = rememberMainNavEntryDecorators(),
                            transitionSpec = { forwardRouteTransition() },
                            popTransitionSpec = { backwardRouteTransition() },
                            predictivePopTransitionSpec = { backwardRouteTransition() },
                            entryProvider =
                                entryProvider {
                                    entry<ProjectListHome> {
                                        RouteSurface(edgeToEdgeBottom = true) {
                                            ProductRouteScreenViewEffect(
                                                screenId = ProductAnalytics.Screen.HOME,
                                            )
                                            val useSplitLayout = currentOpenCodeUsesSplitLayout()
                                            // Trailing pane stack; its root is the selected session.
                                            val paneStack = rememberNavBackStack(HomeSessionPanePlaceholder)
                                            val paneSelection = paneStack.paneSelection
                                            var handledHomeResetGeneration by rememberSaveable {
                                                mutableIntStateOf(homeResetGeneration)
                                            }
                                            LaunchedEffect(homeResetGeneration) {
                                                if (homeResetGeneration != handledHomeResetGeneration) {
                                                    handledHomeResetGeneration = homeResetGeneration
                                                    paneStack.resetPane()
                                                }
                                            }
                                            val showsSplit =
                                                rememberHomeSessionPaneHandoff(useSplitLayout, paneStack) { routes ->
                                                    backStack.addAll(routes)
                                                }
                                            // Screens pushed in the pane pop through its own NavDisplay first.
                                            BackHandler(
                                                enabled = useSplitLayout && paneSelection != null && paneStack.size == 1,
                                            ) {
                                                paneStack.resetPane()
                                            }
                                            val home: @Composable () -> Unit = {
                                                MainScreen(
                                                    onSettingsClick = { backStack.add(SettingsHome) },
                                                    onTerminalClick = { backStack.add(TerminalsCatalog) },
                                                    onSessionClick = { target ->
                                                        if (useSplitLayout) {
                                                            paneStack.selectPaneSession(target.toSessionChatRoute())
                                                        } else {
                                                            backStack.add(target.toSessionChatRoute())
                                                        }
                                                    },
                                                    onEditConnectionClick = { profile ->
                                                        presentConnectionSetup(initialProfile = profile, mode = ConnectionSetupMode.Edit)
                                                    },
                                                    onConnectClick = {
                                                        presentConnectionSetup(initialProfile = null, mode = ConnectionSetupMode.First)
                                                    },
                                                    onAddServerClick = {
                                                        presentConnectionSetup(initialProfile = null, mode = ConnectionSetupMode.Add)
                                                    },
                                                    onManageServersClick = { backStack.add(SettingsHome) },
                                                    selectedSessionId = if (useSplitLayout) paneSelection?.sessionId else null,
                                                    sessionKeyboardNavigationEnabled = useSplitLayout,
                                                )
                                            }
                                            if (showsSplit) {
                                                OpenCodeSplitPane(
                                                    screen = OpenCodeSplitPaneScreen.Home,
                                                    leading = home,
                                                    trailing = { control ->
                                                        KeyboardShortcutHandler(
                                                            KeyboardShortcutAction.ToggleSidebar,
                                                            onShortcut = control.toggle,
                                                        )
                                                        HomeSessionPane(
                                                            paneStack = paneStack,
                                                            detailControl = control,
                                                        )
                                                    },
                                                )
                                            } else {
                                                home()
                                            }
                                        }
                                    }
                                    entry<SettingsHome> {
                                        RouteSurface {
                                            ProductRouteScreenViewEffect(
                                                screenId = ProductAnalytics.Screen.SETTINGS,
                                            )
                                            LaunchedEffect(Unit) {
                                                settingsViewModel.refreshServersSummary()
                                            }
                                            val context = LocalContext.current
                                            val appVersion = remember(context) { AppVersion.from(context) }
                                            val useSplitLayout = currentOpenCodeUsesSplitLayout()
                                            var settingsDetail by rememberSaveable { mutableStateOf(SettingsDetail.Servers) }
                                            val visibleSettingsDetail =
                                                settingsDetail.resolved(DeveloperToolsPreferences.unlocked)
                                            // Last layout this entry rendered, so only a split -> compact
                                            // transition (not first composition) hands off the detail.
                                            var settingsRenderedSplit by rememberSaveable { mutableStateOf(useSplitLayout) }
                                            LaunchedEffect(useSplitLayout) {
                                                if (settingsRenderedSplit && !useSplitLayout) {
                                                    // Window narrowed: hand the open detail to the stacked route.
                                                    // The selection is kept for when the window widens again.
                                                    val route = visibleSettingsDetail.route
                                                    if (backStack.lastOrNull() != route) {
                                                        backStack.add(route)
                                                    }
                                                }
                                                settingsRenderedSplit = useSplitLayout
                                            }

                                            fun openSettingsDetail(detail: SettingsDetail) {
                                                if (useSplitLayout) {
                                                    settingsDetail = detail
                                                } else if (backStack.lastOrNull() != detail.route) {
                                                    backStack.add(detail.route)
                                                }
                                            }
                                            val settings: @Composable () -> Unit = {
                                                SettingsScreen(
                                                    onComponentGalleryClick = {
                                                        openSettingsDetail(SettingsDetail.ComponentGallery)
                                                    },
                                                    onTextMateLanguagesClick = {
                                                        openSettingsDetail(SettingsDetail.TextMateLanguages)
                                                    },
                                                    onFeatureFlagsClick = {
                                                        openSettingsDetail(SettingsDetail.FeatureFlags)
                                                    },
                                                    onTextMateLogbookClick = {
                                                        openSettingsDetail(SettingsDetail.TextMateLogbook)
                                                    },
                                                    onDiagnosticLogsClick = {
                                                        openSettingsDetail(SettingsDetail.DiagnosticLogs)
                                                    },
                                                    onNavigateBack = { backStack.popRouteUnlessRoot() },
                                                    onServersClick = {
                                                        serversViewModel.refresh()
                                                        openSettingsDetail(SettingsDetail.Servers)
                                                    },
                                                    onAppearanceClick = {
                                                        openSettingsDetail(SettingsDetail.Appearance)
                                                    },
                                                    selectedDetail = if (useSplitLayout) visibleSettingsDetail else null,
                                                    serversSummary = serversSummary,
                                                    onClearData = settingsViewModel::clearAllData,
                                                    onClearSessionCache = settingsViewModel::clearSessionCache,
                                                    sessionCacheSizeLabel = sessionCacheSizeLabel,
                                                    onRefreshSessionCacheSize = settingsViewModel::refreshSessionCacheSize,
                                                    version = appVersion.version,
                                                    build = appVersion.build,
                                                    hideUserDirectoryPrefix =
                                                        HomeDisplayPreferences.hideUserDirectoryPrefix,
                                                    onHideUserDirectoryPrefixChange =
                                                        settingsViewModel::setHideUserDirectoryPrefix,
                                                    recentWindowHours = HomeDisplayPreferences.recentWindowHours,
                                                    onRecentWindowHoursChange =
                                                        settingsViewModel::setRecentWindowHours,
                                                    collapseConsecutiveToolCalls =
                                                        SessionChatDisplayPreferences.collapseConsecutiveToolCalls,
                                                    onCollapseConsecutiveToolCallsChange =
                                                        settingsViewModel::setCollapseConsecutiveToolCalls,
                                                    chipTimeline = SessionChatDisplayPreferences.chipTimeline,
                                                    onChipTimelineChange = settingsViewModel::setChipTimeline,
                                                    chipTimelineAvailable =
                                                        SessionChatDisplayPreferences.chipTimelineAvailable,
                                                    followUpDelivery =
                                                        SessionChatDisplayPreferences.followUpDelivery,
                                                    onFollowUpDeliveryChange =
                                                        settingsViewModel::setFollowUpDelivery,
                                                    supportsFollowUpDelivery =
                                                        settingsViewModel.supportsFollowUpDelivery(),
                                                    textMateEnabled = MarkdownHighlightPreferences.textMateEnabled,
                                                    onTextMateEnabledChange = settingsViewModel::setTextMateEnabled,
                                                    productAnalyticsConsented =
                                                        AnalyticsConsentPreferences.productAnalyticsConsented,
                                                    onProductAnalyticsConsentedChange =
                                                        settingsViewModel::setProductAnalyticsConsented,
                                                )
                                            }
                                            if (useSplitLayout) {
                                                OpenCodeSplitPane(
                                                    screen = OpenCodeSplitPaneScreen.Settings,
                                                    leading = settings,
                                                    trailing = { control ->
                                                        KeyboardShortcutHandler(
                                                            KeyboardShortcutAction.ToggleSidebar,
                                                            onShortcut = control.toggle,
                                                        )
                                                        settingsDetailContent(visibleSettingsDetail, control) {}
                                                    },
                                                )
                                            } else {
                                                settings()
                                            }
                                        }
                                    }
                                    entry<AppearanceHome> {
                                        RouteSurface {
                                            settingsDetailContent(SettingsDetail.Appearance, null) {
                                                backStack.popRouteUnlessRoot()
                                            }
                                        }
                                    }
                                    entry<ThemePickerHome> {
                                        RouteSurface {
                                            ProductRouteScreenViewEffect(
                                                screenId = ProductAnalytics.Screen.SETTINGS_THEME_PICKER,
                                            )
                                            ThemePickerScreen(
                                                onNavigateBack = { backStack.popRouteUnlessRoot() },
                                                onSelect = settingsViewModel::applyPreset,
                                            )
                                        }
                                    }
                                    entry<SyntaxPickerHome> {
                                        RouteSurface {
                                            ProductRouteScreenViewEffect(
                                                screenId = ProductAnalytics.Screen.SETTINGS_SYNTAX_PICKER,
                                            )
                                            SyntaxHighlightPickerScreen(
                                                onNavigateBack = { backStack.popRouteUnlessRoot() },
                                                onSelect = settingsViewModel::applySyntaxPreset,
                                            )
                                        }
                                    }
                                    entry<ServersHome> {
                                        RouteSurface {
                                            settingsDetailContent(SettingsDetail.Servers, null) {
                                                backStack.popRouteUnlessRoot()
                                            }
                                        }
                                    }
                                    entry<ConnectionSetupHome> {
                                        RouteSurface {
                                            ProductRouteScreenViewEffect(
                                                screenId = ProductAnalytics.Screen.CONNECTION_SETUP,
                                            )
                                            ConnectionSetupScreen(
                                                uiState = setupUiState,
                                                onNavigateBack = { backStack.popRouteUnlessRoot() },
                                                onNameChanged = setupViewModel::onNameChanged,
                                                onBaseUrlChanged = setupViewModel::onBaseUrlChanged,
                                                onUsernameChanged = setupViewModel::onUsernameChanged,
                                                onPasswordChanged = setupViewModel::onPasswordChanged,
                                                onCredentialSegmentChanged =
                                                    setupViewModel::onCredentialSegmentChanged,
                                                onPairingCodeChanged = setupViewModel::onPairingCodeChanged,
                                                onPasswordFocusConsumed =
                                                    setupViewModel::consumePasswordFocusRequest,
                                                onSubmit = {
                                                    setupViewModel.submit {
                                                        serversViewModel.refresh()
                                                        settingsViewModel.refreshServersSummary()
                                                        backStack.popRouteUnlessRoot()
                                                    }
                                                },
                                                onSnackbarDismissed = setupViewModel::consumeSnackbar,
                                                onPairingQRScanned = setupViewModel::applyPairingQR,
                                                onPairingQRCameraDenied =
                                                    setupViewModel::pairingQRCameraDenied,
                                                onPairingQRCameraUnavailable =
                                                    setupViewModel::pairingQRCameraUnavailable,
                                                onShowPairingCodeForm = setupViewModel::showPairingCodeForm,
                                                onShowHostPasswordForm = setupViewModel::showHostPasswordForm,
                                                onShowScanInstructions = setupViewModel::showScanInstructions,
                                                onRemove = {
                                                    setupViewModel.removeConnection {
                                                        serversViewModel.refresh()
                                                        settingsViewModel.refreshServersSummary()
                                                        backStack.popRouteUnlessRoot()
                                                    }
                                                },
                                            )
                                        }
                                    }
                                    entry<TextMateLanguagesHome> {
                                        RouteSurface {
                                            settingsDetailContent(SettingsDetail.TextMateLanguages, null) {
                                                backStack.popRouteUnlessRoot()
                                            }
                                        }
                                    }
                                    entry<FeatureFlagsHome> {
                                        RouteSurface {
                                            settingsDetailContent(SettingsDetail.FeatureFlags, null) {
                                                backStack.popRouteUnlessRoot()
                                            }
                                        }
                                    }
                                    entry<TextMateLogbookHome> {
                                        RouteSurface {
                                            settingsDetailContent(SettingsDetail.TextMateLogbook, null) {
                                                backStack.popRouteUnlessRoot()
                                            }
                                        }
                                    }
                                    entry<DiagnosticLogsHome> {
                                        RouteSurface {
                                            settingsDetailContent(SettingsDetail.DiagnosticLogs, null) {
                                                backStack.popRouteUnlessRoot()
                                            }
                                        }
                                    }
                                    entry<ComponentGalleryHome> {
                                        RouteSurface {
                                            settingsDetailContent(SettingsDetail.ComponentGallery, null) {
                                                backStack.popRouteUnlessRoot()
                                            }
                                        }
                                    }
                                    entry<ComponentGalleryDetail> { key ->
                                        RouteSurface(edgeToEdgeBottom = key.entryId.usesEdgeToEdgeBottomChrome()) {
                                            val entry = ComponentGalleryEntries.first { it.id == key.entryId }
                                            if (key.entryId == ComponentGalleryEntryId.MessageList) {
                                                MessageListGalleryDetailScreen(
                                                    onNavigateBack = { backStack.popRouteUnlessRoot() },
                                                )
                                            } else {
                                                ComponentGalleryDetailScreen(
                                                    entry = entry,
                                                    themeMode = themeMode,
                                                    onThemeModeChange = { themeMode = it },
                                                    onNavigateBack = { backStack.popRouteUnlessRoot() },
                                                ) {
                                                    when (key.entryId) {
                                                        ComponentGalleryEntryId.Colors -> ColorGalleryScreen()
                                                        ComponentGalleryEntryId.Typography -> TypographyGalleryScreen()
                                                        ComponentGalleryEntryId.Icons -> IconsGalleryScreen()
                                                        ComponentGalleryEntryId.SkeletonIllustration ->
                                                            SkeletonIllustrationGalleryScreen()
                                                        ComponentGalleryEntryId.PageIndicator -> PageIndicatorGalleryScreen()
                                                        ComponentGalleryEntryId.BottomFade -> BottomFadeGalleryScreen()
                                                        ComponentGalleryEntryId.Tag -> TagGalleryScreen()
                                                        ComponentGalleryEntryId.KeyValueRow -> KeyValueRowGalleryScreen()
                                                        ComponentGalleryEntryId.Attachment -> AttachmentGalleryScreen()
                                                        ComponentGalleryEntryId.OutgoingMessage -> OutgoingMessageGalleryScreen()
                                                        ComponentGalleryEntryId.PendingPromptsStrip ->
                                                            PendingPromptsStripGalleryScreen()
                                                        ComponentGalleryEntryId.AssistantMessage -> AssistantMessageGalleryScreen()
                                                        ComponentGalleryEntryId.ToolCallCard -> ToolCallCardGalleryScreen()
                                                        ComponentGalleryEntryId.ToolChip -> ToolChipGalleryScreen()
                                                        ComponentGalleryEntryId.MessageNote -> MessageNoteGalleryScreen()
                                                        ComponentGalleryEntryId.ChildSessionRow -> ChildSessionRowGalleryScreen()
                                                        ComponentGalleryEntryId.WorkingIndicator ->
                                                            WorkingIndicatorGalleryScreen()
                                                        ComponentGalleryEntryId.ReasoningRow -> ReasoningRowGalleryScreen()
                                                        ComponentGalleryEntryId.TopBar -> TopBarGalleryScreen()
                                                        ComponentGalleryEntryId.SplitPane -> SplitPaneGalleryScreen()
                                                        ComponentGalleryEntryId.OverflowMenu -> OverflowMenuGalleryScreen()
                                                        ComponentGalleryEntryId.BottomSheet -> BottomSheetGalleryScreen()
                                                        ComponentGalleryEntryId.Dialog -> DialogGalleryScreen()
                                                        ComponentGalleryEntryId.Modal -> ModalGalleryScreen()
                                                        ComponentGalleryEntryId.Snackbar -> SnackbarGalleryScreen()
                                                        ComponentGalleryEntryId.ListItem -> ListItemGalleryScreen()
                                                        ComponentGalleryEntryId.SelectableCard ->
                                                            SelectableCardGalleryScreen()
                                                        ComponentGalleryEntryId.ListGroup -> ListGroupGalleryScreen()
                                                        ComponentGalleryEntryId.SectionHeader -> SectionHeaderGalleryScreen()
                                                        ComponentGalleryEntryId.ReorderableList -> ReorderableListGalleryScreen()
                                                        ComponentGalleryEntryId.ChoiceBadge -> ChoiceBadgeGalleryScreen()
                                                        ComponentGalleryEntryId.FilterTabs -> FilterTabsGalleryScreen()
                                                        ComponentGalleryEntryId.ActionPill -> ActionPillGalleryScreen()
                                                        ComponentGalleryEntryId.ActionTile -> ActionTileGalleryScreen()
                                                        ComponentGalleryEntryId.SyncingPill -> SyncingPillGalleryScreen()
                                                        ComponentGalleryEntryId.ComposerSuggestionOverlay ->
                                                            ComposerSuggestionOverlayGalleryScreen()
                                                        ComponentGalleryEntryId.ReviewChangesPill -> ReviewChangesPillGalleryScreen()
                                                        ComponentGalleryEntryId.SessionBranchPill ->
                                                            SessionBranchPillGalleryScreen()
                                                        ComponentGalleryEntryId.SessionTodosPill -> SessionTodosPillGalleryScreen()
                                                        ComponentGalleryEntryId.SessionTodoItem -> SessionTodoItemGalleryScreen()
                                                        ComponentGalleryEntryId.HunkNavigationPill -> HunkNavigationPillGalleryScreen()
                                                        ComponentGalleryEntryId.UnifiedDiff -> UnifiedDiffGalleryScreen()
                                                        ComponentGalleryEntryId.Button -> ButtonGalleryScreen()
                                                        ComponentGalleryEntryId.TextInput -> TextInputGalleryScreen()
                                                        ComponentGalleryEntryId.ServerAddressInput ->
                                                            ServerAddressInputGalleryScreen()
                                                        ComponentGalleryEntryId.EmptyState ->
                                                            EmptyStateGalleryScreen()
                                                        ComponentGalleryEntryId.StatusStack ->
                                                            StatusStackGalleryScreen()
                                                        ComponentGalleryEntryId.DiffStat -> DiffStatGalleryScreen()
                                                        ComponentGalleryEntryId.InlineNotice ->
                                                            InlineNoticeGalleryScreen()
                                                        ComponentGalleryEntryId.CodeSnippet ->
                                                            CodeSnippetGalleryScreen()
                                                        ComponentGalleryEntryId.SessionList ->
                                                            SessionListGalleryScreen()
                                                        ComponentGalleryEntryId.SessionListDock ->
                                                            SessionListDockGalleryScreen()
                                                        ComponentGalleryEntryId.SessionStatusBar ->
                                                            SessionStatusBarGalleryScreen()
                                                        ComponentGalleryEntryId.SessionComposer ->
                                                            SessionComposerGalleryScreen()
                                                        ComponentGalleryEntryId.RenderedMarkdown ->
                                                            RenderedMarkdownGalleryScreen()
                                                        ComponentGalleryEntryId.MessageList -> Unit
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    entry<TerminalsCatalog> {
                                        RouteSurface {
                                            ProductRouteScreenViewEffect(
                                                screenId = ProductAnalytics.Screen.TERMINALS_CATALOG,
                                            )
                                            TerminalsCatalogScreen(
                                                onNavigateBack = { backStack.popRouteUnlessRoot() },
                                                onOpenTerminal = { worktree, projectTitle, ptyId ->
                                                    backStack.add(
                                                        Terminal(
                                                            worktree = worktree,
                                                            projectTitle = projectTitle,
                                                            ptyId = ptyId,
                                                        ),
                                                    )
                                                },
                                            )
                                        }
                                    }
                                    chatFlowEntries(rootChatFlowNavigator)
                                },
                        )
                }
            }
        }
    }
}

@Suppress("ktlint:standard:property-naming")
private const val RouteTransitionDurationMillis = 220

internal fun forwardRouteTransition() =
    (
        slideInHorizontally(
            animationSpec = tween(RouteTransitionDurationMillis),
            initialOffsetX = { it / 5 },
        ) + fadeIn(animationSpec = tween(RouteTransitionDurationMillis))
    ) togetherWith (
        slideOutHorizontally(
            animationSpec = tween(RouteTransitionDurationMillis),
            targetOffsetX = { -it / 8 },
        ) + fadeOut(animationSpec = tween(RouteTransitionDurationMillis))
    )

internal fun backwardRouteTransition() =
    (
        slideInHorizontally(
            animationSpec = tween(RouteTransitionDurationMillis),
            initialOffsetX = { -it / 8 },
        ) + fadeIn(animationSpec = tween(RouteTransitionDurationMillis))
    ) togetherWith (
        slideOutHorizontally(
            animationSpec = tween(RouteTransitionDurationMillis),
            targetOffsetX = { it / 5 },
        ) + fadeOut(animationSpec = tween(RouteTransitionDurationMillis))
    )

@Composable
internal fun RouteSurface(
    edgeToEdgeBottom: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxSize()
                .then(if (edgeToEdgeBottom) Modifier else Modifier.navigationBarsPadding()),
        color = MaterialTheme.colorScheme.background,
        content = content,
    )
}

private fun ComponentGalleryEntryId.usesEdgeToEdgeBottomChrome(): Boolean =
    this == ComponentGalleryEntryId.SessionList ||
        this == ComponentGalleryEntryId.SessionListDock ||
        this == ComponentGalleryEntryId.SessionComposer

private fun SessionOpenTarget.toSessionChatRoute(): SessionChat =
    SessionChat(
        sessionId = sessionId,
        sessionTitle = title,
        worktree = worktree,
        optimisticNavigationInstanceId = optimisticNavigationInstanceId,
    )

/** Stacked route that shows a Settings detail on compact width. */
private val SettingsDetail.route: NavKey
    get() =
        when (this) {
            SettingsDetail.Servers -> ServersHome
            SettingsDetail.Appearance -> AppearanceHome
            SettingsDetail.ComponentGallery -> ComponentGalleryHome
            SettingsDetail.TextMateLanguages -> TextMateLanguagesHome
            SettingsDetail.TextMateLogbook -> TextMateLogbookHome
            SettingsDetail.FeatureFlags -> FeatureFlagsHome
            SettingsDetail.DiagnosticLogs -> DiagnosticLogsHome
        }

/**
 * Hands the Home split pane's screens to the stacked routes when the window narrows, and returns
 * whether the split must still be composed. Navigation3 only clears the ViewModels of entries it
 * sees leave the stack while composed, so the pane stays until it has recomposed with its reset
 * stack instead of disappearing in the same frame and keeping the handed-off ViewModels.
 */
@Composable
internal fun rememberHomeSessionPaneHandoff(
    useSplitLayout: Boolean,
    paneStack: MutableList<NavKey>,
    onHandOff: (List<NavKey>) -> Unit,
): Boolean {
    val currentOnHandOff by rememberUpdatedState(onHandOff)
    var handoffPending by remember { mutableStateOf(false) }
    LaunchedEffect(useSplitLayout) {
        if (!useSplitLayout && paneStack.paneSelection != null) {
            handoffPending = true
            currentOnHandOff(paneStack.handOffPane())
        }
    }
    val hasSelection = paneStack.paneSelection != null
    if (handoffPending && !hasSelection) {
        // Runs after the composition that recomposed the pane with its reset stack.
        SideEffect { handoffPending = false }
    }
    return useSplitLayout || hasSelection || handoffPending
}

/**
 * Trailing pane of the Home split view: a nested [NavDisplay] over [paneStack], so screens
 * opened from the selected session stay inside the pane. Each entry gets its own ViewModelStore,
 * cleared when the entry leaves the pane stack, matching how a popped root route behaves.
 */
@Composable
private fun HomeSessionPane(
    paneStack: NavBackStack<NavKey>,
    detailControl: OpenCodeSplitPaneDetailControl,
) {
    // Entries are built once per stack change, so they read the live control through state.
    val currentDetailControl by rememberUpdatedState(detailControl)
    val navigator = remember(paneStack) { paneStack.paneChatFlowNavigator() }
    NavDisplay(
        backStack = paneStack,
        onBack = { paneStack.popRouteUnlessRoot() },
        entryDecorators = rememberMainNavEntryDecorators(),
        transitionSpec = {
            // A new pane root is a selection change, not a push.
            if (targetState.previousEntries.isEmpty()) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                forwardRouteTransition()
            }
        },
        popTransitionSpec = { backwardRouteTransition() },
        predictivePopTransitionSpec = { backwardRouteTransition() },
        entryProvider =
            entryProvider {
                chatFlowEntries(
                    navigator = navigator,
                    sessionChatDetailControl = { key ->
                        currentDetailControl.takeIf { paneStack.firstOrNull() == key }
                    },
                )
                entry<HomeSessionPanePlaceholder> {
                    RouteSurface(edgeToEdgeBottom = true) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            OpenCodeTopBar(leading = openCodeDetailLeading(currentDetailControl) {})
                            OpenCodeEmptyState(
                                title = "No session selected",
                                message = "Choose a session from the list",
                                icon = OpenCodeIcons.List,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .navigationBarsPadding(),
                            )
                        }
                    }
                }
            },
    )
}
