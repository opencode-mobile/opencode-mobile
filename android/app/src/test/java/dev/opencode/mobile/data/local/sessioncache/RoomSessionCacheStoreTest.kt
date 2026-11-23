package dev.opencode.mobile.data.local.sessioncache

import dev.opencode.mobile.domain.sessioncache.SessionCacheCodec
import dev.opencode.mobile.domain.sessioncache.SessionCacheCommitMeta
import dev.opencode.mobile.domain.sessioncache.SessionCacheKey
import dev.opencode.mobile.domain.sessioncache.SessionCacheKind
import dev.opencode.mobile.domain.sessioncache.SessionCacheRetainReason
import dev.opencode.mobile.domain.sessioncache.SessionCacheValue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1033: a row over Android's 2 MB cursor window cannot be read at all. An older build wrote whole
 * transcripts that size; the store must not read one, and must still replace it.
 */
class RoomSessionCacheStoreTest {
    private val key =
        SessionCacheKey(connectionId = "profile-a", worktree = "/repo", kind = SessionCacheKind.MessageTimeline, entryId = "s1")

    @Test
    fun anOversizedRowIsSkippedOnLoadAndReplacedByTheNextCommit() =
        runTest {
            val dao = CursorWindowDao()
            dao.rows[key.cacheKey] = row(payload = "x".repeat(4_000_000), retainedAt = 7L)
            val store = RoomSessionCacheStore(dao)

            assertNull(store.load(key))
            store.retain(key, SessionCacheRetainReason.Opened, atEpochMs = 9L)
            store.commit(key, SessionCacheValue("{\"small\":true}", SessionCacheCodec.SCHEMA_VERSION), SessionCacheCommitMeta(10L))

            val loaded = store.load(key)!!
            assertEquals("{\"small\":true}", loaded.value.payloadJson)
            // The retain time survives the replacement.
            assertEquals(7L, loaded.retainedAtEpochMs)
        }

    @Test
    fun aPayloadWithinTheMessageBudgetIsReadable() {
        assertTrue(SessionCacheCodec.MESSAGE_TIMELINE_BUDGET_BYTES < RoomSessionCacheStore.MAX_READABLE_PAYLOAD_BYTES)
        assertTrue(RoomSessionCacheStore.MAX_READABLE_PAYLOAD_BYTES < CursorWindowDao.WINDOW_BYTES)
    }

    @Test
    fun retainStampsARowWithoutReadingItsPayload() =
        runTest {
            val dao = CursorWindowDao()
            dao.rows[key.cacheKey] = row(payload = "x".repeat(4_000_000), retainedAt = null)
            RoomSessionCacheStore(dao).retain(key, SessionCacheRetainReason.Opened, atEpochMs = 5L)
            assertEquals(5L, dao.rows.getValue(key.cacheKey).retainedAtEpochMs)
        }

    private fun row(
        payload: String,
        retainedAt: Long?,
    ) = SessionCacheSnapshotEntity(
        cacheKey = key.cacheKey,
        connectionId = key.connectionId,
        worktree = key.worktree,
        kind = key.kind.name,
        entryId = key.entryId,
        payloadJson = payload,
        schemaVersion = SessionCacheCodec.SCHEMA_VERSION,
        syncedAtEpochMs = 1L,
        retainedAtEpochMs = retainedAt,
    )
}

/** Throws on [get] for a row over the cursor window, as Room does on device. */
private class CursorWindowDao : SessionCacheDao {
    val rows = mutableMapOf<String, SessionCacheSnapshotEntity>()

    override suspend fun keysBeyondRecent(
        connectionId: String,
        kind: String,
        keep: Int,
    ): List<String> =
        rows.values
            .filter { it.connectionId == connectionId && it.kind == kind }
            .sortedWith(
                compareByDescending<SessionCacheSnapshotEntity> { row ->
                    maxOf(row.retainedAtEpochMs ?: 0L, row.syncedAtEpochMs)
                }.thenBy { row -> row.cacheKey },
            ).drop(keep)
            .map { it.cacheKey }

    override suspend fun deleteKeys(cacheKeys: List<String>) {
        cacheKeys.forEach(rows::remove)
    }

    override suspend fun get(cacheKey: String): SessionCacheSnapshotEntity? {
        val row = rows[cacheKey] ?: return null
        check(row.payloadJson.toByteArray().size < WINDOW_BYTES) { "Row too big to fit into CursorWindow" }
        return row
    }

    override suspend fun meta(cacheKey: String): SessionCacheRowMeta? =
        rows[cacheKey]?.let { SessionCacheRowMeta(it.retainedAtEpochMs, it.payloadJson.toByteArray().size.toLong()) }

    override suspend fun setRetainedAt(
        cacheKey: String,
        retainedAtEpochMs: Long,
    ) {
        rows[cacheKey]?.let { rows[cacheKey] = it.copy(retainedAtEpochMs = retainedAtEpochMs) }
    }

    override suspend fun upsert(entity: SessionCacheSnapshotEntity) {
        rows[entity.cacheKey] = entity
    }

    override suspend fun deleteAll() = unused()

    override suspend fun deleteForConnectionWorktree(
        connectionId: String,
        worktree: String,
    ) = unused()

    override suspend fun deleteForUrlPrefixWorktree(
        connectionIdPrefix: String,
        worktree: String,
    ) = unused()

    override suspend fun deleteForConnection(connectionId: String) = unused()

    override suspend fun deleteForUrlPrefix(connectionIdPrefix: String) = unused()

    override suspend fun payloadBytesForConnection(connectionId: String): Long = unused()

    override suspend fun entryCountForConnection(connectionId: String): Long = unused()

    override suspend fun payloadBytesForUrlPrefix(connectionIdPrefix: String): Long = unused()

    override suspend fun entryCountForUrlPrefix(connectionIdPrefix: String): Long = unused()

    override suspend fun totalPayloadBytes(): Long = unused()

    override suspend fun entryCount(): Long = unused()

    override suspend fun payloadBytesForConnectionWorktree(
        connectionId: String,
        worktree: String,
    ): Long = unused()

    override suspend fun entryCountForConnectionWorktree(
        connectionId: String,
        worktree: String,
    ): Long = unused()

    override suspend fun payloadBytesForUrlPrefixWorktree(
        connectionIdPrefix: String,
        worktree: String,
    ): Long = unused()

    override suspend fun entryCountForUrlPrefixWorktree(
        connectionIdPrefix: String,
        worktree: String,
    ): Long = unused()

    override suspend fun entriesOfKind(kind: String): List<SessionCacheEntryRef> = unused()

    private fun unused(): Nothing = error("not used by this test")

    companion object {
        const val WINDOW_BYTES = 2 * 1024 * 1024
    }
}
