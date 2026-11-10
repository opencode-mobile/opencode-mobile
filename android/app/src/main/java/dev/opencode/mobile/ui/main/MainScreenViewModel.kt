package dev.opencode.mobile.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.connection.AddressCheck
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.FixedLiveUpdates
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.IdleForegroundEventStream
import dev.opencode.mobile.domain.event.LiveUpdates
import dev.opencode.mobile.domain.event.SessionMovedUpdate
import dev.opencode.mobile.domain.filesystem.FilesystemRepository
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeProjectsSnapshot
import dev.opencode.mobile.domain.home.HomeScope
import dev.opencode.mobile.domain.home.HomeWorktreeRequest
import dev.opencode.mobile.domain.home.HomeWorktreesFeed
import dev.opencode.mobile.domain.home.HomeWorktreesSnapshot
import dev.opencode.mobile.domain.live.LiveStoreRefreshPolicy
import dev.opencode.mobile.domain.live.SessionAncestry
import dev.opencode.mobile.domain.live.SessionListSnapshot
import dev.opencode.mobile.domain.live.SessionLiveStore
import dev.opencode.mobile.domain.live.SessionReadyStore
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.domain.session.NewSessionDraftStart
import dev.opencode.mobile.domain.session.NewSessionDraftStore
import dev.opencode.mobile.domain.session.OptimisticSessionCreate
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionForkTitle
import dev.opencode.mobile.domain.session.SessionIdleOutcome
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.domain.worktree.UnavailableWorktreeRepository
import dev.opencode.mobile.domain.worktree.WorktreeInventoryCache
import dev.opencode.mobile.domain.worktree.WorktreeRepository
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.ui.archived.HomeArchivedMenu
import dev.opencode.mobile.ui.archived.HomeArchivedMenuEntry
import dev.opencode.mobile.ui.components.OpenCodeSnackbarVariant
import dev.opencode.mobile.ui.main.region.HomeAddProject
import dev.opencode.mobile.ui.main.region.HomeAddProjectResult
import dev.opencode.mobile.ui.main.region.HomeProjectList
import dev.opencode.mobile.ui.main.region.HomeServers
import dev.opencode.mobile.ui.main.region.HomeSessionActions
import dev.opencode.mobile.ui.main.region.HomeWorktrees
import dev.opencode.mobile.ui.sessionchat.SessionConnectionStatus
import dev.opencode.mobile.ui.sessionchat.toSessionConnectionStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
        internal var homeRowsDispatcher: CoroutineDispatcher
            get() = projectList.homeRowsDispatcher
            set(value) {
                projectList.homeRowsDispatcher = value
            }
        private val _loadingMoreScopeId = MutableStateFlow<String?>(null)

        /** Scope id whose load-more runs, for the list's loading footer; null when none runs. */
        val loadingMoreScopeId: StateFlow<String?> = _loadingMoreScopeId.asStateFlow()

        private val _moreSessionDirectories = MutableStateFlow<Set<String>>(emptySet())

        /**
         * Observed directories whose latest window says older sessions may follow. A window whose
         * refresh failed stays out until a refresh succeeds, so paging never retries in a loop.
         */
        val moreSessionDirectories: StateFlow<Set<String>> = _moreSessionDirectories.asStateFlow()
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

        private val _revealNewestToken = MutableStateFlow(0)
        val revealNewestToken: StateFlow<Int> = _revealNewestToken.asStateFlow()
        private val _pendingScopeRequest = MutableStateFlow<HomeScopeRequest?>(null)
        val pendingScopeRequest: StateFlow<HomeScopeRequest?> = _pendingScopeRequest.asStateFlow()
        private val serversRegion: HomeServers =
            HomeServers(
                connectionProfileStore = connectionProfileStore,
                connectionSessionCoordinator = connectionSessionCoordinator,
                currentProfile = { activeConnectionManager.currentProfile() },
                scope = viewModelScope,
                onSwitchStarted = { target, generation ->
                    // Drop the previous server's live updates immediately. Local projects paint next;
                    // the connection switch and session lists catch up in the background.
                    projectList.stopLiveObservations()
                    DiagnosticLog.info(DiagnosticLogArea.Connection, "switch profile ui id=${target.id}")
                    viewModelScope.launch {
                        projectList.paintSwitchedServer(target.baseUrl, target.id) { serversRegion.isCurrent(generation, target.id) }
                    }
                },
                onSwitchAbandoned = ::abandonSwitch,
            )
        val serversChrome: StateFlow<HomeServersChrome> = serversRegion.state
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
        private val worktreesRegion: HomeWorktrees =
            HomeWorktrees(
                worktreeRepository = worktreeRepository,
                projectRepository = projectRepository,
                sessionRepository = sessionRepository,
                sessionCache = sessionCache,
                worktreeInventoryCache = worktreeInventoryCache,
                homeWorktreesFeed = homeWorktreesFeed,
                homeProjectsSnapshot = homeProjectsSnapshot,
                scope = viewModelScope,
                serverBaseUrl = { serverBaseUrl },
                projects = { projectList.projects() },
                observedWorktrees = { projectList.observedWorktrees },
                observing = { projectList.observing },
                observeRows = { directory -> projectList.observeHomeRows(directory) },
                recordMoreSessions = { directory, snapshot -> projectList.recordMoreSessions(directory, snapshot) },
                dropMoreSessions = { stopped -> projectList.dropMoreSessions(stopped) },
                onIndexChanged = { next ->
                    projectList.updateContent { state ->
                        if (state is MainScreenUiState.Loaded) state.copy(home = next) else state
                    }
                },
            )

        private val projectList: HomeProjectList =
            HomeProjectList(
                projectRepository = projectRepository,
                sessionRepository = sessionRepository,
                connectionProfileStore = connectionProfileStore,
                sessionCache = sessionCache,
                newSessionDraftStore = newSessionDraftStore,
                sessionLiveStore = sessionLiveStore,
                sessionStatusLiveStore = sessionStatusLiveStore,
                sessionReadyStore = sessionReadyStore,
                sessionDomainAdapter = sessionDomainAdapter,
                sessionAncestry = sessionAncestry,
                foregroundEventStream = foregroundEventStream,
                projectFirstLoadTiming = projectFirstLoadTiming,
                homeWorktrees = worktreesRegion,
                scope = viewModelScope,
                supportsSessionViewed = ::supportsSessionViewed,
                serverBaseUrl = { serverBaseUrl },
                currentProfileId = ::currentProfileId,
                isConnected = { activeConnectionManager.current() != null },
                addressCheckRunning = {
                    connectionSessionCoordinator.addressCheck.value.state == AddressCheck.State.Running
                },
                onProbed = archivedMenuRegion::recheck,
                onChange = { state ->
                    _uiState.value = state.content
                    _loadingMoreScopeId.value = state.paging.loadingMoreScopeId
                    _moreSessionDirectories.value = state.paging.moreSessionDirectories
                    _revealNewestToken.value = state.revealNewestToken
                    _pendingScopeRequest.value = state.pendingScopeRequest
                },
            )

        /** Home's content; HomeProjectList owns it and every write goes through it. */
        private var content: MainScreenUiState
            get() = projectList.content
            set(value) = projectList.replaceContent(value)

        // Title fields report one edit per dialog appearance instead of per keystroke.
        private var renameDraftEditReported = false
        private var forkDraftEditReported = false

        private val addProjectRegion: HomeAddProject =
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
                        .mapNotNull { path -> worktreesRegion.index.resolutions[path]?.takeIf { it.isMergeable }?.projectId }
                        .toSet()
                },
                onSheetChanged = ::publishAddProjectSheet,
            )

        init {
            refresh()
            viewModelScope.launch {
                // Session Chat reads the session's project and worktrees from here.
                worktreesRegion.publishProjects(_uiState.map { (it as? MainScreenUiState.Loaded)?.projects.orEmpty() })
            }
            viewModelScope.launch {
                // A checkout created in Session Chat (Move to worktree) lists here at once.
                worktreesRegion.followRefreshRequests()
            }
            viewModelScope.launch {
                // The Worktrees screen lists Home's projects and sessions and asks Home to act.
                worktreesRegion.publishWorktrees(
                    _uiState.map { (it as? MainScreenUiState.Loaded)?.worktreesSnapshot() ?: HomeWorktreesSnapshot() },
                )
            }
            viewModelScope.launch { homeWorktreesFeed.requests.collect(::onWorktreeRequest) }
            viewModelScope.launch {
                // A New session draft's Add project…: the project added here becomes the draft's.
                newSessionDraftStore.addProjectRequested.collect { profileId ->
                    if (profileId == currentProfileId()) showAddProjectSheet()
                }
            }
            viewModelScope.launch { worktreesRegion.followRemoved() }
            viewModelScope.launch {
                connectionSessionCoordinator.profileSaved.collect {
                    val active = connectionProfileStore.activeProfile()
                    val pending = serversRegion.pendingId
                    if (pending != null && active?.id != pending) return@collect
                    serversRegion.completeSwitch()
                    projectList.stopLiveObservations()
                    // A switch already painted its projects; their skeletons stay until sessions arrive.
                    projectList.resetLoads(clearFirstLoads = pending == null)
                    worktreesRegion.reset()
                    refresh()
                }
            }
            viewModelScope.launch {
                connectionSessionCoordinator.addressCheck.collect { check ->
                    if (serversRegion.pendingId != null) return@collect
                    when (check.state) {
                        AddressCheck.State.Running -> Unit
                        AddressCheck.State.Moved -> {
                            projectList.clearProbeWait()
                            if (content is MainScreenUiState.Loaded) {
                                projectList.invalidateAll()
                            } else {
                                // Empty, unreachable, or still loading against the old address.
                                refresh()
                            }
                        }
                        AddressCheck.State.Kept ->
                            if (projectList.consumeProbeWait()) {
                                refresh()
                            }
                    }
                }
            }
            viewModelScope.launch {
                connectionSessionCoordinator.profileCleared.collect {
                    projectList.stopLiveObservations()
                    projectList.resetLoads()
                    worktreesRegion.reset()
                    content = MainScreenUiState.Disconnected
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
            projectList.load()
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
            projectList.resetLoads()
            worktreesRegion.reset()
            refresh()
        }

        /** Soft reload for pull-to-refresh: keeps Loaded/Empty UI, no full-screen spinner. */
        fun pullToRefresh() {
            logAction(ProductAnalytics.Screen.HOME, ProductAnalytics.Action.HOME_PULL_TO_REFRESH)
            when (content) {
                is MainScreenUiState.Loaded,
                is MainScreenUiState.Empty,
                -> {
                    viewModelScope.launch {
                        if (_isRefreshing.value) return@launch
                        _isRefreshing.value = true
                        // A stream backing off (e.g. after a rejected credential) retries now.
                        foregroundEventStream.onReconnectWake()
                        try {
                            val observed = projectList.observedWorktrees
                            if (observed.isNotEmpty()) worktreesRegion.reload(observed, refreshProjects = true)
                            projectList.invalidateAll()
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
            projectList.setSheet(null)
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
                            projectList.projects().firstOrNull { home ->
                                resolution.isMergeable && home.projectId == resolution.projectId
                            }
                        worktreesRegion.remember(worktree, resolution)
                    }
                when (result) {
                    HomeAddProjectResult.AlreadyAdded -> {
                        // Before the sheet closes, which would drop the draft's request.
                        val forDraft = addProjectRegion.consumeDraftRequest()
                        dismissAddProjectSheet()
                        projectList.load(skipProbe = true)
                        if (forDraft) projectList.openDraftWithAddedProject(worktree)
                        projectList.requestScope(HomeScopeRequest(filterId = worktree, evenFromAll = false))
                        _removalSnackbar.value =
                            HomeSnackbar(text = HomeText.alreadyOnHome(ProjectDomainAdapter.displayNameFromWorktree(worktree)))
                    }
                    is HomeAddProjectResult.Added -> {
                        projectList.beginProjectLoad(worktree)
                        val forDraft = addProjectRegion.consumeDraftRequest()
                        dismissAddProjectSheet()
                        projectList.load(skipProbe = true)
                        if (forDraft) projectList.openDraftWithAddedProject(worktree)
                        projectList.requestScope(HomeScopeRequest(filterId = worktree, evenFromAll = true))
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
            projectList.openNewSession(start)
        }

        /** New session in [directory] (the Worktrees screen): the draft keeps its text and runs there. */
        fun openNewSessionHere(directory: String) {
            logAction(ProductAnalytics.Screen.HOME, ProductAnalytics.Action.HOME_CREATE_SESSION)
            projectList.openNewSessionHere(directory)
        }

        private fun currentProfileId(): String = connectionSessionCoordinator.currentProfile()?.id.orEmpty()

        fun consumePendingOpenSession() {
            projectList.consumePendingOpenSession()
        }

        fun consumePendingScopeRequest() {
            projectList.consumePendingScopeRequest()
        }

        /**
         * Failed first load of a newly added project: show loading again and refetch its sessions.
         * [scopeId] is an added path or a Home project key (every member path retries).
         */
        fun retryProjectLoad(scopeId: String) {
            logAction(ProductAnalytics.Screen.HOME, ProductAnalytics.Action.HOME_RETRY_PROJECT_LOAD)
            projectList.retryProjectLoad(scopeId)
        }

        fun markHomeHidden(nowEpochMs: Long = System.currentTimeMillis()) {
            projectList.markHidden(nowEpochMs)
        }

        fun onHomeRevealed(nowEpochMs: Long = System.currentTimeMillis()) {
            projectList.onRevealed(nowEpochMs)
        }

        /**
         * Applies a new added-project order from the project picker. The order
         * shows immediately and persists to the Connection Profile; the dock,
         * home sections, and new-session picker all derive from it.
         */
        fun reorderProjects(projectIds: List<String>) {
            val worktrees = projectList.reorder(projectIds) ?: return
            logAction(ProductAnalytics.Screen.HOME_FILTER_PROJECT_PICKER, ProductAnalytics.Action.HOME_REORDER_PROJECTS)
            projectList.persistOrder(worktrees)
        }

        /** [projectId] is a Home project key or one of its added paths; removal takes every member path. */
        fun requestRemoveProject(projectId: String) {
            logAction(ProductAnalytics.Screen.HOME, ProductAnalytics.Action.HOME_REMOVE_PROJECT)
            projectList.requestRemove(projectId)
        }

        fun dismissProjectRemoval() {
            logAction(
                ProductAnalytics.Screen.HOME_REMOVE_PROJECT_CONFIRM,
                ProductAnalytics.Action.HOME_REMOVE_PROJECT_CANCEL,
            )
            projectList.clearPendingRemoval()
        }

        fun confirmRemoveProject() {
            val pending = projectList.pendingRemoval ?: return
            logAction(
                ProductAnalytics.Screen.HOME_REMOVE_PROJECT_CONFIRM,
                ProductAnalytics.Action.HOME_REMOVE_PROJECT_CONFIRM,
            )
            viewModelScope.launch {
                projectList.removeProject(pending)
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
            projectList.openSession(target)
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

        private fun currentAddProjectSheet(): AddProjectSheetUi? =
            when (val state = content) {
                is MainScreenUiState.Empty -> state.addProjectSheet
                is MainScreenUiState.Loaded -> state.addProjectSheet
                else -> null
            }

        /** Patches the Add project sheet into Empty/Loaded; a content rebuild hides it until the next change. */
        private fun publishAddProjectSheet(sheet: AddProjectSheetUi) {
            projectList.setSheet(sheet)
        }

        private fun onWorktreeRequest(request: HomeWorktreeRequest) {
            when (request) {
                is HomeWorktreeRequest.ShowSessions ->
                    projectList.requestScope(
                        HomeScopeRequest(HomeScope.worktreeId(request.projectKey, request.directory), evenFromAll = true),
                    )
                is HomeWorktreeRequest.NewSession -> openNewSessionHere(request.directory)
                is HomeWorktreeRequest.Removed -> worktreesRegion.onWorktreeRemoved(request.directory)
            }
        }

        /** Whether a directory of [scopeId] in [moreSessionDirectories] has older sessions to load. */
        fun canLoadMoreSessions(
            moreSessionDirectories: Set<String>,
            scopeId: String,
            worktreesEnabled: Boolean = true,
        ): Boolean =
            projectList.canLoadMore(moreSessionDirectories, scopeId, worktreesEnabled)

        /**
         * The Home list scrolled near its end in [scopeId]: every observed directory of that scope
         * whose window has more lists one more page. A no-op while a load-more runs or when no
         * directory there has more.
         */
        fun loadMoreSessions(
            scopeId: String,
            worktreesEnabled: Boolean = true,
        ) {
            projectList.loadMore(scopeId, worktreesEnabled)
        }

        private fun supportsSessionViewed(): Boolean = activeConnectionManager.currentCapabilities().supportsSessionViewed

    }

fun suggestedHomeForkTitle(currentTitle: String): String = SessionForkTitle.suggested(currentTitle)

/** Home's paging: which scope loads more now, and which observed directories have more. */
data class HomeSessionPagingState(
    val loadingMoreScopeId: String? = null,
    /**
     * Observed directories whose latest window says older sessions may follow. A window whose
     * refresh failed stays out until a refresh succeeds, so paging never retries in a loop.
     */
    val moreSessionDirectories: Set<String> = emptySet(),
)

/** One directory's live window and the Home rows built from it. */
internal data class HomeDirectoryRows(
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
