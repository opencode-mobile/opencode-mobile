package dev.opencode.mobile.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun MainScreen(
  onDeveloperComponentsClick: () -> Unit,
  onSessionClick: (SessionOpenTarget) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: MainScreenViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  MainScreen(
    uiState = state,
    onDeveloperComponentsClick = onDeveloperComponentsClick,
    onSessionClick = onSessionClick,
    onRetryProbe = viewModel::retryProbe,
    onRefresh = viewModel::refresh,
    onAddProjectClick = viewModel::showAddProjectSheet,
    onDismissAddProject = viewModel::dismissAddProjectSheet,
    onSelectAddProject = viewModel::addProject,
    onSeeAllSessions = viewModel::showSeeAllSessions,
    onDismissSeeAll = viewModel::dismissSeeAllSessions,
    onNewSession = viewModel::createSession,
    onConsumePendingOpenSession = viewModel::consumePendingOpenSession,
    modifier = modifier,
  )
}

@Composable
internal fun MainScreen(
  uiState: MainScreenUiState,
  onDeveloperComponentsClick: () -> Unit,
  onSessionClick: (SessionOpenTarget) -> Unit,
  onRetryProbe: () -> Unit,
  onRefresh: () -> Unit,
  onAddProjectClick: () -> Unit,
  onDismissAddProject: () -> Unit,
  onSelectAddProject: (String) -> Unit,
  onSeeAllSessions: (String) -> Unit,
  onDismissSeeAll: () -> Unit,
  onNewSession: (String) -> Unit,
  onConsumePendingOpenSession: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val appName = stringResource(R.string.app_name)
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
  val addCandidates =
    when (uiState) {
      is MainScreenUiState.Empty -> uiState.addProjectCandidates
      is MainScreenUiState.Loaded -> uiState.addProjectCandidates
      else -> emptyList()
    }
  val seeAllSheet = (uiState as? MainScreenUiState.Loaded)?.seeAllSessions

  Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
      OpenCodeTopBar(
        title = appName,
        showDivider = true,
        actions =
          listOf(
            OpenCodeTopBarAction.Icon(
              imageVector = OpenCodeIcons.Widgets,
              contentDescription = "Developer components",
              onClick = onDeveloperComponentsClick,
            ),
          ),
      )

      when (uiState) {
        MainScreenUiState.Probing,
        MainScreenUiState.Loading,
        -> {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        }
        is MainScreenUiState.ProbeFailed -> {
          ProbeFailedContent(message = uiState.message, onRetry = onRetryProbe, modifier = Modifier.fillMaxSize())
        }
        is MainScreenUiState.Empty,
        is MainScreenUiState.Loaded,
        -> {
          val sections =
            when (uiState) {
              is MainScreenUiState.Loaded -> uiState.sections
              else -> emptyList()
            }
          HomeProjectsContent(
            sections = sections,
            onBrowseProjectsClick = onAddProjectClick,
            onSessionClick = onSessionClick,
            onSeeAllSessions = onSeeAllSessions,
            onNewSession = onNewSession,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }

  if (showAddSheet) {
    OpenCodeBottomSheet(visible = true, title = "Browse projects", onDismissRequest = onDismissAddProject) {
      if (addCandidates.isEmpty()) {
        OpenCodeBottomSheetEntry(title = "No more projects on this server", enabled = false, onClick = {})
      } else {
        addCandidates.forEach { candidate ->
          OpenCodeBottomSheetEntry(
            title = candidate.displayName,
            onClick = { onSelectAddProject(candidate.worktree) },
          )
        }
      }
    }
  }

  seeAllSheet?.let { sheet ->
    OpenCodeBottomSheet(visible = true, title = sheet.projectName, onDismissRequest = onDismissSeeAll) {
      sheet.sessions.forEach { session ->
        OpenCodeBottomSheetEntry(
          title = session.title,
          onClick = {
            onDismissSeeAll()
            onSessionClick(SessionOpenTarget(session.id, session.title, sheet.worktree))
          },
        )
      }
    }
  }
}

@Composable
private fun ProbeFailedContent(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
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
  }
}

@Composable
private fun BrowseProjectsListItem(onClick: () -> Unit) {
  OpenCodeListItem(
    title = "Browse projects",
    startDecorator = StartDecorator.Icon(OpenCodeIcons.Add, contentDescription = "Browse projects"),
    onClick = onClick,
  )
}

@Composable
private fun HomeProjectsContent(
  sections: List<ProjectSectionUi>,
  onBrowseProjectsClick: () -> Unit,
  onSessionClick: (SessionOpenTarget) -> Unit,
  onSeeAllSessions: (String) -> Unit,
  onNewSession: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(bottom = 16.dp),
  ) {
    item(key = "browse-projects") {
      BrowseProjectsListItem(onClick = onBrowseProjectsClick)
    }
    if (sections.isEmpty()) {
      item(key = "empty-hint") {
        Text(
          text = "Add a project to see recent sessions.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp),
        )
      }
    }
    items(sections, key = { it.worktree }) { section ->
      ProjectSection(
        section = section,
        onSessionClick = onSessionClick,
        onSeeAllSessions = { onSeeAllSessions(section.worktree) },
        onNewSession = onNewSession,
      )
    }
  }
}

@Composable
private fun ProjectSection(
  section: ProjectSectionUi,
  onSessionClick: (SessionOpenTarget) -> Unit,
  onSeeAllSessions: () -> Unit,
  onNewSession: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    OpenCodeListItem(
      title = section.displayName,
      subtitle = section.pathSubtitle,
      startDecorator = StartDecorator.Letter(section.letter, section.letterAccent),
    )
    section.loadError?.let { error ->
      Text(
        text = error,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(start = 16.dp),
      )
    }
    if (section.previewSessions.isEmpty() && section.loadError == null) {
      OpenCodeListItem(
        title = "New Session",
        onClick = { onNewSession(section.worktree) },
      )
    } else {
      section.previewSessions.forEach { session ->
        OpenCodeListItem(
          title = session.title,
          onClick = { onSessionClick(SessionOpenTarget(session.id, session.title, section.worktree)) },
        )
      }
    }
    if (section.totalSessionCount > section.previewSessions.size) {
      OpenCodeListItem(
        title = "See all...",
        onClick = onSeeAllSessions,
      )
    }
  }
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
                previewSessions =
                  listOf(
                    SessionSummary(id = "1", title = "Importing icons explanation"),
                    SessionSummary(id = "2", title = "Material Design Icons integration plan"),
                  ),
                allSessions =
                  listOf(
                    SessionSummary(id = "1", title = "Importing icons explanation"),
                    SessionSummary(id = "2", title = "Material Design Icons integration plan"),
                  ),
                totalSessionCount = 2,
              ),
            ),
        ),
      onDeveloperComponentsClick = {},
      onSessionClick = {},
      onRetryProbe = {},
      onRefresh = {},
      onAddProjectClick = {},
      onDismissAddProject = {},
      onSelectAddProject = {},
      onSeeAllSessions = {},
      onDismissSeeAll = {},
      onNewSession = {},
      onConsumePendingOpenSession = {},
    )
  }
}
