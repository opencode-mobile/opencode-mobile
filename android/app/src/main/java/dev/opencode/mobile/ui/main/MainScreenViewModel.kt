package dev.opencode.mobile.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.live.LiveStoreRefreshPolicy
import dev.opencode.mobile.domain.live.SessionLiveStore
import dev.opencode.mobile.domain.live.SessionReadyStore
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.model.NewSessionModelSelectionResolver
import dev.opencode.mobile.domain.filesystem.DirectoryListingPolicy
import dev.opencode.mobile.domain.filesystem.FilesystemRepository
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionStatus
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.domain.session.activeSessions
import dev.opencode.mobile.domain.session.rootSessions
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.ui.components.OpenCodeSnackbarMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainScreenViewModel
    @Inject
    constructor(
        private val projectRepository: ProjectRepository,
        private val filesystemRepository: FilesystemRepository,
        private val sessionRepository: SessionRepository,
        private val connectionProfileStore: ConnectionProfileStore,
        private val sessionDomainAdapter: SessionDomainAdapter,
        private val eventDomainAdapter: EventDomainAdapter,
        private val sessionLiveStore: SessionLiveStore,
        private val sessionStatusLiveStore: SessionStatusLiveStore,
        private val sessionReadyStore: SessionReadyStore,
        private val foregroundEventStream: ForegroundEventStream,
        private val activeConnectionManager: ActiveConnectionManager,
        private val connectionSessionCoordinator: ConnectionSessionCoordinator,
        private val newSessionModelSelectionResolver: NewSessionModelSelectionResolver,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<MainScreenUiState>(MainScreenUiState.Probing)
        val uiState: StateFlow<MainScreenUiState> = _uiState.asStateFlow()
        private val _isRefreshing = MutableStateFlow(false)
        val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
        private val _removalSnackbar = MutableStateFlow<OpenCodeSnackbarMessage?>(null)
        val removalSnackbar: StateFlow<OpenCodeSnackbarMessage?> = _removalSnackbar.asStateFlow()
        val liveUpdatesConnectionState: StateFlow<EventStreamConnectionState> =
            foregroundEventStream.connectionState
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS),
                    initialValue = EventStreamConnectionState.Idle,
                )

        private val serverBaseUrl: String
            get() = activeConnectionManager.current()?.baseUrl.orEmpty()
        private var liveObservationJobs: List<Job> = emptyList()
        private var requestedFocusWorktree: String? = null
        private var homeFocusedWorktree: String? = null

        init {
            refresh()
            viewModelScope.launch {
                connectionSessionCoordinator.profileSaved.collect {
                    sessionReadyStore.clearCache()
                    refresh()
                }
            }
            viewModelScope.launch {
                connectionSessionCoordinator.profileCleared.collect {
                    _uiState.value = MainScreenUiState.Disconnected
                }
            }
        }

        fun refresh() {
            viewModelScope.launch { runHomeLoad() }
        }

        /** Soft reload for pull-to-refresh: keeps Loaded/Empty UI, no full-screen spinner. */
        fun pullToRefresh() {
            when (_uiState.value) {
                is MainScreenUiState.Loaded,
                is MainScreenUiState.Empty,
                -> {
                    viewModelScope.launch {
                        if (_isRefreshing.value) return@launch
                        _isRefreshing.value = true
                        try {
                            runHomeLoad(skipProbe = true, preserveContent = true)
                        } finally {
                            _isRefreshing.value = false
                        }
                    }
                }
                else -> Unit
            }
        }

        fun retryProbe() {
            refresh()
        }

        fun requestEditConnection(onProfileReady: (ConnectionProfile?) -> Unit) {
            activeConnectionManager.currentProfile()?.let { profile ->
                onProfileReady(profile)
                return
            }
            viewModelScope.launch {
                onProfileReady(connectionProfileStore.activeProfile())
            }
        }

        fun showAddProjectSheet() {
            viewModelScope.launch { openDirectoryNavigator() }
        }

        fun dismissAddProjectSheet() {
            _uiState.update { state ->
                when (state) {
                    is MainScreenUiState.Empty ->
                        state.copy(showAddProjectSheet = false, addProjectNavigator = null)
                    is MainScreenUiState.Loaded ->
                        state.copy(showAddProjectSheet = false, addProjectNavigator = null)
                    else -> state
                }
            }
        }

        fun navigateAddProjectUp() {
            val navigator = currentAddProjectNavigator() ?: return
            val parent = DirectoryListingPolicy.parentPath(navigator.currentPath) ?: return
            viewModelScope.launch { loadDirectoryNavigator(parent) }
        }

        fun navigateAddProjectInto(path: String) {
            viewModelScope.launch { loadDirectoryNavigator(path) }
        }

        fun retryAddProjectNavigator() {
            val navigator = currentAddProjectNavigator() ?: return
            viewModelScope.launch { loadDirectoryNavigator(navigator.currentPath) }
        }

        fun selectCurrentDirectory() {
            val navigator = currentAddProjectNavigator() ?: return
            if (navigator.currentPath.isBlank() || navigator.isLoading || navigator.isValidating) return
            viewModelScope.launch { addProject(navigator.currentPath) }
        }

        fun addProject(worktree: String) {
            viewModelScope.launch {
                val added = connectionProfileStore.addedWorktrees(serverBaseUrl)
                if (worktree in added) {
                    requestedFocusWorktree = worktree
                    dismissAddProjectSheet()
                    runHomeLoad(skipProbe = true)
                    return@launch
                }
                updateAddProjectNavigator { it.copy(isValidating = true, errorMessage = null) }
                val result = runCatching { projectRepository.getCurrentProject(directory = worktree) }
                result
                    .onSuccess {
                        // Persist the selected directory (web open-project behavior), not the
                        // /project/current worktree — non-git dirs resolve to global "/".
                        connectionProfileStore.addWorktree(serverBaseUrl, worktree)
                        requestedFocusWorktree = worktree
                        dismissAddProjectSheet()
                        runHomeLoad(skipProbe = true)
                    }.onFailure { error ->
                        updateAddProjectNavigator {
                            it.copy(
                                isValidating = false,
                                errorMessage = error.message ?: "Unable to add this directory",
                            )
                        }
                    }
            }
        }

        fun focusProject(worktree: String) {
            _uiState.update { state ->
                if (state is MainScreenUiState.Loaded && state.sections.any { it.worktree == worktree }) {
                    homeFocusedWorktree = worktree
                    state.copy(focusedWorktree = worktree, projectSessionCreateError = null)
                } else {
                    state
                }
            }
        }

        fun showSeeAllSessions(worktree: String) {
            val state = _uiState.value as? MainScreenUiState.Loaded ?: return
            val section = state.sections.firstOrNull { it.worktree == worktree } ?: return
            _uiState.value =
                state.copy(
                    seeAllSessions =
                        SeeAllSessionsSheet(
                            worktree = worktree,
                            projectName = section.displayName,
                            sessions = section.allSessions,
                        ),
                )
        }

        fun dismissSeeAllSessions() {
            _uiState.update { state ->
                if (state is MainScreenUiState.Loaded) state.copy(seeAllSessions = null) else state
            }
        }

        fun createSession(worktree: String) {
            viewModelScope.launch {
                clearProjectSessionCreateError()
                val profile = activeConnectionManager.currentProfile()
                val selection =
                    profile?.let { newSessionModelSelectionResolver.resolve(it, worktree) }
                val createResult = runCatching { sessionRepository.createSession(directory = worktree) }
                createResult.onSuccess { session ->
                    if (profile != null && selection != null) {
                        newSessionModelSelectionResolver.seedSession(profile, worktree, session.id, selection)
                    }
                    _uiState.update { state ->
                        if (state is MainScreenUiState.Loaded) {
                            state.copy(
                                pendingOpenSession =
                                    SessionOpenTarget(
                                        sessionId = session.id,
                                        title = session.title,
                                        worktree = worktree,
                                    ),
                            )
                        } else {
                            state
                        }
                    }
                }
                createResult.onFailure { error ->
                    setProjectSessionCreateError(error.message ?: "Unable to create session")
                }
            }
        }

        fun consumePendingOpenSession() {
            _uiState.update { state ->
                if (state is MainScreenUiState.Loaded) state.copy(pendingOpenSession = null) else state
            }
        }

        fun requestRemoveProject(worktree: String) {
            _uiState.update { state ->
                if (state is MainScreenUiState.Loaded) {
                    val section = state.sections.firstOrNull { it.worktree == worktree }
                    if (section != null) {
                        state.copy(
                            pendingProjectRemoval =
                                ProjectRemovalUi(
                                    worktree = worktree,
                                    displayName = section.displayName,
                                ),
                        )
                    } else {
                        state
                    }
                } else {
                    state
                }
            }
        }

        fun dismissProjectRemoval() {
            _uiState.update { state ->
                if (state is MainScreenUiState.Loaded) state.copy(pendingProjectRemoval = null) else state
            }
        }

        fun confirmRemoveProject() {
            val pending = (_uiState.value as? MainScreenUiState.Loaded)?.pendingProjectRemoval ?: return
            viewModelScope.launch {
                runCatching { connectionProfileStore.removeWorktree(serverBaseUrl, pending.worktree) }
                dismissProjectRemoval()
                runHomeLoad(skipProbe = true)
                _removalSnackbar.value =
                    OpenCodeSnackbarMessage(
                        text = "Removed \"${pending.displayName}\" from home",
                    )
            }
        }

        fun consumeProjectRemovalSnackbar() {
            _removalSnackbar.value = null
        }

        private suspend fun runHomeLoad(
            skipProbe: Boolean = false,
            preserveContent: Boolean = false,
        ) {
            if (activeConnectionManager.current() == null) {
                _uiState.value = MainScreenUiState.Disconnected
                return
            }
            if (!skipProbe) {
                if (!preserveContent) {
                    _uiState.value = MainScreenUiState.Probing
                }
                try {
                    projectRepository.probeServer()
                } catch (error: ServerProbeError) {
                    if (preserveContent) return
                    stopLiveObservations()
                    _uiState.value = MainScreenUiState.ProbeFailed(probeErrorMessage(error))
                    return
                } catch (error: Throwable) {
                    if (preserveContent) return
                    stopLiveObservations()
                    _uiState.value = MainScreenUiState.ProbeFailed(error.message ?: "Unable to reach the server")
                    return
                }
            }

            val previous = _uiState.value
            if (!preserveContent) {
                _uiState.value = MainScreenUiState.Loading
            }
            try {
                val worktrees = connectionProfileStore.addedWorktrees(serverBaseUrl)

                if (worktrees.isEmpty()) {
                    stopLiveObservations()
                    homeFocusedWorktree = null
                    _uiState.value =
                        when {
                            preserveContent && previous is MainScreenUiState.Empty -> previous
                            else -> MainScreenUiState.Empty()
                        }
                    return
                }

                val sections = loadProjectSections(worktrees)
                val focusedWorktree = resolvedFocusedWorktree(sections)
                homeFocusedWorktree = focusedWorktree
                requestedFocusWorktree = null
                if (sections.isEmpty()) {
                    stopLiveObservations()
                    _uiState.value =
                        when {
                            preserveContent && previous is MainScreenUiState.Empty -> previous
                            else -> MainScreenUiState.Empty()
                        }
                    return
                }
                _uiState.value =
                    when {
                        preserveContent && previous is MainScreenUiState.Loaded ->
                            previous.copy(sections = sections, focusedWorktree = focusedWorktree)
                        else ->
                            MainScreenUiState.Loaded(sections = sections, focusedWorktree = focusedWorktree)
                    }
                startLiveObservations(worktrees)
            } catch (error: Throwable) {
                if (preserveContent) return
                stopLiveObservations()
                _uiState.value = MainScreenUiState.ProbeFailed(error.message ?: "Unable to load projects")
            }
        }

        private suspend fun loadProjectSections(worktrees: List<String>): List<ProjectSectionUi> =
            coroutineScope {
                worktrees
                    .map { worktree ->
                        async {
                            runCatching { loadSection(worktree) }
                                .getOrElse { throwable ->
                                    ProjectSectionUi(
                                        worktree = worktree,
                                        displayName = ProjectDomainAdapter.displayNameFromWorktree(worktree),
                                        pathSubtitle = worktree,
                                        letter = letterCharacterForProject(ProjectDomainAdapter.displayNameFromWorktree(worktree)),
                                        letterAccent = letterAccentForWorktree(worktree),
                                        previewSessions = emptyList(),
                                        allSessions = emptyList(),
                                        totalSessionCount = 0,
                                        loadError = throwable.message ?: "Unable to load sessions",
                                    )
                                }
                        }
                    }.awaitAll()
            }

        private suspend fun loadSection(worktree: String): ProjectSectionUi {
            projectRepository.getCurrentProject(directory = worktree)
            val sessions =
                sessionRepository
                    .listSessions(directory = worktree)
                    .activeSessions()
                    .rootSessions()
                    .sortedByDescending { it.updatedAtEpochMs ?: 0L }
            val statusBySessionId =
                runCatching { sessionRepository.getAllSessionStatuses(directory = worktree) }
                    .getOrDefault(emptyList())
                    .associateBy { it.sessionId }
            val summaries =
                sessions.map { session ->
                    sessionDomainAdapter.toSessionSummary(
                        session,
                        statusLabel = statusBySessionId[session.id]?.let(eventDomainAdapter::statusLabel),
                    )
                }
            return ProjectSectionUi(
                worktree = worktree,
                displayName = ProjectDomainAdapter.displayNameFromWorktree(worktree),
                pathSubtitle = worktree,
                letter = letterCharacterForProject(ProjectDomainAdapter.displayNameFromWorktree(worktree)),
                letterAccent = letterAccentForWorktree(worktree),
                previewSessions = summaries.take(PREVIEW_SESSION_LIMIT),
                allSessions = summaries,
                totalSessionCount = summaries.size,
                loadError = null,
            )
        }

        private suspend fun openDirectoryNavigator() {
            updateAddProjectNavigator(
                AddProjectNavigatorUi(
                    currentPath = "",
                    canGoUp = false,
                    entries = emptyList(),
                    isLoading = true,
                    isValidating = false,
                    errorMessage = null,
                ),
            )
            try {
                val paths = filesystemRepository.getPaths()
                loadDirectoryNavigator(paths.home)
            } catch (error: Throwable) {
                updateAddProjectNavigator(
                    AddProjectNavigatorUi(
                        currentPath = "",
                        canGoUp = false,
                        entries = emptyList(),
                        isLoading = false,
                        isValidating = false,
                        errorMessage = error.message ?: "Unable to open home directory",
                    ),
                )
            }
        }

        private suspend fun loadDirectoryNavigator(path: String) {
            updateAddProjectNavigator { current ->
                current.copy(
                    currentPath = path,
                    canGoUp = DirectoryListingPolicy.parentPath(path) != null,
                    isLoading = true,
                    isValidating = false,
                    errorMessage = null,
                )
            }
            try {
                val entries =
                    filesystemRepository.listDirectory(path).map { node ->
                        AddProjectDirectoryUi(name = node.name, absolutePath = node.absolutePath)
                    }
                updateAddProjectNavigator { current ->
                    current.copy(
                        currentPath = path,
                        canGoUp = DirectoryListingPolicy.parentPath(path) != null,
                        entries = entries,
                        isLoading = false,
                        isValidating = false,
                        errorMessage = null,
                    )
                }
            } catch (error: Throwable) {
                updateAddProjectNavigator { current ->
                    current.copy(
                        currentPath = path,
                        canGoUp = DirectoryListingPolicy.parentPath(path) != null,
                        entries = emptyList(),
                        isLoading = false,
                        isValidating = false,
                        errorMessage = error.message ?: "Unable to list directory",
                    )
                }
            }
        }

        private fun currentAddProjectNavigator(): AddProjectNavigatorUi? =
            when (val state = _uiState.value) {
                is MainScreenUiState.Empty -> state.addProjectNavigator
                is MainScreenUiState.Loaded -> state.addProjectNavigator
                else -> null
            }

        private fun updateAddProjectNavigator(navigator: AddProjectNavigatorUi) {
            _uiState.update { state ->
                when (state) {
                    is MainScreenUiState.Empty ->
                        state.copy(showAddProjectSheet = true, addProjectNavigator = navigator)
                    is MainScreenUiState.Loaded ->
                        state.copy(showAddProjectSheet = true, addProjectNavigator = navigator)
                    else -> state
                }
            }
        }

        private fun updateAddProjectNavigator(transform: (AddProjectNavigatorUi) -> AddProjectNavigatorUi) {
            _uiState.update { state ->
                when (state) {
                    is MainScreenUiState.Empty -> {
                        val current = state.addProjectNavigator ?: AddProjectNavigatorUi.empty()
                        state.copy(showAddProjectSheet = true, addProjectNavigator = transform(current))
                    }
                    is MainScreenUiState.Loaded -> {
                        val current = state.addProjectNavigator ?: AddProjectNavigatorUi.empty()
                        state.copy(showAddProjectSheet = true, addProjectNavigator = transform(current))
                    }
                    else -> state
                }
            }
        }

        private fun probeErrorMessage(error: ServerProbeError): String =
            when (error) {
                is ServerProbeError.Transport -> "Unable to reach the server"
                is ServerProbeError.Http -> error.message ?: "Server returned ${error.statusCode}"
                is ServerProbeError.Unhealthy -> "Server reported unhealthy"
            }

        private fun startLiveObservations(worktrees: List<String>) {
            stopLiveObservations()
            liveObservationJobs =
                worktrees.map { worktree ->
                    viewModelScope.launch {
                        combine(
                            sessionLiveStore.observeSessions(worktree),
                            sessionStatusLiveStore.observeStatuses(worktree),
                            sessionReadyStore.observeReadyIds(worktree),
                        ) { snapshot, statuses, readyIds ->
                            Triple(snapshot, statuses, readyIds)
                        }.collect { (snapshot, statuses, readyIds) ->
                            updateSectionFromLiveData(
                                worktree,
                                snapshot.sessions,
                                statuses,
                                readyIds,
                                sessionsFromCache = snapshot.isFromCache,
                            )
                        }
                    }
                }
        }

        private fun stopLiveObservations() {
            liveObservationJobs.forEach { it.cancel() }
            liveObservationJobs = emptyList()
        }

        private fun updateSectionFromLiveData(
            worktree: String,
            sessions: List<Session>,
            statuses: Map<String, SessionStatus>,
            readyIds: Set<String>,
            sessionsFromCache: Boolean,
        ) {
            val state = _uiState.value as? MainScreenUiState.Loaded ?: return
            val section = state.sections.firstOrNull { it.worktree == worktree } ?: return
            val summaries =
                sessions
                    .activeSessions()
                    .rootSessions()
                    .sortedByDescending { it.updatedAtEpochMs ?: 0L }
                    .map { session ->
                        sessionDomainAdapter.toSessionSummary(
                            session,
                            statusLabel = statuses[session.id]?.let(eventDomainAdapter::statusLabel),
                            isReady = session.id in readyIds,
                        )
                    }
            val updatedSection =
                section.copy(
                    previewSessions = summaries.take(PREVIEW_SESSION_LIMIT),
                    allSessions = summaries,
                    totalSessionCount = summaries.size,
                    sessionsFromCache = sessionsFromCache,
                )
            val updatedSections =
                state.sections.map { current ->
                    if (current.worktree == worktree) updatedSection else current
                }
            val updatedSeeAll =
                state.seeAllSessions?.takeIf { it.worktree == worktree }?.copy(
                    sessions = summaries,
                ) ?: state.seeAllSessions
            _uiState.value = state.copy(sections = updatedSections, seeAllSessions = updatedSeeAll)
        }

        private fun resolvedFocusedWorktree(sections: List<ProjectSectionUi>): String? {
            val requested = requestedFocusWorktree
            if (requested != null && sections.any { it.worktree == requested }) return requested
            val current = homeFocusedWorktree
            if (current != null && sections.any { it.worktree == current }) return current
            return sections.firstOrNull()?.worktree
        }

        private fun clearProjectSessionCreateError() {
            setProjectSessionCreateError(null)
        }

        private fun setProjectSessionCreateError(message: String?) {
            _uiState.update { state ->
                when (state) {
                    is MainScreenUiState.Empty -> state.copy(projectSessionCreateError = message)
                    is MainScreenUiState.Loaded -> state.copy(projectSessionCreateError = message)
                    else -> state
                }
            }
        }

        private companion object {
            const val PREVIEW_SESSION_LIMIT = 3
        }
    }

sealed interface MainScreenUiState {
    data object Disconnected : MainScreenUiState

    data object Probing : MainScreenUiState

    data class ProbeFailed(
        val message: String,
    ) : MainScreenUiState

    data object Loading : MainScreenUiState

    data class Empty(
        val showAddProjectSheet: Boolean = false,
        val addProjectNavigator: AddProjectNavigatorUi? = null,
        val projectSessionCreateError: String? = null,
    ) : MainScreenUiState

    data class Loaded(
        val sections: List<ProjectSectionUi>,
        val showAddProjectSheet: Boolean = false,
        val addProjectNavigator: AddProjectNavigatorUi? = null,
        val seeAllSessions: SeeAllSessionsSheet? = null,
        val pendingOpenSession: SessionOpenTarget? = null,
        val focusedWorktree: String? = null,
        val projectSessionCreateError: String? = null,
        val pendingProjectRemoval: ProjectRemovalUi? = null,
    ) : MainScreenUiState
}

data class ProjectRemovalUi(
    val worktree: String,
    val displayName: String,
)

data class ProjectSectionUi(
    val worktree: String,
    val displayName: String,
    val pathSubtitle: String,
    val letter: Char,
    val letterAccent: LetterAccent,
    val previewSessions: List<SessionSummary>,
    val allSessions: List<SessionSummary>,
    val totalSessionCount: Int,
    val loadError: String? = null,
    val sessionsFromCache: Boolean = false,
)

data class AddProjectDirectoryUi(
    val name: String,
    val absolutePath: String,
)

data class AddProjectNavigatorUi(
    val currentPath: String,
    val canGoUp: Boolean,
    val entries: List<AddProjectDirectoryUi>,
    val isLoading: Boolean,
    val isValidating: Boolean,
    val errorMessage: String?,
) {
    companion object {
        fun empty(): AddProjectNavigatorUi =
            AddProjectNavigatorUi(
                currentPath = "",
                canGoUp = false,
                entries = emptyList(),
                isLoading = false,
                isValidating = false,
                errorMessage = null,
            )
    }
}

data class SeeAllSessionsSheet(
    val worktree: String,
    val projectName: String,
    val sessions: List<SessionSummary>,
)

data class SessionOpenTarget(
    val sessionId: String,
    val title: String,
    val worktree: String,
)
