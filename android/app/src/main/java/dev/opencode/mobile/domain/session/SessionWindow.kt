package dev.opencode.mobile.domain.session

/**
 * The newest root sessions of one directory's list, as one page request returned them (#981).
 * [sessions] are the rows as listed: an older v1 server that ignores `roots` also returns
 * children, which Home filters out and uses as parent links. [hasMore] is true when the server
 * filled the requested size, so a larger window may hold more.
 */
data class SessionWindow(
    val sessions: List<Session>,
    val hasMore: Boolean,
    /** v2: the project the list covers (every worktree of it); null for a directory list. */
    val projectId: String? = null,
)
