package dev.opencode.mobile.domain.sessioncache

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

class DefaultSessionCache(
    private val store: SessionCacheStore,
    private val connectionIdProvider: () -> String?,
    private val scope: CoroutineScope,
    private val json: Json,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val activityDebounceMs: Long = DEFAULT_ACTIVITY_DEBOUNCE_MS,
) : SessionCache {
    private val activityMutex = Mutex()
    private val pendingActivity = mutableMapOf<String, List<SessionActivityCacheEntry>>()
    private val lastCommittedActivity = ConcurrentHashMap<String, List<SessionActivityCacheEntry>>()
    private val activityDebounceJobs = ConcurrentHashMap<String, Job>()

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

    override suspend fun seedActivity(directory: String): List<SessionActivityCacheEntry>? {
        val connectionId = connectionIdProvider() ?: return null
        val key =
            SessionCacheKey(
                connectionId = connectionId,
                worktree = directory,
                kind = SessionCacheKind.Activity,
            )
        val record =
            runCatching { store.load(key) }
                .onFailure { logSessionCacheIo("load activity", it) }
                .getOrNull() ?: return null
        val decoded = SessionCacheCodec.decodeActivity(record.value.payloadJson, json) ?: return null
        val now = clock()
        val fresh = decoded.filter { now - it.observedAtMs <= ACTIVITY_TTL_MS }
        return fresh.takeIf { it.isNotEmpty() }
    }

    override suspend fun rememberActivity(
        directory: String,
        entries: List<SessionActivityCacheEntry>,
    ) {
        val connectionId = connectionIdProvider() ?: return
        val key =
            SessionCacheKey(
                connectionId = connectionId,
                worktree = directory,
                kind = SessionCacheKind.Activity,
            )
        val normalized = entries.map { it.normalized() }.sortedBy { it.sessionId }
        activityMutex.withLock {
            if (normalized == lastCommittedActivity[directory]) {
                pendingActivity.remove(directory)
                activityDebounceJobs.remove(directory)?.cancel()
                return
            }
            pendingActivity[directory] = normalized
            activityDebounceJobs.remove(directory)?.cancel()
            activityDebounceJobs[directory] =
                scope.launch {
                    delay(activityDebounceMs)
                    flushActivity(directory, key)
                }
        }
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
        dropActivityMemory(worktree)
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
        dropActivityMemory(null)
        runCatching { store.evict(SessionCacheEvictSelector.All) }
            .onFailure { logSessionCacheIo("clear all", it) }
    }

    override suspend fun estimatedSizeBytes(): Long =
        runCatching { store.report(SessionCacheEvictSelector.All).totalBytes }
            .onFailure { logSessionCacheIo("report size", it) }
            .getOrDefault(0L)

    private suspend fun flushActivity(
        directory: String,
        key: SessionCacheKey,
    ) {
        val entries =
            activityMutex.withLock {
                activityDebounceJobs.remove(directory)
                pendingActivity.remove(directory)
            } ?: return
        if (entries == lastCommittedActivity[directory]) return
        val payload =
            runCatching { SessionCacheCodec.encodeActivity(entries, json) }
                .onFailure { logSessionCacheIo("encode activity", it) }
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
            lastCommittedActivity[directory] = entries
        }.onFailure { logSessionCacheIo("commit activity", it) }
    }

    private fun dropActivityMemory(worktree: String?) {
        val directories =
            if (worktree != null) {
                listOf(worktree)
            } else {
                (lastCommittedActivity.keys + pendingActivity.keys + activityDebounceJobs.keys).toList()
            }
        directories.distinct().forEach { directory ->
            activityDebounceJobs.remove(directory)?.cancel()
            pendingActivity.remove(directory)
            lastCommittedActivity.remove(directory)
        }
    }

    private companion object {
        const val DEFAULT_DEBOUNCE_MS = 3_000L
        const val DEFAULT_ACTIVITY_DEBOUNCE_MS = 1_000L
        const val ACTIVITY_TTL_MS = 6L * 60L * 60L * 1_000L
    }
}

private fun logSessionCacheIo(
    action: String,
    error: Throwable,
) {
    DiagnosticLog.warning(DiagnosticLogArea.Session, action, error)
}

private fun SessionActivityCacheEntry.normalized(): SessionActivityCacheEntry = copy(activity = activity.forCache())

private fun SessionActivity.forCache(): SessionActivity =
    when (this) {
        is SessionActivity.Retrying -> SessionActivity.Retrying(attempt = attempt)
        else -> this
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
