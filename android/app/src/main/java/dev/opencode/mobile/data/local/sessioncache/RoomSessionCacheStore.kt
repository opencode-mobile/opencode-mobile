package dev.opencode.mobile.data.local.sessioncache

import dev.opencode.mobile.domain.sessioncache.SessionCacheCodec
import dev.opencode.mobile.domain.sessioncache.SessionCacheCommitMeta
import dev.opencode.mobile.domain.sessioncache.SessionCacheEvictSelector
import dev.opencode.mobile.domain.sessioncache.SessionCacheKey
import dev.opencode.mobile.domain.sessioncache.SessionCacheRecord
import dev.opencode.mobile.domain.sessioncache.SessionCacheReport
import dev.opencode.mobile.domain.sessioncache.SessionCacheRetainReason
import dev.opencode.mobile.domain.sessioncache.SessionCacheStore
import dev.opencode.mobile.domain.sessioncache.SessionCacheValue
import javax.inject.Inject

class RoomSessionCacheStore
    @Inject
    constructor(
        private val dao: SessionCacheDao,
    ) : SessionCacheStore {
        override suspend fun load(key: SessionCacheKey): SessionCacheRecord? {
            val row = dao.get(key.cacheKey) ?: return null
            if (row.payloadJson.isBlank()) return null
            return SessionCacheRecord(
                value =
                    SessionCacheValue(
                        payloadJson = row.payloadJson,
                        schemaVersion = row.schemaVersion,
                    ),
                syncedAtEpochMs = row.syncedAtEpochMs,
                retainedAtEpochMs = row.retainedAtEpochMs,
            )
        }

        override suspend fun commit(
            key: SessionCacheKey,
            value: SessionCacheValue,
            meta: SessionCacheCommitMeta,
        ) {
            val existing = dao.get(key.cacheKey)
            dao.upsert(
                SessionCacheSnapshotEntity(
                    cacheKey = key.cacheKey,
                    connectionId = key.connectionId,
                    worktree = key.worktree,
                    kind = key.kind.name,
                    entryId = key.entryId,
                    payloadJson = value.payloadJson,
                    schemaVersion = value.schemaVersion,
                    syncedAtEpochMs = meta.syncedAtEpochMs,
                    retainedAtEpochMs = existing?.retainedAtEpochMs,
                ),
            )
        }

        override suspend fun retain(
            key: SessionCacheKey,
            reason: SessionCacheRetainReason,
            atEpochMs: Long,
        ) {
            val existing = dao.get(key.cacheKey)
            if (existing == null) {
                dao.upsert(
                    SessionCacheSnapshotEntity(
                        cacheKey = key.cacheKey,
                        connectionId = key.connectionId,
                        worktree = key.worktree,
                        kind = key.kind.name,
                        entryId = key.entryId,
                        payloadJson = "",
                        schemaVersion = SessionCacheCodec.SCHEMA_VERSION,
                        syncedAtEpochMs = 0L,
                        retainedAtEpochMs = atEpochMs,
                    ),
                )
            } else if (existing.retainedAtEpochMs == null || existing.retainedAtEpochMs == 0L) {
                dao.upsert(existing.copy(retainedAtEpochMs = atEpochMs))
            }
        }

        override suspend fun evict(selector: SessionCacheEvictSelector) {
            when (selector) {
                SessionCacheEvictSelector.All -> dao.deleteAll()
                is SessionCacheEvictSelector.Worktree -> {
                    if (selector.matchByUrlPrefix) {
                        dao.deleteForUrlPrefixWorktree(
                            connectionIdPrefix = selector.connectionIdOrUrlPrefix,
                            worktree = selector.worktree,
                        )
                    } else {
                        dao.deleteForConnectionWorktree(
                            connectionId = selector.connectionIdOrUrlPrefix,
                            worktree = selector.worktree,
                        )
                    }
                }
                is SessionCacheEvictSelector.Connection ->
                    if (selector.matchByUrlPrefix) {
                        dao.deleteForUrlPrefix(selector.connectionIdOrUrlPrefix)
                    } else {
                        dao.deleteForConnection(selector.connectionIdOrUrlPrefix)
                    }
            }
        }

        override suspend fun report(selector: SessionCacheEvictSelector): SessionCacheReport =
            when (selector) {
                SessionCacheEvictSelector.All ->
                    SessionCacheReport(
                        totalBytes = dao.totalPayloadBytes(),
                        entryCount = dao.entryCount(),
                    )
                is SessionCacheEvictSelector.Worktree ->
                    if (selector.matchByUrlPrefix) {
                        SessionCacheReport(
                            totalBytes =
                                dao.payloadBytesForUrlPrefixWorktree(
                                    connectionIdPrefix = selector.connectionIdOrUrlPrefix,
                                    worktree = selector.worktree,
                                ),
                            entryCount =
                                dao.entryCountForUrlPrefixWorktree(
                                    connectionIdPrefix = selector.connectionIdOrUrlPrefix,
                                    worktree = selector.worktree,
                                ),
                        )
                    } else {
                        SessionCacheReport(
                            totalBytes =
                                dao.payloadBytesForConnectionWorktree(
                                    connectionId = selector.connectionIdOrUrlPrefix,
                                    worktree = selector.worktree,
                                ),
                            entryCount =
                                dao.entryCountForConnectionWorktree(
                                    connectionId = selector.connectionIdOrUrlPrefix,
                                    worktree = selector.worktree,
                                ),
                        )
                    }
                is SessionCacheEvictSelector.Connection ->
                    if (selector.matchByUrlPrefix) {
                        SessionCacheReport(
                            totalBytes = dao.payloadBytesForUrlPrefix(selector.connectionIdOrUrlPrefix),
                            entryCount = dao.entryCountForUrlPrefix(selector.connectionIdOrUrlPrefix),
                        )
                    } else {
                        SessionCacheReport(
                            totalBytes = dao.payloadBytesForConnection(selector.connectionIdOrUrlPrefix),
                            entryCount = dao.entryCountForConnection(selector.connectionIdOrUrlPrefix),
                        )
                    }
            }
    }
