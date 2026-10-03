package dev.opencode.mobile

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.opencode.mobile.domain.app.AppVersion
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.preferences.AnalyticsConsentPreferences
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.preferences.MarkdownHighlightPreferences
import dev.opencode.mobile.domain.preferences.SessionChatDisplayPreferences
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.changes.ChangeSource
import dev.opencode.mobile.ui.changes.ChangesScreen
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryDetailScreen
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryEntries
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryEntryId
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryScreen
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryThemeMode
import dev.opencode.mobile.ui.componentgallery.components.ActionPillGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ActionTileGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.AssistantMessageGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.AttachmentGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.BottomSheetGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ButtonGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ChildSessionRowGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ChoiceBadgeGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ComposerSuggestionOverlayGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.DialogGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.EmptyStateGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.FilterTabsGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.HunkNavigationPillGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.IconsGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ListGroupGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ListItemGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.MessageListGalleryDetailScreen
import dev.opencode.mobile.ui.componentgallery.components.MessageNoteGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.OutgoingMessageGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.OverflowMenuGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.PendingPromptsStripGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.RenderedMarkdownGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ReorderableListGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ReviewChangesPillGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SectionHeaderGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ServerAddressInputGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SessionBranchPillGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SessionComposerGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SessionListDockGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SessionListGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SessionStatusBarGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SessionTodoItemGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SessionTodosPillGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ModalGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.PageIndicatorGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SkeletonIllustrationGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SnackbarGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.StatusStackGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SyncingPillGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.TextInputGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ToolCallCardGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ToolChipGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.TopBarGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.UnifiedDiffGalleryScreen
import dev.opencode.mobile.ui.componentgallery.foundation.ColorGalleryScreen
import dev.opencode.mobile.ui.componentgallery.foundation.TypographyGalleryScreen
import dev.opencode.mobile.ui.components.OpenCodeStatusStack
import dev.opencode.mobile.ui.connection.ConnectionSetupMode
import dev.opencode.mobile.ui.connection.ConnectionSetupScreen
import dev.opencode.mobile.ui.connection.ConnectionSetupViewModel
import dev.opencode.mobile.ui.main.MainScreen
import dev.opencode.mobile.ui.onboarding.AppOnboardingScreen
import dev.opencode.mobile.ui.root.AppRootState
import dev.opencode.mobile.ui.root.AppRootViewModel
import dev.opencode.mobile.ui.sessionchat.SessionChatScreen
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolDetailSheet
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolDetailTarget
import dev.opencode.mobile.ui.sessionchat.view.SessionChatToolGroupScreen
import dev.opencode.mobile.ui.sessionchat.view.toTarget
import dev.opencode.mobile.ui.sessioncontext.SessionContextScreen
import dev.opencode.mobile.ui.settings.AppearanceSettingsScreen
import dev.opencode.mobile.ui.settings.DiagnosticLogsScreen
import dev.opencode.mobile.ui.settings.DiagnosticLogsViewModel
import dev.opencode.mobile.ui.settings.ServersScreen
import dev.opencode.mobile.ui.settings.ServersViewModel
import dev.opencode.mobile.ui.settings.SettingsScreen
import dev.opencode.mobile.ui.settings.SettingsViewModel
import dev.opencode.mobile.ui.settings.SyntaxHighlightPickerScreen
import dev.opencode.mobile.ui.settings.FeatureFlagsRoute
import dev.opencode.mobile.ui.settings.TextMateLanguagesRoute
import dev.opencode.mobile.ui.settings.TextMateLogbookScreen
import dev.opencode.mobile.ui.settings.TextMateLogbookViewModel
import dev.opencode.mobile.ui.settings.ThemePickerScreen
import dev.opencode.mobile.ui.terminal.TerminalScreen
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
                                            MainScreen(
                                                onSettingsClick = { backStack.add(SettingsHome) },
                                                onTerminalClick = { backStack.add(TerminalsCatalog) },
                                                onSessionClick = { target ->
                                                    backStack.add(
                                                        SessionChat(
                                                            sessionId = target.sessionId,
                                                            sessionTitle = target.title,
                                                            worktree = target.worktree,
                                                            optimisticNavigationInstanceId = target.optimisticNavigationInstanceId,
                                                        ),
                                                    )
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
                                            )
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
                                            SettingsScreen(
                                                onComponentGalleryClick = { backStack.add(ComponentGalleryHome) },
                                                onTextMateLanguagesClick = { backStack.add(TextMateLanguagesHome) },
                                                onFeatureFlagsClick = { backStack.add(FeatureFlagsHome) },
                                                onTextMateLogbookClick = { backStack.add(TextMateLogbookHome) },
                                                onDiagnosticLogsClick = { backStack.add(DiagnosticLogsHome) },
                                                onNavigateBack = { backStack.popRouteUnlessRoot() },
                                                onServersClick = {
                                                    serversViewModel.refresh()
                                                    if (backStack.lastOrNull() != ServersHome) {
                                                        backStack.add(ServersHome)
                                                    }
                                                },
                                                onAppearanceClick = {
                                                    if (backStack.lastOrNull() != AppearanceHome) {
                                                        backStack.add(AppearanceHome)
                                                    }
                                                },
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
                                                sessionListProjectPickerEnabled =
                                                    HomeDisplayPreferences.sessionListProjectPickerEnabled,
                                                onSessionListProjectPickerEnabledChange =
                                                    settingsViewModel::setSessionListProjectPickerEnabled,
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
                                    }
                                    entry<AppearanceHome> {
                                        RouteSurface {
                                            ProductRouteScreenViewEffect(
                                                screenId = ProductAnalytics.Screen.SETTINGS_APPEARANCE,
                                            )
                                            AppearanceSettingsScreen(
                                                onNavigateBack = { backStack.popRouteUnlessRoot() },
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
                                            LaunchedEffect(Unit) {
                                                serversViewModel.refresh()
                                            }
                                            ServersScreen(
                                                uiState = serversUiState,
                                                onNavigateBack = { backStack.popRouteUnlessRoot() },
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
                                                onCredentialsExpandedChanged =
                                                    setupViewModel::onCredentialsExpandedChanged,
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
                                            TextMateLanguagesRoute(
                                                onNavigateBack = { backStack.popRouteUnlessRoot() },
                                            )
                                        }
                                    }
                                    entry<FeatureFlagsHome> {
                                        RouteSurface {
                                            FeatureFlagsRoute(
                                                onNavigateBack = { backStack.popRouteUnlessRoot() },
                                            )
                                        }
                                    }
                                    entry<TextMateLogbookHome> {
                                        RouteSurface {
                                            val logbookViewModel: TextMateLogbookViewModel = hiltViewModel()
                                            val logbookState by logbookViewModel.state.collectAsStateWithLifecycle()
                                            TextMateLogbookScreen(
                                                state = logbookState,
                                                onNavigateBack = { backStack.popRouteUnlessRoot() },
                                                onRefresh = logbookViewModel::refresh,
                                                onClear = logbookViewModel::clear,
                                            )
                                        }
                                    }
                                    entry<DiagnosticLogsHome> {
                                        RouteSurface {
                                            val diagnosticLogsViewModel: DiagnosticLogsViewModel = hiltViewModel()
                                            val diagnosticLogsState by
                                                diagnosticLogsViewModel.state.collectAsStateWithLifecycle()
                                            val context = LocalContext.current
                                            DiagnosticLogsScreen(
                                                state = diagnosticLogsState,
                                                onNavigateBack = { backStack.popRouteUnlessRoot() },
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
                                    entry<ComponentGalleryHome> {
                                        RouteSurface {
                                            ComponentGalleryScreen(
                                                onEntryClick = { entryId -> backStack.add(ComponentGalleryDetail(entryId)) },
                                                onNavigateBack = { backStack.popRouteUnlessRoot() },
                                            )
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
                                                        ComponentGalleryEntryId.Attachment -> AttachmentGalleryScreen()
                                                        ComponentGalleryEntryId.OutgoingMessage -> OutgoingMessageGalleryScreen()
                                                        ComponentGalleryEntryId.PendingPromptsStrip ->
                                                            PendingPromptsStripGalleryScreen()
                                                        ComponentGalleryEntryId.AssistantMessage -> AssistantMessageGalleryScreen()
                                                        ComponentGalleryEntryId.ToolCallCard -> ToolCallCardGalleryScreen()
                                                        ComponentGalleryEntryId.ToolChip -> ToolChipGalleryScreen()
                                                        ComponentGalleryEntryId.MessageNote -> MessageNoteGalleryScreen()
                                                        ComponentGalleryEntryId.ChildSessionRow -> ChildSessionRowGalleryScreen()
                                                        ComponentGalleryEntryId.TopBar -> TopBarGalleryScreen()
                                                        ComponentGalleryEntryId.OverflowMenu -> OverflowMenuGalleryScreen()
                                                        ComponentGalleryEntryId.BottomSheet -> BottomSheetGalleryScreen()
                                                        ComponentGalleryEntryId.Dialog -> DialogGalleryScreen()
                                                        ComponentGalleryEntryId.Modal -> ModalGalleryScreen()
                                                        ComponentGalleryEntryId.Snackbar -> SnackbarGalleryScreen()
                                                        ComponentGalleryEntryId.ListItem -> ListItemGalleryScreen()
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
                                    entry<Terminal> { key ->
                                        RouteSurface {
                                            ProductRouteScreenViewEffect(
                                                screenId = ProductAnalytics.Screen.TERMINAL,
                                            )
                                            TerminalScreen(
                                                worktree = key.worktree,
                                                projectTitle = key.projectTitle,
                                                ptyId = key.ptyId,
                                                onNavigateBack = { backStack.popRouteUnlessRoot() },
                                            )
                                        }
                                    }
                                    entry<SessionChat> { key ->
                                        RouteSurface(edgeToEdgeBottom = true) {
                                            ProductRouteScreenViewEffect(
                                                screenId = ProductAnalytics.Screen.SESSION_CHAT,
                                            )
                                            SessionChatScreen(
                                                sessionId = key.sessionId,
                                                sessionTitle = key.sessionTitle,
                                                worktree = key.worktree,
                                                optimisticNavigationInstanceId = key.optimisticNavigationInstanceId,
                                                parentId = key.parentId,
                                                onNavigateBack = { backStack.popRouteUnlessRoot() },
                                                onSessionContextClick = {
                                                    backStack.add(SessionContext(key.sessionId, key.worktree))
                                                },
                                                onReviewChangesClick = { target ->
                                                    backStack.add(
                                                        Changes(
                                                            target.sessionId,
                                                            target.worktree,
                                                            target.messageId,
                                                            target.initialSource,
                                                            target.initialFilePath,
                                                        ),
                                                    )
                                                },
                                                onChildSessionClick = { target ->
                                                    backStack.add(
                                                        SessionChat(
                                                            sessionId = target.sessionId,
                                                            sessionTitle = target.sessionTitle,
                                                            worktree = target.worktree,
                                                            optimisticNavigationInstanceId = target.optimisticNavigationInstanceId,
                                                            parentId = key.sessionId,
                                                        ),
                                                    )
                                                },
                                                onToolGroupClick = { target ->
                                                    backStack.add(target)
                                                },
                                                onTerminalRequested = { worktree, projectTitle ->
                                                    backStack.add(
                                                        Terminal(
                                                            worktree = worktree,
                                                            projectTitle = projectTitle,
                                                        ),
                                                    )
                                                },
                                                onSessionReplaceRequested = { target ->
                                                    backStack.popRouteUnlessRoot()
                                                    backStack.add(
                                                        SessionChat(
                                                            sessionId = target.sessionId,
                                                            sessionTitle = target.sessionTitle,
                                                            worktree = target.worktree,
                                                            optimisticNavigationInstanceId = target.optimisticNavigationInstanceId,
                                                        ),
                                                    )
                                                },
                                            )
                                        }
                                    }
                                    entry<SessionContext> { key ->
                                        RouteSurface {
                                            ProductRouteScreenViewEffect(
                                                screenId = ProductAnalytics.Screen.SESSION_CONTEXT,
                                            )
                                            SessionContextScreen(
                                                sessionId = key.sessionId,
                                                worktree = key.worktree,
                                                onNavigateBack = { backStack.popRouteUnlessRoot() },
                                            )
                                        }
                                    }
                                    entry<Changes> { key ->
                                        RouteSurface {
                                            ChangesScreen(
                                                sessionId = key.sessionId,
                                                worktree = key.worktree,
                                                messageId = key.messageId,
                                                initialSource = key.initialSource,
                                                initialFilePath = key.initialFilePath,
                                                onNavigateBack = { backStack.popRouteUnlessRoot() },
                                            )
                                        }
                                    }
                                    entry<SessionToolGroup> { key ->
                                        RouteSurface {
                                            var toolDetailTarget by remember {
                                                mutableStateOf<SessionChatToolDetailTarget?>(null)
                                            }
                                            SessionChatToolGroupScreen(
                                                target = key.toTarget(),
                                                supportsSessionMessageDiff = key.supportsSessionMessageDiff,
                                                onNavigateBack = { backStack.popRouteUnlessRoot() },
                                                onToolDetailTap = {
                                                    ProductAnalytics.logUserAction(
                                                        screenId = ProductAnalytics.Screen.SESSION_CHAT,
                                                        actionId = ProductAnalytics.Action.CHAT_OPEN_TOOL_DETAIL,
                                                    )
                                                    toolDetailTarget = it
                                                },
                                                onChildSessionTap = { childSessionId, childTitle ->
                                                    ProductAnalytics.logUserAction(
                                                        screenId = ProductAnalytics.Screen.SESSION_CHAT,
                                                        actionId = ProductAnalytics.Action.CHAT_OPEN_CHILD_SESSION,
                                                    )
                                                    backStack.add(
                                                        SessionChat(
                                                            sessionId = childSessionId,
                                                            sessionTitle = childTitle,
                                                            worktree = key.worktree,
                                                            parentId = key.sessionId,
                                                        ),
                                                    )
                                                },
                                                onChangesReviewTap = changesReview@{ _, initialFilePath ->
                                                    val owningTurn = key.owningTurnMessageId
                                                    if (!key.supportsSessionMessageDiff || owningTurn == null) {
                                                        return@changesReview
                                                    }
                                                    ProductAnalytics.logUserAction(
                                                        screenId = ProductAnalytics.Screen.SESSION_CHAT,
                                                        actionId = ProductAnalytics.Action.CHAT_OPEN_CHANGES_FROM_TOOL,
                                                    )
                                                    backStack.add(
                                                        Changes(
                                                            sessionId = key.sessionId,
                                                            worktree = key.worktree,
                                                            messageId = owningTurn,
                                                            initialSource = ChangeSource.LastTurn,
                                                            initialFilePath = initialFilePath,
                                                        ),
                                                    )
                                                },
                                            )
                                            SessionChatToolDetailSheet(
                                                target = toolDetailTarget,
                                                onDismiss = { toolDetailTarget = null },
                                            )
                                        }
                                    }
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
private fun RouteSurface(
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
