package dev.opencode.mobile.data.local.sessioncache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SessionCacheDao {
    @Query("SELECT * FROM session_cache_snapshot WHERE cache_key = :cacheKey LIMIT 1")
    suspend fun get(cacheKey: String): SessionCacheSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionCacheSnapshotEntity)

    @Query("DELETE FROM session_cache_snapshot")
    suspend fun deleteAll()

    @Query(
        """
        DELETE FROM session_cache_snapshot
        WHERE worktree = :worktree AND connection_id = :connectionId
        """,
    )
    suspend fun deleteForConnectionWorktree(
        connectionId: String,
        worktree: String,
    )

    @Query(
        """
        DELETE FROM session_cache_snapshot
        WHERE worktree = :worktree AND (
            substr(connection_id, 1, length(:connectionIdPrefix) + 1) = :connectionIdPrefix || '|'
            OR substr(connection_id, 1, length(:connectionIdPrefix) + 2) = :connectionIdPrefix || '/|'
        )
        """,
    )
    suspend fun deleteForUrlPrefixWorktree(
        connectionIdPrefix: String,
        worktree: String,
    )

    @Query("DELETE FROM session_cache_snapshot WHERE connection_id = :connectionId")
    suspend fun deleteForConnection(connectionId: String)

    @Query(
        """
        DELETE FROM session_cache_snapshot
        WHERE (
            substr(connection_id, 1, length(:connectionIdPrefix) + 1) = :connectionIdPrefix || '|'
            OR substr(connection_id, 1, length(:connectionIdPrefix) + 2) = :connectionIdPrefix || '/|'
        )
        """,
    )
    suspend fun deleteForUrlPrefix(connectionIdPrefix: String)

    @Query("SELECT COALESCE(SUM(LENGTH(payload_json)), 0) FROM session_cache_snapshot WHERE connection_id = :connectionId")
    suspend fun payloadBytesForConnection(connectionId: String): Long

    @Query("SELECT COUNT(*) FROM session_cache_snapshot WHERE connection_id = :connectionId")
    suspend fun entryCountForConnection(connectionId: String): Long

    @Query(
        """
        SELECT COALESCE(SUM(LENGTH(payload_json)), 0) FROM session_cache_snapshot
        WHERE (
            substr(connection_id, 1, length(:connectionIdPrefix) + 1) = :connectionIdPrefix || '|'
            OR substr(connection_id, 1, length(:connectionIdPrefix) + 2) = :connectionIdPrefix || '/|'
        )
        """,
    )
    suspend fun payloadBytesForUrlPrefix(connectionIdPrefix: String): Long

    @Query(
        """
        SELECT COUNT(*) FROM session_cache_snapshot
        WHERE (
            substr(connection_id, 1, length(:connectionIdPrefix) + 1) = :connectionIdPrefix || '|'
            OR substr(connection_id, 1, length(:connectionIdPrefix) + 2) = :connectionIdPrefix || '/|'
        )
        """,
    )
    suspend fun entryCountForUrlPrefix(connectionIdPrefix: String): Long

    @Query("SELECT COALESCE(SUM(LENGTH(payload_json)), 0) FROM session_cache_snapshot")
    suspend fun totalPayloadBytes(): Long

    @Query("SELECT COUNT(*) FROM session_cache_snapshot")
    suspend fun entryCount(): Long

    @Query(
        """
        SELECT COALESCE(SUM(LENGTH(payload_json)), 0) FROM session_cache_snapshot
        WHERE worktree = :worktree AND connection_id = :connectionId
        """,
    )
    suspend fun payloadBytesForConnectionWorktree(
        connectionId: String,
        worktree: String,
    ): Long

    @Query(
        """
        SELECT COUNT(*) FROM session_cache_snapshot
        WHERE worktree = :worktree AND connection_id = :connectionId
        """,
    )
    suspend fun entryCountForConnectionWorktree(
        connectionId: String,
        worktree: String,
    ): Long

    @Query(
        """
        SELECT COALESCE(SUM(LENGTH(payload_json)), 0) FROM session_cache_snapshot
        WHERE worktree = :worktree AND (
            substr(connection_id, 1, length(:connectionIdPrefix) + 1) = :connectionIdPrefix || '|'
            OR substr(connection_id, 1, length(:connectionIdPrefix) + 2) = :connectionIdPrefix || '/|'
        )
        """,
    )
    suspend fun payloadBytesForUrlPrefixWorktree(
        connectionIdPrefix: String,
        worktree: String,
    ): Long

    @Query(
        """
        SELECT COUNT(*) FROM session_cache_snapshot
        WHERE worktree = :worktree AND (
            substr(connection_id, 1, length(:connectionIdPrefix) + 1) = :connectionIdPrefix || '|'
            OR substr(connection_id, 1, length(:connectionIdPrefix) + 2) = :connectionIdPrefix || '/|'
        )
        """,
    )
    suspend fun entryCountForUrlPrefixWorktree(
        connectionIdPrefix: String,
        worktree: String,
    ): Long
}
