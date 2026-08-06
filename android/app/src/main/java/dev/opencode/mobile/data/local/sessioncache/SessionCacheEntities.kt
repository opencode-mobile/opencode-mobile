package dev.opencode.mobile.data.local.sessioncache

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_cache_snapshot")
data class SessionCacheSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "cache_key")
    val cacheKey: String,
    @ColumnInfo(name = "connection_id")
    val connectionId: String,
    @ColumnInfo(name = "worktree")
    val worktree: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "entry_id")
    val entryId: String,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
    @ColumnInfo(name = "synced_at_ms")
    val syncedAtEpochMs: Long,
    @ColumnInfo(name = "retained_at_ms")
    val retainedAtEpochMs: Long?,
)
