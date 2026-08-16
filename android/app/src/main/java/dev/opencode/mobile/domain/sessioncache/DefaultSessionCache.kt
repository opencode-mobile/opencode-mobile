package dev.opencode.mobile.domain.sessioncache

import android.util.Log
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.session.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class DefaultSessionCache(
    private val store: SessionCacheStore,
    private val connectionIdProvider: () -> String?,
    private val scope: CoroutineScope,
    private val json: Json,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) : SessionCache {
    override suspend fun seedSessions(worktree: String): List<Session>? {
        val connectionId = connectionIdProvider() ?: return null
        val key =
            SessionCacheKey(
                connectionId = connectionId,
                worktree = worktree,
                kind = SessionCacheKind.SessionList,
            )
        val record =
            runCatching { store.load(key) }
                .onFailure { logSessionCacheIo("load sessions", it) }
                .getOrNull() ?: return null
        return SessionCacheCodec.decodeSessions(record.value.payloadJson, json)
    }

    override suspend fun rememberSessions(
        worktree: String,
        sessions: List<Session>,
    ) {
        val connectionId = connectionIdProvider() ?: return
        val key =
            SessionCacheKey(
                connectionId = connectionId,
                worktree = worktree,
                kind = SessionCacheKind.SessionList,
            )
        val payload =
            runCatching { SessionCacheCodec.encodeSessions(sessions, json) }
                .onFailure { logSessionCacheIo("encode sessions", it) }
                .getOrNull() ?: return
        runCatching {
            store.commit(
                key = key,
                value =
                    SessionCacheValue(
                        payloadJson = payload,
                        schemaVersion = SessionCacheCodec.SCHEMA_VERSION,
                    ),
                meta = SessionCacheCommitMeta(syncedAtEpochMs = clock()),
            )
        }.onFailure { logSessionCacheIo("commit sessions", it) }
    }

    override fun openMessages(
        sessionId: String,
        worktree: String,
    ): SessionMessageCacheHandle =
        DefaultSessionMessageCacheHandle(
            sessionId = sessionId,
            worktree = worktree,
            store = store,
            connectionIdProvider = connectionIdProvider,
            scope = scope,
            json = json,
            clock = clock,
            debounceMs = debounceMs,
        )

    override suspend fun purgeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {
        val connectionId = connectionIdProvider()
        if (connectionId != null) {
            runCatching {
                store.evict(
                    SessionCacheEvictSelector.Worktree(
                        connectionIdOrUrlPrefix = connectionId,
                        worktree = worktree,
                        matchByUrlPrefix = false,
                    ),
                )
            }.onFailure { logSessionCacheIo("purge worktree by profile", it) }
        }
        runCatching {
            store.evict(
                SessionCacheEvictSelector.Worktree(
                    connectionIdOrUrlPrefix = CacheUrlNormalizer.connectionIdPrefix(serverBaseUrl),
                    worktree = worktree,
                    matchByUrlPrefix = true,
                ),
            )
        }.onFailure { logSessionCacheIo("purge worktree", it) }
    }

    override suspend fun clearAll() {
        runCatching { store.evict(SessionCacheEvictSelector.All) }
            .onFailure { logSessionCacheIo("clear all", it) }
    }

    override suspend fun estimatedSizeBytes(): Long =
        runCatching { store.report(SessionCacheEvictSelector.All).totalBytes }
            .onFailure { logSessionCacheIo("report size", it) }
            .getOrDefault(0L)

    private companion object {
        const val DEFAULT_DEBOUNCE_MS = 3_000L
    }
}

private const val SESSION_CACHE_TAG = "SessionCache"

private fun logSessionCacheIo(
    action: String,
    error: Throwable,
) {
    Log.w(SESSION_CACHE_TAG, action, error)
}

private class DefaultSessionMessageCacheHandle(
    private val sessionId: String,
    private val worktree: String,
    private val store: SessionCacheStore,
    private val connectionIdProvider: () -> String?,
    private val scope: CoroutineScope,
    private val json: Json,
    private val clock: () -> Long,
    private val debounceMs: Long,
) : SessionMessageCacheHandle {
    private val mutex = Mutex()
    private var pending: List<Message>? = null
    private var debounceJob: Job? = null
    private var closed = false

    private fun keyOrNull(): SessionCacheKey? {
        val connectionId = connectionIdProvider() ?: return null
        return SessionCacheKey(
            connectionId = connectionId,
            worktree = worktree,
            kind = SessionCacheKind.MessageTimeline,
            entryId = sessionId,
        )
    }

    override suspend fun seed(): List<Message>? {
        val key = keyOrNull() ?: return null
        runCatching {
            store.retain(key, SessionCacheRetainReason.Opened, clock())
        }.onFailure { logSessionCacheIo("retain opened", it) }
        val record =
            runCatching { store.load(key) }
                .onFailure { logSessionCacheIo("load messages", it) }
                .getOrNull()
                ?: return null
        if (record.value.payloadJson.isBlank()) return null
        return SessionCacheCodec.decodeMessages(record.value.payloadJson, json)
    }

    override fun note(messages: List<Message>) {
        if (closed || keyOrNull() == null) return
        scope.launch {
            mutex.withLock {
                if (closed) return@withLock
                pending = messages
                debounceJob?.cancel()
                debounceJob =
                    scope.launch {
                        delay(debounceMs)
                        flushLocked()
                    }
            }
        }
    }

    override suspend fun flush() {
        mutex.withLock {
            debounceJob?.cancel()
            debounceJob = null
            flushLocked()
        }
    }

    override fun close() {
        scope.launch {
            mutex.withLock {
                if (closed) return@withLock
                closed = true
                debounceJob?.cancel()
                debounceJob = null
                flushLocked()
            }
        }
    }

    private suspend fun flushLocked() {
        val messages = pending ?: return
        pending = null
        val key = keyOrNull() ?: return
        val payload =
            runCatching { SessionCacheCodec.encodeMessages(messages, json) }
                .onFailure { logSessionCacheIo("encode messages", it) }
                .getOrNull() ?: return
        runCatching {
            store.retain(key, SessionCacheRetainReason.Opened, clock())
            store.commit(
                key = key,
                value =
                    SessionCacheValue(
                        payloadJson = payload,
                        schemaVersion = SessionCacheCodec.SCHEMA_VERSION,
                    ),
                meta = SessionCacheCommitMeta(syncedAtEpochMs = clock()),
            )
        }.onFailure { logSessionCacheIo("commit messages", it) }
    }
}
