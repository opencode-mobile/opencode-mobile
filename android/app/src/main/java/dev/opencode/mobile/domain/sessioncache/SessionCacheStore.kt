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

    /**
     * Every cached message timeline as (connection id, session id), payloads not loaded. Null
     * when the store cannot list them; callers then skip work that needs the full set.
     */
    suspend fun messageTimelineEntries(): List<Pair<String, String>>? = null

    /** Marks a message timeline as just used, for [trimMessageTimelines]. */
    suspend fun touch(
        key: SessionCacheKey,
        atEpochMs: Long,
    ) = Unit

    /**
     * Keeps [connectionId]'s [keep] most recently used message timelines and deletes the rest.
     * Lists, activity and layout rows are never touched. Returns how many rows went.
     */
    suspend fun trimMessageTimelines(
        connectionId: String,
        keep: Int,
    ): Int = 0
}

enum class SessionCacheKind {
    SessionList,
    MessageTimeline,
    Activity,
    ProjectResolution,
    WorktreeLayout,
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
                SessionCacheKind.Activity -> "$connectionId|$worktree|activity"
                SessionCacheKind.ProjectResolution -> "$connectionId|$worktree|project"
                SessionCacheKind.WorktreeLayout -> "$connectionId|$worktree|worktrees"
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

    /** Every worktree of one connection: by profile id, or by `normalizedBaseUrl|` prefix. */
    data class Connection(
        val connectionIdOrUrlPrefix: String,
        val matchByUrlPrefix: Boolean = false,
    ) : SessionCacheEvictSelector
}

data class SessionCacheReport(
    val totalBytes: Long,
    val entryCount: Long,
)
