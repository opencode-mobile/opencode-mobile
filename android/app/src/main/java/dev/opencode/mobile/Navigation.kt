package dev.opencode.mobile

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.changes.ChangesScreen
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryDetailScreen
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryEntries
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryEntryId
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryScreen
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryThemeMode
import dev.opencode.mobile.ui.componentgallery.components.ActionPillGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ComposerSuggestionOverlayGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.AttachmentGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.BottomSheetGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ButtonGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ChoiceBadgeGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.FilterTabsGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.DialogGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SnackbarGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.HunkNavigationPillGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.IconsGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ListGroupGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ListItemGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.MessageListGalleryDetailScreen
import dev.opencode.mobile.ui.componentgallery.components.OutgoingMessageGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.OverflowMenuGalleryScreen
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.preferences.MarkdownHighlightPreferences
import dev.opencode.mobile.ui.componentgallery.components.ProjectSessionSelectorGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SessionListDockGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SessionListGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.RenderedMarkdownGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ReviewChangesPillGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SessionComposerGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.SessionStatusBarGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.TextInputGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ToolCallCardGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.TopBarGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.UnifiedDiffGalleryScreen
import dev.opencode.mobile.ui.componentgallery.foundation.ColorGalleryScreen
import dev.opencode.mobile.ui.componentgallery.foundation.TypographyGalleryScreen
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetContentMode
import dev.opencode.mobile.ui.connection.ConnectionSetupSheetContent
import dev.opencode.mobile.ui.connection.ConnectionSetupViewModel
import dev.opencode.mobile.ui.main.MainScreen
import dev.opencode.mobile.ui.root.AppRootState
import dev.opencode.mobile.ui.root.AppRootViewModel
import dev.opencode.mobile.ui.sessionchat.SessionChatScreen
import dev.opencode.mobile.ui.sessioncontext.SessionContextScreen
import dev.opencode.mobile.ui.settings.SettingsScreen
import dev.opencode.mobile.ui.settings.SettingsViewModel
import dev.opencode.mobile.ui.settings.TextMateLogbookScreen
import dev.opencode.mobile.ui.settings.TextMateLogbookViewModel

@Composable
fun MainNavigation() {
    val rootViewModel: AppRootViewModel = hiltViewModel()
    val rootState by rootViewModel.state.collectAsStateWithLifecycle()
    var connectionSetupVisible by rememberSaveable { mutableStateOf(false) }
    val setupViewModel: ConnectionSetupViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()

    fun presentConnectionSetup(initialProfile: ConnectionProfile?) {
        setupViewModel.configure(initialProfile)
        connectionSetupVisible = true
    }

    val setupUiState by setupViewModel.uiState.collectAsStateWithLifecycle()

    val backStack = rememberNavBackStack(ProjectListHome)
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
                    AppRootState.Loading ->
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    AppRootState.Home ->
                        NavDisplay(
                            modifier =
                                Modifier
                                    .systemBarsPadding()
                                    .displayCutoutPadding(),
                            backStack = backStack,
                            onBack = { backStack.removeLastOrNull() },
                            transitionSpec = { forwardRouteTransition() },
                            popTransitionSpec = { backwardRouteTransition() },
                            predictivePopTransitionSpec = { backwardRouteTransition() },
                            entryProvider =
                                entryProvider {
                                    entry<ProjectListHome> {
                                        RouteSurface {
                                            MainScreen(
                                                onSettingsClick = { backStack.add(SettingsHome) },
                                                onSessionClick = { target ->
                                                    backStack.add(SessionChat(target.sessionId, target.title, target.worktree))
                                                },
                                                onEditConnectionClick = { profile ->
                                                    presentConnectionSetup(initialProfile = profile)
                                                },
                                                onConnectClick = { presentConnectionSetup(initialProfile = null) },
                                            )
                                        }
                                    }
                                    entry<SettingsHome> {
                                        RouteSurface {
                                            SettingsScreen(
                                                onComponentGalleryClick = { backStack.add(ComponentGalleryHome) },
                                                onTextMateLogbookClick = { backStack.add(TextMateLogbookHome) },
                                                onNavigateBack = { backStack.removeLastOrNull() },
                                                onConnectionSetup = {
                                                    setupViewModel.configureFromCurrentProfile()
                                                    connectionSetupVisible = true
                                                },
                                                onClearData = settingsViewModel::clearAllData,
                                                onImportTheme = settingsViewModel::importThemeFromUri,
                                                onApplyPreset = settingsViewModel::applyPreset,
                                                showSessionListOnHome = HomeDisplayPreferences.showSessionListOnHome,
                                                onShowSessionListOnHomeChange =
                                                    settingsViewModel::setShowSessionListOnHome,
                                                textMateEnabled = MarkdownHighlightPreferences.textMateEnabled,
                                                onTextMateEnabledChange = settingsViewModel::setTextMateEnabled,
                                            )
                                        }
                                    }
                                    entry<TextMateLogbookHome> {
                                        RouteSurface {
                                            val logbookViewModel: TextMateLogbookViewModel = hiltViewModel()
                                            val logbookState by logbookViewModel.state.collectAsStateWithLifecycle()
                                            TextMateLogbookScreen(
                                                state = logbookState,
                                                onNavigateBack = { backStack.removeLastOrNull() },
                                                onRefresh = logbookViewModel::refresh,
                                                onClear = logbookViewModel::clear,
                                            )
                                        }
                                    }
                                    entry<ComponentGalleryHome> {
                                        RouteSurface {
                                            ComponentGalleryScreen(
                                                onEntryClick = { entryId -> backStack.add(ComponentGalleryDetail(entryId)) },
                                                onNavigateBack = { backStack.removeLastOrNull() },
                                            )
                                        }
                                    }
                                    entry<ComponentGalleryDetail> { key ->
                                        RouteSurface {
                                            val entry = ComponentGalleryEntries.first { it.id == key.entryId }
                                            if (key.entryId == ComponentGalleryEntryId.MessageList) {
                                                MessageListGalleryDetailScreen(
                                                    onNavigateBack = { backStack.removeLastOrNull() },
                                                )
                                            } else {
                                                ComponentGalleryDetailScreen(
                                                    entry = entry,
                                                    themeMode = themeMode,
                                                    onThemeModeChange = { themeMode = it },
                                                    onNavigateBack = { backStack.removeLastOrNull() },
                                                ) {
                                                    when (key.entryId) {
                                                        ComponentGalleryEntryId.Colors -> ColorGalleryScreen()
                                                        ComponentGalleryEntryId.Typography -> TypographyGalleryScreen()
                                                        ComponentGalleryEntryId.Icons -> IconsGalleryScreen()
                                                        ComponentGalleryEntryId.Attachment -> AttachmentGalleryScreen()
                                                        ComponentGalleryEntryId.OutgoingMessage -> OutgoingMessageGalleryScreen()
                                                        ComponentGalleryEntryId.ToolCallCard -> ToolCallCardGalleryScreen()
                                                        ComponentGalleryEntryId.TopBar -> TopBarGalleryScreen()
                                                        ComponentGalleryEntryId.OverflowMenu -> OverflowMenuGalleryScreen()
                                                        ComponentGalleryEntryId.BottomSheet -> BottomSheetGalleryScreen()
                                                        ComponentGalleryEntryId.Dialog -> DialogGalleryScreen()
                                                        ComponentGalleryEntryId.Snackbar -> SnackbarGalleryScreen()
                                                        ComponentGalleryEntryId.ListItem -> ListItemGalleryScreen()
                                                        ComponentGalleryEntryId.ListGroup -> ListGroupGalleryScreen()
                                                        ComponentGalleryEntryId.ChoiceBadge -> ChoiceBadgeGalleryScreen()
                                                        ComponentGalleryEntryId.FilterTabs -> FilterTabsGalleryScreen()
                                                        ComponentGalleryEntryId.ActionPill -> ActionPillGalleryScreen()
                                                        ComponentGalleryEntryId.ComposerSuggestionOverlay ->
                                                            ComposerSuggestionOverlayGalleryScreen()
                                                        ComponentGalleryEntryId.ReviewChangesPill -> ReviewChangesPillGalleryScreen()
                                                        ComponentGalleryEntryId.HunkNavigationPill -> HunkNavigationPillGalleryScreen()
                                                        ComponentGalleryEntryId.UnifiedDiff -> UnifiedDiffGalleryScreen()
                                                        ComponentGalleryEntryId.Button -> ButtonGalleryScreen()
                                                        ComponentGalleryEntryId.TextInput -> TextInputGalleryScreen()
                                                        ComponentGalleryEntryId.ProjectSessionSelector ->
                                                            ProjectSessionSelectorGalleryScreen()
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
                                    entry<SessionChat> { key ->
                                        RouteSurface {
                                            SessionChatScreen(
                                                sessionId = key.sessionId,
                                                sessionTitle = key.sessionTitle,
                                                worktree = key.worktree,
                                                onNavigateBack = { backStack.removeLastOrNull() },
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
                                                        ),
                                                    )
                                                },
                                                onSessionReplaceRequested = { target ->
                                                    backStack.removeLastOrNull()
                                                    backStack.add(
                                                        SessionChat(
                                                            sessionId = target.sessionId,
                                                            sessionTitle = target.sessionTitle,
                                                            worktree = target.worktree,
                                                        ),
                                                    )
                                                },
                                            )
                                        }
                                    }
                                    entry<SessionContext> { key ->
                                        RouteSurface {
                                            SessionContextScreen(
                                                sessionId = key.sessionId,
                                                worktree = key.worktree,
                                                onNavigateBack = { backStack.removeLastOrNull() },
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
                                                onNavigateBack = { backStack.removeLastOrNull() },
                                            )
                                        }
                                    }
                                },
                        )
                }

                OpenCodeBottomSheet(
                    visible = connectionSetupVisible,
                    title = "Connect to server",
                    onDismissRequest = { connectionSetupVisible = false },
                    contentMode = OpenCodeBottomSheetContentMode.Form,
                ) {
                    ConnectionSetupSheetContent(
                        uiState = setupUiState,
                        onBaseUrlChanged = setupViewModel::onBaseUrlChanged,
                        onUsernameChanged = setupViewModel::onUsernameChanged,
                        onPasswordChanged = setupViewModel::onPasswordChanged,
                        onTestConnection = setupViewModel::testConnection,
                        onSave = {
                            setupViewModel.saveConnection {
                                connectionSetupVisible = false
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
private fun RouteSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        content = content,
    )
}
