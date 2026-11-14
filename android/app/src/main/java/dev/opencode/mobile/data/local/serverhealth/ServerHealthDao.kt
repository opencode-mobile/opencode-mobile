package dev.opencode.mobile.data.local.serverhealth

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ServerHealthDao {
    @Insert
    suspend fun insertPing(entity: ServerHealthPingEntity)

    @Insert
    suspend fun insertEvent(entity: ServerHealthEventEntity)

    @Query("SELECT * FROM server_health_ping WHERE profile_id = :profileId AND at_ms >= :sinceMs ORDER BY at_ms ASC, id ASC")
    suspend fun pings(
        profileId: String,
        sinceMs: Long,
    ): List<ServerHealthPingEntity>

    @Query("SELECT * FROM server_health_event WHERE profile_id = :profileId AND at_ms >= :sinceMs ORDER BY at_ms ASC, id ASC")
    suspend fun events(
        profileId: String,
        sinceMs: Long,
    ): List<ServerHealthEventEntity>

    @Query(
        """
        SELECT * FROM server_health_event
        WHERE profile_id = :profileId AND kind = :kind AND at_ms < :beforeMs
        ORDER BY at_ms DESC, id DESC LIMIT 1
        """,
    )
    suspend fun latestEventBefore(
        profileId: String,
        kind: String,
        beforeMs: Long,
    ): ServerHealthEventEntity?

    @Query(
        """
        SELECT * FROM server_health_ping
        WHERE profile_id = :profileId AND (pid IS NOT NULL OR version IS NOT NULL)
        ORDER BY at_ms DESC, id DESC LIMIT 1
        """,
    )
    suspend fun latestIdentifiedPing(profileId: String): ServerHealthPingEntity?

    @Query("DELETE FROM server_health_ping WHERE at_ms < :olderThanMs")
    suspend fun prunePings(olderThanMs: Long)

    @Query("DELETE FROM server_health_event WHERE at_ms < :olderThanMs")
    suspend fun pruneEvents(olderThanMs: Long)

    @Query("DELETE FROM server_health_ping WHERE profile_id = :profileId")
    suspend fun deletePings(profileId: String)

    @Query("DELETE FROM server_health_event WHERE profile_id = :profileId")
    suspend fun deleteEvents(profileId: String)

    @Query("DELETE FROM server_health_ping")
    suspend fun deleteAllPings()

    @Query("DELETE FROM server_health_event")
    suspend fun deleteAllEvents()
}
