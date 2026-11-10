package dev.opencode.mobile.ui.main

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.connection.AddressCheck
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
import dev.opencode.mobile.domain.event.isWorktreeUpdated
import dev.opencode.mobile.domain.event.namedProjectId
import dev.opencode.mobile.domain.event.sessionMoved
import dev.opencode.mobile.domain.filesystem.FilesystemRepository
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeProjectGrouping
import dev.opencode.mobile.domain.home.HomeProjectsSnapshot
import dev.opencode.mobile.domain.home.HomeScope
import dev.opencode.mobile.domain.home.HomeWorktreeRequest
import dev.opencode.mobile.domain.home.HomeWorktreesFeed
import dev.opencode.mobile.domain.home.HomeWorktreesSnapshot
import dev.opencode.mobile.domain.live.HomeSessionPaging
import dev.opencode.mobile.domain.live.LiveStoreRefreshPolicy
import dev.opencode.mobile.domain.live.SessionAncestry
import dev.opencode.mobile.domain.live.SessionListSnapshot
import dev.opencode.mobile.domain.live.SessionLiveStore
import dev.opencode.mobile.domain.live.SessionReadyStore
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.preferences.HomeDisplayPreferences
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.domain.project.ServerProbeError
import dev.opencode.mobile.domain.session.NewSessionDraftStart
import dev.opencode.mobile.domain.session.NewSessionDraftStore
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
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.domain.worktree.UnavailableWorktreeRepository
import dev.opencode.mobile.domain.worktree.WorktreeInventory
import dev.opencode.mobile.domain.worktree.WorktreeInventoryCache
import dev.opencode.mobile.domain.worktree.WorktreeRepository
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.ui.archived.HomeArchivedMenu
import dev.opencode.mobile.ui.archived.HomeArchivedMenuEntry
import dev.opencode.mobile.theme.letterAccentForWorktree
import dev.opencode.mobile.theme.letterCharacterForProject
import dev.opencode.mobile.ui.components.OpenCodeSnackbarVariant
import dev.opencode.mobile.ui.components.SessionListScrollPin
import dev.opencode.mobile.ui.main.region.HomeAddProject
import dev.opencode.mobile.ui.main.region.HomeAddProjectResult
import dev.opencode.mobile.ui.main.region.HomeServers
import dev.opencode.mobile.ui.main.region.HomeSessionActions
import dev.opencode.mobile.ui.sessionchat.SessionConnectionStatus
import dev.opencode.mobile.ui.sessionchat.toSessionConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
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
        private val sessionLiveStore: SessionLiveStore,
        private val sessionStatusLiveStore: SessionStatusLiveStore,
        private val sessionReadyStore: SessionReadyStore,
        private val activeConnectionManager: ActiveConnectionManager,
        private val connectionSessionCoordinator: ConnectionSessionCoordinator,
        private val sessionCache: SessionCache,
        private val sessionAncestry: SessionAncestry,
        private val liveUpdates: LiveUpdates = FixedLiveUpdates(),
        worktreeRepository: WorktreeRepository = UnavailableWorktreeRepository,
        private val foregroundEventStream: ForegroundEventStream = IdleForegroundEventStream,
        projectFirstLoadTiming: ProjectFirstLoadTiming = ProjectFirstLoadTiming(),
        private val homeProjectsSnapshot: HomeProjectsSnapshot = HomeProjectsSnapshot(),
        private val worktreeInventoryCache: WorktreeInventoryCache = WorktreeInventoryCache(),
        private val homeWorktreesFeed: HomeWorktreesFeed = HomeWorktreesFeed(),
        private val newSessionDraftStore: NewSessionDraftStore = NewSessionDraftStore(),
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<MainScreenUiState>(MainScreenUiState.Probing)
        val uiState: StateFlow<MainScreenUiState> = _uiState.asStateFlow()

        /** Builds Home rows off the main thread (#981); tests run it on their scheduler. */
        internal var homeRowsDispatcher: CoroutineDispatcher = Dispatchers.Default
        private val _loadingMoreScopeId = MutableStateFlow<String?>(null)

        /** Scope id whose load-more runs, for the list's loading footer; null when none runs. */
        val loadingMoreScopeId: StateFlow<String?> = _loadingMoreScopeId.asStateFlow()

        private val _moreSessionDirectories = MutableStateFlow<Set<String>>(emptySet())

        /**
         * Observed directories whose latest window says older sessions may follow. A window whose
         * refresh failed stays out until a refresh succeeds, so paging never retries in a loop.
         */
        val moreSessionDirectories: StateFlow<Set<String>> = _moreSessionDirectories.asStateFlow()
        private var loadMoreJob: Job? = null
        private val _isRefreshing = MutableStateFlow(false)
        val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
        private val _removalSnackbar = MutableStateFlow<HomeSnackbar?>(null)
        val removalSnackbar: StateFlow<HomeSnackbar?> = _removalSnackbar.asStateFlow()
        private val sessionActionsRegion =
            HomeSessionActions(
                sessionRepository = sessionRepository,
                messageRepository = messageRepository,
                scope = viewModelScope,
                onLifecycleError = { message ->
                    _removalSnackbar.value =
                        HomeSnackbar(
                            text = message,
                            variant = OpenCodeSnackbarVariant.Error,
                        )
                },
            )
        val sessionActions: StateFlow<HomeSessionActionsUiState> = sessionActionsRegion.state
        private val archivedMenuRegion =
            HomeArchivedMenu(
                sessionRepository = sessionRepository,
                projects = homeProjectsSnapshot.projects,
                scope = viewModelScope,
                supported = { activeConnectionManager.currentCapabilities().supportsArchivedSessions },
            )

        /** Home menu's Archived sessions entry; null on servers that cannot restore archived sessions. */
        val archivedMenu: StateFlow<HomeArchivedMenuEntry?> = archivedMenuRegion.state

        fun refreshArchivedMenu(
            scopeId: String,
            worktreesEnabled: Boolean,
        ) = archivedMenuRegion.refresh(scopeId, worktreesEnabled)

        /** Probes the server; its answer can turn on capabilities the menu entry was waiting for. */
        private suspend fun probeServer() {
            projectRepository.probeServer()
            archivedMenuRegion.recheck()
        }
        private val _revealNewestToken = MutableStateFlow(0)
        val revealNewestToken: StateFlow<Int> = _revealNewestToken.asStateFlow()
        private val _pendingScopeRequest = MutableStateFlow<HomeScopeRequest?>(null)
        val pendingScopeRequest: StateFlow<HomeScopeRequest?> = _pendingScopeRequest.asStateFlow()
        private val serversRegion =
            HomeServers(
                connectionProfileStore = connectionProfileStore,
                connectionSessionCoordinator = connectionSessionCoordinator,
                currentProfile = { activeConnectionManager.currentProfile() },
                scope = viewModelScope,
                onSwitchStarted = { target, generation ->
                    // Drop the previous server's live updates immediately. Local projects paint next;
                    // the connection switch and session lists catch up in the background.
                    stopLiveObservations()
                    DiagnosticLog.info(DiagnosticLogArea.Connection, "switch profile ui id=${target.id}")
                    viewModelScope.launch { paintSwitchedServer(target.baseUrl, target.id, generation) }
                },
                onSwitchAbandoned = ::abandonSwitch,
            )
        val serversChrome: StateFlow<HomeServersChrome> = serversRegion.state
        private var lastHomeHiddenAtEpochMs: Long? = HomeDisplayPreferences.lastHomeHiddenAtEpochMs()

        /** An empty Home failed its probe while other saved addresses were still being tried. */
        private var probeWaitsOnAddressCheck = false
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

        private val addProjectRegion =
            HomeAddProject(
                filesystemRepository = filesystemRepository,
                projectRepository = projectRepository,
                connectionProfileStore = connectionProfileStore,
                newSessionDraftStore = newSessionDraftStore,
                scope = viewModelScope,
                serverBaseUrl = { serverBaseUrl },
                currentProfileId = ::currentProfileId,
                addedProjectIds = { added ->
                    added
                        .mapNotNull { path -> homeIndex.resolutions[path]?.takeIf { it.isMergeable }?.projectId }
                        .toSet()
                },
                onSheetChanged = ::publishAddProjectSheet,
            )

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
                // A checkout created in Session Chat (Move to worktree) lists here at once.
                homeProjectsSnapshot.worktreeRefreshRequests.collect(::refreshProjectWorktrees)
            }
            viewModelScope.launch {
                // The Worktrees screen lists Home's projects and sessions and asks Home to act.
                _uiState
                    .map { (it as? MainScreenUiState.Loaded)?.worktreesSnapshot() ?: HomeWorktreesSnapshot() }
                    .distinctUntilChanged()
                    .collect(homeWorktreesFeed::publish)
            }
            viewModelScope.launch { homeWorktreesFeed.requests.collect(::onWorktreeRequest) }
            viewModelScope.launch {
                // A New session draft's Add project…: the project added here becomes the draft's.
                newSessionDraftStore.addProjectRequested.collect { profileId ->
                    if (profileId == currentProfileId()) showAddProjectSheet()
                }
            }
            viewModelScope.launch {
                homeWorktreesFeed.removed.collect { removed ->
                    updateHomeIndex { it.copy(removedWorktrees = removed) }
                }
            }
            viewModelScope.launch {
                connectionSessionCoordinator.profileSaved.collect {
                    val active = connectionProfileStore.activeProfile()
                    val pending = serversRegion.pendingId
                    if (pending != null && active?.id != pending) return@collect
                    serversRegion.completeSwitch()
                    stopLiveObservations()
                    // A switch already painted its projects; their skeletons stay until sessions arrive.
                    if (pending == null) projectFirstLoads.clearAll()
                    pendingFirstServerSnapshots.clear()
                    resetHomeIndex()
                    refresh()
                }
            }
            viewModelScope.launch {
                connectionSessionCoordinator.addressCheck.collect { check ->
                    if (serversRegion.pendingId != null) return@collect
                    when (check.state) {
                        AddressCheck.State.Running -> Unit
                        AddressCheck.State.Moved -> {
                            probeWaitsOnAddressCheck = false
                            if (_uiState.value is MainScreenUiState.Loaded) {
                                sessionLiveStore.invalidate(null)
                                sessionStatusLiveStore.invalidate(null)
                            } else {
                                // Empty, unreachable, or still loading against the old address.
                                refresh()
                            }
                        }
                        AddressCheck.State.Kept ->
                            if (probeWaitsOnAddressCheck) {
                                probeWaitsOnAddressCheck = false
                                refresh()
                            }
                    }
                }
            }
            viewModelScope.launch {
                connectionSessionCoordinator.profileCleared.collect {
                    stopLiveObservations()
                    projectFirstLoads.clearAll()
                    pendingFirstServerSnapshots.clear()
                    resetHomeIndex()
                    _uiState.value = MainScreenUiState.Disconnected
                    serversRegion.clear()
                }
            }
        }

        fun refresh() {
            viewModelScope.launch {
                refreshSuspending()
            }
        }

        private suspend fun refreshSuspending() {
            serversRegion.refreshChrome()
            runHomeLoad()
        }

        fun showServersSheet() {
            serversRegion.showSheet()
        }

        fun dismissServersSheet() {
            serversRegion.dismissSheet()
        }

        fun switchProfile(id: String) {
            serversRegion.switchProfile(id)
        }

        /** A switch did not stick: reload Home and its title from the server that is actually active. */
        private fun abandonSwitch() {
            projectFirstLoads.clearAll()
            pendingFirstServerSnapshots.clear()
            resetHomeIndex()
            refresh()
        }

        /**
         * The target server's saved projects with their cached sessions, read by profile id because
         * the switch has not bound that server yet. A project with no cache shows the skeleton.
         */
        private suspend fun paintSwitchedServer(
            baseUrl: String,
            id: String,
            generation: Int,
        ) {
            val worktrees = connectionProfileStore.addedWorktrees(baseUrl)
            val sections = worktrees.map { worktree -> sectionFromCache(worktree, connectionId = id) }
            if (!serversRegion.isCurrent(generation, id)) return
            resetHomeIndex()
            seedProjectResolutions(worktrees, connectionId = id)
            if (!serversRegion.isCurrent(generation, id)) return
            projectFirstLoads.clearAll()
            pendingFirstServerSnapshots.clear()
            probeWaitsOnAddressCheck = false
            if (sections.isEmpty()) {
                _uiState.value = MainScreenUiState.Empty()
                return
            }
            projectFirstLoads.awaitServer(sections.filterNot { it.sessionsFromCache }.map { it.worktree })
            _uiState.value =
                MainScreenUiState.Loaded(
                    sections = sections,
                    projectLoads = projectFirstLoads.visible,
                    home = homeIndex,
                )
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
            addProjectRegion.open()
        }

        fun dismissAddProjectSheet() {
            // A draft that asked for a project gets none when the sheet closes without one.
            addProjectRegion.cancelDraftRequest()
            addProjectRegion.end()
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
            addProjectRegion.updateField(text)
        }

        fun addProjectUp() {
            val target = currentAddProjectSheet()?.upTarget ?: return
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_GO_UP,
            )
            addProjectRegion.setFolder(target)
        }

        fun selectAddProjectRoot(path: String) {
            if (addProjectRegion.session == null) return
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_SELECT_ROOT,
            )
            addProjectRegion.setFolder(path)
        }

        fun openAddProjectFolder(path: String) {
            if (addProjectRegion.session == null) return
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_ENTER_DIRECTORY,
            )
            addProjectRegion.setFolder(path)
        }

        /**
         * Return in the path field: opens [AddProjectSheetUi.goTarget] (the exact child match, or
         * the only matching row). Does nothing otherwise; never adds.
         */
        fun submitAddProjectField() {
            if (addProjectRegion.session == null) return
            val target = currentAddProjectSheet()?.goTarget ?: return
            openAddProjectFolder(target)
        }

        /** A browser row came on screen: queue its folder count and git check. */
        fun addProjectFolderRowAppeared(path: String) {
            addProjectRegion.rowAppeared(path)
        }

        /** A browser row left the screen: drop its summary if it has not started. */
        fun addProjectFolderRowDisappeared(path: String) {
            addProjectRegion.rowDisappeared(path)
        }

        fun selectAddProjectSuggestion(path: String) {
            if (addProjectRegion.session?.isValidating != false) return
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_SELECT_SUGGESTION,
            )
            addProject(path)
        }

        /** Replaces the suggestion sheet with the full-screen folder browser. */
        fun browseAddProjectFolders() {
            if (addProjectRegion.session == null || addProjectRegion.session?.isValidating == true) return
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_BROWSE,
            )
            addProjectRegion.browse()
        }

        /**
         * Back from the browser. Returns to the suggestion sheet at the server home, so
         * "On this server" is not lost because the field was left in a subfolder.
         */
        fun closeAddProjectBrowser() {
            val session = addProjectRegion.session ?: return
            if (!session.browsing) return
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_BROWSE_BACK,
            )
            addProjectRegion.closeBrowser()
        }

        fun retryAddProjectListing() {
            if (addProjectRegion.session == null) return
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_RETRY_NAVIGATOR,
            )
            addProjectRegion.retryListing()
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
                var joined: HomeProject? = null
                val result =
                    addProjectRegion.add(worktree) { resolution ->
                        // A path of a repository already on Home joins that project as a worktree.
                        joined =
                            currentHomeProjects().firstOrNull { home ->
                                resolution.isMergeable && home.projectId == resolution.projectId
                            }
                        worktreeLoader.remember(worktree, resolution)
                        updateHomeIndex { it.copy(resolutions = it.resolutions + (worktree to resolution)) }
                    }
                when (result) {
                    HomeAddProjectResult.AlreadyAdded -> {
                        // Before the sheet closes, which would drop the draft's request.
                        val forDraft = addProjectRegion.consumeDraftRequest()
                        dismissAddProjectSheet()
                        runHomeLoad(skipProbe = true)
                        if (forDraft) openDraftWithAddedProject(worktree)
                        _pendingScopeRequest.value = HomeScopeRequest(filterId = worktree, evenFromAll = false)
                        _removalSnackbar.value =
                            HomeSnackbar(text = HomeText.alreadyOnHome(ProjectDomainAdapter.displayNameFromWorktree(worktree)))
                    }
                    is HomeAddProjectResult.Added -> {
                        projectFirstLoads.start(worktree)
                        val forDraft = addProjectRegion.consumeDraftRequest()
                        dismissAddProjectSheet()
                        runHomeLoad(skipProbe = true)
                        if (forDraft) openDraftWithAddedProject(worktree)
                        _pendingScopeRequest.value = HomeScopeRequest(filterId = worktree, evenFromAll = true)
                        joined?.let { project ->
                            _removalSnackbar.value =
                                HomeSnackbar(text = HomeText.joinedProject(project.displayName))
                        }
                    }
                    HomeAddProjectResult.Failed -> Unit
                }
            }
        }

        /**
         * New session (#1004): opens the profile's draft (it wins), else a fresh one at [start].
         * Nothing is created on the server until the draft's first send.
         */
        fun openNewSession(start: NewSessionDraftStart) {
            logAction(ProductAnalytics.Screen.HOME, ProductAnalytics.Action.HOME_CREATE_SESSION)
            val draft = newSessionDraftStore.open(currentProfileId(), start)
            openDraft(draft.directory)
        }

        /** New session in [directory] (the Worktrees screen): the draft keeps its text and runs there. */
        fun openNewSessionHere(directory: String) {
            logAction(ProductAnalytics.Screen.HOME, ProductAnalytics.Action.HOME_CREATE_SESSION)
            val draft = newSessionDraftStore.openAt(currentProfileId(), NewSessionDraftStart.forDirectory(currentHomeProjects(), directory))
            openDraft(draft.directory)
        }

        private fun openDraft(directory: String) {
            clearProjectSessionCreateError()
            _revealNewestToken.update { it + 1 }
            _uiState.update { state ->
                if (state is MainScreenUiState.Loaded) {
                    state.copy(pendingOpenSession = newSessionOpenTarget(directory))
                } else {
                    state
                }
            }
        }

        /** A project the draft's Add project… asked for was added: it becomes the draft's, which opens again. */
        private fun openDraftWithAddedProject(worktree: String) {
            val profileId = currentProfileId()
            val draft = newSessionDraftStore.openAt(profileId, NewSessionDraftStart.forDirectory(currentHomeProjects(), worktree))
            openDraft(draft.directory)
        }

        private fun currentProfileId(): String = connectionSessionCoordinator.currentProfile()?.id.orEmpty()

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
                _removalSnackbar.value = HomeSnackbar(text = HomeText.removedProject(pending.displayName))
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
                seedProjectResolutions(worktrees)
                _uiState.value =
                    MainScreenUiState.Loaded(sections = sections, projectLoads = projectFirstLoads.visible, home = homeIndex)
                applyPendingFirstServerSnapshots()
                if (!skipProbe) {
                    runCatching { probeServer() }
                        .onFailure {
                            // Keep cached home; connection chrome reflects reachability.
                        }
                }
                startLiveObservations(worktrees)
                projectFirstLoads.startAwaiting()
                reloadWorktreeMarks(worktrees)
                return
            }

            if (worktrees.isEmpty() && !preserveContent) {
                stopLiveObservations()
                _uiState.value = MainScreenUiState.Empty()
                if (!skipProbe) {
                    val probedBaseUrl = serverBaseUrl
                    runCatching { probeServer() }
                        .onFailure { error ->
                            if (!probeFailureIsFinal(probedBaseUrl)) return@onFailure
                            _uiState.value =
                                MainScreenUiState.ProbeFailed(
                                    when (error) {
                                        is ServerProbeError -> probeErrorMessage(error)
                                        else -> serverMessage(error, R.string.connection_error_unreachable)
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
                    probeServer()
                } catch (error: ServerProbeError) {
                    if (preserveContent) return
                    stopLiveObservations()
                    _uiState.value = MainScreenUiState.ProbeFailed(probeErrorMessage(error))
                    return
                } catch (error: Throwable) {
                    if (preserveContent) return
                    stopLiveObservations()
                    _uiState.value = MainScreenUiState.ProbeFailed(serverMessage(error, R.string.connection_error_unreachable))
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
                _uiState.value = MainScreenUiState.ProbeFailed(serverMessage(error, R.string.home_error_load_projects))
            }
        }

        /**
         * Cached projects for paths not resolved yet, so the cached paint groups (and keys its
         * scopes) the way the live load will. A key that changes later reloads the whole list.
         */
        private suspend fun seedProjectResolutions(
            worktrees: List<String>,
            connectionId: String? = null,
        ) {
            val cached =
                worktrees
                    .filter { it !in homeIndex.resolutions }
                    .mapNotNull { path ->
                        runCatching { sessionCache.seedProjectResolution(path, connectionId) }.getOrNull()?.let { path to it }
                    }
            if (cached.isNotEmpty()) {
                homeIndex = homeIndex.copy(resolutions = cached.toMap() + homeIndex.resolutions)
            }
        }

        private suspend fun loadSectionsFromCache(worktrees: List<String>): List<ProjectSectionUi> =
            worktrees.map { worktree -> sectionFromCache(worktree) }

        private suspend fun sectionFromCache(
            worktree: String,
            connectionId: String? = null,
        ): ProjectSectionUi {
            val seeded =
                runCatching {
                    if (connectionId != null) {
                        sessionCache.seedSessions(worktree, connectionId)
                    } else {
                        sessionCache.seedSessions(worktree)
                    }
                }.getOrNull()
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

        /**
         * False while another saved address may still answer: the address check's result reloads
         * Home. Also false when the address moved during the probe, since a newer load is running.
         */
        private fun probeFailureIsFinal(probedBaseUrl: String): Boolean {
            if (serverBaseUrl != probedBaseUrl) return false
            if (connectionSessionCoordinator.addressCheck.value.state == AddressCheck.State.Running) {
                probeWaitsOnAddressCheck = true
                return false
            }
            return true
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
            // Home's first page only (#981); the live window takes over from here.
            val sessions =
                sessionRepository
                    .listRootSessionsWindow(directory = worktree, limit = HomeSessionPaging.PAGE_SIZE)
                    .sessions
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

        private fun currentAddProjectSheet(): AddProjectSheetUi? =
            when (val state = _uiState.value) {
                is MainScreenUiState.Empty -> state.addProjectSheet
                is MainScreenUiState.Loaded -> state.addProjectSheet
                else -> null
            }

        /** Patches the Add project sheet into Empty/Loaded; a content rebuild hides it until the next change. */
        private fun publishAddProjectSheet(sheet: AddProjectSheetUi) {
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

        /** The domain's probe message, shown as is (the shared domain messages are not keyed yet). */
        private fun probeErrorMessage(error: ServerProbeError): UiText = UiText.verbatim(error.userMessage())

        /** The error's own text, verbatim, else the keyed [fallback]. */
        private fun serverMessage(
            error: Throwable,
            @StringRes fallback: Int,
        ): UiText = error.message?.let(UiText::verbatim) ?: UiText.resource(fallback)

        private fun startLiveObservations(worktrees: List<String>) {
            if (liveObservationsRunning(worktrees)) return
            stopLiveObservations()
            observedWorktrees = worktrees
            livePaintedWorktrees.clear()
            liveObservationJobs =
                worktrees.map { worktree ->
                    viewModelScope.launch {
                        observeHomeRows(worktree).collect { rows ->
                            livePaintedWorktrees += worktree
                            recordMoreSessions(worktree, rows.snapshot)
                            updateSectionFromLiveData(worktree, rows.summaries, sessionsFromCache = rows.snapshot.isFromCache)
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
                            if (!event.isWorktreeUpdated) return@collect
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

        /**
         * Lists the worktrees of [directory]'s project again (v2 list + refresh, v1
         * `GET /experimental/worktree`), so a checkout made in the app shows without a refresh.
         */
        private fun refreshProjectWorktrees(directory: String) {
            val canonical = ServerPathPolicy.canonical(directory.trim())
            val projects = currentHomeProjects()
            val project =
                projects.firstOrNull { it.worktree(canonical) != null }
                    ?: projects.firstOrNull { it.contains(canonical) }
                    ?: return
            viewModelScope.launch {
                for (path in project.memberPaths) {
                    applyPathLoad(worktreeLoader.load(path, homeIndex.resolutions[path]))
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
            val previous = homeIndex
            val next = transform(previous)
            if (next == previous) return
            homeIndex = next
            val changed = next.resolutions.filter { (path, resolution) -> previous.resolutions[path] != resolution }
            changed.forEach { (path, resolution) -> sessionCache.rememberProjectResolution(path, resolution) }
            _uiState.update { state ->
                if (state is MainScreenUiState.Loaded) state.copy(home = next) else state
            }
        }

        private fun onWorktreeRequest(request: HomeWorktreeRequest) {
            when (request) {
                is HomeWorktreeRequest.ShowSessions ->
                    _pendingScopeRequest.value =
                        HomeScopeRequest(HomeScope.worktreeId(request.projectKey, request.directory), evenFromAll = true)
                is HomeWorktreeRequest.NewSession -> openNewSessionHere(request.directory)
                is HomeWorktreeRequest.Removed -> {
                    // Before the reload, so the v1 extra-session sync already keeps its sessions.
                    val removed = homeWorktreesFeed.removed.value
                    updateHomeIndex { it.copy(removedWorktrees = removed) }
                    refreshProjectWorktrees(request.directory)
                }
            }
        }

        private fun resetHomeIndex() {
            homeWorktreesFeed.clear()
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
                        observeHomeRows(directory).collect { rows ->
                            recordMoreSessions(directory, rows.snapshot)
                            val summaries =
                                rows.summaries.map { summary ->
                                    if (summary.directory.isNullOrBlank()) summary.copy(directory = directory) else summary
                                }
                            updateHomeIndex { it.copy(extraSessions = it.extraSessions + (directory to summaries)) }
                        }
                    }
                }
            _moreSessionDirectories.update { more -> more - extraObservationJobs.keys.filter { it !in wanted }.toSet() }
            extraObservationJobs = kept + started
            // Sessions of a worktree removed from this app stay on Home (v1 cannot list them again).
            updateHomeIndex { index ->
                index.copy(
                    extraSessions = index.extraSessions.filterKeys { it in wanted || it in index.removedWorktrees },
                )
            }
        }

        private fun stopLiveObservations() {
            loadMoreJob?.cancel()
            loadMoreJob = null
            _loadingMoreScopeId.value = null
            _moreSessionDirectories.value = emptySet()
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
            summaries: List<SessionSummary>,
            sessionsFromCache: Boolean,
        ) {
            val state = _uiState.value as? MainScreenUiState.Loaded ?: return
            val section = state.sections.firstOrNull { it.worktree == worktree } ?: return
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
            recordMoreSessions(worktree, snapshot)
            updateSectionFromLiveData(
                worktree,
                homeRows(worktree, snapshot, SessionActivitySnapshot(), emptySet()).summaries,
                sessionsFromCache = false,
            )
        }

        private fun applyPendingFirstServerSnapshots() {
            pendingFirstServerSnapshots.toMap().forEach { (worktree, snapshot) ->
                paintFirstServerSnapshot(worktree, snapshot)
            }
        }

        /**
         * [directory]'s Home rows from its live window, statuses, and ready ids (#981). Rows are
         * built on [homeRowsDispatcher] and conflated, so only the latest reaches the main thread;
         * a new parent link learned by the ancestry rebuilds them.
         */
        private fun observeHomeRows(directory: String): Flow<HomeDirectoryRows> =
            combine(
                sessionLiveStore.observeSessions(directory),
                sessionStatusLiveStore.observeActivity(directory),
                sessionReadyStore.observeReadyIds(directory),
                sessionAncestry.changes,
            ) { snapshot, statuses, readyIds, _ ->
                val rows = homeRows(directory, snapshot, statuses, readyIds)
                sessionAncestry.requestRoots(rows.unresolvedBusyIds, directory)
                HomeDirectoryRows(snapshot, rows.summaries)
            }.conflate()
                .flowOn(homeRowsDispatcher)

        private fun homeRows(
            directory: String,
            snapshot: SessionListSnapshot,
            statuses: SessionActivitySnapshot,
            readyIds: Set<String>,
        ): HomeSessionRows.Result =
            HomeSessionRows.build(
                directory = directory,
                snapshot = snapshot,
                activity = statuses,
                readyIds = readyIds,
                // v2 servers own read state; the local per-device Ready ids stay a v1 fallback.
                serverReadState = supportsSessionViewed(),
                adapter = sessionDomainAdapter,
                rootOf = sessionAncestry::rootOf,
                rootSession = sessionAncestry::rootSession,
            )

        private fun recordMoreSessions(
            directory: String,
            snapshot: SessionListSnapshot,
        ) {
            _moreSessionDirectories.update { more ->
                if (snapshot.hasMore && !snapshot.loadFailed) more + directory else more - directory
            }
        }

        /** Whether a directory of [scopeId] in [moreSessionDirectories] has older sessions to load. */
        fun canLoadMoreSessions(
            moreSessionDirectories: Set<String>,
            scopeId: String,
            worktreesEnabled: Boolean = true,
        ): Boolean =
            moreSessionDirectories.isNotEmpty() &&
                scopeDirectories(scopeId, worktreesEnabled).any { it in moreSessionDirectories }

        /**
         * The Home list scrolled near its end in [scopeId]: every observed directory of that scope
         * whose window has more lists one more page. A no-op while a load-more runs or when no
         * directory there has more.
         */
        fun loadMoreSessions(
            scopeId: String,
            worktreesEnabled: Boolean = true,
        ) {
            if (loadMoreJob?.isActive == true) return
            val directories = scopeDirectories(scopeId, worktreesEnabled).filter { it in _moreSessionDirectories.value }
            if (directories.isEmpty()) return
            _loadingMoreScopeId.value = scopeId
            loadMoreJob =
                viewModelScope.launch {
                    try {
                        coroutineScope {
                            directories.forEach { directory -> launch { sessionLiveStore.loadMore(directory) } }
                        }
                    } finally {
                        _loadingMoreScopeId.value = null
                    }
                }
        }

        /**
         * Observed directories whose lists feed [scopeId]: every one for All; a project's added
         * paths and (v1) its other worktrees' lists; for a worktree, the lists that can hold it
         * (its own, or a project-wide added path).
         */
        private fun scopeDirectories(
            scopeId: String,
            worktreesEnabled: Boolean,
        ): List<String> {
            val extras = extraObservationJobs.keys
            val projects = currentHomeProjects()
            return when (val scope = HomeScope.resolve(scopeId, projects, worktreesEnabled)) {
                null, HomeScope.All -> observedWorktrees + extras
                is HomeScope.Project -> {
                    val project = projects.firstOrNull { it.key == scope.key } ?: return emptyList()
                    val worktrees = project.worktrees.map { ServerPathPolicy.canonical(it.directory) }.toSet()
                    project.memberPaths.filter { it in observedWorktrees } + extras.filter { it in worktrees }
                }
                is HomeScope.Worktree -> {
                    val project = projects.firstOrNull { it.key == scope.projectKey } ?: return emptyList()
                    val directory = ServerPathPolicy.canonical(scope.directory)
                    project.memberPaths.filter { it in observedWorktrees } + extras.filter { it == directory }
                }
            }.distinct()
        }

        private fun supportsSessionViewed(): Boolean = activeConnectionManager.currentCapabilities().supportsSessionViewed

        private fun clearProjectSessionCreateError() {
            setProjectSessionCreateError(null)
        }

        private fun setProjectSessionCreateError(message: UiText?) {
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

/** One directory's live window and the Home rows built from it. */
private data class HomeDirectoryRows(
    val snapshot: SessionListSnapshot,
    val summaries: List<SessionSummary>,
)

/** A Home snackbar; the screen resolves [text] when it shows it. */
data class HomeSnackbar(
    val text: UiText,
    val variant: OpenCodeSnackbarVariant = OpenCodeSnackbarVariant.Default,
    val id: Long = System.nanoTime(),
)

data class HomeServersChrome(
    /** The active profile's name, verbatim, else "Sessions". */
    val title: UiText = UiText.resource(R.string.home_sessions_title),
    val isSwitcher: Boolean = false,
    val active: ConnectionProfile? = null,
    val others: List<ConnectionProfile> = emptyList(),
    val sheetVisible: Boolean = false,
)

sealed interface MainScreenUiState {
    data object Disconnected : MainScreenUiState

    data object Probing : MainScreenUiState

    data class ProbeFailed(
        val message: UiText,
    ) : MainScreenUiState

    data object Loading : MainScreenUiState

    data class Empty(
        val showAddProjectSheet: Boolean = false,
        val addProjectSheet: AddProjectSheetUi? = null,
        val projectSessionCreateError: UiText? = null,
    ) : MainScreenUiState

    data class Loaded(
        val sections: List<ProjectSectionUi>,
        val showAddProjectSheet: Boolean = false,
        val addProjectSheet: AddProjectSheetUi? = null,
        val pendingOpenSession: SessionOpenTarget? = null,
        val projectSessionCreateError: UiText? = null,
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
    val renameError: UiText? = null,
    val forkVisible: Boolean = false,
    val forkDraft: String = "",
    val forkSubmitting: Boolean = false,
    val forkError: UiText? = null,
    /** Set when there is nothing to fork (no user message); disables confirm. */
    val forkBlockedMessage: UiText? = null,
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

/**
 * The New session draft, whose checkout is [worktree]: Session Chat opens at once and the server
 * session (and any new worktree) is created with the first send (#1004). Home's New session,
 * Ctrl+N (Home and Session Chat), `/new` and the Worktrees screen all open this.
 */
fun newSessionOpenTarget(worktree: String): SessionOpenTarget =
    SessionOpenTarget(
        sessionId = OptimisticSessionCreate.PLACEHOLDER_SESSION_ID,
        title = OptimisticSessionCreate.PLACEHOLDER_TITLE,
        worktree = worktree,
        optimisticNavigationInstanceId =
            java.util.UUID
                .randomUUID()
                .toString(),
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
