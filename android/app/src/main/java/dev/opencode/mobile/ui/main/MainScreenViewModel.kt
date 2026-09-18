package dev.opencode.mobile.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.connection.userMessage
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.FixedLiveUpdates
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.LiveUpdates
import dev.opencode.mobile.domain.filesystem.DirectoryListingPolicy
import dev.opencode.mobile.domain.filesystem.FilesystemRepository
import dev.opencode.mobile.domain.live.LiveStoreRefreshPolicy
import dev.opencode.mobile.domain.live.PermissionLiveStore
import dev.opencode.mobile.domain.live.QuestionLiveStore
import dev.opencode.mobile.domain.live.SessionLiveStore
import dev.opencode.mobile.domain.live.SessionReadyStore
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.live.SessionTodoLiveStore
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.model.NewSessionModelSelectionResolver
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.domain.session.OptimisticSessionCreate
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionActivitySnapshot
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.domain.session.activeSessions
import dev.opencode.mobile.domain.session.rootSessions
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.ui.components.OpenCodeSnackbarMessage
import dev.opencode.mobile.ui.components.OpenCodeSnackbarVariant
import dev.opencode.mobile.ui.components.SessionListScrollPin
import dev.opencode.mobile.ui.main.region.HomeSessionActions
import dev.opencode.mobile.ui.sessionchat.SessionConnectionStatus
import dev.opencode.mobile.ui.sessionchat.toSessionConnectionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
        private val messageRepository: MessageRepository,
        private val connectionProfileStore: ConnectionProfileStore,
        private val sessionDomainAdapter: SessionDomainAdapter,
        private val eventDomainAdapter: EventDomainAdapter,
        private val sessionLiveStore: SessionLiveStore,
        private val sessionStatusLiveStore: SessionStatusLiveStore,
        private val permissionLiveStore: PermissionLiveStore,
        private val questionLiveStore: QuestionLiveStore,
        private val sessionTodoLiveStore: SessionTodoLiveStore,
        private val sessionReadyStore: SessionReadyStore,
        private val foregroundEventStream: ForegroundEventStream,
        private val activeConnectionManager: ActiveConnectionManager,
        private val connectionSessionCoordinator: ConnectionSessionCoordinator,
        private val newSessionModelSelectionResolver: NewSessionModelSelectionResolver,
        private val sessionCache: SessionCache,
        private val liveUpdates: LiveUpdates = FixedLiveUpdates(),
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<MainScreenUiState>(MainScreenUiState.Probing)
        val uiState: StateFlow<MainScreenUiState> = _uiState.asStateFlow()
        private val _userRefreshing = MutableStateFlow(false)
        val isRefreshing: StateFlow<Boolean> = _userRefreshing.asStateFlow()
        private val _removalSnackbar = MutableStateFlow<OpenCodeSnackbarMessage?>(null)
        val removalSnackbar: StateFlow<OpenCodeSnackbarMessage?> = _removalSnackbar.asStateFlow()
        private val sessionActionsRegion =
            HomeSessionActions(
                sessionRepository = sessionRepository,
                messageRepository = messageRepository,
                scope = viewModelScope,
                onLifecycleError = { message ->
                    _removalSnackbar.value =
                        OpenCodeSnackbarMessage(
                            text = message,
                            variant = OpenCodeSnackbarVariant.Error,
                        )
                },
            )
        val sessionActions: StateFlow<HomeSessionActionsUiState> = sessionActionsRegion.state
        private val _revealNewestToken = MutableStateFlow(0)
        val revealNewestToken: StateFlow<Int> = _revealNewestToken.asStateFlow()
        private val _pendingSelectedFilterId = MutableStateFlow<String?>(null)
        val pendingSelectedFilterId: StateFlow<String?> = _pendingSelectedFilterId.asStateFlow()
        private val _serversChrome = MutableStateFlow(HomeServersChrome())
        val serversChrome: StateFlow<HomeServersChrome> = _serversChrome.asStateFlow()
        private var lastHomeHiddenAtEpochMs: Long? = HomeDisplayPreferences.lastHomeHiddenAtEpochMs()
        val liveUpdatesConnectionStatus: StateFlow<SessionConnectionStatus> =
            liveUpdates.chrome
                .map { it.label.toSessionConnectionStatus() }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS),
                    initialValue =
                        liveUpdates.chrome.value.label
                            .toSessionConnectionStatus(),
                )

        private val serverBaseUrl: String
            get() = activeConnectionManager.current()?.baseUrl.orEmpty()
        private var liveObservationJobs: List<Job> = emptyList()
        private var observedWorktrees: List<String> = emptyList()

        // Title fields report one edit per dialog appearance instead of per keystroke.
        private var renameDraftEditReported = false
        private var forkDraftEditReported = false

        init {
            refresh()
            viewModelScope.launch {
                connectionSessionCoordinator.profileSaved.collect {
                    stopLiveObservations()
                    refresh()
                }
            }
            viewModelScope.launch {
                connectionSessionCoordinator.profileCleared.collect {
                    stopLiveObservations()
                    _uiState.value = MainScreenUiState.Disconnected
                    _serversChrome.value = HomeServersChrome()
                }
            }
        }

        fun refresh() {
            viewModelScope.launch {
                refreshSuspending()
            }
        }

        private suspend fun refreshSuspending() {
            refreshServersChrome()
            runHomeLoad()
        }

        fun showServersSheet() {
            _serversChrome.update { it.copy(sheetVisible = true) }
        }

        fun dismissServersSheet() {
            _serversChrome.update { it.copy(sheetVisible = false) }
        }

        fun switchProfile(id: String) {
            viewModelScope.launch {
                connectionSessionCoordinator.switchProfile(id)
                dismissServersSheet()
            }
        }

        private suspend fun refreshServersChrome() {
            val profiles = connectionProfileStore.listProfiles()
            val active =
                connectionProfileStore.activeProfile()
                    ?: activeConnectionManager.currentProfile()
            _serversChrome.update { current ->
                current.copy(
                    title = active?.displayName ?: "Sessions",
                    isSwitcher = active != null,
                    active = active,
                    others = profiles.filter { it.id != active?.id },
                )
            }
        }

        /** Soft reload for pull-to-refresh: keeps Loaded/Empty UI, no full-screen spinner. */
        fun pullToRefresh() {
            logAction(ProductAnalytics.Screen.HOME, ProductAnalytics.Action.HOME_PULL_TO_REFRESH)
            when (_uiState.value) {
                is MainScreenUiState.Loaded,
                is MainScreenUiState.Empty,
                -> {
                    viewModelScope.launch {
                        if (_userRefreshing.value) return@launch
                        _userRefreshing.value = true
                        try {
                            sessionLiveStore.invalidate(null)
                            sessionStatusLiveStore.invalidate(null)
                        } finally {
                            _userRefreshing.value = false
                        }
                    }
                }
                else -> Unit
            }
        }

        fun retryProbe() {
            logAction(
                ProductAnalytics.Screen.HOME_PROBE_FAILED,
                ProductAnalytics.Action.HOME_RETRY_PROBE,
            )
            refresh()
        }

        fun requestEditConnection(onProfileReady: (ConnectionProfile?) -> Unit) {
            logAction(
                ProductAnalytics.Screen.HOME_PROBE_FAILED,
                ProductAnalytics.Action.HOME_EDIT_CONNECTION,
            )
            activeConnectionManager.currentProfile()?.let { profile ->
                onProfileReady(profile)
                return
            }
            viewModelScope.launch {
                onProfileReady(connectionProfileStore.activeProfile())
            }
        }

        fun showAddProjectSheet() {
            logAction(ProductAnalytics.Screen.HOME, ProductAnalytics.Action.HOME_ADD_PROJECT)
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
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_GO_UP,
            )
            viewModelScope.launch { loadDirectoryNavigator(parent) }
        }

        fun navigateAddProjectInto(path: String) {
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_ENTER_DIRECTORY,
            )
            viewModelScope.launch { loadDirectoryNavigator(path) }
        }

        fun retryAddProjectNavigator() {
            val navigator = currentAddProjectNavigator() ?: return
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_RETRY_NAVIGATOR,
            )
            viewModelScope.launch { loadDirectoryNavigator(navigator.currentPath) }
        }

        fun selectCurrentDirectory() {
            val navigator = currentAddProjectNavigator() ?: return
            if (navigator.currentPath.isBlank() || navigator.isLoading || navigator.isValidating) return
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_SELECT_CURRENT,
            )
            viewModelScope.launch { addProject(navigator.currentPath) }
        }

        fun addProject(worktree: String) {
            viewModelScope.launch {
                val added = connectionProfileStore.addedWorktrees(serverBaseUrl)
                if (worktree in added) {
                    dismissAddProjectSheet()
                    runHomeLoad(skipProbe = true)
                    _pendingSelectedFilterId.value = worktree
                    _removalSnackbar.value =
                        OpenCodeSnackbarMessage(
                            text =
                                "\"${ProjectDomainAdapter.displayNameFromWorktree(worktree)}\" is already on home",
                        )
                    return@launch
                }
                updateAddProjectNavigator { it.copy(isValidating = true, errorMessage = null) }
                val result = runCatching { projectRepository.getCurrentProject(directory = worktree) }
                result
                    .onSuccess {
                        // Persist the selected directory (web open-project behavior), not the
                        // /project/current worktree — non-git dirs resolve to global "/".
                        connectionProfileStore.addWorktree(serverBaseUrl, worktree)
                        dismissAddProjectSheet()
                        runHomeLoad(skipProbe = true)
                        _pendingSelectedFilterId.value = worktree
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

        fun createSession(worktree: String) {
            logAction(ProductAnalytics.Screen.HOME, ProductAnalytics.Action.HOME_CREATE_SESSION)
            if (!liveUpdates.chrome.value.canMutate) {
                setProjectSessionCreateError("Reconnect to create a session")
                return
            }
            clearProjectSessionCreateError()
            _revealNewestToken.update { it + 1 }
            _uiState.update { state ->
                if (state is MainScreenUiState.Loaded) {
                    state.copy(
                        pendingOpenSession =
                            SessionOpenTarget(
                                sessionId = OptimisticSessionCreate.PLACEHOLDER_SESSION_ID,
                                title = "New Session",
                                worktree = worktree,
                                optimisticNavigationInstanceId =
                                    java.util.UUID
                                        .randomUUID()
                                        .toString(),
                            ),
                    )
                } else {
                    state
                }
            }
        }

        fun consumePendingOpenSession() {
            _uiState.update { state ->
                if (state is MainScreenUiState.Loaded) state.copy(pendingOpenSession = null) else state
            }
        }

        fun consumePendingSelectedFilter() {
            _pendingSelectedFilterId.value = null
        }

        fun markHomeHidden(nowEpochMs: Long = System.currentTimeMillis()) {
            if (lastHomeHiddenAtEpochMs != null) return
            lastHomeHiddenAtEpochMs = nowEpochMs
            HomeDisplayPreferences.setLastHomeHiddenAtEpochMs(nowEpochMs)
        }

        fun onHomeRevealed(nowEpochMs: Long = System.currentTimeMillis()) {
            if (SessionListScrollPin.shouldResetForStaleAbsence(
                    lastHiddenAtEpochMs = lastHomeHiddenAtEpochMs,
                    nowEpochMs = nowEpochMs,
                )
            ) {
                _revealNewestToken.update { it + 1 }
            }
            lastHomeHiddenAtEpochMs = null
            HomeDisplayPreferences.setLastHomeHiddenAtEpochMs(null)
        }

        /**
         * Applies a new added-project order from the project picker. The order
         * shows immediately and persists to the Connection Profile; the dock,
         * home sections, and new-session picker all derive from it.
         */
        fun reorderProjects(worktrees: List<String>) {
            val state = _uiState.value as? MainScreenUiState.Loaded ?: return
            val current = state.sections.map { it.worktree }
            if (current == worktrees || current.size != worktrees.size || current.toSet() != worktrees.toSet()) return
            val byWorktree = state.sections.associateBy { it.worktree }
            _uiState.update { latest ->
                if (latest is MainScreenUiState.Loaded) {
                    latest.copy(sections = worktrees.mapNotNull { byWorktree[it] })
                } else {
                    latest
                }
            }
            if (observedWorktrees.isNotEmpty()) {
                observedWorktrees = worktrees
            }
            logAction(ProductAnalytics.Screen.HOME_FILTER_PROJECT_PICKER, ProductAnalytics.Action.HOME_REORDER_PROJECTS)
            val serverBaseUrl = serverBaseUrl
            viewModelScope.launch {
                runCatching { connectionProfileStore.setAddedWorktrees(serverBaseUrl, worktrees) }
                    .onFailure { logI("reorder projects persist failed: ${it.message}") }
            }
        }

        fun requestRemoveProject(worktree: String) {
            logAction(ProductAnalytics.Screen.HOME, ProductAnalytics.Action.HOME_REMOVE_PROJECT)
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
            logAction(
                ProductAnalytics.Screen.HOME_REMOVE_PROJECT_CONFIRM,
                ProductAnalytics.Action.HOME_REMOVE_PROJECT_CANCEL,
            )
            clearPendingProjectRemoval()
        }

        private fun clearPendingProjectRemoval() {
            _uiState.update { state ->
                if (state is MainScreenUiState.Loaded) state.copy(pendingProjectRemoval = null) else state
            }
        }

        fun confirmRemoveProject() {
            val pending = (_uiState.value as? MainScreenUiState.Loaded)?.pendingProjectRemoval ?: return
            logAction(
                ProductAnalytics.Screen.HOME_REMOVE_PROJECT_CONFIRM,
                ProductAnalytics.Action.HOME_REMOVE_PROJECT_CONFIRM,
            )
            viewModelScope.launch {
                runCatching { connectionProfileStore.removeWorktree(serverBaseUrl, pending.worktree) }
                runCatching { sessionCache.purgeWorktree(serverBaseUrl, pending.worktree) }
                logI("remove project worktree=${pending.worktree}")
                clearPendingProjectRemoval()
                runHomeLoad(skipProbe = true)
                _removalSnackbar.value =
                    OpenCodeSnackbarMessage(
                        text = "Removed \"${pending.displayName}\" from home",
                    )
            }
        }

        fun consumeProjectRemovalSnackbar() {
            if (_removalSnackbar.value != null) {
                logAction(
                    ProductAnalytics.Screen.HOME,
                    ProductAnalytics.Action.HOME_DISMISS_REMOVAL_SNACKBAR,
                )
            }
            _removalSnackbar.value = null
        }

        fun onSessionLongPress(
            sessionId: String,
            title: String,
            worktree: String,
        ) {
            logAction(
                ProductAnalytics.Screen.HOME,
                ProductAnalytics.Action.HOME_LONG_PRESS_SESSION,
            )
            sessionActionsRegion.begin(
                sessionId = sessionId,
                title = title,
                worktree = worktree,
                canArchive = activeConnectionManager.currentCapabilities().supportsSessionArchive,
            )
        }

        /**
         * Dismissal from the sheet chrome. Action handlers hide the sheet themselves and keep the
         * target alive for the dialog they open.
         */
        fun dismissSessionActionsSheet() {
            logAction(
                ProductAnalytics.Screen.HOME_SESSION_ACTIONS,
                ProductAnalytics.Action.SESSION_ACTIONS_DISMISS,
            )
            sessionActionsRegion.dismissSheet()
        }

        fun chooseRename() {
            if (sessionActions.value.target == null) return
            logAction(
                ProductAnalytics.Screen.HOME_SESSION_ACTIONS,
                ProductAnalytics.Action.SESSION_ACTIONS_RENAME,
            )
            renameDraftEditReported = false
            sessionActionsRegion.chooseRename()
        }

        fun updateRenameDraft(text: String) {
            if (!renameDraftEditReported) {
                renameDraftEditReported = true
                logAction(
                    ProductAnalytics.Screen.HOME_RENAME_SESSION,
                    ProductAnalytics.Action.HOME_RENAME_EDIT_TITLE,
                )
            }
            sessionActionsRegion.updateRenameDraft(text)
        }

        fun cancelRename() {
            if (sessionActions.value.renameSubmitting) return
            logAction(
                ProductAnalytics.Screen.HOME_RENAME_SESSION,
                ProductAnalytics.Action.HOME_RENAME_CANCEL,
            )
            sessionActionsRegion.cancelRename()
        }

        fun confirmRename() {
            sessionActionsRegion.confirmRename { outcome ->
                logAction(
                    ProductAnalytics.Screen.HOME_RENAME_SESSION,
                    ProductAnalytics.Action.HOME_RENAME_SAVE,
                    mapOf(ProductAnalytics.Param.OUTCOME to outcome),
                )
            }
        }

        fun chooseFork() {
            val state = sessionActions.value
            if (state.target == null || !state.canFork) return
            logAction(
                ProductAnalytics.Screen.HOME_SESSION_ACTIONS,
                ProductAnalytics.Action.SESSION_ACTIONS_FORK,
            )
            forkDraftEditReported = false
            sessionActionsRegion.chooseFork()
        }

        fun updateForkDraft(text: String) {
            if (!forkDraftEditReported) {
                forkDraftEditReported = true
                logAction(
                    ProductAnalytics.Screen.HOME_FORK_SESSION,
                    ProductAnalytics.Action.HOME_FORK_EDIT_TITLE,
                )
            }
            sessionActionsRegion.updateForkDraft(text)
        }

        fun cancelFork() {
            if (sessionActions.value.forkSubmitting) return
            logAction(
                ProductAnalytics.Screen.HOME_FORK_SESSION,
                ProductAnalytics.Action.HOME_FORK_CANCEL,
            )
            sessionActionsRegion.cancelFork()
        }

        fun confirmFork() {
            sessionActionsRegion.confirmFork { outcome ->
                logAction(
                    ProductAnalytics.Screen.HOME_FORK_SESSION,
                    ProductAnalytics.Action.HOME_FORK_CONFIRM,
                    mapOf(ProductAnalytics.Param.OUTCOME to outcome),
                )
            }
        }

        fun chooseForkOpen() {
            logAction(
                ProductAnalytics.Screen.HOME_FORK_RESULT,
                ProductAnalytics.Action.HOME_FORK_OPEN,
            )
            val target = sessionActionsRegion.takeForkedOpenTarget() ?: return
            _uiState.update { state ->
                if (state is MainScreenUiState.Loaded) state.copy(pendingOpenSession = target) else state
            }
        }

        fun chooseForkStay() {
            logAction(
                ProductAnalytics.Screen.HOME_FORK_RESULT,
                ProductAnalytics.Action.HOME_FORK_STAY,
            )
            sessionActionsRegion.clearForkedOpenTarget()
        }

        fun chooseArchive() {
            val state = sessionActions.value
            if (state.target == null || !state.canArchive || state.lifecycleInFlight) return
            logAction(
                ProductAnalytics.Screen.HOME_SESSION_ACTIONS,
                ProductAnalytics.Action.SESSION_ACTIONS_ARCHIVE,
            )
            sessionActionsRegion.chooseArchive()
        }

        fun chooseDelete() {
            if (sessionActions.value.target == null) return
            logAction(
                ProductAnalytics.Screen.HOME_SESSION_ACTIONS,
                ProductAnalytics.Action.SESSION_ACTIONS_DELETE,
            )
            sessionActionsRegion.chooseDelete()
        }

        fun dismissDeleteConfirm() {
            if (sessionActions.value.lifecycleInFlight) return
            logAction(
                ProductAnalytics.Screen.HOME_DELETE_SESSION_CONFIRM,
                ProductAnalytics.Action.HOME_DELETE_CANCEL,
            )
            sessionActionsRegion.dismissDeleteConfirm()
        }

        fun confirmDelete() {
            val state = sessionActions.value
            if (state.target == null || state.lifecycleInFlight) return
            logAction(
                ProductAnalytics.Screen.HOME_DELETE_SESSION_CONFIRM,
                ProductAnalytics.Action.HOME_DELETE_CONFIRM,
            )
            sessionActionsRegion.confirmDelete()
        }

        private fun logAction(
            screenId: String,
            actionId: String,
            parameters: Map<String, String> = emptyMap(),
        ) {
            ProductAnalytics.logUserAction(
                screenId = screenId,
                actionId = actionId,
                parameters = parameters,
            )
        }

        private fun logI(message: String) {
            DiagnosticLog.info(DiagnosticLogArea.Chat, message)
        }

        private fun logW(message: String) {
            DiagnosticLog.warning(DiagnosticLogArea.Chat, message)
        }

        private suspend fun runHomeLoad(
            skipProbe: Boolean = false,
            preserveContent: Boolean = false,
        ) {
            if (activeConnectionManager.current() == null) {
                _uiState.value = MainScreenUiState.Disconnected
                return
            }

            val worktrees = connectionProfileStore.addedWorktrees(serverBaseUrl)

            // Disk-first home: local worktrees + Session Cache paint Loaded
            // without Probing/Loading. Live store then SWR-refreshes.
            if (!preserveContent && worktrees.isNotEmpty()) {
                if (_uiState.value is MainScreenUiState.Loaded && liveObservationsRunning(worktrees)) {
                    return
                }
                val sections = loadSectionsFromCache(worktrees)
                _uiState.value = MainScreenUiState.Loaded(sections = sections)
                if (!skipProbe) {
                    runCatching { projectRepository.probeServer() }
                        .onFailure {
                            // Keep cached home; connection chrome reflects reachability.
                        }
                }
                startLiveObservations(worktrees)
                return
            }

            if (worktrees.isEmpty() && !preserveContent) {
                stopLiveObservations()
                _uiState.value = MainScreenUiState.Empty()
                if (!skipProbe) {
                    runCatching { projectRepository.probeServer() }
                        .onFailure { error ->
                            _uiState.value =
                                MainScreenUiState.ProbeFailed(
                                    when (error) {
                                        is ServerProbeError -> probeErrorMessage(error)
                                        else -> error.message ?: "Unable to reach the server"
                                    },
                                )
                        }
                }
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
                if (worktrees.isEmpty()) {
                    stopLiveObservations()
                    _uiState.value =
                        when {
                            preserveContent && previous is MainScreenUiState.Empty -> previous
                            else -> MainScreenUiState.Empty()
                        }
                    return
                }

                val sections = loadProjectSections(worktrees)
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
                            previous.copy(sections = sections)
                        else ->
                            MainScreenUiState.Loaded(sections = sections)
                    }
                startLiveObservations(worktrees)
            } catch (error: Throwable) {
                if (preserveContent) return
                stopLiveObservations()
                _uiState.value = MainScreenUiState.ProbeFailed(error.message ?: "Unable to load projects")
            }
        }

        private suspend fun loadSectionsFromCache(worktrees: List<String>): List<ProjectSectionUi> =
            worktrees.map { worktree -> sectionFromCache(worktree) }

        private suspend fun sectionFromCache(worktree: String): ProjectSectionUi {
            val seeded = runCatching { sessionCache.seedSessions(worktree) }.getOrNull()
            val sessions =
                seeded
                    ?.activeSessions()
                    ?.rootSessions()
                    ?.sortedByDescending { it.updatedAtEpochMs ?: 0L }
                    .orEmpty()
            val summaries =
                sessions.map { session ->
                    sessionDomainAdapter.toSessionSummary(session)
                }
            val displayName = ProjectDomainAdapter.displayNameFromWorktree(worktree)
            return ProjectSectionUi(
                worktree = worktree,
                displayName = displayName,
                pathSubtitle = worktree,
                letter = letterCharacterForProject(displayName),
                letterAccent = letterAccentForWorktree(worktree),
                allSessions = summaries,
                loadError = null,
                sessionsFromCache = seeded != null,
            )
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
                                        allSessions = emptyList(),
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
            val summaries =
                sessions.map { session ->
                    sessionDomainAdapter.toSessionSummary(session)
                }
            return ProjectSectionUi(
                worktree = worktree,
                displayName = ProjectDomainAdapter.displayNameFromWorktree(worktree),
                pathSubtitle = worktree,
                letter = letterCharacterForProject(ProjectDomainAdapter.displayNameFromWorktree(worktree)),
                letterAccent = letterAccentForWorktree(worktree),
                allSessions = summaries,
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
            error.userMessage()

        private fun startLiveObservations(worktrees: List<String>) {
            if (liveObservationsRunning(worktrees)) return
            stopLiveObservations()
            observedWorktrees = worktrees
            liveObservationJobs =
                worktrees.map { worktree ->
                    viewModelScope.launch {
                        combine(
                            sessionLiveStore.observeSessions(worktree),
                            sessionStatusLiveStore.observeActivity(worktree),
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
            observedWorktrees = emptyList()
        }

        private fun liveObservationsRunning(worktrees: List<String>): Boolean =
            liveObservationJobs.isNotEmpty() && observedWorktrees == worktrees

        private fun updateSectionFromLiveData(
            worktree: String,
            sessions: List<Session>,
            snapshot: SessionActivitySnapshot,
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
                            statusLabel =
                                if (snapshot.seeded || snapshot.fromCache) {
                                    snapshot.entries[session.id]?.activity?.statusLabel()
                                } else {
                                    null
                                },
                            isReady = session.id in readyIds,
                            statusStale = snapshot.fromCache && !snapshot.seeded,
                        )
                    }
            val updatedSection =
                section.copy(
                    allSessions = summaries,
                    sessionsFromCache = sessionsFromCache,
                )
            val updatedSections =
                state.sections.map { current ->
                    if (current.worktree == worktree) updatedSection else current
                }
            _uiState.value = state.copy(sections = updatedSections)
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
    }

fun suggestedHomeForkTitle(currentTitle: String): String {
    val trimmed = currentTitle.trim()
    return if (trimmed.isEmpty()) "Forked session" else "Fork of $trimmed"
}

data class HomeServersChrome(
    val title: String = "Sessions",
    val isSwitcher: Boolean = false,
    val active: ConnectionProfile? = null,
    val others: List<ConnectionProfile> = emptyList(),
    val sheetVisible: Boolean = false,
)

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
        val pendingOpenSession: SessionOpenTarget? = null,
        val projectSessionCreateError: String? = null,
        val pendingProjectRemoval: ProjectRemovalUi? = null,
    ) : MainScreenUiState
}

data class ProjectRemovalUi(
    val worktree: String,
    val displayName: String,
)

data class HomeSessionActionTarget(
    val sessionId: String,
    val title: String,
    val worktree: String,
)

data class HomeSessionActionsUiState(
    val target: HomeSessionActionTarget? = null,
    val sheetVisible: Boolean = false,
    val canFork: Boolean = false,
    val canArchive: Boolean = true,
    val renameVisible: Boolean = false,
    val renameDraft: String = "",
    val renameSubmitting: Boolean = false,
    val renameError: String? = null,
    val forkVisible: Boolean = false,
    val forkDraft: String = "",
    val forkSubmitting: Boolean = false,
    val forkError: String? = null,
    val forkResultVisible: Boolean = false,
    val forkedTitle: String = "",
    val deleteConfirmVisible: Boolean = false,
    val lifecycleInFlight: Boolean = false,
) {
    val hasOpenDialog: Boolean
        get() = renameVisible || forkVisible || forkResultVisible || deleteConfirmVisible || lifecycleInFlight
}

data class ProjectSectionUi(
    val worktree: String,
    val displayName: String,
    val pathSubtitle: String,
    val letter: Char,
    val letterAccent: LetterAccent,
    val allSessions: List<SessionSummary>,
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

data class SessionOpenTarget(
    val sessionId: String,
    val title: String,
    val worktree: String,
    val optimisticNavigationInstanceId: String? = null,
)
