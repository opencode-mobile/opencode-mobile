package dev.opencode.mobile.domain.sessioncache

/**
 * Internal persistence seam for Room / SwiftData / in-memory adapters.
 * Not injected into Live Store or MessageSync — use [SessionCache] instead.
 */
interface SessionCacheStore {
    suspend fun load(key: SessionCacheKey): SessionCacheRecord?

    suspend fun commit(
        key: SessionCacheKey,
        value: SessionCacheValue,
        meta: SessionCacheCommitMeta,
    )

    suspend fun retain(
        key: SessionCacheKey,
        reason: SessionCacheRetainReason,
        atEpochMs: Long,
    )

    suspend fun evict(selector: SessionCacheEvictSelector)

    suspend fun report(selector: SessionCacheEvictSelector = SessionCacheEvictSelector.All): SessionCacheReport
}

enum class SessionCacheKind {
    SessionList,
    MessageTimeline,
}

data class SessionCacheKey(
    val connectionId: String,
    val worktree: String,
    val kind: SessionCacheKind,
    val entryId: String = "",
) {
    val cacheKey: String
        get() =
            when (kind) {
                SessionCacheKind.SessionList -> "$connectionId|$worktree|list"
                SessionCacheKind.MessageTimeline -> "$connectionId|$worktree|msg|$entryId"
            }
}

data class SessionCacheValue(
    val payloadJson: String,
    val schemaVersion: Int,
)

data class SessionCacheRecord(
    val value: SessionCacheValue,
    val syncedAtEpochMs: Long,
    val retainedAtEpochMs: Long? = null,
)

data class SessionCacheCommitMeta(
    val syncedAtEpochMs: Long,
)

enum class SessionCacheRetainReason {
    Opened,
}

sealed interface SessionCacheEvictSelector {
    data object All : SessionCacheEvictSelector

    data class Worktree(
        val connectionIdOrUrlPrefix: String,
        val worktree: String,
        val matchByUrlPrefix: Boolean = false,
    ) : SessionCacheEvictSelector
}

data class SessionCacheReport(
    val totalBytes: Long,
    val entryCount: Long,
)
