package dev.opencode.mobile.domain.worktree

sealed class WorktreeLoad {
    data class Loaded(
        val directories: List<String>,
        /** V1 workspace directories need a session list. V2 sessions are already listed. */
        val listsSessions: Boolean,
    ) : WorktreeLoad()

    data object Unloaded : WorktreeLoad()

    /** V1 probe returned 404. Stop until the next connect. */
    data object Stopped : WorktreeLoad()

    data class Failed(
        val cause: Throwable,
    ) : WorktreeLoad()
}

interface WorktreeRepository {
    /**
     * V2 lists `GET /api/worktree?projectID=` when [projectId] is present.
     * V1 probes `GET /experimental/workspace` for [addedPath]. A missing V2
     * project id does not call the worktree routes.
     */
    suspend fun load(
        addedPath: String,
        projectId: String?,
    ): WorktreeLoad

    /**
     * V2 list, then refresh, then list again so a `worktree.updated` emitted during
     * refresh is not required for the post-refresh inventory. A failed refresh keeps
     * the first list. A failed second list replaces it.
     */
    suspend fun loadRefreshed(
        addedPath: String,
        projectId: String?,
    ): WorktreeLoad

    /** V2 `POST /api/worktree/refresh`. Failure is reported and does not clear a list. */
    suspend fun refresh(projectId: String): Boolean

    /** V2 list again. Does not refresh. */
    suspend fun refetch(projectId: String): WorktreeLoad

    fun onDisconnected()

    fun onConnection(key: String)
}

/** Tests and previews: never calls the server, so nothing is badged. */
object UnavailableWorktreeRepository : WorktreeRepository {
    override suspend fun load(
        addedPath: String,
        projectId: String?,
    ): WorktreeLoad = WorktreeLoad.Unloaded

    override suspend fun loadRefreshed(
        addedPath: String,
        projectId: String?,
    ): WorktreeLoad = WorktreeLoad.Unloaded

    override suspend fun refresh(projectId: String): Boolean = false

    override suspend fun refetch(projectId: String): WorktreeLoad = WorktreeLoad.Unloaded

    override fun onDisconnected() = Unit

    override fun onConnection(key: String) = Unit
}
