package dev.opencode.mobile

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
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
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryDetailScreen
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryEntries
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryEntryId
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryScreen
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryThemeMode
import dev.opencode.mobile.ui.componentgallery.components.BottomSheetGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ButtonGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ChoiceBadgeGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.IconsGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ListItemGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.OutgoingMessageGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ProjectSessionSelectorGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.TopBarGalleryScreen
import dev.opencode.mobile.ui.componentgallery.foundation.ColorGalleryScreen
import dev.opencode.mobile.ui.componentgallery.foundation.TypographyGalleryScreen
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.connection.ConnectionOnboardingScreen
import dev.opencode.mobile.ui.connection.ConnectionSetupSheetContent
import dev.opencode.mobile.ui.connection.ConnectionSetupViewModel
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.ui.main.MainScreen
import dev.opencode.mobile.ui.root.AppRootState
import dev.opencode.mobile.ui.root.AppRootViewModel
import dev.opencode.mobile.ui.sessionchat.SessionChatScreen

@Composable
fun MainNavigation() {
    val rootViewModel: AppRootViewModel = hiltViewModel()
    val rootState by rootViewModel.state.collectAsStateWithLifecycle()
    var connectionSetupVisible by rememberSaveable { mutableStateOf(false) }
    val setupViewModel: ConnectionSetupViewModel = hiltViewModel()

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
                    AppRootState.Onboarding ->
                        ConnectionOnboardingScreen(
                            onConnectClick = { presentConnectionSetup(initialProfile = null) },
                        )
                    AppRootState.Home ->
                        NavDisplay(
                            modifier = Modifier.safeDrawingPadding(),
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
                                                onDeveloperComponentsClick = { backStack.add(ComponentGalleryHome) },
                                                onSessionClick = { target ->
                                                    backStack.add(SessionChat(target.sessionId, target.title, target.worktree))
                                                },
                                                onEditConnectionClick = { profile ->
                                                    presentConnectionSetup(initialProfile = profile)
                                                },
                                            )
                                        }
                                    }
                                    entry<ComponentGalleryHome> {
                                        RouteSurface {
                                            ComponentGalleryScreen(onEntryClick = { entryId -> backStack.add(ComponentGalleryDetail(entryId)) })
                                        }
                                    }
                                    entry<ComponentGalleryDetail> { key ->
                                        RouteSurface {
                                            val entry = ComponentGalleryEntries.first { it.id == key.entryId }
                                            ComponentGalleryDetailScreen(
                                                entry = entry,
                                                themeMode = themeMode,
                                                onThemeModeChange = { themeMode = it },
                                            ) {
                                                when (key.entryId) {
                                                    ComponentGalleryEntryId.Colors -> ColorGalleryScreen()
                                                    ComponentGalleryEntryId.Typography -> TypographyGalleryScreen()
                                                    ComponentGalleryEntryId.Icons -> IconsGalleryScreen()
                                                    ComponentGalleryEntryId.OutgoingMessage -> OutgoingMessageGalleryScreen()
                                                    ComponentGalleryEntryId.TopBar -> TopBarGalleryScreen()
                                                    ComponentGalleryEntryId.BottomSheet -> BottomSheetGalleryScreen()
                                                    ComponentGalleryEntryId.ListItem -> ListItemGalleryScreen()
                                                    ComponentGalleryEntryId.ChoiceBadge -> ChoiceBadgeGalleryScreen()
                                                    ComponentGalleryEntryId.Button -> ButtonGalleryScreen()
                                                    ComponentGalleryEntryId.ProjectSessionSelector ->
                                                        ProjectSessionSelectorGalleryScreen()
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
                    keyboardAware = false,
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

private const val RouteTransitionDurationMillis = 220

private fun forwardRouteTransition() =
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

private fun backwardRouteTransition() =
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
