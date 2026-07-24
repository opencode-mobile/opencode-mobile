package dev.opencode.mobile.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.ui.components.BottomSheetEntryDecorator
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeBottomSheetEntry
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeSessionConnectionStatus
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarAction
import dev.opencode.mobile.ui.components.OpenCodeTopBarSubtitleAccessory
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.time.RelativeTimeFormatting
import dev.opencode.mobile.ui.components.ProjectSessionSelector
import dev.opencode.mobile.ui.components.ProjectSessionSelectorProject
import dev.opencode.mobile.ui.components.ProjectSessionSelectorSession
import dev.opencode.mobile.ui.components.SessionList
import dev.opencode.mobile.ui.components.SessionListFilterTab
import dev.opencode.mobile.ui.components.SessionListSession
import dev.opencode.mobile.ui.components.SessionListStatus
import dev.opencode.mobile.ui.connection.ConnectionOnboardingScreen
import dev.opencode.mobile.ui.icons.OpenCodeIcons

@Composable
fun MainScreen(
    onSettingsClick: () -> Unit,
    onSessionClick: (SessionOpenTarget) -> Unit,
    onEditConnectionClick: (dev.opencode.mobile.domain.connection.ConnectionProfile?) -> Unit,
    onConnectClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val liveUpdatesConnectionState by viewModel.liveUpdatesConnectionState.collectAsStateWithLifecycle()
    MainScreen(
        uiState = state,
        liveUpdatesConnectionState = liveUpdatesConnectionState,
        onSettingsClick = onSettingsClick,
        onSessionClick = onSessionClick,
        onRetryProbe = viewModel::retryProbe,
        onEditConnection = { viewModel.requestEditConnection(onEditConnectionClick) },
        onConnectClick = onConnectClick,
        onRefresh = viewModel::refresh,
        onAddProjectClick = viewModel::showAddProjectSheet,
        onFocusProject = viewModel::focusProject,
        onDismissAddProject = viewModel::dismissAddProjectSheet,
        onNavigateAddProjectUp = viewModel::navigateAddProjectUp,
        onNavigateAddProjectInto = viewModel::navigateAddProjectInto,
        onRetryAddProjectNavigator = viewModel::retryAddProjectNavigator,
        onSelectCurrentDirectory = viewModel::selectCurrentDirectory,
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
    onSettingsClick: () -> Unit,
    onSessionClick: (SessionOpenTarget) -> Unit,
    onEditConnection: () -> Unit,
    onConnectClick: () -> Unit,
    onRetryProbe: () -> Unit,
    onRefresh: () -> Unit,
    onAddProjectClick: () -> Unit,
    onFocusProject: (String) -> Unit,
    onDismissAddProject: () -> Unit,
    onNavigateAddProjectUp: () -> Unit,
    onNavigateAddProjectInto: (String) -> Unit,
    onRetryAddProjectNavigator: () -> Unit,
    onSelectCurrentDirectory: () -> Unit,
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
    val addNavigator =
        when (uiState) {
            is MainScreenUiState.Empty -> uiState.addProjectNavigator
            is MainScreenUiState.Loaded -> uiState.addProjectNavigator
            else -> null
        }
    val seeAllSheet = (uiState as? MainScreenUiState.Loaded)?.seeAllSessions
    var showNewSessionProjectPicker by rememberSaveable { mutableStateOf(false) }
    val newSessionPickerProjects =
        when (uiState) {
            is MainScreenUiState.Loaded -> uiState.sections
            else -> emptyList()
        }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OpenCodeTopBar(
                title = appName,
                subtitleAccessory =
                    if (showsLiveUpdatesStatus(uiState)) {
                        OpenCodeTopBarSubtitleAccessory.StatusDot(
                            liveUpdatesConnectionState.toOpenCodeSessionConnectionStatus(),
                        )
                    } else {
                        null
                    },
                showDivider = true,
                actions =
                    listOf(
                        OpenCodeTopBarAction.Icon(
                            imageVector = OpenCodeIcons.Settings,
                            contentDescription = "Settings",
                            onClick = onSettingsClick,
                        ),
                    ),
            )

            when (uiState) {
                is MainScreenUiState.Disconnected -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ConnectionOnboardingScreen(onConnectClick = onConnectClick)
                    }
                }
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
                    val projectSessionCreateError =
                        when (uiState) {
                            is MainScreenUiState.Empty -> uiState.projectSessionCreateError
                            is MainScreenUiState.Loaded -> uiState.projectSessionCreateError
                            else -> null
                        }
                    HomeProjectsContent(
                        sections = sections,
                        focusedWorktree = focusedWorktree,
                        projectSessionCreateError = projectSessionCreateError,
                        onBrowseProjectsClick = onAddProjectClick,
                        onFocusProject = onFocusProject,
                        onSessionClick = onSessionClick,
                        onSeeAllSessions = onSeeAllSessions,
                        onNewSession = onNewSession,
                        onRequestNewSessionProjectPicker = { showNewSessionProjectPicker = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        val navigator = addNavigator ?: AddProjectNavigatorUi.empty()
        OpenCodeBottomSheet(
            visible = true,
            title = "Add project",
            onDismissRequest = onDismissAddProject,
            alwaysFixedHeight = true,
            pinnedContent = {
                OpenCodeBottomSheetEntry(
                    title = "Go up",
                    enabled = navigator.canGoUp && !navigator.isLoading && !navigator.isValidating,
                    startDecorator =
                        BottomSheetEntryDecorator.Icon(
                            imageVector = OpenCodeIcons.ArrowUpward,
                            contentDescription = null,
                        ),
                    onClick = onNavigateAddProjectUp,
                )
                val currentTitle =
                    when {
                        navigator.currentPath.isBlank() -> "Current directory"
                        else -> navigator.currentPath
                    }
                OpenCodeBottomSheetEntry(
                    title = currentTitle,
                    enabled = navigator.currentPath.isNotBlank() && !navigator.isLoading && !navigator.isValidating,
                    startDecorator =
                        BottomSheetEntryDecorator.Icon(
                            imageVector = OpenCodeIcons.FolderCheck,
                            contentDescription = null,
                        ),
                    onClick = onSelectCurrentDirectory,
                )
            },
        ) {
            when {
                navigator.isLoading || navigator.isValidating -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                navigator.errorMessage != null -> {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = navigator.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        OpenCodeButton(
                            variant = OpenCodeButtonVariant.FullWidthText("Retry"),
                            onClick = onRetryAddProjectNavigator,
                        )
                    }
                }
                navigator.entries.isEmpty() -> {
                    OpenCodeBottomSheetEntry(title = "No subdirectories", enabled = false, onClick = {})
                }
                else -> {
                    navigator.entries.forEach { entry ->
                        OpenCodeBottomSheetEntry(
                            title = entry.name,
                            startDecorator =
                                BottomSheetEntryDecorator.Icon(
                                    imageVector = OpenCodeIcons.SubdirectoryArrowRight,
                                    contentDescription = null,
                                ),
                            onClick = { onNavigateAddProjectInto(entry.absolutePath) },
                        )
                    }
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

    if (showNewSessionProjectPicker) {
        OpenCodeBottomSheet(
            visible = true,
            title = "New session",
            onDismissRequest = { showNewSessionProjectPicker = false },
        ) {
            if (newSessionPickerProjects.isEmpty()) {
                OpenCodeBottomSheetEntry(title = "No projects", enabled = false, onClick = {})
            } else {
                newSessionPickerProjects.forEach { project ->
                    OpenCodeBottomSheetEntry(
                        title = project.displayName,
                        startDecorator =
                            BottomSheetEntryDecorator.Icon(
                                imageVector = OpenCodeIcons.Folder,
                                contentDescription = null,
                            ),
                        onClick = {
                            showNewSessionProjectPicker = false
                            onNewSession(project.worktree)
                        },
                    )
                }
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
private fun HomeProjectsContent(
    sections: List<ProjectSectionUi>,
    focusedWorktree: String?,
    projectSessionCreateError: String?,
    onBrowseProjectsClick: () -> Unit,
    onFocusProject: (String) -> Unit,
    onSessionClick: (SessionOpenTarget) -> Unit,
    onSeeAllSessions: (String) -> Unit,
    onNewSession: (String) -> Unit,
    onRequestNewSessionProjectPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showSessionList = HomeDisplayPreferences.showSessionListOnHome
    if (showSessionList) {
        val sessions = sections.toCrossProjectSessionListSessions()
        val filterTabs = sections.toSessionListFilterTabs(sessionCount = sessions.size)
        var selectedFilterId by rememberSaveable {
            mutableStateOf(SessionListFilterTab.ALL_ID)
        }
        val resolvedFilterId =
            if (filterTabs.any { it.id == selectedFilterId }) {
                selectedFilterId
            } else {
                SessionListFilterTab.ALL_ID
            }
        SessionList(
            sessions = sessions,
            filterTabs = filterTabs,
            selectedFilterId = resolvedFilterId,
            createError = projectSessionCreateError,
            onFilterSelected = { selectedFilterId = it },
            onSessionSelected = { session ->
                onSessionClick(
                    SessionOpenTarget(
                        sessionId = session.id,
                        title = session.title,
                        worktree = session.worktree,
                    ),
                )
            },
            onAddProject = onBrowseProjectsClick,
            onNewSession = onRequestNewSessionProjectPicker,
            modifier = modifier.fillMaxSize(),
        )
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            item(key = "project-session-selector") {
                ProjectSessionSelector(
                    projects = sections.map(ProjectSectionUi::toProjectSessionSelectorProject),
                    selectedWorktree = focusedWorktree,
                    createError = projectSessionCreateError,
                    onProjectSelected = onFocusProject,
                    onBrowseProjects = onBrowseProjectsClick,
                    onSessionSelected = { project, session ->
                        onSessionClick(
                            SessionOpenTarget(
                                sessionId = session.id,
                                title = session.title,
                                worktree = project.worktree,
                            ),
                        )
                    },
                    onShowAllSessions = onSeeAllSessions,
                    onNewSession = onNewSession,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

private fun ProjectSectionUi.toProjectSessionSelectorProject(): ProjectSessionSelectorProject =
    ProjectSessionSelectorProject(
        worktree = worktree,
        displayName = displayName,
        pathSubtitle = pathSubtitle,
        letter = letter,
        letterAccent = letterAccent,
        previewSessions = previewSessions.map(SessionSummary::toProjectSessionSelectorSession),
        totalSessionCount = totalSessionCount,
        loadError = loadError,
    )

private fun SessionSummary.toProjectSessionSelectorSession(): ProjectSessionSelectorSession =
    ProjectSessionSelectorSession(
        id = id,
        title = title,
        subtitle = updatedAtLabel ?: statusLabel,
    )

private fun List<ProjectSectionUi>.toCrossProjectSessionListSessions(): List<SessionListSession> =
    flatMap { section ->
        section.allSessions.map { session ->
            section to session
        }
    }.sortedByDescending { (_, session) -> session.updatedAtEpochMs ?: 0L }
        .map { (section, session) ->
            SessionListSession(
                id = session.id,
                title = session.title,
                worktree = section.worktree,
                letter = section.letter,
                letterAccent = section.letterAccent,
                subtitle = section.pathSubtitle,
                status = session.toSessionListStatus(),
                caption = session.updatedAtEpochMs?.let(RelativeTimeFormatting::format).orEmpty(),
            )
        }

private fun List<ProjectSectionUi>.toSessionListFilterTabs(sessionCount: Int): List<SessionListFilterTab> =
    listOf(
        SessionListFilterTab(
            id = SessionListFilterTab.ALL_ID,
            title = "All",
            count = sessionCount,
        ),
    ) +
        map { section ->
            SessionListFilterTab(
                id = section.worktree,
                title = section.displayName,
                count = section.allSessions.size,
            )
        }

private fun SessionSummary.toSessionListStatus(): SessionListStatus =
    when (statusLabel) {
        "Working", "Retrying" -> SessionListStatus.Running
        else -> SessionListStatus.Idle
    }

private fun showsLiveUpdatesStatus(uiState: MainScreenUiState): Boolean =
    when (uiState) {
        MainScreenUiState.Disconnected, MainScreenUiState.Probing, MainScreenUiState.Loading -> false
        else -> true
    }

private fun EventStreamConnectionState.toOpenCodeSessionConnectionStatus(): OpenCodeSessionConnectionStatus =
    when (this) {
        EventStreamConnectionState.Connected -> OpenCodeSessionConnectionStatus.Live
        EventStreamConnectionState.Connecting,
        EventStreamConnectionState.Reconnecting,
        -> OpenCodeSessionConnectionStatus.Connecting
        EventStreamConnectionState.Idle,
        EventStreamConnectionState.Disconnected,
        -> OpenCodeSessionConnectionStatus.Offline
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
            onSettingsClick = {},
            onSessionClick = {},
            onEditConnection = {},
            onConnectClick = {},
            onRetryProbe = {},
            onRefresh = {},
            onAddProjectClick = {},
            onFocusProject = {},
            onDismissAddProject = {},
            onNavigateAddProjectUp = {},
            onNavigateAddProjectInto = {},
            onRetryAddProjectNavigator = {},
            onSelectCurrentDirectory = {},
            onSeeAllSessions = {},
            onDismissSeeAll = {},
            onNewSession = {},
            onConsumePendingOpenSession = {},
        )
    }
}
