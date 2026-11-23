package dev.opencode.mobile.data.local.sessioncache

import dev.opencode.mobile.domain.sessioncache.SessionCacheCodec
import dev.opencode.mobile.domain.sessioncache.SessionCacheCommitMeta
import dev.opencode.mobile.domain.sessioncache.SessionCacheEvictSelector
import dev.opencode.mobile.domain.sessioncache.SessionCacheKey
import dev.opencode.mobile.domain.sessioncache.SessionCacheKind
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
            val meta = dao.meta(key.cacheKey) ?: return null
            if (meta.payloadBytes == 0L) return null
            // An older build wrote whole transcripts; a row that cannot fit a cursor window would
            // throw on read. Skip it: the next commit replaces it with one that fits (#1033).
            if (meta.payloadBytes > MAX_READABLE_PAYLOAD_BYTES) return null
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
            // Reads no payload, so an oversized row left by an older build is still replaced (#1033).
            val existing = dao.meta(key.cacheKey)
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
            val existing = dao.meta(key.cacheKey)
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
                dao.setRetainedAt(key.cacheKey, atEpochMs)
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

        companion object {
            /**
             * The largest payload [load] reads, in UTF-8 bytes: Android's cursor window holds 2 MB,
             * and the row's other columns share it. [SessionCacheCodec.MESSAGE_TIMELINE_BUDGET_BYTES]
             * keeps message timelines under it.
             */
            const val MAX_READABLE_PAYLOAD_BYTES: Long = 1_950_000L

            /** Stays under SQLite's bound-variable limit. */
            private const val DELETE_CHUNK = 500
        }

        override suspend fun touch(
            key: SessionCacheKey,
            atEpochMs: Long,
        ) {
            dao.setRetainedAt(key.cacheKey, atEpochMs)
        }

        override suspend fun trimMessageTimelines(
            connectionId: String,
            keep: Int,
        ): Int {
            val excess = dao.keysBeyondRecent(connectionId, SessionCacheKind.MessageTimeline.name, keep)
            excess.chunked(DELETE_CHUNK).forEach { dao.deleteKeys(it) }
            return excess.size
        }

        override suspend fun messageTimelineEntries(): List<Pair<String, String>> =
            dao.entriesOfKind(SessionCacheKind.MessageTimeline.name).map { it.connectionId to it.entryId }

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
