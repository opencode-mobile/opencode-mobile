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
import dev.opencode.mobile.domain.event.FixedLiveUpdates
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.IdleForegroundEventStream
import dev.opencode.mobile.domain.event.LiveUpdates
import dev.opencode.mobile.domain.event.OpencodeEvent
import dev.opencode.mobile.domain.event.SessionMovedUpdate
import dev.opencode.mobile.domain.event.namedProjectId
import dev.opencode.mobile.domain.event.sessionMoved
import dev.opencode.mobile.domain.filesystem.DirectoryNotFoundException
import dev.opencode.mobile.domain.filesystem.FilesystemRepository
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeProjectGrouping
import dev.opencode.mobile.domain.home.HomeProjectsSnapshot
import dev.opencode.mobile.domain.home.HomeWorktreeCopy
import dev.opencode.mobile.domain.home.ResolvedHomePath
import dev.opencode.mobile.domain.live.LiveStoreRefreshPolicy
import dev.opencode.mobile.domain.live.SessionListSnapshot
import dev.opencode.mobile.domain.live.SessionLiveStore
import dev.opencode.mobile.domain.live.SessionReadyStore
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.domain.session.OptimisticSessionCreate
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionActivitySnapshot
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionForkTitle
import dev.opencode.mobile.domain.session.SessionIdleOutcome
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.domain.session.activeSessions
import dev.opencode.mobile.domain.session.rootSessions
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.worktree.UnavailableWorktreeRepository
import dev.opencode.mobile.domain.worktree.WorktreeInventory
import dev.opencode.mobile.domain.worktree.WorktreeInventoryCache
import dev.opencode.mobile.domain.worktree.WorktreeRepository
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.flow.distinctUntilChanged

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
        private val sessionLiveStore: SessionLiveStore,
        private val sessionStatusLiveStore: SessionStatusLiveStore,
        private val sessionReadyStore: SessionReadyStore,
        private val activeConnectionManager: ActiveConnectionManager,
        private val connectionSessionCoordinator: ConnectionSessionCoordinator,
        private val sessionCache: SessionCache,
        private val liveUpdates: LiveUpdates = FixedLiveUpdates(),
        worktreeRepository: WorktreeRepository = UnavailableWorktreeRepository,
        private val foregroundEventStream: ForegroundEventStream = IdleForegroundEventStream,
        projectFirstLoadTiming: ProjectFirstLoadTiming = ProjectFirstLoadTiming(),
        private val homeProjectsSnapshot: HomeProjectsSnapshot = HomeProjectsSnapshot(),
        private val worktreeInventoryCache: WorktreeInventoryCache = WorktreeInventoryCache(),
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<MainScreenUiState>(MainScreenUiState.Probing)
        val uiState: StateFlow<MainScreenUiState> = _uiState.asStateFlow()
        private val _isRefreshing = MutableStateFlow(false)
        val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
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
        private val _pendingScopeRequest = MutableStateFlow<HomeScopeRequest?>(null)
        val pendingScopeRequest: StateFlow<HomeScopeRequest?> = _pendingScopeRequest.asStateFlow()
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

        /** Worktrees whose combined sessions + status + ready stream has painted since observations started. */
        private val livePaintedWorktrees = mutableSetOf<String>()
        private val pendingFirstServerSnapshots = mutableMapOf<String, SessionListSnapshot>()
        private val worktreeLoader =
            HomeWorktreeLoader(
                repository = worktreeRepository,
                currentProject = { directory -> projectRepository.getCurrentProject(directory) },
            )
        private val projectFirstLoads =
            HomeProjectFirstLoads(
                scope = viewModelScope,
                timing = projectFirstLoadTiming,
                observeSessions = { worktree -> sessionLiveStore.observeSessions(worktree) },
                refetchSessions = { worktree -> sessionLiveStore.invalidate(worktree) },
                onServerSnapshot = { worktree, snapshot -> paintFirstServerSnapshot(worktree, snapshot) },
                onChange = { loads ->
                    _uiState.update { state ->
                        if (state is MainScreenUiState.Loaded) state.copy(projectLoads = loads) else state
                    }
                },
            )

        /** Projects, worktrees, branches, and (v1) other-worktree sessions; published on Loaded. */
        private var homeIndex = HomeWorktreeIndex()

        /** V1 added paths whose listed worktrees each need a session list. */
        private val listsSessionsPaths = mutableSetOf<String>()

        /** V1 live lists of worktrees that are not added paths, by canonical directory. */
        private var extraObservationJobs: Map<String, Job> = emptyMap()

        /** Branch per worktree directory; only a settled server answer decides it. */
        private val branchLookups =
            HomeBranchLookups(
                scope = viewModelScope,
                fetch = ::lookUpBranch,
                onChange = { branches -> updateHomeIndex { it.copy(branches = branches) } },
            )
        private var worktreeMarkJob: Job? = null

        // Title fields report one edit per dialog appearance instead of per keystroke.
        private var renameDraftEditReported = false
        private var forkDraftEditReported = false

        // Add project sheet session; results from an earlier session are dropped by generation.
        private var addProjectSession: AddProjectSheetSession? = null
        private var addProjectGeneration = 0
        private var addProjectDebounceJob: Job? = null

        // Row summaries (folder count, git) for visible browser rows: FIFO, a few at a time,
        // each path tried once per sheet session.
        private val addProjectSummaryQueue = ArrayDeque<String>()
        private val addProjectSummariesInFlight = mutableSetOf<String>()
        private val addProjectSummaryFailures = mutableSetOf<String>()

        init {
            refresh()
            viewModelScope.launch {
                // Session Chat reads the session's project and worktrees from here.
                _uiState
                    .map { (it as? MainScreenUiState.Loaded)?.projects.orEmpty() }
                    .distinctUntilChanged()
                    .collect(homeProjectsSnapshot::publish)
            }
            viewModelScope.launch {
                connectionSessionCoordinator.profileSaved.collect {
                    stopLiveObservations()
                    projectFirstLoads.clearAll()
                    pendingFirstServerSnapshots.clear()
                    resetHomeIndex()
                    refresh()
                }
            }
            viewModelScope.launch {
                connectionSessionCoordinator.profileCleared.collect {
                    stopLiveObservations()
                    projectFirstLoads.clearAll()
                    pendingFirstServerSnapshots.clear()
                    resetHomeIndex()
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
                        if (_isRefreshing.value) return@launch
                        _isRefreshing.value = true
                        // A stream backing off (e.g. after a rejected credential) retries now.
                        foregroundEventStream.onReconnectWake()
                        try {
                            if (observedWorktrees.isNotEmpty()) reloadWorktreeMarks(observedWorktrees, refreshProjects = true)
                            sessionLiveStore.invalidate(null)
                            sessionStatusLiveStore.invalidate(null)
                        } finally {
                            _isRefreshing.value = false
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
            openAddProjectSheet()
        }

        fun dismissAddProjectSheet() {
            endAddProjectSession()
            _uiState.update { state ->
                when (state) {
                    is MainScreenUiState.Empty ->
                        state.copy(showAddProjectSheet = false, addProjectSheet = null)
                    is MainScreenUiState.Loaded ->
                        state.copy(showAddProjectSheet = false, addProjectSheet = null)
                    else -> state
                }
            }
        }

        /** Typing in the path field: filters locally, and lists a new folder after a pause. */
        fun updateAddProjectField(text: String) {
            val session = addProjectSession ?: return
            if (session.fieldText == text) return
            updateAddProjectSession { it.copy(fieldText = text, fieldError = null) }
            scheduleAddProjectListing()
        }

        fun addProjectUp() {
            val target = currentAddProjectSheet()?.upTarget ?: return
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_GO_UP,
            )
            setAddProjectFolder(target)
        }

        fun selectAddProjectRoot(path: String) {
            if (addProjectSession == null) return
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_SELECT_ROOT,
            )
            setAddProjectFolder(path)
        }

        fun openAddProjectFolder(path: String) {
            if (addProjectSession == null) return
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_ENTER_DIRECTORY,
            )
            setAddProjectFolder(path)
        }

        /**
         * Return in the path field: opens [AddProjectSheetUi.goTarget] (the exact child match, or
         * the only matching row). Does nothing otherwise; never adds.
         */
        fun submitAddProjectField() {
            if (addProjectSession == null) return
            val target = currentAddProjectSheet()?.goTarget ?: return
            openAddProjectFolder(target)
        }

        /** A browser row came on screen: queue its folder count and git check. */
        fun addProjectFolderRowAppeared(path: String) {
            val session = addProjectSession ?: return
            val key = ServerPathPolicy.canonical(path)
            if (session.listings.containsKey(key) ||
                key in addProjectSummariesInFlight ||
                key in addProjectSummaryQueue ||
                key in addProjectSummaryFailures
            ) {
                return
            }
            addProjectSummaryQueue.addLast(key)
            startAddProjectSummaries()
        }

        /** A browser row left the screen: drop its summary if it has not started. */
        fun addProjectFolderRowDisappeared(path: String) {
            addProjectSummaryQueue.remove(ServerPathPolicy.canonical(path))
        }

        fun selectAddProjectSuggestion(path: String) {
            if (addProjectSession?.isValidating != false) return
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_SELECT_SUGGESTION,
            )
            addProject(path)
        }

        /** Replaces the suggestion sheet with the full-screen folder browser. */
        fun browseAddProjectFolders() {
            if (addProjectSession == null || addProjectSession?.isValidating == true) return
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_BROWSE,
            )
            updateAddProjectSession { it.copy(browsing = true) }
        }

        /**
         * Back from the browser. Returns to the suggestion sheet at the server home, so
         * "On this server" is not lost because the field was left in a subfolder.
         */
        fun closeAddProjectBrowser() {
            val session = addProjectSession ?: return
            if (!session.browsing) return
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_BROWSE_BACK,
            )
            val home = session.home
            if (home.isEmpty()) {
                updateAddProjectSession { it.copy(browsing = false) }
                return
            }
            addProjectDebounceJob?.cancel()
            updateAddProjectSession {
                it.copy(
                    browsing = false,
                    fieldText = ServerPathPolicy.fieldText(home),
                    fieldError = null,
                )
            }
            loadAddProjectListing(home)
        }

        fun retryAddProjectListing() {
            val session = addProjectSession ?: return
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_RETRY_NAVIGATOR,
            )
            if (session.openError != null) {
                openAddProjectSheet()
                return
            }
            val directory = session.parts.directory ?: return
            updateAddProjectSession { it.copy(listings = it.listings - directory) }
            loadAddProjectListing(directory)
        }

        fun confirmAddProject() {
            val sheet = currentAddProjectSheet() ?: return
            val target = sheet.addTarget
            if (!sheet.addEnabled || target == null) return
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_SELECT_CURRENT,
            )
            addProject(target)
        }

        fun addProject(worktree: String) {
            viewModelScope.launch {
                val added = connectionProfileStore.addedWorktrees(serverBaseUrl)
                if (worktree in added) {
                    dismissAddProjectSheet()
                    runHomeLoad(skipProbe = true)
                    _pendingScopeRequest.value = HomeScopeRequest(filterId = worktree, evenFromAll = false)
                    _removalSnackbar.value =
                        OpenCodeSnackbarMessage(
                            text =
                                "\"${ProjectDomainAdapter.displayNameFromWorktree(worktree)}\" is already on home",
                        )
                    return@launch
                }
                updateAddProjectSession { it.copy(isValidating = true, fieldError = null) }
                val result = runCatching { projectRepository.getCurrentProject(directory = worktree) }
                result
                    .onSuccess { project ->
                        // A path of a repository already on Home joins that project as a worktree.
                        val resolution = ResolvedHomePath.from(project)
                        val joined =
                            currentHomeProjects().firstOrNull { home ->
                                resolution.isMergeable && home.projectId == resolution.projectId
                            }
                        worktreeLoader.remember(worktree, resolution)
                        updateHomeIndex { it.copy(resolutions = it.resolutions + (worktree to resolution)) }
                        // Persist the selected directory (web open-project behavior), not the
                        // /project/current worktree — non-git dirs resolve to global "/".
                        connectionProfileStore.addWorktree(serverBaseUrl, worktree)
                        projectFirstLoads.start(worktree)
                        dismissAddProjectSheet()
                        runHomeLoad(skipProbe = true)
                        _pendingScopeRequest.value = HomeScopeRequest(filterId = worktree, evenFromAll = true)
                        if (joined != null) {
                            _removalSnackbar.value =
                                OpenCodeSnackbarMessage(text = HomeWorktreeCopy.joinedProject(joined.displayName))
                        }
                    }.onFailure { error ->
                        updateAddProjectSession {
                            it.copy(
                                isValidating = false,
                                fieldError = error.message ?: "Unable to add this directory",
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

        fun consumePendingScopeRequest() {
            _pendingScopeRequest.value = null
        }

        /**
         * Failed first load of a newly added project: show loading again and refetch its sessions.
         * [scopeId] is an added path or a Home project key (every member path retries).
         */
        fun retryProjectLoad(scopeId: String) {
            logAction(ProductAnalytics.Screen.HOME, ProductAnalytics.Action.HOME_RETRY_PROJECT_LOAD)
            memberPathsOf(scopeId).forEach(projectFirstLoads::retry)
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
        fun reorderProjects(projectIds: List<String>) {
            val state = _uiState.value as? MainScreenUiState.Loaded ?: return
            // Picker rows are projects; each moves with every added path it groups.
            val worktrees = projectIds.flatMap { id -> memberPathsOf(id, state.projects) }.distinct()
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

        /** [projectId] is a Home project key or one of its added paths; removal takes every member path. */
        fun requestRemoveProject(projectId: String) {
            logAction(ProductAnalytics.Screen.HOME, ProductAnalytics.Action.HOME_REMOVE_PROJECT)
            _uiState.update { state ->
                if (state is MainScreenUiState.Loaded) {
                    val project = state.projects.firstOrNull { it.key == projectId || projectId in it.memberPaths }
                    if (project != null) {
                        state.copy(
                            pendingProjectRemoval =
                                ProjectRemovalUi(
                                    worktree = project.primaryPath,
                                    displayName = project.displayName,
                                    worktrees = project.memberPaths,
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
                for (worktree in pending.worktrees) {
                    runCatching { connectionProfileStore.removeWorktree(serverBaseUrl, worktree) }
                    runCatching { sessionCache.purgeWorktree(serverBaseUrl, worktree) }
                    projectFirstLoads.clear(worktree)
                    pendingFirstServerSnapshots.remove(worktree)
                    worktreeLoader.forget(worktree)
                    listsSessionsPaths.remove(worktree)
                    logI("remove project worktree=$worktree")
                }
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
            if (state.target == null) return
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
                    reloadWorktreeMarks(worktrees)
                    return
                }
                val sections = loadSectionsFromCache(worktrees)
                _uiState.value =
                    MainScreenUiState.Loaded(sections = sections, projectLoads = projectFirstLoads.visible, home = homeIndex)
                applyPendingFirstServerSnapshots()
                if (!skipProbe) {
                    runCatching { projectRepository.probeServer() }
                        .onFailure {
                            // Keep cached home; connection chrome reflects reachability.
                        }
                }
                startLiveObservations(worktrees)
                reloadWorktreeMarks(worktrees)
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
                            previous.copy(sections = sections, home = homeIndex)
                        else ->
                            MainScreenUiState.Loaded(
                                sections = sections,
                                projectLoads = projectFirstLoads.visible,
                                home = homeIndex,
                            )
                    }
                applyPendingFirstServerSnapshots()
                startLiveObservations(worktrees)
                reloadWorktreeMarks(worktrees)
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
            // No status here, so no unread outcome: the live paint adds both once seeded.
            val summaries = sessions.map { session -> sessionDomainAdapter.toSessionSummary(session) }
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
            // No status here, so no unread outcome: the live paint adds both once seeded.
            val summaries = sessions.map { session -> sessionDomainAdapter.toSessionSummary(session) }
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

        private fun openAddProjectSheet() {
            endAddProjectSession()
            val generation = addProjectGeneration
            addProjectSession = AddProjectSheetSession()
            publishAddProjectSheet()
            viewModelScope.launch {
                val paths =
                    try {
                        filesystemRepository.getPaths()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        DiagnosticLog.warning(DiagnosticLogArea.Connection, "add project home lookup failed", error)
                        if (generation != addProjectGeneration) return@launch
                        updateAddProjectSession {
                            it.copy(isOpening = false, openError = AddProjectSheetUi.LIST_ERROR_MESSAGE)
                        }
                        return@launch
                    }
                val added = connectionProfileStore.addedWorktrees(serverBaseUrl)
                if (generation != addProjectGeneration) return@launch
                val home = ServerPathPolicy.canonical(paths.home)
                updateAddProjectSession {
                    it.copy(
                        home = home,
                        isOpening = false,
                        openError = null,
                        fieldText = ServerPathPolicy.fieldText(home),
                        addedWorktrees = added,
                        addedProjectIds =
                            added
                                .mapNotNull { path -> homeIndex.resolutions[path]?.takeIf { it.isMergeable }?.projectId }
                                .toSet(),
                    )
                }
                loadAddProjectListing(home)
                loadAddProjectServerProjects(generation)
                if (ServerPathPolicy.root(home) == "/") loadAddProjectVolumes(generation)
            }
        }

        private fun endAddProjectSession() {
            addProjectGeneration++
            addProjectDebounceJob?.cancel()
            addProjectDebounceJob = null
            addProjectSession = null
            addProjectSummaryQueue.clear()
            addProjectSummariesInFlight.clear()
            addProjectSummaryFailures.clear()
        }

        /** Chip, row, and Up taps list the folder right away. */
        private fun setAddProjectFolder(path: String) {
            addProjectDebounceJob?.cancel()
            updateAddProjectSession {
                it.copy(fieldText = ServerPathPolicy.fieldText(path), fieldError = null)
            }
            addProjectSession?.parts?.directory?.let(::loadAddProjectListing)
        }

        private fun scheduleAddProjectListing() {
            addProjectDebounceJob?.cancel()
            val directory = addProjectSession?.parts?.directory ?: return
            if (addProjectSession?.listings?.containsKey(directory) == true) return
            addProjectDebounceJob =
                viewModelScope.launch {
                    delay(AddProjectSheetModel.LISTING_DEBOUNCE_MS)
                    loadAddProjectListing(directory)
                }
        }

        /** Lists [directory] once per sheet session; a failure stays until Retry. */
        private fun loadAddProjectListing(directory: String) {
            val session = addProjectSession ?: return
            if (session.listings.containsKey(directory)) return
            val generation = addProjectGeneration
            updateAddProjectSession { it.copy(listings = it.listings + (directory to AddProjectListing.Loading)) }
            viewModelScope.launch {
                val listing =
                    try {
                        AddProjectListing.Loaded(filesystemRepository.listDirectory(directory))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: DirectoryNotFoundException) {
                        AddProjectListing.NotFound
                    } catch (error: Exception) {
                        DiagnosticLog.warning(DiagnosticLogArea.Connection, "add project listing failed", error)
                        AddProjectListing.Failed(AddProjectSheetUi.LIST_ERROR_MESSAGE)
                    }
                if (generation != addProjectGeneration) return@launch
                updateAddProjectSession { session ->
                    val updated = session.copy(listings = session.listings + (directory to listing))
                    if (listing is AddProjectListing.Loaded) updated.withListedRoot(directory) else updated
                }
            }
        }

        /** Starts queued row summaries, oldest first, up to [AddProjectSheetModel.FOLDER_SUMMARY_CONCURRENCY]. */
        private fun startAddProjectSummaries() {
            while (addProjectSummariesInFlight.size < AddProjectSheetModel.FOLDER_SUMMARY_CONCURRENCY) {
                val path = addProjectSummaryQueue.removeFirstOrNull() ?: return
                // The browser may have listed it while it waited.
                if (addProjectSession?.listings?.containsKey(path) != false) continue
                loadAddProjectSummary(path)
            }
        }

        /**
         * Lists one row's folder into the browser's listing cache, so opening it is instant. A
         * failure only hides the row's count: no error, no retry this session.
         */
        private fun loadAddProjectSummary(path: String) {
            val generation = addProjectGeneration
            addProjectSummariesInFlight += path
            viewModelScope.launch {
                val listing =
                    try {
                        filesystemRepository.listDirectory(path)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        null
                    }
                if (generation != addProjectGeneration) return@launch
                addProjectSummariesInFlight -= path
                if (listing == null) {
                    addProjectSummaryFailures += path
                } else {
                    updateAddProjectSession { session ->
                        when (session.listings[path]) {
                            null, AddProjectListing.Loading ->
                                session
                                    .copy(listings = session.listings + (path to AddProjectListing.Loaded(listing)))
                                    .withListedRoot(path)
                            else -> session
                        }
                    }
                }
                startAddProjectSummaries()
            }
        }

        private fun loadAddProjectServerProjects(generation: Int) {
            viewModelScope.launch {
                val projects =
                    try {
                        projectRepository.listProjects()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        // The section stays hidden.
                        if (generation != addProjectGeneration) return@launch
                        updateAddProjectSession { it.copy(suggestionsResolved = true) }
                        return@launch
                    }
                if (generation != addProjectGeneration) return@launch
                updateAddProjectSession { it.copy(serverProjects = projects, suggestionsResolved = true) }
            }
        }

        private fun loadAddProjectVolumes(generation: Int) {
            viewModelScope.launch {
                val volumes =
                    try {
                        filesystemRepository.listDirectory(AddProjectSheetModel.VOLUMES_PATH).folders
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        // Linux has no /Volumes.
                        return@launch
                    }
                if (generation != addProjectGeneration) return@launch
                updateAddProjectSession { it.copy(volumes = volumes) }
            }
        }

        private fun currentAddProjectSheet(): AddProjectSheetUi? =
            when (val state = _uiState.value) {
                is MainScreenUiState.Empty -> state.addProjectSheet
                is MainScreenUiState.Loaded -> state.addProjectSheet
                else -> null
            }

        private fun updateAddProjectSession(transform: (AddProjectSheetSession) -> AddProjectSheetSession) {
            val session = addProjectSession ?: return
            addProjectSession = transform(session)
            publishAddProjectSheet()
        }

        private fun publishAddProjectSheet() {
            val sheet = addProjectSession?.let { AddProjectSheetModel.build(it) } ?: return
            _uiState.update { state ->
                when (state) {
                    is MainScreenUiState.Empty ->
                        state.copy(showAddProjectSheet = true, addProjectSheet = sheet)
                    is MainScreenUiState.Loaded ->
                        state.copy(showAddProjectSheet = true, addProjectSheet = sheet)
                    else -> state
                }
            }
        }

        private fun probeErrorMessage(error: ServerProbeError): String = error.userMessage()

        private fun startLiveObservations(worktrees: List<String>) {
            if (liveObservationsRunning(worktrees)) return
            stopLiveObservations()
            observedWorktrees = worktrees
            livePaintedWorktrees.clear()
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
                            livePaintedWorktrees += worktree
                            updateSectionFromLiveData(
                                worktree,
                                snapshot.sessions,
                                statuses,
                                readyIds,
                                sessionsFromCache = snapshot.isFromCache,
                            )
                        }
                    }
                } +
                    viewModelScope.launch {
                        foregroundEventStream.events.collect { event ->
                            event.sessionMoved()?.let { moved ->
                                applySessionMoved(moved)
                                return@collect
                            }
                            val projectId = event.namedProjectId() ?: return@collect
                            if (event.type != "worktree.updated") return@collect
                            if (!worktreeLoader.knows(projectId)) return@collect
                            worktreeLoader.refetch(projectId).forEach(::applyPathLoad)
                        }
                    }
            syncExtraObservations()
        }

        /**
         * Resolves every added path's project (so Home groups as soon as possible), then loads
         * each project's worktrees and their branches. Sessions never wait for this.
         */
        private fun reloadWorktreeMarks(
            worktrees: List<String>,
            refreshProjects: Boolean = false,
        ) {
            worktreeMarkJob?.cancel()
            worktreeLoader.onConnection(serverBaseUrl)
            worktreeInventoryCache.setAddedPaths(worktrees, serverBaseUrl)
            branchLookups.refresh()
            worktreeMarkJob =
                viewModelScope.launch {
                    val resolved =
                        coroutineScope {
                            worktrees
                                .map { worktree ->
                                    async { worktree to worktreeLoader.resolve(worktree, refresh = refreshProjects) }
                                }.awaitAll()
                        }
                    updateHomeIndex { index ->
                        index.copy(resolutions = index.resolutions + resolved.mapNotNull { (path, it) -> it?.let { path to it } })
                    }
                    requestBranches()
                    for ((worktree, resolution) in resolved) {
                        applyPathLoad(worktreeLoader.load(worktree, resolution))
                    }
                }
        }

        private fun applyPathLoad(load: HomePathLoad) {
            if (load.listsSessions) listsSessionsPaths += load.addedPath else listsSessionsPaths -= load.addedPath
            worktreeInventoryCache.record(load.inventory, load.addedPath, serverBaseUrl)
            updateHomeIndex { index ->
                val resolutions = load.resolution?.let { index.resolutions + (load.addedPath to it) } ?: index.resolutions
                val inventories =
                    when (val inventory = load.inventory) {
                        is WorktreeInventory.Loaded -> index.inventories + (load.addedPath to inventory.directories)
                        WorktreeInventory.Unloaded -> index.inventories - load.addedPath
                        // A failed reload keeps the last list.
                        WorktreeInventory.Failed -> index.inventories
                    }
                index.copy(resolutions = resolutions, inventories = inventories)
            }
            syncExtraObservations()
            requestBranches()
        }

        /** Regroups a moved session's row at once; the list refetch on the same event confirms it. */
        private fun applySessionMoved(moved: SessionMovedUpdate) {
            updateHomeIndex { it.copy(extraSessions = it.extraSessions.withSessionMoved(moved)) }
            _uiState.update { state ->
                if (state is MainScreenUiState.Loaded) state.withSessionMoved(moved) else state
            }
        }

        private fun updateHomeIndex(transform: (HomeWorktreeIndex) -> HomeWorktreeIndex) {
            val next = transform(homeIndex)
            if (next == homeIndex) return
            homeIndex = next
            _uiState.update { state ->
                if (state is MainScreenUiState.Loaded) state.copy(home = next) else state
            }
        }

        private fun resetHomeIndex() {
            worktreeLoader.onDisconnected()
            worktreeInventoryCache.clear()
            listsSessionsPaths.clear()
            branchLookups.clear()
            homeIndex = HomeWorktreeIndex()
        }

        private fun currentHomeProjects(): List<HomeProject> = (_uiState.value as? MainScreenUiState.Loaded)?.projects.orEmpty()

        /** Added paths for a project key, or the path itself. */
        private fun memberPathsOf(
            id: String,
            projects: List<HomeProject> = currentHomeProjects(),
        ): List<String> =
            projects.firstOrNull { it.key == id }?.memberPaths
                ?: projects.firstOrNull { id in it.memberPaths }?.memberPaths
                ?: listOf(id)

        /** Branch names for every project worktree not answered since the last reload. */
        private fun requestBranches() {
            branchLookups.lookUp(
                currentHomeProjects()
                    .filter { it.projectId != null }
                    .flatMap { project -> project.worktrees.map { it.directory } },
            )
        }

        /**
         * One branch lookup. A v2 answer before VCS detection settles, or a failed request, is
         * unknown (asked again); a 4xx means the server has no branch for it.
         */
        private suspend fun lookUpBranch(directory: String): HomeBranchLookups.Outcome =
            try {
                val info = sessionRepository.getVcsInfo(directory)
                if (info.isSettled) {
                    HomeBranchLookups.Outcome.Branch(HomeProjectGrouping.normalizedBranch(info.currentBranch))
                } else {
                    HomeBranchLookups.Outcome.Unknown
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: SessionError.Http) {
                if (error.statusCode in 400..499) HomeBranchLookups.Outcome.Branch(null) else HomeBranchLookups.Outcome.Unknown
            } catch (error: Exception) {
                logI("home branch failed directory=$directory: ${error.message}")
                HomeBranchLookups.Outcome.Unknown
            }

        /**
         * V1 lists sessions per directory, so each listed worktree that is not an added path gets
         * its own live list. It refetches on the same session events as the added paths.
         */
        private fun syncExtraObservations() {
            if (liveObservationJobs.isEmpty()) return
            val wanted = homeExtraDirectories(observedWorktrees, homeIndex.inventories, listsSessionsPaths).toSet()
            if (wanted == extraObservationJobs.keys) return
            val kept = extraObservationJobs.filterKeys { it in wanted }
            extraObservationJobs.filterKeys { it !in wanted }.values.forEach { it.cancel() }
            val started =
                (wanted - kept.keys).associateWith { directory ->
                    viewModelScope.launch {
                        combine(
                            sessionLiveStore.observeSessions(directory),
                            sessionStatusLiveStore.observeActivity(directory),
                            sessionReadyStore.observeReadyIds(directory),
                        ) { snapshot, statuses, readyIds ->
                            homeSummaries(snapshot.sessions, statuses, readyIds).map { summary ->
                                if (summary.directory.isNullOrBlank()) summary.copy(directory = directory) else summary
                            }
                        }.collect { summaries ->
                            updateHomeIndex { it.copy(extraSessions = it.extraSessions + (directory to summaries)) }
                        }
                    }
                }
            extraObservationJobs = kept + started
            updateHomeIndex { it.copy(extraSessions = it.extraSessions.filterKeys { directory -> directory in wanted }) }
        }

        private fun stopLiveObservations() {
            liveObservationJobs.forEach { it.cancel() }
            liveObservationJobs = emptyList()
            extraObservationJobs.values.forEach { it.cancel() }
            extraObservationJobs = emptyMap()
            observedWorktrees = emptyList()
            livePaintedWorktrees.clear()
            worktreeMarkJob?.cancel()
            worktreeMarkJob = null
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
            val updatedSection =
                section.copy(
                    allSessions = homeSummaries(sessions, snapshot, readyIds),
                    sessionsFromCache = sessionsFromCache,
                )
            val updatedSections =
                state.sections.map { current ->
                    if (current.worktree == worktree) updatedSection else current
                }
            _uiState.value = state.copy(sections = updatedSections)
        }

        /**
         * A newly added project's first server list paints right away, even while its status or
         * ready-id stream has not emitted (the combined stream waits for all three). Statuses stay
         * unknown until that stream overwrites the section.
         */
        private fun paintFirstServerSnapshot(
            worktree: String,
            snapshot: SessionListSnapshot,
        ) {
            if (worktree in livePaintedWorktrees) {
                pendingFirstServerSnapshots.remove(worktree)
                return
            }
            val loaded = _uiState.value as? MainScreenUiState.Loaded
            if (loaded?.sections?.any { it.worktree == worktree } != true) {
                // Home has not painted the project's section yet; runHomeLoad applies this after it does.
                pendingFirstServerSnapshots[worktree] = snapshot
                return
            }
            pendingFirstServerSnapshots.remove(worktree)
            updateSectionFromLiveData(
                worktree,
                snapshot.sessions,
                SessionActivitySnapshot(),
                emptySet(),
                sessionsFromCache = false,
            )
        }

        private fun applyPendingFirstServerSnapshots() {
            pendingFirstServerSnapshots.toMap().forEach { (worktree, snapshot) ->
                paintFirstServerSnapshot(worktree, snapshot)
            }
        }

        /** Home rows of one session list: active roots, newest first, with status and unread outcome. */
        private fun homeSummaries(
            sessions: List<Session>,
            snapshot: SessionActivitySnapshot,
            readyIds: Set<String>,
        ): List<SessionSummary> {
            // v2 servers own read state; the local per-device Ready ids stay a v1 fallback.
            val serverReadState = supportsSessionViewed()
            return sessions
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
                        isReady = !serverReadState && session.id in readyIds,
                        statusStale = snapshot.fromCache && !snapshot.seeded,
                        // Waits for seeded status (iOS parity): a session running again with an
                        // older unviewed `time.idle` must not paint Done/Failed before its status is known.
                        unreadOutcome = if (snapshot.seeded) session.homeUnreadOutcome(serverReadState) else null,
                    )
                }
        }

        private fun supportsSessionViewed(): Boolean = activeConnectionManager.currentCapabilities().supportsSessionViewed

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

fun suggestedHomeForkTitle(currentTitle: String): String = SessionForkTitle.suggested(currentTitle)

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
        val addProjectSheet: AddProjectSheetUi? = null,
        val projectSessionCreateError: String? = null,
    ) : MainScreenUiState

    data class Loaded(
        val sections: List<ProjectSectionUi>,
        val showAddProjectSheet: Boolean = false,
        val addProjectSheet: AddProjectSheetUi? = null,
        val pendingOpenSession: SessionOpenTarget? = null,
        val projectSessionCreateError: String? = null,
        val pendingProjectRemoval: ProjectRemovalUi? = null,
        /** Unfinished first loads of projects added this session, keyed by worktree. */
        val projectLoads: Map<String, ProjectFirstLoad> = emptyMap(),
        /** Projects, worktrees, and branches behind [sections]. */
        val home: HomeWorktreeIndex = HomeWorktreeIndex(),
    ) : MainScreenUiState {
        /** Home projects: added paths of one repository grouped, in added order. */
        val projects: List<HomeProject> = home.projects(sections.map { it.worktree })
    }
}

/**
 * One-shot request for Home to switch its project scope. [evenFromAll] also switches away from
 * "All" (a newly added project); otherwise "All" stays selected.
 */
data class HomeScopeRequest(
    val filterId: String,
    val evenFromAll: Boolean,
)

data class ProjectRemovalUi(
    val worktree: String,
    val displayName: String,
    /** Every added path of the project; all leave Home together. */
    val worktrees: List<String> = listOf(worktree),
)

data class HomeSessionActionTarget(
    val sessionId: String,
    val title: String,
    val worktree: String,
)

data class HomeSessionActionsUiState(
    val target: HomeSessionActionTarget? = null,
    val sheetVisible: Boolean = false,
    val canArchive: Boolean = true,
    val renameVisible: Boolean = false,
    val renameDraft: String = "",
    val renameSubmitting: Boolean = false,
    val renameError: String? = null,
    val forkVisible: Boolean = false,
    val forkDraft: String = "",
    val forkSubmitting: Boolean = false,
    val forkError: String? = null,
    /** Set when there is nothing to fork (no user message); disables confirm. */
    val forkBlockedMessage: String? = null,
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

data class SessionOpenTarget(
    val sessionId: String,
    val title: String,
    val worktree: String,
    val optimisticNavigationInstanceId: String? = null,
)

/** Home with [moved]'s session row in its new directory, so it regroups under that worktree. */
internal fun MainScreenUiState.Loaded.withSessionMoved(moved: SessionMovedUpdate): MainScreenUiState.Loaded {
    val patched =
        sections.map { section ->
            if (section.allSessions.none { it.id == moved.sessionId }) {
                section
            } else {
                section.copy(allSessions = section.allSessions.map { it.movedTo(moved) })
            }
        }
    return copy(sections = patched, home = home.copy(extraSessions = home.extraSessions.withSessionMoved(moved)))
}

internal fun Map<String, List<SessionSummary>>.withSessionMoved(moved: SessionMovedUpdate): Map<String, List<SessionSummary>> =
    mapValues { (_, sessions) ->
        if (sessions.none { it.id == moved.sessionId }) sessions else sessions.map { it.movedTo(moved) }
    }

private fun SessionSummary.movedTo(moved: SessionMovedUpdate): SessionSummary =
    if (id == moved.sessionId) copy(directory = moved.directory) else this

/** Unread finished-run outcome for Home; null when read or when the server has no read state. */
internal fun Session.homeUnreadOutcome(serverReadState: Boolean): SessionIdleOutcome? =
    if (serverReadState && isServerUnread) idleOutcome ?: SessionIdleOutcome.Succeeded else null
