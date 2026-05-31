package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.ui.components.OpenCodeChoiceBadge
import dev.opencode.mobile.ui.components.ProjectSessionSelector
import dev.opencode.mobile.ui.components.ProjectSessionSelectorProject
import dev.opencode.mobile.ui.components.ProjectSessionSelectorSession

@Composable
fun ProjectSessionSelectorGalleryScreen(modifier: Modifier = Modifier) {
    var scenario by rememberSaveable { mutableStateOf(ProjectSessionSelectorScenario.Normal.name) }
    var selectedWorktree by rememberSaveable { mutableStateOf(projectSessionSelectorSampleProjects.first().worktree) }
    val selectedScenario = ProjectSessionSelectorScenario.valueOf(scenario)
    val projects = selectedScenario.projects

    LaunchedEffect(scenario) {
        if (projects.none { it.worktree == selectedWorktree }) {
            selectedWorktree = projects.firstOrNull()?.worktree.orEmpty()
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = "Scenario", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProjectSessionSelectorScenario.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { option ->
                        OpenCodeChoiceBadge(
                            label = option.label,
                            selected = selectedScenario == option,
                            onClick = { scenario = option.name },
                        )
                    }
                }
            }
        }

        Text(text = "Selected project", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            projects.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { project ->
                        OpenCodeChoiceBadge(
                            label = project.displayName,
                            selected = selectedWorktree == project.worktree,
                            onClick = { selectedWorktree = project.worktree },
                        )
                    }
                }
            }
        }

        ProjectSessionSelector(
            projects = projects,
            selectedWorktree = selectedWorktree,
            createError = if (selectedScenario == ProjectSessionSelectorScenario.CreateError) "Unable to create session" else null,
            onProjectSelected = { selectedWorktree = it },
            onBrowseProjects = {},
            onSessionSelected = { _, _ -> },
            onShowAllSessions = {},
            onNewSession = {},
        )
    }
}

private enum class ProjectSessionSelectorScenario(
    val label: String,
) {
    Normal("Normal"),
    Empty("Empty"),
    LoadError("Load error"),
    CreateError("Create error"),
    ;

    val projects: List<ProjectSessionSelectorProject>
        get() =
            when (this) {
                Normal, CreateError -> projectSessionSelectorSampleProjects
                Empty -> listOf(projectSessionSelectorEmptyProject)
                LoadError -> listOf(projectSessionSelectorLoadErrorProject)
            }
}

private val projectSessionSelectorSampleSessions =
    listOf(
        ProjectSessionSelectorSession(id = "session-1", title = "Refactor Navigation", subtitle = "Oct 24 - 2:30 PM"),
        ProjectSessionSelectorSession(id = "session-2", title = "Bug: Header Clipping", subtitle = "Oct 23 - 11:45 AM"),
        ProjectSessionSelectorSession(id = "session-3", title = "Add Dark Mode Assets", subtitle = "Oct 22 - 4:15 PM"),
        ProjectSessionSelectorSession(id = "session-4", title = "Review Session List", subtitle = "Idle"),
    )

private val projectSessionSelectorSampleProjects =
    listOf(
        ProjectSessionSelectorProject(
            worktree = "/Users/demo/opencode-mobile",
            displayName = "opencode-mobile",
            pathSubtitle = "/Users/demo/opencode-mobile",
            letter = 'O',
            letterAccent = LetterAccent.Violet,
            previewSessions = projectSessionSelectorSampleSessions.take(3),
            totalSessionCount = projectSessionSelectorSampleSessions.size,
        ),
        ProjectSessionSelectorProject(
            worktree = "/Users/demo/playground",
            displayName = "playground",
            pathSubtitle = "/Users/demo/playground",
            letter = 'P',
            letterAccent = LetterAccent.Amber,
            previewSessions = emptyList(),
            totalSessionCount = 0,
        ),
    )

private val projectSessionSelectorEmptyProject =
    ProjectSessionSelectorProject(
        worktree = "/Users/demo/empty",
        displayName = "empty",
        pathSubtitle = "/Users/demo/empty",
        letter = 'E',
        letterAccent = LetterAccent.Teal,
        previewSessions = emptyList(),
        totalSessionCount = 0,
    )

private val projectSessionSelectorLoadErrorProject =
    ProjectSessionSelectorProject(
        worktree = "/Users/demo/api-client",
        displayName = "api-client",
        pathSubtitle = "/Users/demo/api-client",
        letter = 'A',
        letterAccent = LetterAccent.Rose,
        previewSessions = emptyList(),
        totalSessionCount = 0,
        loadError = "Unable to load sessions",
    )
