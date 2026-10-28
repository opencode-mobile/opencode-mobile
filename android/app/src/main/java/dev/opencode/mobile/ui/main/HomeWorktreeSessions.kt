package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeProjectGrouping
import dev.opencode.mobile.domain.home.ResolvedHomePath
import dev.opencode.mobile.domain.project.Project
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.domain.worktree.WorktreeInventory
import dev.opencode.mobile.domain.worktree.WorktreeLoad
import dev.opencode.mobile.domain.worktree.WorktreeRepository
import kotlinx.coroutines.CancellationException

/**
 * What Home knows about added paths beyond their own session lists: the current project of
 * each path, the worktrees it lists, branch names, and (v1) the sessions of worktrees that
 * are not added paths themselves.
 */
data class HomeWorktreeIndex(
    val resolutions: Map<String, ResolvedHomePath> = emptyMap(),
    /** Listed worktree directories per added path, once loaded. */
    val inventories: Map<String, Set<String>> = emptyMap(),
    /** Known branch per canonical worktree directory. */
    val branches: Map<String, String> = emptyMap(),
    /** V1 sessions of listed worktrees that are not added paths, per canonical directory. */
    val extraSessions: Map<String, List<SessionSummary>> = emptyMap(),
) {
    fun projects(addedPaths: List<String>): List<HomeProject> =
        HomeProjectGrouping.group(
            addedPaths = addedPaths,
            resolved = resolutions,
            inventories = inventories,
            branches = branches,
        )
}

/** One added path's resolved project and worktree inventory. */
internal data class HomePathLoad(
    val addedPath: String,
    val resolution: ResolvedHomePath?,
    val inventory: WorktreeInventory,
    /** V1: every listed directory needs its own session list. */
    val listsSessions: Boolean,
)

/**
 * Resolves each added path's current project and loads its worktree inventory. Only paths
 * whose project merges (a real project id) list worktrees; `global` and unresolved paths stay
 * one checkout each.
 */
internal class HomeWorktreeLoader(
    private val repository: WorktreeRepository,
    private val currentProject: suspend (String) -> Project,
) {
    private val resolutions = mutableMapOf<String, ResolvedHomePath>()
    private val epochs = mutableMapOf<String, Int>()

    fun onDisconnected() {
        repository.onDisconnected()
        resolutions.clear()
        epochs.clear()
    }

    fun onConnection(key: String) = repository.onConnection(key)

    fun knows(projectId: String): Boolean = resolutions.values.any { it.projectId == projectId }

    /** Records a resolution made elsewhere (Add project) so the next load starts from it. */
    fun remember(
        addedPath: String,
        resolution: ResolvedHomePath,
    ) {
        resolutions[addedPath] = resolution
    }

    fun forget(addedPath: String) {
        resolutions.remove(addedPath)
        epochs.remove(addedPath)
    }

    /** The current project of [addedPath]; a known one is reused unless [refresh]. A failure keeps the last one. */
    suspend fun resolve(
        addedPath: String,
        refresh: Boolean = true,
    ): ResolvedHomePath? {
        if (!refresh) resolutions[addedPath]?.let { return it }
        val resolved =
            try {
                ResolvedHomePath.from(currentProject(addedPath))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        if (resolved != null) resolutions[addedPath] = resolved
        return resolved ?: resolutions[addedPath]
    }

    /** Loads [addedPath]'s worktrees; resolves its project first unless [resolved] is given. */
    suspend fun load(
        addedPath: String,
        resolved: ResolvedHomePath? = null,
    ): HomePathLoad {
        val resolution = resolved ?: resolve(addedPath)
        if (resolution?.isMergeable != true) {
            return HomePathLoad(addedPath, resolution, WorktreeInventory.Unloaded, listsSessions = false)
        }
        val epoch = begin(addedPath)
        val loaded = repository.loadRefreshed(addedPath, resolution.projectId, resolution.canonicalWorktree)
        // A refetch that started during this load saw the pre-refresh list. Drop it.
        end(addedPath, epoch)
        return update(addedPath, resolution, loaded)
    }

    /** V2 `worktree.updated`: lists [projectId] again for every added path of that project. */
    suspend fun refetch(projectId: String): List<HomePathLoad> {
        val paths = resolutions.filterValues { it.projectId == projectId }.keys.toList()
        if (paths.isEmpty()) return emptyList()
        val started = paths.associateWith { epochs[it] }
        val loaded = repository.refetch(projectId)
        return paths.mapNotNull { path ->
            val epoch = started[path] ?: return@mapNotNull null
            if (epochs[path] != epoch) return@mapNotNull null
            update(path, resolutions[path], loaded)
        }
    }

    private fun begin(addedPath: String): Int {
        val next = (epochs[addedPath] ?: 0) + 1
        epochs[addedPath] = next
        return next
    }

    private fun end(
        addedPath: String,
        epoch: Int,
    ) {
        if (epochs[addedPath] == epoch) epochs[addedPath] = epoch + 1
    }

    private fun update(
        addedPath: String,
        resolution: ResolvedHomePath?,
        load: WorktreeLoad,
    ): HomePathLoad {
        val inventory =
            when (load) {
                is WorktreeLoad.Loaded -> WorktreeInventory.loaded(load.directories)
                WorktreeLoad.Unloaded, WorktreeLoad.Stopped -> WorktreeInventory.Unloaded
                is WorktreeLoad.Failed -> WorktreeInventory.Failed
            }
        return HomePathLoad(
            addedPath = addedPath,
            resolution = resolution,
            inventory = inventory,
            listsSessions = load is WorktreeLoad.Loaded && load.listsSessions,
        )
    }
}

/**
 * V1 directories Home lists on their own: every listed worktree of a project that lists
 * sessions per directory, except the added paths (which already have a list).
 */
internal fun homeExtraDirectories(
    addedPaths: List<String>,
    inventories: Map<String, Set<String>>,
    listsSessions: Set<String>,
): List<String> {
    val added = addedPaths.mapTo(HashSet()) { ServerPathPolicy.canonical(it.trim()) }
    return addedPaths
        .filter { it in listsSessions }
        .flatMap { inventories[it].orEmpty() }
        .map { ServerPathPolicy.canonical(it.trim()) }
        .filter { it.isNotBlank() && it !in added }
        .distinct()
}
