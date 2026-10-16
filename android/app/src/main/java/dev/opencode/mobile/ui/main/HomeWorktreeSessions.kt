package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.domain.worktree.WorktreeInventory
import dev.opencode.mobile.domain.worktree.WorktreeLoad
import dev.opencode.mobile.domain.worktree.WorktreeRepository
import kotlinx.coroutines.CancellationException

internal fun mergeHomeSessions(
    primary: List<SessionSummary>,
    extra: List<SessionSummary>,
): List<SessionSummary> {
    if (extra.isEmpty()) return primary
    val byId = LinkedHashMap<String, SessionSummary>()
    for (session in primary + extra) {
        val existing = byId[session.id]
        if (existing == null || (session.updatedAtEpochMs ?: 0L) >= (existing.updatedAtEpochMs ?: 0L)) {
            byId[session.id] = session
        }
    }
    return byId.values.sortedByDescending { it.updatedAtEpochMs ?: 0L }
}

/**
 * Keeps workspace sessions that were merged into a Home section separate from the
 * added-path list, so a later live update or a failed probe can drop them.
 */
internal class HomeWorktreeSessionFold {
    private val extras = mutableMapOf<String, List<SessionSummary>>()
    private val extraIds = mutableMapOf<String, Set<String>>()

    fun apply(
        addedPath: String,
        current: List<SessionSummary>,
        nextExtras: List<SessionSummary>,
    ): List<SessionSummary> {
        val previous = extraIds[addedPath].orEmpty()
        val primary = current.filter { it.id !in previous }
        extras[addedPath] = nextExtras
        extraIds[addedPath] = nextExtras.map { it.id }.toSet()
        return mergeHomeSessions(primary, nextExtras)
    }

    fun withLive(
        addedPath: String,
        primary: List<SessionSummary>,
    ): List<SessionSummary> = mergeHomeSessions(primary, extras[addedPath].orEmpty())
}

internal class HomeWorktreeMarks(
    private val repository: WorktreeRepository,
    private val currentProjectId: suspend (String) -> String?,
    private val listSessions: suspend (String) -> List<SessionSummary>,
) {
    private val projectIds = mutableMapOf<String, String>()
    private val fold = HomeWorktreeSessionFold()

    fun onDisconnected() = repository.onDisconnected()

    fun onConnection(key: String) = repository.onConnection(key)

    suspend fun load(addedPath: String): WorktreeMarkUpdate {
        val projectId = runCatching { currentProjectId(addedPath) }.getOrNull()?.takeIf { it.isNotBlank() }
        if (projectId != null) projectIds[addedPath] = projectId
        val load = repository.load(addedPath, projectId)
        if (load is WorktreeLoad.Loaded && !load.listsSessions && projectId != null) {
            repository.refresh(projectId)
        }
        return update(addedPath, projectId, load)
    }

    suspend fun refetch(projectId: String): WorktreeMarkUpdate? {
        val addedPath = projectIds.entries.firstOrNull { it.value == projectId }?.key ?: return null
        return update(addedPath, projectId, repository.refetch(projectId))
    }

    fun withLive(
        addedPath: String,
        primary: List<SessionSummary>,
    ): List<SessionSummary> = fold.withLive(addedPath, primary)

    private suspend fun update(
        addedPath: String,
        projectId: String?,
        load: WorktreeLoad,
    ): WorktreeMarkUpdate {
        val inventory =
            when (load) {
                is WorktreeLoad.Loaded -> WorktreeInventory.loaded(load.directories)
                WorktreeLoad.Unloaded, WorktreeLoad.Stopped -> WorktreeInventory.Unloaded
                is WorktreeLoad.Failed -> WorktreeInventory.Failed
            }
        val extras =
            if (load is WorktreeLoad.Loaded && load.listsSessions) {
                listExtra(addedPath, load.directories)
            } else {
                emptyList()
            }
        return WorktreeMarkUpdate(addedPath, projectId, inventory, extras)
    }

    fun applyExtras(
        addedPath: String,
        current: List<SessionSummary>,
        extras: List<SessionSummary>,
    ): List<SessionSummary> = fold.apply(addedPath, current, extras)

    private suspend fun listExtra(
        addedPath: String,
        directories: List<String>,
    ): List<SessionSummary> {
        val added = ServerPathPolicy.canonical(addedPath)
        val sessions = mutableListOf<SessionSummary>()
        for (directory in directories) {
            if (ServerPathPolicy.canonical(directory) == added) continue
            try {
                sessions += listSessions(directory)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // One workspace list failing does not fail the inventory.
            }
        }
        return sessions
    }
}

internal data class WorktreeMarkUpdate(
    val addedPath: String,
    val projectId: String?,
    val inventory: WorktreeInventory,
    val extras: List<SessionSummary>,
)
