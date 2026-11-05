package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.session.Session

data class SessionListSnapshot(
    val sessions: List<Session>,
    val isFromCache: Boolean = false,
    /**
     * True when the latest server load for this directory failed. [sessions] then holds
     * whatever was known before (cache or an earlier load) and [isFromCache] is true.
     * The next successful load publishes with this cleared.
     */
    val loadFailed: Boolean = false,
    /** The server filled Home's window, so [SessionLiveStore.loadMore] may list older sessions. */
    val hasMore: Boolean = false,
    /** v2: the project the list covers (every worktree of it); null for a directory list. */
    val projectId: String? = null,
)

/** Home lists sessions a window at a time (#981): one page first, one more per load-more. */
object HomeSessionPaging {
    const val PAGE_SIZE = 50
}
