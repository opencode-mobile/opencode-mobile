package dev.opencode.mobile.data.local.serverhealth

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "server_health_ping",
    indices = [Index(value = ["profile_id", "at_ms"], name = "index_server_health_ping_profile_id_at_ms")],
)
data class ServerHealthPingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    @ColumnInfo(name = "latency_ms") val latencyMs: Int?,
    @ColumnInfo(name = "failure") val failure: String?,
    @ColumnInfo(name = "pid") val pid: Int?,
    @ColumnInfo(name = "version") val version: String?,
)

@Entity(
    tableName = "server_health_event",
    indices = [Index(value = ["profile_id", "at_ms"], name = "index_server_health_event_profile_id_at_ms")],
)
data class ServerHealthEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "value") val value: String,
    @ColumnInfo(name = "detail") val detail: String?,
)
