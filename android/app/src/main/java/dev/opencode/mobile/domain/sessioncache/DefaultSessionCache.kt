package dev.opencode.mobile.domain.sessioncache

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.home.ResolvedHomePath
import dev.opencode.mobile.domain.home.WorktreeLayout
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.ToolImageThumbnails
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class DefaultSessionCache(
    private val store: SessionCacheStore,
    private val connectionIdProvider: () -> String?,
    private val scope: CoroutineScope,
    private val json: Json,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val activityDebounceMs: Long = DEFAULT_ACTIVITY_DEBOUNCE_MS,
    /** Tool-image thumbnails saved next to message timelines; null keeps none. */
    private val thumbnails: ToolImageThumbnails? = null,
    private val thumbnailDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val thumbnailEvictionDebounceMs: Long = DEFAULT_THUMBNAIL_EVICTION_DEBOUNCE_MS,
    /** Encodes a message timeline flush; within [SessionCacheCodec.MESSAGE_TIMELINE_BUDGET_BYTES] by default. */
    private val messageEncoder: SessionCacheMessageEncoder = SessionCacheMessageEncoder.Default,
) : SessionCache {
    private val thumbnailEvictionJob = AtomicReference<Job?>(null)
    private val thumbnailSweepJob = AtomicReference<Job?>(null)

    private val activityMutex = Mutex()

    // Keyed by connection and directory: two servers can hold the same path.
    private val pendingActivity = mutableMapOf<SessionCacheKey, List<SessionActivityCacheEntry>>()
    private val lastCommittedActivity = ConcurrentHashMap<SessionCacheKey, List<SessionActivityCacheEntry>>()
    private val activityDebounceJobs = ConcurrentHashMap<SessionCacheKey, Job>()

    /** Timeline commits hold this, so a purge waits for one in flight and later ones see the purge. */
    private val timelinePurges = TimelinePurges()

    /** Bumped by every purge; a remember that started before a purge must not register after it. */
    @Volatile
    private var activityGeneration = 0

    /** Commits hold this, so a purge waits for one in flight and then evicts its row. */
    private val homeRecordMutex = Mutex()

    /** Small Home records (project resolution, worktree layout), encoded, newest per key. */
    private val pendingHomeRecords = LinkedHashMap<SessionCacheKey, String>()

    override suspend fun seedSessions(worktree: String): List<Session>? {
        val connectionId = connectionIdProvider() ?: return null
        return seedSessions(worktree, connectionId)
    }

    override suspend fun seedSessions(
        worktree: String,
        connectionId: String,
    ): List<Session>? {
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
        val generation = activityGeneration
        val connectionId = connectionIdProvider() ?: return
        val key =
            SessionCacheKey(
                connectionId = connectionId,
                worktree = directory,
                kind = SessionCacheKind.Activity,
            )
        val normalized = entries.map { it.normalized() }.sortedBy { it.sessionId }
        activityMutex.withLock {
            if (generation != activityGeneration) return
            if (normalized == lastCommittedActivity[key]) {
                pendingActivity.remove(key)
                activityDebounceJobs.remove(key)?.cancel()
                return
            }
            pendingActivity[key] = normalized
            activityDebounceJobs.remove(key)?.cancel()
            activityDebounceJobs[key] =
                scope.launch {
                    delay(activityDebounceMs)
                    flushActivity(key)
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
            thumbnails = thumbnails,
            thumbnailDispatcher = thumbnailDispatcher,
            onThumbnailsWritten = ::scheduleThumbnailEviction,
            messageEncoder = messageEncoder,
            purges = timelinePurges,
            onDiscardedAfterPurge = ::sweepThumbnails,
            onTimelinesTrimmed = ::scheduleThumbnailSweep,
        )

    /**
     * Once per launch: drops thumbnail folders whose timeline row is gone, then trims the
     * thumbnail cache to its budget. Runs off the main thread.
     */
    fun startThumbnailMaintenance() {
        val thumbnails = thumbnails ?: return
        scope.launch(thumbnailDispatcher) {
            sweepThumbnails()
            runCatching { thumbnails.evictIfNeeded() }.onFailure { logSessionCacheIo("evict thumbnails", it) }
        }
    }

    /** Debounced global eviction after thumbnail writes. */
    private fun scheduleThumbnailEviction() {
        val thumbnails = thumbnails ?: return
        val job =
            scope.launch(thumbnailDispatcher) {
                delay(thumbnailEvictionDebounceMs)
                runCatching { thumbnails.evictIfNeeded() }.onFailure { logSessionCacheIo("evict thumbnails", it) }
            }
        thumbnailEvictionJob.getAndSet(job)?.cancel()
    }

    /** Trimmed timelines release their thumbnails; debounced because the walk lists every folder. */
    private fun scheduleThumbnailSweep() {
        if (thumbnails == null) return
        val job =
            scope.launch(thumbnailDispatcher) {
                delay(thumbnailEvictionDebounceMs)
                sweepThumbnails()
            }
        thumbnailSweepJob.getAndSet(job)?.cancel()
    }

    /** Thumbnail folders follow the timeline rows: any session folder without a row goes. */
    private suspend fun sweepThumbnails() {
        val thumbnails = thumbnails ?: return
        val entries =
            runCatching { store.messageTimelineEntries() }
                .onFailure { logSessionCacheIo("list timelines", it) }
                .getOrNull() ?: return
        withContext(thumbnailDispatcher) {
            runCatching { thumbnails.sweepOrphans(entries) }.onFailure { logSessionCacheIo("sweep thumbnails", it) }
        }
    }

    override suspend fun purgeWorktree(
        serverBaseUrl: String,
        worktree: String,
    ) {
        val connectionId = connectionIdProvider()
        dropActivityMemory(worktree, connectionId)
        dropPendingHomeRecords(worktree, connectionId)
        timelinePurges.purgingWorktree(connectionId, worktree) {
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
        sweepThumbnails()
    }

    override suspend fun purgeConnection(
        profileId: String,
        serverBaseUrl: String,
    ) {
        // Only this profile's queued writes: another server may be active and still writing.
        val connectionId = profileId.takeIf { it.isNotBlank() }
        dropActivityMemory(null, connectionId)
        dropPendingHomeRecords(null, connectionId)
        timelinePurges.purging(connectionId) {
            if (connectionId != null) {
                runCatching { store.evict(SessionCacheEvictSelector.Connection(connectionIdOrUrlPrefix = connectionId)) }
                    .onFailure { logSessionCacheIo("purge connection by profile", it) }
            }
            runCatching {
                store.evict(
                    SessionCacheEvictSelector.Connection(
                        connectionIdOrUrlPrefix = CacheUrlNormalizer.connectionIdPrefix(serverBaseUrl),
                        matchByUrlPrefix = true,
                    ),
                )
            }.onFailure { logSessionCacheIo("purge connection", it) }
        }
        sweepThumbnails()
    }

    override suspend fun clearAll() {
        dropActivityMemory(null, null)
        dropPendingHomeRecords(null, null)
        timelinePurges.purging(null) {
            runCatching { store.evict(SessionCacheEvictSelector.All) }
                .onFailure { logSessionCacheIo("clear all", it) }
        }
        thumbnails?.let { store ->
            withContext(thumbnailDispatcher) {
                runCatching { store.deleteAll() }.onFailure { logSessionCacheIo("clear thumbnails", it) }
            }
        }
    }

    override suspend fun seedProjectResolution(
        worktree: String,
        connectionId: String?,
    ): ResolvedHomePath? {
        val payload = loadHomeRecord(worktree, connectionId, SessionCacheKind.ProjectResolution) ?: return null
        return SessionCacheCodec.decodeProjectResolution(payload, json)
    }

    override fun rememberProjectResolution(
        worktree: String,
        resolution: ResolvedHomePath,
    ) {
        val payload =
            runCatching { SessionCacheCodec.encodeProjectResolution(resolution, json) }
                .onFailure { logSessionCacheIo("encode project resolution", it) }
                .getOrNull() ?: return
        queueHomeRecord(worktree, SessionCacheKind.ProjectResolution, payload)
    }

    override suspend fun seedWorktreeLayout(
        worktree: String,
        connectionId: String?,
    ): WorktreeLayout? {
        val payload = loadHomeRecord(worktree, connectionId, SessionCacheKind.WorktreeLayout) ?: return null
        return SessionCacheCodec.decodeWorktreeLayout(payload, json)
    }

    override fun rememberWorktreeLayout(
        worktree: String,
        layout: WorktreeLayout,
    ) {
        val payload =
            runCatching { SessionCacheCodec.encodeWorktreeLayout(layout, json) }
                .onFailure { logSessionCacheIo("encode worktree layout", it) }
                .getOrNull() ?: return
        queueHomeRecord(worktree, SessionCacheKind.WorktreeLayout, payload)
    }

    private suspend fun loadHomeRecord(
        worktree: String,
        connectionId: String?,
        kind: SessionCacheKind,
    ): String? {
        val id = connectionId ?: connectionIdProvider() ?: return null
        val key = SessionCacheKey(connectionId = id, worktree = worktree, kind = kind)
        val record =
            runCatching { store.load(key) }
                .onFailure { logSessionCacheIo("load ${kind.name}", it) }
                .getOrNull() ?: return null
        return record.value.payloadJson
    }

    /** Queues one small encoded Home record under the connection active now. */
    private fun queueHomeRecord(
        worktree: String,
        kind: SessionCacheKind,
        payload: String,
    ) {
        val connectionId = connectionIdProvider() ?: return
        val key = SessionCacheKey(connectionId = connectionId, worktree = worktree, kind = kind)
        synchronized(pendingHomeRecords) { pendingHomeRecords[key] = payload }
        scope.launch { flushHomeRecords() }
    }

    /** Commits the newest queued record of each key; a flush that finds the queue empty does nothing. */
    private suspend fun flushHomeRecords() {
        homeRecordMutex.withLock {
            val queued =
                synchronized(pendingHomeRecords) {
                    pendingHomeRecords.toMap().also { pendingHomeRecords.clear() }
                }
            queued.forEach { (key, payload) ->
                runCatching {
                    store.commit(
                        key = key,
                        value = SessionCacheValue(payloadJson = payload, schemaVersion = SessionCacheCodec.SCHEMA_VERSION),
                        meta = SessionCacheCommitMeta(syncedAtEpochMs = clock()),
                    )
                }.onFailure { logSessionCacheIo("commit ${key.kind.name}", it) }
            }
        }
    }

    /**
     * Drops queued Home records for [worktree] (all when `null`) of [connectionId] (every
     * connection when `null`), after any commit in flight.
     */
    private suspend fun dropPendingHomeRecords(
        worktree: String?,
        connectionId: String?,
    ) {
        homeRecordMutex.withLock {
            synchronized(pendingHomeRecords) {
                pendingHomeRecords.keys.removeAll { it.matches(worktree, connectionId) }
            }
        }
    }

    override suspend fun estimatedSizeBytes(): Long =
        runCatching { store.report(SessionCacheEvictSelector.All).totalBytes }
            .onFailure { logSessionCacheIo("report size", it) }
            .getOrDefault(0L)

    private suspend fun flushActivity(key: SessionCacheKey) {
        // The commit stays under the lock so a purge waits for it and then evicts the row,
        // instead of the commit landing after the purge.
        activityMutex.withLock {
            activityDebounceJobs.remove(key)
            val entries = pendingActivity.remove(key) ?: return
            if (entries == lastCommittedActivity[key]) return
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
                lastCommittedActivity[key] = entries
            }.onFailure { logSessionCacheIo("commit activity", it) }
        }
    }

    private suspend fun dropActivityMemory(
        worktree: String?,
        connectionId: String?,
    ) {
        activityMutex.withLock {
            activityGeneration += 1
            (lastCommittedActivity.keys + pendingActivity.keys + activityDebounceJobs.keys)
                .filter { it.matches(worktree, connectionId) }
                .forEach { key ->
                    activityDebounceJobs.remove(key)?.cancel()
                    pendingActivity.remove(key)
                    lastCommittedActivity.remove(key)
                }
        }
    }

    private companion object {
        const val DEFAULT_DEBOUNCE_MS = 3_000L
        const val DEFAULT_ACTIVITY_DEBOUNCE_MS = 1_000L
        const val ACTIVITY_TTL_MS = 6L * 60L * 60L * 1_000L
        const val DEFAULT_THUMBNAIL_EVICTION_DEBOUNCE_MS = 5_000L
    }
}

/**
 * [runCatching] for exceptions only: an [Error] such as [OutOfMemoryError], and cancellation,
 * propagate instead of being logged away.
 */
private inline fun <T> catchingExceptions(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (exception: Exception) {
        Result.failure(exception)
    }

/** Message timelines kept per Connection Profile; the least recently used go on write. */
internal const val MAX_TRANSCRIPTS_PER_PROFILE = 200

/** Whether this key is under [worktree] and [connectionId]; `null` matches any. */
private fun SessionCacheKey.matches(
    worktree: String?,
    connectionId: String?,
): Boolean = (worktree == null || this.worktree == worktree) && (connectionId == null || this.connectionId == connectionId)

/**
 * Counts purges per connection and project. A transcript handle remembers the count when it takes its key
 * and commits only while it is unchanged, so a write still pending cannot undo a clear.
 */
private class TimelinePurges {
    private val mutex = Mutex()
    private val perConnection = ConcurrentHashMap<String, Long>()

    /** Keyed by connection and worktree; [ANY_CONNECTION] when no profile was bound at the purge. */
    private val perWorktree = ConcurrentHashMap<Pair<String, String>, Long>()

    @Volatile
    private var all = 0L

    data class Stamp(
        val all: Long,
        val connection: Long,
        val worktree: Long,
    )

    fun stamp(
        connectionId: String,
        worktree: String,
    ): Stamp =
        Stamp(
            all = all,
            connection = perConnection[connectionId] ?: 0L,
            worktree = (perWorktree[connectionId to worktree] ?: 0L) + (perWorktree[ANY_CONNECTION to worktree] ?: 0L),
        )

    /** Runs [evict] with commits held off; [connectionId] null means every connection. */
    suspend fun purging(
        connectionId: String?,
        evict: suspend () -> Unit,
    ) {
        mutex.withLock {
            if (connectionId == null) {
                all += 1
                perWorktree.clear()
            } else {
                perConnection.merge(connectionId, 1L, Long::plus)
                // The connection's count already outdates its handles, so these rows can go.
                perWorktree.keys.removeAll { it.first == connectionId }
            }
            evict()
        }
    }

    suspend fun purgingWorktree(
        connectionId: String?,
        worktree: String,
        evict: suspend () -> Unit,
    ) {
        mutex.withLock {
            perWorktree.merge((connectionId ?: ANY_CONNECTION) to worktree, 1L, Long::plus)
            evict()
        }
    }

    /** Runs [commit] unless [connectionId] was purged since [stamp]; returns whether it ran. */
    suspend fun committing(
        connectionId: String,
        worktree: String,
        stamp: Stamp,
        commit: suspend () -> Unit,
    ): Boolean =
        mutex.withLock {
            if (stamp(connectionId, worktree) != stamp) return@withLock false
            commit()
            true
        }

    private companion object {
        const val ANY_CONNECTION = ""
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
    private val thumbnails: ToolImageThumbnails?,
    private val thumbnailDispatcher: CoroutineDispatcher,
    private val onThumbnailsWritten: () -> Unit,
    private val messageEncoder: SessionCacheMessageEncoder,
    private val purges: TimelinePurges,
    private val onDiscardedAfterPurge: suspend () -> Unit,
    private val onTimelinesTrimmed: () -> Unit,
) : SessionMessageCacheHandle {
    private val mutex = Mutex()
    private var pending: List<Message>? = null
    private var debounceJob: Job? = null
    private var closed = false

    /** Taken once: this handle's messages belong to the server it first saw, even after a switch. */
    @Volatile
    private var pinned: PinnedKey? = connectionIdProvider()?.let(::pin)

    private class PinnedKey(
        val key: SessionCacheKey,
        val purgeStamp: TimelinePurges.Stamp,
    )

    private fun pin(connectionId: String) =
        PinnedKey(
            key =
                SessionCacheKey(
                    connectionId = connectionId,
                    worktree = worktree,
                    kind = SessionCacheKind.MessageTimeline,
                    entryId = sessionId,
                ),
            purgeStamp = purges.stamp(connectionId, worktree),
        )

    private fun pinnedOrNull(): PinnedKey? = pinned ?: connectionIdProvider()?.let(::pin)?.also { pinned = it }

    private fun keyOrNull(): SessionCacheKey? = pinnedOrNull()?.key

    override suspend fun seed(): List<Message>? {
        val key = keyOrNull() ?: return null
        catchingExceptions {
            store.retain(key, SessionCacheRetainReason.Opened, clock())
            store.touch(key, clock())
        }.onFailure { logSessionCacheIo("retain opened", it) }
        // A seed is only a first paint, so any failure is a miss and the live load still runs
        // (#1033). An OutOfMemoryError is caught too, but only here: by now the row and whatever
        // the decode built belong to [loadSeed]'s finished frame, so the heap is free again
        // before the log line, unlike swallowing it where it was thrown.
        val failure: Throwable =
            try {
                return loadSeed(key)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                exception
            } catch (outOfMemory: OutOfMemoryError) {
                outOfMemory
            } catch (overflow: StackOverflowError) {
                overflow
            }
        logSessionCacheIo("seed messages", failure)
        return null
    }

    private suspend fun loadSeed(key: SessionCacheKey): List<Message>? {
        val record = store.load(key) ?: return null
        if (record.value.payloadJson.isBlank()) return null
        val connectionId = key.connectionId
        return SessionCacheCodec
            .decodeMessages(
                record.value.payloadJson,
                json,
                thumbnailPath = { imageId -> thumbnails?.existingPath(connectionId, sessionId, imageId) },
            )?.takeIf { it.isNotEmpty() }
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
        val pinnedKey = pinnedOrNull() ?: return
        val key = pinnedKey.key
        // Thumbnails first (newest tool images, off the main thread), so the row marks only
        // the images whose file is really there.
        val saved =
            thumbnails?.let { store ->
                withContext(thumbnailDispatcher) {
                    catchingExceptions { store.sync(key.connectionId, sessionId, messages) }
                        .onFailure { logSessionCacheIo("save thumbnails", it) }
                        .getOrNull()
                }
            }
        if (saved?.wrote == true) onThumbnailsWritten()
        val savedIds = saved?.paths?.keys.orEmpty()
        // Exceptions only (#1033): an OutOfMemoryError here used to be logged and swallowed, leaving
        // the heap at its limit for the next allocation anywhere in the app.
        val payload =
            catchingExceptions { messageEncoder.encode(messages, json, hasThumbnail = { it.id in savedIds }) }
                .onFailure { logSessionCacheIo("encode messages", it) }
                .getOrNull() ?: return
        var trimmed = 0
        val committed =
            catchingExceptions {
                purges.committing(key.connectionId, key.worktree, pinnedKey.purgeStamp) {
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
                    trimmed = store.trimMessageTimelines(key.connectionId, MAX_TRANSCRIPTS_PER_PROFILE)
                }
            }.onFailure { logSessionCacheIo("commit messages", it) }
        // Purged while encoding: the thumbnails just written have no row and must not stay.
        if (committed.getOrNull() == false && saved?.wrote == true) onDiscardedAfterPurge()
        if (trimmed > 0) onTimelinesTrimmed()
    }
}
