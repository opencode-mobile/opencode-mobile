package dev.opencode.mobile

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryDetailScreen
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryEntries
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryEntryId
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.BottomSheetGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ButtonGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ChoiceBadgeGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.ListItemGalleryScreen
import dev.opencode.mobile.ui.componentgallery.components.TopBarGalleryScreen
import dev.opencode.mobile.ui.componentgallery.ComponentGalleryThemeMode
import dev.opencode.mobile.ui.componentgallery.foundation.ColorGalleryScreen
import dev.opencode.mobile.ui.componentgallery.foundation.TypographyGalleryScreen
import dev.opencode.mobile.ui.main.MainScreen
import dev.opencode.mobile.ui.sessionchat.SessionChatScreen

@Composable
fun MainNavigation() {
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
                onSessionClick = { session -> backStack.add(SessionChat(session.id, session.title)) },
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
                onNavigateBack = { backStack.removeLastOrNull() },
              )
            }
          },
      )
    }
  }
}
