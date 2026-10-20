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
)
