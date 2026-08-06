package dev.opencode.mobile.domain.sessioncache

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.session.Session

/**
 * Deep Session Cache module used by Live Store, MessageSync, Settings, Clear All
 * Data, and remove-project. Persistence details stay behind [SessionCacheStore].
 */
interface SessionCache {
    suspend fun seedSessions(worktree: String): List<Session>?

    suspend fun rememberSessions(
        worktree: String,
        sessions: List<Session>,
    )

    fun openMessages(
        sessionId: String,
        worktree: String,
    ): SessionMessageCacheHandle

    suspend fun purgeWorktree(
        serverBaseUrl: String,
        worktree: String,
    )

    suspend fun clearAll()

    suspend fun estimatedSizeBytes(): Long
}

interface SessionMessageCacheHandle {
    suspend fun seed(): List<Message>?

    fun note(messages: List<Message>)

    suspend fun flush()

    fun close()
}
