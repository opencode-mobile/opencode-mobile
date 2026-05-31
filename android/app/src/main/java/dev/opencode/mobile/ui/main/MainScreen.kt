package dev.opencode.mobile.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.badgeColors
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonSize
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons

private val projectFocusHorizontalSpacing = 12.dp

@Composable
fun MainScreen(
    onDeveloperComponentsClick: () -> Unit,
    onSessionClick: (SessionOpenTarget) -> Unit,
    onEditConnectionClick: (dev.opencode.mobile.domain.connection.ConnectionProfile?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val liveUpdatesConnectionState by viewModel.liveUpdatesConnectionState.collectAsStateWithLifecycle()
    MainScreen(
        uiState = state,
        liveUpdatesConnectionState = liveUpdatesConnectionState,
        onDeveloperComponentsClick = onDeveloperComponentsClick,
        onSessionClick = onSessionClick,
        onRetryProbe = viewModel::retryProbe,
        onEditConnection = { viewModel.requestEditConnection(onEditConnectionClick) },
        onRefresh = viewModel::refresh,
        onAddProjectClick = viewModel::showAddProjectSheet,
        onFocusProject = viewModel::focusProject,
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
    liveUpdatesConnectionState: EventStreamConnectionState,
    onDeveloperComponentsClick: () -> Unit,
    onSessionClick: (SessionOpenTarget) -> Unit,
    onEditConnection: () -> Unit,
    onRetryProbe: () -> Unit,
    onRefresh: () -> Unit,
    onAddProjectClick: () -> Unit,
    onFocusProject: (String) -> Unit,
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
                            imageVector = OpenCodeIcons.Edit,
                            contentDescription = "Edit connection",
                            onClick = onEditConnection,
                        ),
                        OpenCodeTopBarAction.Icon(
                            imageVector = OpenCodeIcons.Widgets,
                            contentDescription = "Developer components",
                            onClick = onDeveloperComponentsClick,
                        ),
                    ),
            )

            if (showsLiveUpdatesStatus(uiState)) {
                LiveUpdatesConnectionBanner(state = liveUpdatesConnectionState)
            }

            when (uiState) {
                MainScreenUiState.Probing,
                MainScreenUiState.Loading,
                -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is MainScreenUiState.ProbeFailed -> {
                    ProbeFailedContent(
                        message = uiState.message,
                        onRetry = onRetryProbe,
                        onEditConnection = onEditConnection,
                        modifier = Modifier.fillMaxSize(),
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
                    val focusedWorktree = (uiState as? MainScreenUiState.Loaded)?.focusedWorktree
                    val projectFocusCreateError =
                        when (uiState) {
                            is MainScreenUiState.Empty -> uiState.projectFocusCreateError
                            is MainScreenUiState.Loaded -> uiState.projectFocusCreateError
                            else -> null
                        }
                    HomeProjectsContent(
                        sections = sections,
                        focusedWorktree = focusedWorktree,
                        projectFocusCreateError = projectFocusCreateError,
                        onBrowseProjectsClick = onAddProjectClick,
                        onFocusProject = onFocusProject,
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
                    title =
                        session.statusLabel?.let { label -> "${session.title} · $label" } ?: session.title,
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
private fun ProbeFailedContent(
    message: String,
    onRetry: () -> Unit,
    onEditConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        OpenCodeButton(
            variant = OpenCodeButtonVariant.FullWidthText("Edit connection"),
            onClick = onEditConnection,
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
    focusedWorktree: String?,
    projectFocusCreateError: String?,
    onBrowseProjectsClick: () -> Unit,
    onFocusProject: (String) -> Unit,
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
        item(key = "project-focus") {
            ProjectFocusSection(
                sections = sections,
                focusedWorktree = focusedWorktree,
                createError = projectFocusCreateError,
                onFocusProject = onFocusProject,
                onBrowseProjects = onBrowseProjectsClick,
                onSessionClick = onSessionClick,
                onSeeAllSessions = onSeeAllSessions,
                onNewSession = onNewSession,
                modifier = Modifier.padding(horizontal = 16.dp),
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
                    subtitle = session.statusLabel,
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

@Composable
private fun ProjectFocusSection(
    sections: List<ProjectSectionUi>,
    focusedWorktree: String?,
    createError: String?,
    onFocusProject: (String) -> Unit,
    onBrowseProjects: () -> Unit,
    onSessionClick: (SessionOpenTarget) -> Unit,
    onSeeAllSessions: (String) -> Unit,
    onNewSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusedSection =
        sections.firstOrNull { it.worktree == focusedWorktree } ?: sections.firstOrNull()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column {
                ProjectFocusTiles(
                    sections = sections,
                    focusedWorktree = focusedSection?.worktree,
                    onFocusProject = onFocusProject,
                    onBrowseProjects = onBrowseProjects,
                )
                if (focusedSection != null) {
                    ProjectFocusHeader(section = focusedSection)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ProjectFocusSessionPreview(
                        section = focusedSection,
                        onSessionClick = onSessionClick,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ProjectFocusFooter(
                        section = focusedSection,
                        createError = createError,
                        onSeeAllSessions = onSeeAllSessions,
                        onNewSession = onNewSession,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectFocusTiles(
    sections: List<ProjectSectionUi>,
    focusedWorktree: String?,
    onFocusProject: (String) -> Unit,
    onBrowseProjects: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = projectFocusHorizontalSpacing, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(projectFocusHorizontalSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sections.forEach { section ->
            ProjectFocusTile(
                section = section,
                focused = section.worktree == focusedWorktree,
                onClick = { onFocusProject(section.worktree) },
            )
        }
        AddProjectFocusTile(onClick = onBrowseProjects)
    }
}

@Composable
private fun ProjectFocusTile(
    section: ProjectSectionUi,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val (fill, tint) = section.letterAccent.badgeColors(dark)
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .semantics {
                    contentDescription = "${section.displayName}, ${compactHomePath(section.pathSubtitle)}"
                    stateDescription = if (focused) "Focused" else "Not focused"
                }.clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (focused) MaterialTheme.colorScheme.surface else fill)
                    .then(
                        if (focused) {
                            Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                        } else {
                            Modifier
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = section.letter.uppercaseChar().toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (focused) MaterialTheme.colorScheme.primary else tint,
                maxLines = 1,
            )
        }
        if (focused) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.onSurface),
            )
        }
    }
}

@Composable
private fun AddProjectFocusTile(onClick: () -> Unit) {
    val border = MaterialTheme.colorScheme.outline
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .semantics { contentDescription = "Browse projects" }
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
private fun ProjectFocusHeader(section: ProjectSectionUi) {
    Column(
        modifier =
            Modifier.padding(
                start = projectFocusHorizontalSpacing,
                end = projectFocusHorizontalSpacing,
                bottom = 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = section.displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = compactHomePath(section.pathSubtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProjectFocusSessionPreview(
    section: ProjectSectionUi,
    onSessionClick: (SessionOpenTarget) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when {
            section.loadError != null -> {
                Text(
                    text = section.loadError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = projectFocusHorizontalSpacing, vertical = 14.dp),
                )
            }
            !section.previewSessions.isEmpty() -> {
                section.previewSessions.forEach { session ->
                    OpenCodeListItem(
                        title = session.title,
                        subtitle = session.projectFocusSubtitle,
                        onClick = {
                            onSessionClick(
                                SessionOpenTarget(
                                    sessionId = session.id,
                                    title = session.title,
                                    worktree = section.worktree,
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectFocusFooter(
    section: ProjectSectionUi,
    createError: String?,
    onSeeAllSessions: (String) -> Unit,
    onNewSession: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 16.dp)
                .padding(horizontal = projectFocusHorizontalSpacing),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeButton(
                variant = OpenCodeButtonVariant.IconSquare(OpenCodeIcons.List, "Show all"),
                onClick = { onSeeAllSessions(section.worktree) },
            )
            OpenCodeButton(
                variant = OpenCodeButtonVariant.FullWidthText("New session"),
                size = OpenCodeButtonSize.Medium,
                onClick = { onNewSession(section.worktree) },
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
    return when {
        home.isNotEmpty() && path == home -> "~"
        home.isNotEmpty() && path.startsWith("$home/") -> "~" + path.removePrefix(home)
        else -> path
    }
}

private val SessionSummary.projectFocusSubtitle: String?
    get() = updatedAtLabel ?: statusLabel

private fun showsLiveUpdatesStatus(uiState: MainScreenUiState): Boolean =
    when (uiState) {
        MainScreenUiState.Probing, MainScreenUiState.Loading -> false
        else -> true
    }

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    OpenCodeTheme(darkTheme = false) {
        MainScreen(
            uiState =
                MainScreenUiState.Loaded(
                    focusedWorktree = "/Users/demo/opencode-mobile",
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
            liveUpdatesConnectionState = EventStreamConnectionState.Connected,
            onDeveloperComponentsClick = {},
            onSessionClick = {},
            onEditConnection = {},
            onRetryProbe = {},
            onRefresh = {},
            onAddProjectClick = {},
            onFocusProject = {},
            onDismissAddProject = {},
            onSelectAddProject = {},
            onSeeAllSessions = {},
            onDismissSeeAll = {},
            onNewSession = {},
            onConsumePendingOpenSession = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProjectFocusOverflowPreview() {
    OpenCodeTheme(darkTheme = false) {
        ProjectFocusSection(
            sections = previewProjectFocusSections(),
            focusedWorktree = "/Users/demo/opencode-mobile",
            createError = null,
            onFocusProject = {},
            onBrowseProjects = {},
            onSessionClick = {},
            onSeeAllSessions = {},
            onNewSession = {},
            modifier = Modifier.padding(vertical = 24.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProjectFocusEmptyPreview() {
    OpenCodeTheme(darkTheme = true) {
        ProjectFocusSection(
            sections =
                listOf(
                    ProjectSectionUi(
                        worktree = "/Users/demo/empty",
                        displayName = "empty",
                        pathSubtitle = "/Users/demo/empty",
                        letter = 'E',
                        letterAccent = LetterAccent.Teal,
                        previewSessions = emptyList(),
                        allSessions = emptyList(),
                        totalSessionCount = 0,
                    ),
                ),
            focusedWorktree = "/Users/demo/empty",
            createError = null,
            onFocusProject = {},
            onBrowseProjects = {},
            onSessionClick = {},
            onSeeAllSessions = {},
            onNewSession = {},
            modifier = Modifier.padding(vertical = 24.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProjectFocusAddTilePreview() {
    OpenCodeTheme(darkTheme = false) {
        ProjectFocusSection(
            sections = emptyList(),
            focusedWorktree = null,
            createError = null,
            onFocusProject = {},
            onBrowseProjects = {},
            onSessionClick = {},
            onSeeAllSessions = {},
            onNewSession = {},
            modifier = Modifier.padding(vertical = 24.dp),
        )
    }
}

private fun previewProjectFocusSections(): List<ProjectSectionUi> {
    val sessions =
        listOf(
            SessionSummary(id = "1", title = "Refactor Navigation", updatedAtLabel = "Oct 24 • 2:30 PM"),
            SessionSummary(id = "2", title = "Bug: Header Clipping", updatedAtLabel = "Oct 23 • 11:45 AM"),
            SessionSummary(id = "3", title = "Add Dark Mode Assets", updatedAtLabel = "Oct 22 • 4:15 PM"),
            SessionSummary(id = "4", title = "Review Session List"),
        )
    return listOf(
        ProjectSectionUi(
            worktree = "/Users/demo/opencode-mobile",
            displayName = "opencode-mobile",
            pathSubtitle = "/Users/demo/opencode-mobile",
            letter = 'O',
            letterAccent = LetterAccent.Violet,
            previewSessions = sessions.take(3),
            allSessions = sessions,
            totalSessionCount = sessions.size,
        ),
        ProjectSectionUi(
            worktree = "/Users/demo/playground",
            displayName = "playground",
            pathSubtitle = "/Users/demo/playground",
            letter = 'P',
            letterAccent = LetterAccent.Amber,
            previewSessions = emptyList(),
            allSessions = emptyList(),
            totalSessionCount = 0,
        ),
    )
}
