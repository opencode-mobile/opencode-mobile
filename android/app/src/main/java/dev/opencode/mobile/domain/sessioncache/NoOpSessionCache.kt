package dev.opencode.mobile.domain.sessioncache

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.session.Session

/** Test / optional injection default that never reads or writes disk. */
object NoOpSessionCache : SessionCache {
    override suspend fun seedSessions(worktree: String): List<Session>? = null

    override suspend fun rememberSessions(
        worktree: String,
        sessions: List<Session>,
    ) = Unit

    override suspend fun seedActivity(directory: String): List<SessionActivityCacheEntry>? = null

    override suspend fun rememberActivity(
        directory: String,
        entries: List<SessionActivityCacheEntry>,
    ) = Unit

    override fun openMessages(
        sessionId: String,
        worktree: String,
    ): SessionMessageCacheHandle = NoOpSessionMessageCacheHandle

    override suspend fun purgeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) = Unit

    override suspend fun clearAll() = Unit

    override suspend fun estimatedSizeBytes(): Long = 0L
}

private object NoOpSessionMessageCacheHandle : SessionMessageCacheHandle {
    override suspend fun seed(): List<Message>? = null

    override fun note(messages: List<Message>) = Unit

    override suspend fun flush() = Unit

    override fun close() = Unit
}
