package dev.opencode.mobile

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.opencode.mobile.ui.componentgallery.components.TopBarGalleryScreen
import dev.opencode.mobile.ui.componentgallery.foundation.ColorGalleryScreen
import dev.opencode.mobile.ui.componentgallery.foundation.TypographyGalleryScreen
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.connection.ConnectionOnboardingScreen
import dev.opencode.mobile.ui.connection.ConnectionSetupSheetContent
import dev.opencode.mobile.ui.connection.ConnectionSetupViewModel
import dev.opencode.mobile.ui.main.MainScreen
import dev.opencode.mobile.ui.root.AppRootState
import dev.opencode.mobile.ui.root.AppRootViewModel
import dev.opencode.mobile.ui.sessionchat.SessionChatScreen

@Composable
fun MainNavigation() {
    val rootViewModel: AppRootViewModel = hiltViewModel()
    val rootState by rootViewModel.state.collectAsStateWithLifecycle()
    var showSetupSheet by rememberSaveable { mutableStateOf(false) }
    val setupViewModel: ConnectionSetupViewModel = hiltViewModel()
    var setupConfigured by remember { mutableStateOf(false) }

    LaunchedEffect(showSetupSheet) {
        if (showSetupSheet && !setupConfigured) {
            setupViewModel.configure(initialProfile = null)
            setupConfigured = true
        }
        if (!showSetupSheet) {
            setupConfigured = false
        }
    }

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
                        ConnectionOnboardingScreen(onConnectClick = { showSetupSheet = true })
                    AppRootState.Home ->
                        NavDisplay(
                            modifier = Modifier.safeDrawingPadding(),
                            backStack = backStack,
                            onBack = { backStack.removeLastOrNull() },
                            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                            popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                            predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                            entryProvider =
                                entryProvider {
                                    entry<ProjectListHome> {
                                        MainScreen(
                                            onDeveloperComponentsClick = { backStack.add(ComponentGalleryHome) },
                                            onSessionClick = { target ->
                                                backStack.add(SessionChat(target.sessionId, target.title, target.worktree))
                                            },
                                            onEditConnectionClick = { profile ->
                                                setupViewModel.configure(initialProfile = profile)
                                                setupConfigured = true
                                                showSetupSheet = true
                                            },
                                        )
                                    }
                                    entry<ComponentGalleryHome> {
                                        ComponentGalleryScreen(onEntryClick = { entryId -> backStack.add(ComponentGalleryDetail(entryId)) })
                                    }
                                    entry<ComponentGalleryDetail> { key ->
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
                                            }
                                        }
                                    }
                                    entry<SessionChat> { key ->
                                        SessionChatScreen(
                                            sessionId = key.sessionId,
                                            sessionTitle = key.sessionTitle,
                                            worktree = key.worktree,
                                            onNavigateBack = { backStack.removeLastOrNull() },
                                        )
                                    }
                                },
                        )
                }

                val setupUiState by setupViewModel.uiState.collectAsStateWithLifecycle()
                OpenCodeBottomSheet(
                    visible = showSetupSheet,
                    title = "Connect to server",
                    onDismissRequest = { showSetupSheet = false },
                    keyboardAware = true,
                ) {
                    ConnectionSetupSheetContent(
                        uiState = setupUiState,
                        onBaseUrlChanged = setupViewModel::onBaseUrlChanged,
                        onUsernameChanged = setupViewModel::onUsernameChanged,
                        onPasswordChanged = setupViewModel::onPasswordChanged,
                        onTestConnection = setupViewModel::testConnection,
                        onSave = {
                            setupViewModel.saveConnection {
                                showSetupSheet = false
                            }
                        },
                    )
                }
            }
        }
    }
}
