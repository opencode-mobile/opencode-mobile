package dev.opencode.mobile.domain.sessioncache

import dev.opencode.mobile.domain.home.ResolvedHomePath
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionActivity

/**
 * Deep Session Cache module used by Live Store, MessageSync, Settings, Clear All
 * Data, and remove-project. Persistence details stay behind [SessionCacheStore].
 */
data class SessionActivityCacheEntry(
    val sessionId: String,
    val activity: SessionActivity,
    val observedAtMs: Long,
)

interface SessionCache {
    suspend fun seedSessions(worktree: String): List<Session>?

    /** Cached list for [worktree] under [connectionId] (a Connection Profile id), bound or not. */
    suspend fun seedSessions(
        worktree: String,
        connectionId: String,
    ): List<Session>? = null

    suspend fun rememberSessions(
        worktree: String,
        sessions: List<Session>,
    )

    suspend fun seedActivity(directory: String): List<SessionActivityCacheEntry>?

    suspend fun rememberActivity(
        directory: String,
        entries: List<SessionActivityCacheEntry>,
    )

    /**
     * The server project [worktree] last resolved to, so a cold Home groups it as the live one
     * will. [connectionId] reads another profile's entry (a switch before its server is bound).
     */
    suspend fun seedProjectResolution(
        worktree: String,
        connectionId: String? = null,
    ): ResolvedHomePath? = null

    /**
     * Queues the write under the connection active now and returns; writes land in call order,
     * and a purge of the worktree or connection drops one still queued.
     */
    fun rememberProjectResolution(
        worktree: String,
        resolution: ResolvedHomePath,
    ) = Unit

    fun openMessages(
        sessionId: String,
        worktree: String,
    ): SessionMessageCacheHandle

    suspend fun purgeWorktree(
        serverBaseUrl: String,
        worktree: String,
    )

    /** Everything cached for one removed Connection Profile, under its id and under its URL. */
    suspend fun purgeConnection(
        profileId: String,
        serverBaseUrl: String,
    ) = Unit

    suspend fun clearAll()

    suspend fun estimatedSizeBytes(): Long
}

interface SessionMessageCacheHandle {
    suspend fun seed(): List<Message>?

    fun note(messages: List<Message>)

    suspend fun flush()

    fun close()
}
