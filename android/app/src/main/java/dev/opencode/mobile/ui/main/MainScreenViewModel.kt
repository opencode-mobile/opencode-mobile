package dev.opencode.mobile.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.domain.connection.ConnectionEngine
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.engine.SessionCapabilities
import dev.opencode.mobile.domain.home.HomeSettleFeed
import dev.opencode.mobile.domain.home.HomeSettleGesture
import dev.opencode.mobile.domain.home.SnoozeUnit
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.connection.userMessage
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.FixedLiveUpdates
import dev.opencode.mobile.domain.event.LiveUpdates
import dev.opencode.mobile.domain.filesystem.FilesystemRepository
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.live.LiveStoreRefreshPolicy
import dev.opencode.mobile.domain.live.NoOpPermissionLiveStore
import dev.opencode.mobile.domain.live.PermissionLiveStore
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
import dev.opencode.mobile.domain.session.SessionForkTitle
import dev.opencode.mobile.domain.session.SessionIdleOutcome
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
import java.util.concurrent.ConcurrentHashMap
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
        private val permissionLiveStore: PermissionLiveStore = NoOpPermissionLiveStore,
        private val activeConnectionManager: ActiveConnectionManager,
        private val connectionSessionCoordinator: ConnectionSessionCoordinator,
        private val sessionCache: SessionCache,
        private val liveUpdates: LiveUpdates = FixedLiveUpdates(),
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
                capabilities = { SessionCapabilities.forProfile(activeConnectionManager.currentProfile()) },
                onSessionRestored = { reloadAfterSessionLifecycle() },
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
        private val archivedByWorktree = ConcurrentHashMap<String, List<SessionSummary>>()
        private var archivedReloadJob: Job? = null
        private var archivedGeneration = 0

        // Title fields report one edit per dialog appearance instead of per keystroke.
        private var renameDraftEditReported = false
        private var forkDraftEditReported = false

        // Add project sheet session; results from an earlier session are dropped by generation.
        private var addProjectSession: AddProjectSheetSession? = null
        private var addProjectGeneration = 0
        private var addProjectDebounceJob: Job? = null

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

        /**
         * Settle, snooze, and wake change which list a thread belongs to.
         * [refresh] returns immediately while live observations are running, and that
         * path never loads settled threads, so the row would stay on the ladder.
         */
        private fun reloadAfterSessionLifecycle() {
            viewModelScope.launch {
                sessionLiveStore.invalidate(null)
                refreshArchivedSessions()
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
                        try {
                            sessionLiveStore.invalidate(null)
                            sessionStatusLiveStore.invalidate(null)
                            refreshArchivedSessions()
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

        fun selectAddProjectSuggestion(path: String) {
            if (addProjectSession?.isValidating != false) return
            logAction(
                ProductAnalytics.Screen.HOME_ADD_PROJECT,
                ProductAnalytics.Action.ADD_PROJECT_SELECT_SUGGESTION,
            )
            addProject(path)
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
                    _pendingSelectedFilterId.value = worktree
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
                    .onSuccess {
                        // Persist the selected directory (web open-project behavior), not the
                        // /project/current worktree — non-git dirs resolve to global "/".
                        connectionProfileStore.addWorktree(serverBaseUrl, worktree)
                        dismissAddProjectSheet()
                        runHomeLoad(skipProbe = true)
                        _pendingSelectedFilterId.value = worktree
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
            // A settle from the session screen leaves home covered. Reveal reloads the lists.
            if (SessionCapabilities.forProfile(activeConnectionManager.currentProfile()).showsArchivedSessions) {
                reloadAfterSessionLifecycle()
            }
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
            val details = sessionActionDetails(sessionId, worktree)
            sessionActionsRegion.begin(
                sessionId = sessionId,
                title = title,
                worktree = worktree,
                canArchive = activeConnectionManager.currentCapabilities().supportsSessionArchive,
                lifecycle = details.lifecycle,
                showsSettle = showsHomeSettle(),
                snoozeBlockReason = details.snoozeBlockReason,
            )
        }

        fun showsHomeSettle(): Boolean = SessionCapabilities.forProfile(activeConnectionManager.currentProfile()).showsSettle

        fun showsTerminal(): Boolean = activeConnectionManager.currentCapabilities().supportsTerminal

        fun onSettleGesture(
            sessionId: String,
            title: String,
            worktree: String,
            gesture: HomeSettleGesture,
        ) {
            val details = sessionActionDetails(sessionId, worktree)
            sessionActionsRegion.begin(
                sessionId = sessionId,
                title = title,
                worktree = worktree,
                canArchive = activeConnectionManager.currentCapabilities().supportsSessionArchive,
                lifecycle = details.lifecycle,
                showsSettle = showsHomeSettle(),
                snoozeBlockReason = details.snoozeBlockReason,
                presentSheet = false,
            )
            when (gesture) {
                HomeSettleGesture.Settle -> sessionActionsRegion.chooseSettle()
                HomeSettleGesture.Snooze -> sessionActionsRegion.openSnooze()
                HomeSettleGesture.Wake -> sessionActionsRegion.chooseWake()
                HomeSettleGesture.Unsettle -> sessionActionsRegion.chooseUnsettle()
            }
        }

        private data class SessionActionDetails(
            val lifecycle: HomeSessionLifecycle,
            val snoozeBlockReason: String?,
        )

        /** Active rows can be snoozed; archived and settled rows come from the restore list. */
        private fun sessionActionDetails(
            sessionId: String,
            worktree: String,
        ): SessionActionDetails {
            val state = _uiState.value as? MainScreenUiState.Loaded
                ?: return SessionActionDetails(HomeSessionLifecycle.Active, null)
            val section = state.sections.firstOrNull { it.worktree == worktree }
                ?: return SessionActionDetails(HomeSessionLifecycle.Active, null)
            val active = section.allSessions.firstOrNull { it.id == sessionId }
            if (active != null) {
                val snoozed = HomeSettleFeed.isSnoozed(active.snoozedUntilEpochMs, System.currentTimeMillis(), active.isSettled)
                return SessionActionDetails(
                    lifecycle = if (snoozed) HomeSessionLifecycle.Snoozed else HomeSessionLifecycle.Active,
                    snoozeBlockReason = active.snoozeBlockReason,
                )
            }
            val archived = section.archivedSessions.firstOrNull { it.id == sessionId }
                ?: return SessionActionDetails(HomeSessionLifecycle.Active, null)
            val lifecycle =
                when {
                    archived.isArchived -> HomeSessionLifecycle.Archived
                    archived.isSettled -> HomeSessionLifecycle.Settled
                    else -> HomeSessionLifecycle.Active
                }
            return SessionActionDetails(lifecycle, null)
        }

        fun chooseUnarchive() {
            val state = sessionActions.value
            if (state.target == null || state.lifecycle != HomeSessionLifecycle.Archived || state.lifecycleInFlight) return
            sessionActionsRegion.chooseUnarchive()
        }

        fun chooseUnsettle() {
            val state = sessionActions.value
            if (state.target == null || state.lifecycle != HomeSessionLifecycle.Settled || state.lifecycleInFlight) return
            sessionActionsRegion.chooseUnsettle()
        }

        fun chooseSettle() {
            val state = sessionActions.value
            if (state.target == null || state.lifecycle != HomeSessionLifecycle.Active || state.lifecycleInFlight) return
            sessionActionsRegion.chooseSettle()
        }

        fun chooseWake() {
            val state = sessionActions.value
            if (state.target == null || state.lifecycle != HomeSessionLifecycle.Snoozed || state.lifecycleInFlight) return
            sessionActionsRegion.chooseWake()
        }

        fun openSnooze() {
            sessionActionsRegion.openSnooze()
        }

        fun dismissSnooze() {
            sessionActionsRegion.dismissSnooze()
        }

        fun updateSnoozeAmount(text: String) {
            sessionActionsRegion.updateSnoozeAmount(text)
        }

        fun cycleSnoozeUnit() {
            sessionActionsRegion.cycleSnoozeUnit()
        }

        fun chooseSnoozePreset(untilEpochMs: Long) {
            sessionActionsRegion.chooseSnoozePreset(untilEpochMs)
        }

        fun confirmCustomSnooze() {
            sessionActionsRegion.confirmCustomSnooze()
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

            var worktrees = connectionProfileStore.addedWorktrees(serverBaseUrl)

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
                if (!skipProbe) {
                    runCatching { projectRepository.probeServer() }
                        .onFailure { error ->
                            stopLiveObservations()
                            _uiState.value =
                                MainScreenUiState.ProbeFailed(
                                    when (error) {
                                        is ServerProbeError -> probeErrorMessage(error)
                                        else -> error.message ?: "Unable to reach the server"
                                    },
                                )
                            return
                        }
                    worktrees = connectionProfileStore.addedWorktrees(serverBaseUrl)
                }
                if (worktrees.isEmpty()) {
                    stopLiveObservations()
                    _uiState.value = MainScreenUiState.Empty()
                    return
                }
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
                archivedSessions = loadArchivedSummaries(worktree),
            )
        }

        /** Archived and settled sessions for engines that list them; a failed load hides the rows. */
        private suspend fun loadArchivedSummaries(worktree: String): List<SessionSummary> {
            val capabilities = SessionCapabilities.forProfile(activeConnectionManager.currentProfile())
            if (!capabilities.showsArchivedSessions) return emptyList()
            val archived = runCatching { sessionRepository.listArchivedSessions(directory = worktree) }.getOrDefault(emptyList())
            return archived
                .rootSessions()
                .sortedByDescending { it.updatedAtEpochMs ?: 0L }
                .map { session ->
                    sessionDomainAdapter
                        .toSessionSummary(session)
                        .copy(
                            isArchived = session.isArchived,
                            isSettled = session.isSettled && !session.isArchived,
                            snoozedUntilEpochMs = session.snoozedUntilEpochMs,
                            snoozeBlockReason = session.snoozeBlockReason,
                            settledAtEpochMs = session.settledAtEpochMs,
                        )
                }
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

        private fun loadAddProjectServerProjects(generation: Int) {
            viewModelScope.launch {
                val projects =
                    try {
                        projectRepository.listProjects()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        // The section stays hidden.
                        return@launch
                    }
                if (generation != addProjectGeneration) return@launch
                updateAddProjectSession { it.copy(serverProjects = projects) }
            }
        }

        private fun loadAddProjectVolumes(generation: Int) {
            viewModelScope.launch {
                val volumes =
                    try {
                        filesystemRepository.listDirectory(AddProjectSheetModel.VOLUMES_PATH)
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
            archivedReloadJob?.cancel()
            archivedReloadJob = viewModelScope.launch { refreshArchivedSessions() }
            liveObservationJobs =
                worktrees.map { worktree ->
                    viewModelScope.launch {
                        val pendingApprovalIds = MutableStateFlow<Set<String>>(emptySet())
                        if (observesHomePermissions) {
                            launch {
                                permissionLiveStore.observePermissions(worktree).collect { requests ->
                                    pendingApprovalIds.value = requests.mapTo(mutableSetOf()) { it.sessionId }
                                }
                            }
                        }
                        combine(
                            sessionLiveStore.observeSessions(worktree),
                            sessionStatusLiveStore.observeActivity(worktree),
                            sessionReadyStore.observeReadyIds(worktree),
                            pendingApprovalIds,
                        ) { snapshot, statuses, readyIds, approvalIds ->
                            HomeLiveFrame(snapshot.sessions, snapshot.isFromCache, statuses, readyIds, approvalIds)
                        }.collect { frame ->
                            if (observesHomePermissions) {
                                permissionLiveStore.noteSessions(
                                    worktree,
                                    frame.sessions.mapTo(mutableSetOf()) { it.id },
                                )
                            }
                            updateSectionFromLiveData(
                                worktree,
                                frame.sessions,
                                frame.statuses,
                                frame.readyIds,
                                sessionsFromCache = frame.sessionsFromCache,
                                pendingApprovalIds = frame.pendingApprovalIds,
                            )
                        }
                    }
                }
        }

        /** OpenCode pending approvals are GET /api/permission/request. T3 already flags them on the shell. */
        private val observesHomePermissions: Boolean
            get() = activeConnectionManager.currentProfile()?.engine != ConnectionEngine.T3

        private fun stopLiveObservations() {
            archivedGeneration += 1
            archivedReloadJob?.cancel()
            archivedReloadJob = null
            archivedByWorktree.clear()
            liveObservationJobs.forEach { it.cancel() }
            liveObservationJobs = emptyList()
            observedWorktrees = emptyList()
            permissionLiveStore.clearSessionIndex()
        }

        /** Settled and archived rows are not in the live session list. Keep the latest fetch. */
        private suspend fun refreshArchivedSessions() {
            val generation = archivedGeneration
            val loaded = _uiState.value as? MainScreenUiState.Loaded ?: return
            if (!SessionCapabilities.forProfile(activeConnectionManager.currentProfile()).showsArchivedSessions) return
            val fetched =
                loaded.sections.associate { section ->
                    section.worktree to loadArchivedSummaries(section.worktree)
                }
            if (generation != archivedGeneration) return
            archivedByWorktree.putAll(fetched)
            applyArchivedSessions()
        }

        private fun applyArchivedSessions() {
            val loaded = _uiState.value as? MainScreenUiState.Loaded ?: return
            val sections =
                loaded.sections.map { section ->
                    val archived = archivedByWorktree[section.worktree] ?: return@map section
                    if (archived == section.archivedSessions) section else section.copy(archivedSessions = archived)
                }
            _uiState.value = loaded.copy(sections = sections)
        }

        private fun liveObservationsRunning(worktrees: List<String>): Boolean =
            liveObservationJobs.isNotEmpty() && observedWorktrees == worktrees

        private fun updateSectionFromLiveData(
            worktree: String,
            sessions: List<Session>,
            snapshot: SessionActivitySnapshot,
            readyIds: Set<String>,
            sessionsFromCache: Boolean,
            pendingApprovalIds: Set<String> = emptySet(),
        ) {
            val state = _uiState.value as? MainScreenUiState.Loaded ?: return
            val section = state.sections.firstOrNull { it.worktree == worktree } ?: return
            // v2 servers own read state; the local per-device Ready ids stay a v1 fallback.
            val serverReadState = supportsSessionViewed()
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
                            isReady = !serverReadState && session.id in readyIds,
                            statusStale = snapshot.fromCache && !snapshot.seeded,
                            // Waits for seeded status (iOS parity): a session running again with an
                            // older unviewed `time.idle` must not paint Done/Failed before its status is known.
                            unreadOutcome = if (snapshot.seeded) session.homeUnreadOutcome(serverReadState) else null,
                        ).let { summary ->
                            if (session.id in pendingApprovalIds) summary.copy(needsApproval = true) else summary
                        }
                    }
            val updatedSection =
                section.copy(
                    allSessions = summaries,
                    sessionsFromCache = sessionsFromCache,
                    archivedSessions = archivedByWorktree[worktree] ?: section.archivedSessions,
                )
            val updatedSections =
                state.sections.map { current ->
                    if (current.worktree == worktree) updatedSection else current
                }
            _uiState.value = state.copy(sections = updatedSections)
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

private data class HomeLiveFrame(
    val sessions: List<Session>,
    val sessionsFromCache: Boolean,
    val statuses: SessionActivitySnapshot,
    val readyIds: Set<String>,
    val pendingApprovalIds: Set<String>,
)

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
    val lifecycle: HomeSessionLifecycle = HomeSessionLifecycle.Active,
    val showsSettle: Boolean = false,
    val snoozeBlockReason: String? = null,
    val snoozeVisible: Boolean = false,
    val snoozeAmount: String = "1",
    val snoozeUnit: SnoozeUnit = SnoozeUnit.Hours,
    val snoozeError: String? = null,
) {
    val hasOpenDialog: Boolean
        get() = renameVisible || forkVisible || forkResultVisible || deleteConfirmVisible || lifecycleInFlight || snoozeVisible
}

/** Where a home session row sits in its lifecycle; drives the long-press actions. */
enum class HomeSessionLifecycle {
    Active,
    Archived,
    Settled,
    Snoozed,
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
    /** Archived and settled sessions listed after the active ones; T3 only. */
    val archivedSessions: List<SessionSummary> = emptyList(),
)

data class SessionOpenTarget(
    val sessionId: String,
    val title: String,
    val worktree: String,
    val optimisticNavigationInstanceId: String? = null,
)

/** Unread finished-run outcome for Home; null when read or when the server has no read state. */
internal fun Session.homeUnreadOutcome(serverReadState: Boolean): SessionIdleOutcome? =
    if (serverReadState && isServerUnread) idleOutcome ?: SessionIdleOutcome.Succeeded else null
