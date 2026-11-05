package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.filesystem.ServerPathPolicy
import dev.opencode.mobile.domain.live.SessionListSnapshot
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionActivitySnapshot
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.domain.session.activeSessions
import dev.opencode.mobile.domain.session.rootSessions

/**
 * Home rows of one directory's list (#981): its window's active roots plus busy roots outside
 * the window, newest first, with status and unread outcome. Pure, so Home builds it off the
 * main thread.
 */
internal object HomeSessionRows {
    data class Result(
        val summaries: List<SessionSummary>,
        /** Busy sessions whose root is not known yet; Home asks the ancestry to resolve them. */
        val unresolvedBusyIds: List<String>,
    )

    /**
     * [rootOf] and [rootSession] read the known parent links (`SessionAncestry`). A busy root
     * outside the window joins the rows only when it is active and belongs to [directory]'s
     * list: the same project on a project-scoped (v2) list, else the same directory. It drops
     * out again once its family is idle.
     */
    fun build(
        directory: String,
        snapshot: SessionListSnapshot,
        activity: SessionActivitySnapshot,
        readyIds: Set<String>,
        serverReadState: Boolean,
        adapter: SessionDomainAdapter,
        rootOf: (String) -> String?,
        rootSession: (String) -> Session?,
    ): Result {
        val listed = snapshot.sessions.activeSessions().rootSessions()
        val listedIds = listed.mapTo(HashSet(listed.size)) { it.id }
        val resolveRoot: (String) -> String? = { id -> if (id in listedIds) id else rootOf(id) }
        val sourceByRoot = activity.homeActivityByRoot(resolveRoot)
        val merged =
            sourceByRoot.keys
                .filter { it !in listedIds }
                .mapNotNull(rootSession)
                .filter { root -> !root.isArchived && root.belongsTo(directory, snapshot.projectId) }
        val unresolved = activity.busySessionIds().filter { resolveRoot(it) == null }
        val showsStatus = activity.seeded || activity.fromCache
        val summaries =
            (listed + merged)
                .sortedByDescending { it.updatedAtEpochMs ?: 0L }
                .map { session ->
                    val sourceId = sourceByRoot[session.id] ?: session.id
                    adapter.toSessionSummary(
                        session,
                        statusLabel = if (showsStatus) activity.entries[sourceId]?.activity?.statusLabel() else null,
                        isReady = !serverReadState && session.id in readyIds,
                        statusStale = activity.fromCache && !activity.seeded,
                        // Waits for seeded status (iOS parity): a session running again with an
                        // older unviewed `time.idle` must not paint Done/Failed before its status is known.
                        unreadOutcome = if (activity.seeded) session.homeUnreadOutcome(serverReadState) else null,
                    )
                }
        return Result(summaries = summaries, unresolvedBusyIds = unresolved)
    }

    private fun Session.belongsTo(
        directory: String,
        projectId: String?,
    ): Boolean {
        if (projectId != null) return this.projectId == projectId
        val own = this.directory?.takeIf { it.isNotBlank() } ?: return false
        return ServerPathPolicy.canonical(own) == ServerPathPolicy.canonical(directory)
    }
}
