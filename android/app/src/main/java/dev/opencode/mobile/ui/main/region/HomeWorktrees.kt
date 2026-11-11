package dev.opencode.mobile.ui.main.region

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.event.SessionMovedUpdate
import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeProjectGrouping
import dev.opencode.mobile.domain.home.HomeProjectsSnapshot
import dev.opencode.mobile.domain.home.HomeWorktreesFeed
import dev.opencode.mobile.domain.home.HomeWorktreesSnapshot
import dev.opencode.mobile.domain.home.ResolvedHomePath
import dev.opencode.mobile.domain.live.SessionListSnapshot
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.domain.session.SessionError
import dev.opencode.mobile.domain.session.SessionRepository
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.worktree.WorktreeInventory
import dev.opencode.mobile.domain.worktree.WorktreeInventoryCache
import dev.opencode.mobile.domain.worktree.WorktreeRepository
import dev.opencode.mobile.ui.main.HomeBranchLookups
import dev.opencode.mobile.ui.main.HomeDirectoryRows
import dev.opencode.mobile.ui.main.HomePathLoad
import dev.opencode.mobile.ui.main.HomeWorktreeIndex
import dev.opencode.mobile.ui.main.HomeWorktreeLoader
import dev.opencode.mobile.ui.main.homeExtraDirectories
import dev.opencode.mobile.ui.main.withSessionMoved
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** What Home knows about its projects beyond the added paths. */
data class HomeWorktreesState(
    val index: HomeWorktreeIndex = HomeWorktreeIndex(),
)

/**
 * Everything Home knows about projects beyond the added paths: each added path's project
 * (cache-seeded, then live), worktree inventories, the branch per worktree directory, and (v1)
 * the live session lists of listed worktrees that are not added paths. Publishes Home's projects
 * to Session Chat ([HomeProjectsSnapshot]) and the Worktrees screen ([HomeWorktreesFeed]).
 *
 * Sections, live rows of added paths, first loads and analytics stay with the facade and
 * HomeProjectList. Index changes go to [onIndexChanged] synchronously.
 */
internal class HomeWorktrees(
    worktreeRepository: WorktreeRepository,
    projectRepository: ProjectRepository,
    private val sessionRepository: SessionRepository,
    private val sessionCache: SessionCache,
    private val worktreeInventoryCache: WorktreeInventoryCache,
    private val homeWorktreesFeed: HomeWorktreesFeed,
    private val homeProjectsSnapshot: HomeProjectsSnapshot,
    private val scope: CoroutineScope,
    private val serverBaseUrl: () -> String,
    /** Home's current projects (from the Loaded content). */
    private val projects: () -> List<HomeProject>,
    /** Added paths with running live observations; empty while none run. */
    private val observedWorktrees: () -> List<String>,
    /** Whether Home's live observations run. */
    private val observing: () -> Boolean,
    /** One directory's live Home rows, built by HomeProjectList. */
    private val observeRows: (String) -> Flow<HomeDirectoryRows>,
    /** Records whether [directory]'s window has older sessions, for paging. */
    private val recordMoreSessions: (directory: String, snapshot: SessionListSnapshot) -> Unit,
    /** Directories whose extra lists stopped; paging forgets them. */
    private val dropMoreSessions: (Set<String>) -> Unit,
    private val onIndexChanged: (HomeWorktreeIndex) -> Unit,
) {
    var state = HomeWorktreesState()
        private set

    /** Projects, worktrees, branches, and (v1) other-worktree sessions; published on Loaded. */
    val index: HomeWorktreeIndex
        get() = state.index

    /** V1 directories with their own live list (listed worktrees that are not added paths). */
    val extraDirectories: Set<String>
        get() = extraObservationJobs.keys

    private val worktreeLoader =
        HomeWorktreeLoader(
            repository = worktreeRepository,
            currentProject = { directory -> projectRepository.getCurrentProject(directory) },
        )

    /** V1 added paths whose listed worktrees each need a session list. */
    private val listsSessionsPaths = mutableSetOf<String>()

    /** V1 live lists of worktrees that are not added paths, by canonical directory. */
    private var extraObservationJobs: Map<String, Job> = emptyMap()

    /** Branch per worktree directory; only a settled server answer decides it. */
    private val branchLookups =
        HomeBranchLookups(
            scope = scope,
            fetch = ::lookUpBranch,
            onChange = { branches -> updateIndex { it.copy(branches = branches) } },
        )
    private var worktreeMarkJob: Job? = null

    /** Session Chat reads the session's project and worktrees from here. */
    suspend fun publishProjects(projects: Flow<List<HomeProject>>) {
        projects
            .distinctUntilChanged()
            .collect(homeProjectsSnapshot::publish)
    }

    /** A checkout created in Session Chat (Move to worktree) lists here at once. */
    suspend fun followRefreshRequests() {
        homeProjectsSnapshot.worktreeRefreshRequests.collect(::refreshProjectWorktrees)
    }

    /** The Worktrees screen lists Home's projects and sessions. */
    suspend fun publishWorktrees(snapshots: Flow<HomeWorktreesSnapshot>) {
        snapshots
            .distinctUntilChanged()
            .collect(homeWorktreesFeed::publish)
    }

    suspend fun followRemoved() {
        homeWorktreesFeed.removed.collect { removed ->
            updateIndex { it.copy(removedWorktrees = removed) }
        }
    }

    /** The Worktrees screen removed a worktree: keep its sessions and list its project again. */
    fun onWorktreeRemoved(directory: String) {
        // Before the reload, so the v1 extra-session sync already keeps its sessions.
        val removed = homeWorktreesFeed.removed.value
        updateIndex { it.copy(removedWorktrees = removed) }
        refreshProjectWorktrees(directory)
    }

    /**
     * Cached projects for paths not resolved yet, so the cached paint groups (and keys its
     * scopes) the way the live load will. A key that changes later reloads the whole list.
     */
    suspend fun seedResolutions(
        worktrees: List<String>,
        connectionId: String? = null,
    ) {
        val cached =
            worktrees
                .filter { it !in index.resolutions }
                .mapNotNull { path ->
                    runCatching { sessionCache.seedProjectResolution(path, connectionId) }.getOrNull()?.let { path to it }
                }
        if (cached.isNotEmpty()) {
            state = state.copy(index = index.copy(resolutions = cached.toMap() + index.resolutions))
        }
    }

    /**
     * Resolves every added path's project (so Home groups as soon as possible), then loads
     * each project's worktrees and their branches. Sessions never wait for this.
     */
    fun reload(
        worktrees: List<String>,
        refreshProjects: Boolean = false,
    ) {
        worktreeMarkJob?.cancel()
        worktreeLoader.onConnection(serverBaseUrl())
        worktreeInventoryCache.setAddedPaths(worktrees, serverBaseUrl())
        branchLookups.refresh()
        worktreeMarkJob =
            scope.launch {
                val resolved =
                    coroutineScope {
                        worktrees
                            .map { worktree ->
                                async { worktree to worktreeLoader.resolve(worktree, refresh = refreshProjects) }
                            }.awaitAll()
                    }
                updateIndex { index ->
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
    fun refreshProjectWorktrees(directory: String) {
        val canonical = ServerPathPolicy.canonical(directory.trim())
        val projects = projects()
        val project =
            projects.firstOrNull { it.worktree(canonical) != null }
                ?: projects.firstOrNull { it.contains(canonical) }
                ?: return
        scope.launch {
            for (path in project.memberPaths) {
                applyPathLoad(worktreeLoader.load(path, index.resolutions[path]))
            }
        }
    }

    /** V2 `worktree.updated`: lists [projectId] again for every added path of that project. */
    suspend fun onWorktreeUpdated(projectId: String) {
        if (!worktreeLoader.knows(projectId)) return
        worktreeLoader.refetch(projectId).forEach(::applyPathLoad)
    }

    /** Regroups a moved session's v1 extra row at once. */
    fun applySessionMoved(moved: SessionMovedUpdate) {
        updateIndex { it.copy(extraSessions = it.extraSessions.withSessionMoved(moved)) }
    }

    /** A path Add project resolved; the next load starts from it. */
    fun remember(
        path: String,
        resolution: ResolvedHomePath,
    ) {
        worktreeLoader.remember(path, resolution)
        updateIndex { it.copy(resolutions = it.resolutions + (path to resolution)) }
    }

    /** A removed project's path. */
    fun forget(path: String) {
        worktreeLoader.forget(path)
        listsSessionsPaths.remove(path)
    }

    /** Disconnect or profile switch: forgets every project, inventory and branch. */
    fun reset() {
        homeWorktreesFeed.clear()
        worktreeLoader.onDisconnected()
        worktreeInventoryCache.clear()
        listsSessionsPaths.clear()
        branchLookups.clear()
        state = state.copy(index = HomeWorktreeIndex())
    }

    /**
     * V1 lists sessions per directory, so each listed worktree that is not an added path gets
     * its own live list. It refetches on the same session events as the added paths.
     */
    fun syncExtraObservations() {
        if (!observing()) return
        val wanted = homeExtraDirectories(observedWorktrees(), index.inventories, listsSessionsPaths).toSet()
        if (wanted == extraObservationJobs.keys) return
        val kept = extraObservationJobs.filterKeys { it in wanted }
        extraObservationJobs.filterKeys { it !in wanted }.values.forEach { it.cancel() }
        val started =
            (wanted - kept.keys).associateWith { directory ->
                scope.launch {
                    observeRows(directory).collect { rows ->
                        recordMoreSessions(directory, rows.snapshot)
                        val summaries =
                            rows.summaries.map { summary ->
                                if (summary.directory.isNullOrBlank()) summary.copy(directory = directory) else summary
                            }
                        updateIndex { it.copy(extraSessions = it.extraSessions + (directory to summaries)) }
                    }
                }
            }
        dropMoreSessions(extraObservationJobs.keys.filter { it !in wanted }.toSet())
        extraObservationJobs = kept + started
        // Sessions of a worktree removed from this app stay on Home (v1 cannot list them again).
        updateIndex { index ->
            index.copy(
                extraSessions = index.extraSessions.filterKeys { it in wanted || it in index.removedWorktrees },
            )
        }
    }

    /** Stops the v1 extra lists. */
    fun stopExtraObservations() {
        extraObservationJobs.values.forEach { it.cancel() }
        extraObservationJobs = emptyMap()
    }

    /** Stops a running [reload]. */
    fun stopReload() {
        worktreeMarkJob?.cancel()
        worktreeMarkJob = null
    }

    private fun applyPathLoad(load: HomePathLoad) {
        if (load.listsSessions) listsSessionsPaths += load.addedPath else listsSessionsPaths -= load.addedPath
        worktreeInventoryCache.record(load.inventory, load.addedPath, serverBaseUrl())
        updateIndex { index ->
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

    private fun updateIndex(transform: (HomeWorktreeIndex) -> HomeWorktreeIndex) {
        val previous = index
        val next = transform(previous)
        if (next == previous) return
        state = state.copy(index = next)
        val changed = next.resolutions.filter { (path, resolution) -> previous.resolutions[path] != resolution }
        changed.forEach { (path, resolution) -> sessionCache.rememberProjectResolution(path, resolution) }
        onIndexChanged(next)
    }

    /** Branch names for every project worktree not answered since the last reload. */
    private fun requestBranches() {
        branchLookups.lookUp(
            projects()
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
            DiagnosticLog.info(DiagnosticLogArea.Chat, "home branch failed directory=$directory: ${error.message}")
            HomeBranchLookups.Outcome.Unknown
        }
}
