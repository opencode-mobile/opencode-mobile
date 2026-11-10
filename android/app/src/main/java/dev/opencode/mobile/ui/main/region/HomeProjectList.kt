package dev.opencode.mobile.ui.main.region

import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.SessionMovedUpdate
import dev.opencode.mobile.domain.event.isWorktreeUpdated
import dev.opencode.mobile.domain.event.namedProjectId
import dev.opencode.mobile.domain.event.sessionMoved
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeScope
import dev.opencode.mobile.domain.live.SessionAncestry
import dev.opencode.mobile.domain.live.SessionListSnapshot
import dev.opencode.mobile.domain.live.SessionLiveStore
import dev.opencode.mobile.domain.live.SessionReadyStore
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.session.SessionActivitySnapshot
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.ui.main.HomeDirectoryRows
import dev.opencode.mobile.ui.main.HomeProjectFirstLoads
import dev.opencode.mobile.ui.main.HomeSessionPagingState
import dev.opencode.mobile.ui.main.HomeSessionRows
import dev.opencode.mobile.ui.main.MainScreenUiState
import dev.opencode.mobile.ui.main.ProjectFirstLoad
import dev.opencode.mobile.ui.main.ProjectFirstLoadTiming
import dev.opencode.mobile.ui.main.withSessionMoved
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** Home's content and the list state that goes with it. */
data class HomeProjectListState(
    val content: MainScreenUiState = MainScreenUiState.Probing,
    val paging: HomeSessionPagingState = HomeSessionPagingState(),
)

/**
 * The Home content: [MainScreenUiState] and everything that writes it. Live observation of each
 * added path (sessions + statuses + ready ids + ancestry → Home rows, off the main thread),
 * first loads of newly added projects, first server snapshots that arrive before their section,
 * and paging. Every state change goes to [onChange] synchronously, in the same call as the write.
 */
internal class HomeProjectList(
    private val sessionLiveStore: SessionLiveStore,
    private val sessionStatusLiveStore: SessionStatusLiveStore,
    private val sessionReadyStore: SessionReadyStore,
    private val sessionDomainAdapter: SessionDomainAdapter,
    private val sessionAncestry: SessionAncestry,
    private val foregroundEventStream: ForegroundEventStream,
    projectFirstLoadTiming: ProjectFirstLoadTiming,
    private val worktrees: HomeWorktrees,
    private val scope: CoroutineScope,
    private val supportsSessionViewed: () -> Boolean,
    private val onChange: (HomeProjectListState) -> Unit,
) {
    var state = HomeProjectListState()
        private set

    val content: MainScreenUiState
        get() = state.content

    /** Builds Home rows off the main thread (#981); tests run it on their scheduler. */
    var homeRowsDispatcher: CoroutineDispatcher = Dispatchers.Default

    private var loadMoreJob: Job? = null
    private var liveObservationJobs: List<Job> = emptyList()

    /** Added paths with running live observations; empty while none run. */
    var observedWorktrees: List<String> = emptyList()
        private set

    /** Worktrees whose combined sessions + status + ready stream has painted since observations started. */
    private val livePaintedWorktrees = mutableSetOf<String>()
    private val pendingFirstServerSnapshots = mutableMapOf<String, SessionListSnapshot>()

    private val projectFirstLoads =
        HomeProjectFirstLoads(
            scope = scope,
            timing = projectFirstLoadTiming,
            observeSessions = { worktree -> sessionLiveStore.observeSessions(worktree) },
            refetchSessions = { worktree -> sessionLiveStore.invalidate(worktree) },
            onServerSnapshot = { worktree, snapshot -> paintFirstServerSnapshot(worktree, snapshot) },
            onChange = { loads ->
                updateContent { state ->
                    if (state is MainScreenUiState.Loaded) state.copy(projectLoads = loads) else state
                }
            },
        )

    /** First loads Home shows now. */
    val visibleProjectLoads: Map<String, ProjectFirstLoad>
        get() = projectFirstLoads.visible

    /** Whether live observations run. */
    val observing: Boolean
        get() = liveObservationJobs.isNotEmpty()

    fun setContent(next: MainScreenUiState) {
        state = state.copy(content = next)
        onChange(state)
    }

    fun updateContent(transform: (MainScreenUiState) -> MainScreenUiState) {
        setContent(transform(state.content))
    }

    /** Home projects: added paths of one repository grouped, in added order. */
    fun projects(): List<HomeProject> = (content as? MainScreenUiState.Loaded)?.projects.orEmpty()

    /** Added paths for a project key, or the path itself. */
    fun memberPathsOf(
        id: String,
        projects: List<HomeProject> = projects(),
    ): List<String> =
        projects.firstOrNull { it.key == id }?.memberPaths
            ?: projects.firstOrNull { id in it.memberPaths }?.memberPaths
            ?: listOf(id)

    /** A project just added: track its first load. */
    fun beginProjectLoad(worktree: String) {
        projectFirstLoads.start(worktree)
    }

    /** Projects painted from the switched-to server's cache whose sessions wait for the server. */
    fun awaitServer(worktrees: Collection<String>) {
        projectFirstLoads.awaitServer(worktrees)
    }

    fun startAwaitingProjectLoads() {
        projectFirstLoads.startAwaiting()
    }

    /**
     * Failed first load of a newly added project: show loading again and refetch its sessions.
     * [scopeId] is an added path or a Home project key (every member path retries).
     */
    fun retryProjectLoad(scopeId: String) {
        memberPathsOf(scopeId).forEach(projectFirstLoads::retry)
    }

    /** A removed project's path: its first load and any parked snapshot go. */
    fun forgetProjectLoad(worktree: String) {
        projectFirstLoads.clear(worktree)
        pendingFirstServerSnapshots.remove(worktree)
    }

    /** Server or profile change: parked snapshots go, and first loads unless [clearFirstLoads] is false. */
    fun resetLoads(clearFirstLoads: Boolean = true) {
        if (clearFirstLoads) projectFirstLoads.clearAll()
        pendingFirstServerSnapshots.clear()
    }

    /** Every live list and status refetches. */
    suspend fun invalidateAll() {
        sessionLiveStore.invalidate(null)
        sessionStatusLiveStore.invalidate(null)
    }

    /** New added-path order; live observations follow it. */
    fun reorderObserved(worktrees: List<String>) {
        if (observedWorktrees.isNotEmpty()) {
            observedWorktrees = worktrees
        }
    }

    fun startLiveObservations(worktrees: List<String>) {
        if (liveObservationsRunning(worktrees)) return
        stopLiveObservations()
        observedWorktrees = worktrees
        livePaintedWorktrees.clear()
        liveObservationJobs =
            worktrees.map { worktree ->
                scope.launch {
                    observeHomeRows(worktree).collect { rows ->
                        livePaintedWorktrees += worktree
                        recordMoreSessions(worktree, rows.snapshot)
                        updateSectionFromLiveData(worktree, rows.summaries, sessionsFromCache = rows.snapshot.isFromCache)
                    }
                }
            } +
                scope.launch {
                    foregroundEventStream.events.collect { event ->
                        event.sessionMoved()?.let { moved ->
                            applySessionMoved(moved)
                            return@collect
                        }
                        val projectId = event.namedProjectId() ?: return@collect
                        if (!event.isWorktreeUpdated) return@collect
                        this@HomeProjectList.worktrees.onWorktreeUpdated(projectId)
                    }
                }
        this.worktrees.syncExtraObservations()
    }

    /** Stops every live list, load-more and worktree reload, and resets paging (H-AH-7). */
    fun stopLiveObservations() {
        loadMoreJob?.cancel()
        loadMoreJob = null
        updatePaging { it.copy(loadingMoreScopeId = null) }
        updatePaging { it.copy(moreSessionDirectories = emptySet()) }
        liveObservationJobs.forEach { it.cancel() }
        liveObservationJobs = emptyList()
        worktrees.stopExtraObservations()
        observedWorktrees = emptyList()
        livePaintedWorktrees.clear()
        worktrees.stopReload()
    }

    fun liveObservationsRunning(worktrees: List<String>): Boolean = liveObservationJobs.isNotEmpty() && observedWorktrees == worktrees

    fun applyPendingFirstServerSnapshots() {
        pendingFirstServerSnapshots.toMap().forEach { (worktree, snapshot) ->
            paintFirstServerSnapshot(worktree, snapshot)
        }
    }

    /**
     * [directory]'s Home rows from its live window, statuses, and ready ids (#981). Rows are
     * built on [homeRowsDispatcher] and conflated, so only the latest reaches the main thread;
     * a new parent link learned by the ancestry rebuilds them.
     */
    fun observeHomeRows(directory: String): Flow<HomeDirectoryRows> =
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

    fun recordMoreSessions(
        directory: String,
        snapshot: SessionListSnapshot,
    ) {
        updatePaging { paging ->
            val more = paging.moreSessionDirectories
            paging.copy(moreSessionDirectories = if (snapshot.hasMore && !snapshot.loadFailed) more + directory else more - directory)
        }
    }

    /** Directories whose lists stopped; paging forgets them. */
    fun dropMoreSessions(stopped: Set<String>) {
        updatePaging { it.copy(moreSessionDirectories = it.moreSessionDirectories - stopped) }
    }

    /** Whether a directory of [scopeId] in [moreSessionDirectories] has older sessions to load. */
    fun canLoadMore(
        moreSessionDirectories: Set<String>,
        scopeId: String,
        worktreesEnabled: Boolean,
    ): Boolean =
        moreSessionDirectories.isNotEmpty() &&
            scopeDirectories(scopeId, worktreesEnabled).any { it in moreSessionDirectories }

    /**
     * The Home list scrolled near its end in [scopeId]: every observed directory of that scope
     * whose window has more lists one more page. A no-op while a load-more runs or when no
     * directory there has more.
     */
    fun loadMore(
        scopeId: String,
        worktreesEnabled: Boolean,
    ) {
        if (loadMoreJob?.isActive == true) return
        val directories = scopeDirectories(scopeId, worktreesEnabled).filter { it in state.paging.moreSessionDirectories }
        if (directories.isEmpty()) return
        updatePaging { it.copy(loadingMoreScopeId = scopeId) }
        loadMoreJob =
            scope.launch {
                try {
                    coroutineScope {
                        directories.forEach { directory -> launch { sessionLiveStore.loadMore(directory) } }
                    }
                } finally {
                    updatePaging { it.copy(loadingMoreScopeId = null) }
                }
            }
    }

    /** Regroups a moved session's row at once; the list refetch on the same event confirms it. */
    private fun applySessionMoved(moved: SessionMovedUpdate) {
        worktrees.applySessionMoved(moved)
        updateContent { state ->
            if (state is MainScreenUiState.Loaded) state.withSessionMoved(moved) else state
        }
    }

    private fun updateSectionFromLiveData(
        worktree: String,
        summaries: List<SessionSummary>,
        sessionsFromCache: Boolean,
    ) {
        val state = content as? MainScreenUiState.Loaded ?: return
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
        setContent(state.copy(sections = updatedSections))
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
        val loaded = content as? MainScreenUiState.Loaded
        if (loaded?.sections?.any { it.worktree == worktree } != true) {
            // Home has not painted the project's section yet; the load applies this after it does.
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

    /**
     * Observed directories whose lists feed [scopeId]: every one for All; a project's added
     * paths and (v1) its other worktrees' lists; for a worktree, the lists that can hold it
     * (its own, or a project-wide added path).
     */
    private fun scopeDirectories(
        scopeId: String,
        worktreesEnabled: Boolean,
    ): List<String> {
        val extras = worktrees.extraDirectories
        val projects = projects()
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

    private fun updatePaging(transform: (HomeSessionPagingState) -> HomeSessionPagingState) {
        state = state.copy(paging = transform(state.paging))
        onChange(state)
    }
}
