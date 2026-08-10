package dev.opencode.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.badgeColors
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Suppress("ktlint:standard:property-naming")
private val projectSessionSelectorHorizontalSpacing = 12.dp

@Suppress("ktlint:standard:property-naming")
private const val ProjectSessionSelectorPreviewSlotCount = 3

data class ProjectSessionSelectorProject(
    val worktree: String,
    val displayName: String,
    val pathSubtitle: String,
    val letter: Char,
    val letterAccent: LetterAccent,
    val previewSessions: List<ProjectSessionSelectorSession>,
    val totalSessionCount: Int,
    val loadError: String? = null,
)

data class ProjectSessionSelectorSession(
    val id: String,
    val title: String,
    val subtitle: String? = null,
)

@Composable
fun ProjectSessionSelector(
    projects: List<ProjectSessionSelectorProject>,
    selectedWorktree: String?,
    createError: String?,
    onProjectSelected: (String) -> Unit,
    onBrowseProjects: () -> Unit,
    onSessionSelected: (ProjectSessionSelectorProject, ProjectSessionSelectorSession) -> Unit,
    onShowAllSessions: (String) -> Unit,
    onNewSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedProject =
        projects.firstOrNull { it.worktree == selectedWorktree } ?: projects.firstOrNull()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            // Raised fill: surface-base matches background-base in the theme.
            color = openCodeColor("surface-raised-base"),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column {
                ProjectSessionSelectorTiles(
                    projects = projects,
                    selectedWorktree = selectedProject?.worktree,
                    onProjectSelected = onProjectSelected,
                    onBrowseProjects = onBrowseProjects,
                )
                if (selectedProject != null) {
                    ProjectSessionSelectorHeader(project = selectedProject)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ProjectSessionSelectorSessionPreview(
                        project = selectedProject,
                        onSessionSelected = onSessionSelected,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ProjectSessionSelectorFooter(
                        project = selectedProject,
                        createError = createError,
                        onShowAllSessions = onShowAllSessions,
                        onNewSession = onNewSession,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectSessionSelectorTiles(
    projects: List<ProjectSessionSelectorProject>,
    selectedWorktree: String?,
    onProjectSelected: (String) -> Unit,
    onBrowseProjects: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = projectSessionSelectorHorizontalSpacing, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(projectSessionSelectorHorizontalSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        projects.forEach { project ->
            ProjectSessionSelectorTile(
                project = project,
                selected = project.worktree == selectedWorktree,
                onClick = { onProjectSelected(project.worktree) },
            )
        }
        AddProjectSessionSelectorTile(onClick = onBrowseProjects)
    }
}

@Composable
private fun ProjectSessionSelectorTile(
    project: ProjectSessionSelectorProject,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val (fill, tint) = project.letterAccent.badgeColors(dark)
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .semantics {
                    contentDescription = "${project.displayName}, ${compactHomePath(project.pathSubtitle)}"
                    stateDescription = if (selected) "Selected" else "Not selected"
                }.clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(fill)
                    .then(
                        if (selected) {
                            Modifier.border(3.dp, tint, RoundedCornerShape(12.dp))
                        } else {
                            Modifier
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = project.letter.uppercaseChar().toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AddProjectSessionSelectorTile(onClick: () -> Unit) {
    val border = MaterialTheme.colorScheme.outline
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .semantics { contentDescription = "Add project" }
                .clickable(
                    role = Role.Button,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .drawBehind {
                        drawRoundRect(
                            color = border,
                            style =
                                Stroke(
                                    width = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                                ),
                            cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
                        )
                    },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = OpenCodeIcons.Add,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProjectSessionSelectorHeader(project: ProjectSessionSelectorProject) {
    Column(
        modifier =
            Modifier.padding(
                start = projectSessionSelectorHorizontalSpacing,
                end = projectSessionSelectorHorizontalSpacing,
                bottom = 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = OpenCodeIcons.Folder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = project.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = compactHomePath(project.pathSubtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProjectSessionSelectorSessionPreview(
    project: ProjectSessionSelectorProject,
    onSessionSelected: (ProjectSessionSelectorProject, ProjectSessionSelectorSession) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when {
            project.loadError != null -> {
                Box(modifier = Modifier.fillMaxWidth()) {
                    ProjectSessionSelectorPlaceholderSlots(count = 1)
                    Text(
                        text = project.loadError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .align(Alignment.CenterStart)
                                .padding(horizontal = projectSessionSelectorHorizontalSpacing),
                    )
                }
                ProjectSessionSelectorPlaceholderSlots(count = ProjectSessionSelectorPreviewSlotCount - 1)
            }
            project.previewSessions.isNotEmpty() -> {
                val sessions = project.previewSessions.take(ProjectSessionSelectorPreviewSlotCount)
                sessions.forEach { session ->
                    OpenCodeListItem(
                        title = session.title,
                        subtitle = session.subtitle.orEmpty(),
                        onClick = { onSessionSelected(project, session) },
                    )
                }
                ProjectSessionSelectorPlaceholderSlots(count = ProjectSessionSelectorPreviewSlotCount - sessions.size)
            }
            else -> {
                ProjectSessionSelectorPlaceholderSlots(count = ProjectSessionSelectorPreviewSlotCount)
            }
        }
    }
}

@Composable
private fun ProjectSessionSelectorPlaceholderSlots(count: Int) {
    repeat(count.coerceAtLeast(0)) {
        OpenCodeListItem(
            title = "",
            subtitle = "",
            enabled = false,
            modifier = Modifier.alpha(0f),
        )
    }
}

@Composable
private fun ProjectSessionSelectorFooter(
    project: ProjectSessionSelectorProject,
    createError: String?,
    onShowAllSessions: (String) -> Unit,
    onNewSession: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 16.dp)
                .padding(horizontal = projectSessionSelectorHorizontalSpacing),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconSquare(OpenCodeIcons.List, "Show all"),
                onClick = { onShowAllSessions(project.worktree) },
            )
            OpenCodeButton(
                variant = OpenCodeButtonVariant.FullWidthText("New session"),
                size = OpenCodeButtonSize.Medium,
                onClick = { onNewSession(project.worktree) },
                modifier = Modifier.weight(1f),
            )
        }
        createError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun compactHomePath(path: String): String {
    val home = System.getProperty("user.home").orEmpty()
    if (home.isNotEmpty()) {
        if (path == home) return "~"
        if (path.startsWith("$home/")) return "~" + path.removePrefix(home)
    }
    if (!HomeDisplayPreferences.hideUserDirectoryPrefix) return path
    remoteUnixHomePrefix(path)?.let { remoteHome ->
        if (path == remoteHome) return "~"
        if (path.startsWith("$remoteHome/")) return "~" + path.removePrefix(remoteHome)
    }
    return path
}

/** `/Users/<name>` or `/home/<name>` prefix when the path is a remote server worktree. */
private fun remoteUnixHomePrefix(path: String): String? {
    val parts = path.split('/', limit = 4)
    if (parts.size < 3 || parts[0].isNotEmpty()) return null
    val root = parts[1]
    if (root != "Users" && root != "home") return null
    val user = parts[2]
    if (user.isEmpty()) return null
    return "/$root/$user"
}

@Preview(showBackground = true)
@Composable
private fun ProjectSessionSelectorPreview() {
    OpenCodeTheme(darkTheme = false) {
        ProjectSessionSelector(
            projects = previewProjectSessionSelectorProjects(),
            selectedWorktree = "/Users/demo/opencode-mobile",
            createError = null,
            onProjectSelected = {},
            onBrowseProjects = {},
            onSessionSelected = { _, _ -> },
            onShowAllSessions = {},
            onNewSession = {},
            modifier = Modifier.padding(vertical = 24.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProjectSessionSelectorEmptyPreview() {
    OpenCodeTheme(darkTheme = true) {
        ProjectSessionSelector(
            projects =
                listOf(
                    ProjectSessionSelectorProject(
                        worktree = "/Users/demo/empty",
                        displayName = "empty",
                        pathSubtitle = "/Users/demo/empty",
                        letter = 'E',
                        letterAccent = LetterAccent.Teal,
                        previewSessions = emptyList(),
                        totalSessionCount = 0,
                    ),
                ),
            selectedWorktree = "/Users/demo/empty",
            createError = null,
            onProjectSelected = {},
            onBrowseProjects = {},
            onSessionSelected = { _, _ -> },
            onShowAllSessions = {},
            onNewSession = {},
            modifier = Modifier.padding(vertical = 24.dp),
        )
    }
}

private fun previewProjectSessionSelectorProjects(): List<ProjectSessionSelectorProject> {
    val sessions =
        listOf(
            ProjectSessionSelectorSession(id = "1", title = "Refactor Navigation", subtitle = "Oct 24 - 2:30 PM"),
            ProjectSessionSelectorSession(id = "2", title = "Bug: Header Clipping", subtitle = "Oct 23 - 11:45 AM"),
            ProjectSessionSelectorSession(id = "3", title = "Add Dark Mode Assets", subtitle = "Oct 22 - 4:15 PM"),
            ProjectSessionSelectorSession(id = "4", title = "Review Session List"),
        )
    return listOf(
        ProjectSessionSelectorProject(
            worktree = "/Users/demo/opencode-mobile",
            displayName = "opencode-mobile",
            pathSubtitle = "/Users/demo/opencode-mobile",
            letter = 'O',
            letterAccent = LetterAccent.Violet,
            previewSessions = sessions.take(3),
            totalSessionCount = sessions.size,
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
}
