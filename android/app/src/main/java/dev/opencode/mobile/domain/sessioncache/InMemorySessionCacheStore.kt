package dev.opencode.mobile.domain.sessioncache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemorySessionCacheStore : SessionCacheStore {
    private data class Row(
        val key: SessionCacheKey,
        var value: SessionCacheValue?,
        var syncedAtEpochMs: Long,
        var retainedAtEpochMs: Long?,
    )

    private val mutex = Mutex()
    private val rows = linkedMapOf<String, Row>()

    override suspend fun load(key: SessionCacheKey): SessionCacheRecord? =
        mutex.withLock {
            val row = rows[key.cacheKey] ?: return null
            val value = row.value ?: return null
            if (value.payloadJson.isBlank()) return null
            SessionCacheRecord(
                value = value,
                syncedAtEpochMs = row.syncedAtEpochMs,
                retainedAtEpochMs = row.retainedAtEpochMs,
            )
        }

    override suspend fun commit(
        key: SessionCacheKey,
        value: SessionCacheValue,
        meta: SessionCacheCommitMeta,
    ) {
        mutex.withLock {
            val existing = rows[key.cacheKey]
            rows[key.cacheKey] =
                Row(
                    key = key,
                    value = value,
                    syncedAtEpochMs = meta.syncedAtEpochMs,
                    retainedAtEpochMs = existing?.retainedAtEpochMs,
                )
        }
    }

    override suspend fun retain(
        key: SessionCacheKey,
        reason: SessionCacheRetainReason,
        atEpochMs: Long,
    ) {
        mutex.withLock {
            val existing = rows[key.cacheKey]
            if (existing == null) {
                rows[key.cacheKey] =
                    Row(
                        key = key,
                        value =
                            SessionCacheValue(
                                payloadJson = "",
                                schemaVersion = SessionCacheCodec.SCHEMA_VERSION,
                            ),
                        syncedAtEpochMs = 0L,
                        retainedAtEpochMs = atEpochMs,
                    )
            } else if (existing.retainedAtEpochMs == null || existing.retainedAtEpochMs == 0L) {
                existing.retainedAtEpochMs = atEpochMs
            }
        }
    }

    override suspend fun evict(selector: SessionCacheEvictSelector) {
        mutex.withLock {
            when (selector) {
                SessionCacheEvictSelector.All -> rows.clear()
                is SessionCacheEvictSelector.Worktree -> {
                    val keys =
                        rows
                            .filterValues { row ->
                                matchesWorktree(row.key, selector)
                            }.keys
                    keys.forEach { rows.remove(it) }
                }
                is SessionCacheEvictSelector.Connection -> {
                    val keys = rows.filterValues { row -> matchesConnection(row.key, selector) }.keys
                    keys.forEach { rows.remove(it) }
                }
            }
        }
    }

    override suspend fun report(selector: SessionCacheEvictSelector): SessionCacheReport =
        mutex.withLock {
            val selected =
                when (selector) {
                    SessionCacheEvictSelector.All -> rows.values
                    is SessionCacheEvictSelector.Worktree ->
                        rows.values.filter { matchesWorktree(it.key, selector) }
                    is SessionCacheEvictSelector.Connection ->
                        rows.values.filter { matchesConnection(it.key, selector) }
                }
            SessionCacheReport(
                totalBytes =
                    selected.sumOf { row ->
                        (row.value?.payloadJson?.length ?: 0).toLong()
                    },
                entryCount = selected.size.toLong(),
            )
        }

    private fun matchesConnection(
        key: SessionCacheKey,
        selector: SessionCacheEvictSelector.Connection,
    ): Boolean =
        if (selector.matchByUrlPrefix) {
            CacheUrlNormalizer.matchesConnectionIdPrefix(key.connectionId, selector.connectionIdOrUrlPrefix)
        } else {
            key.connectionId.equals(selector.connectionIdOrUrlPrefix, ignoreCase = true)
        }

    private fun matchesWorktree(
        key: SessionCacheKey,
        selector: SessionCacheEvictSelector.Worktree,
    ): Boolean {
        if (key.worktree != selector.worktree) return false
        return if (selector.matchByUrlPrefix) {
            CacheUrlNormalizer.matchesConnectionIdPrefix(key.connectionId, selector.connectionIdOrUrlPrefix)
        } else {
            key.connectionId.equals(selector.connectionIdOrUrlPrefix, ignoreCase = true)
        }
    }
}
