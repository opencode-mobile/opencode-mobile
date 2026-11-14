package dev.opencode.mobile.data.local.serverhealth

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.opencode.mobile.domain.serverhealth.ServerHealthEvent
import dev.opencode.mobile.domain.serverhealth.ServerHealthEventKind
import dev.opencode.mobile.domain.serverhealth.ServerHealthFailureCategory
import dev.opencode.mobile.domain.serverhealth.ServerHealthPing
import dev.opencode.mobile.domain.serverhealth.ServerHealthProcessIdentity
import dev.opencode.mobile.domain.serverhealth.ServerHealthStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomServerHealthStore
    @Inject
    constructor(
        private val dao: ServerHealthDao,
    ) : ServerHealthStore {
        override suspend fun appendPing(ping: ServerHealthPing) {
            dao.insertPing(
                ServerHealthPingEntity(
                    profileId = ping.profileId,
                    atMs = ping.atMs,
                    latencyMs = ping.latencyMs,
                    failure = ping.failure?.raw,
                    pid = ping.pid,
                    version = ping.version,
                ),
            )
        }

        override suspend fun appendEvent(event: ServerHealthEvent) {
            dao.insertEvent(
                ServerHealthEventEntity(
                    profileId = event.profileId,
                    atMs = event.atMs,
                    kind = event.kind.raw,
                    value = event.value,
                    detail = event.detail,
                ),
            )
        }

        override suspend fun pings(
            profileId: String,
            sinceMs: Long,
        ): List<ServerHealthPing> = dao.pings(profileId, sinceMs).map { it.toDomain() }

        override suspend fun events(
            profileId: String,
            sinceMs: Long,
        ): List<ServerHealthEvent> {
            val lead = dao.latestEventBefore(profileId, ServerHealthEventKind.State.raw, sinceMs)
            val rows = if (sinceMs == Long.MAX_VALUE) emptyList() else dao.events(profileId, sinceMs)
            return listOfNotNull(lead).plus(rows).mapNotNull { it.toDomain() }
        }

        override suspend fun lastPid(profileId: String): ServerHealthProcessIdentity? =
            dao.latestIdentifiedPing(profileId)?.let { ServerHealthProcessIdentity(it.pid, it.version) }

        override suspend fun prune(olderThanMs: Long) {
            dao.prunePings(olderThanMs)
            dao.pruneEvents(olderThanMs)
        }

        override suspend fun purge(profileId: String) {
            dao.deletePings(profileId)
            dao.deleteEvents(profileId)
        }

        override suspend fun purgeAll() {
            dao.deleteAllPings()
            dao.deleteAllEvents()
        }
    }

private fun ServerHealthPingEntity.toDomain(): ServerHealthPing {
    val category = failure?.let { ServerHealthFailureCategory.fromRaw(it) ?: ServerHealthFailureCategory.Other }
    return ServerHealthPing(
        profileId = profileId,
        atMs = atMs,
        latencyMs = if (category == null) latencyMs else null,
        failure = category,
        pid = pid,
        version = version,
    )
}

private fun ServerHealthEventEntity.toDomain(): ServerHealthEvent? {
    val kind = ServerHealthEventKind.fromRaw(kind) ?: return null
    return ServerHealthEvent(profileId = profileId, atMs = atMs, kind = kind, value = value, detail = detail)
}

/** Adds the Server Health tables to a version 1 database. */
val MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `server_health_ping` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `profile_id` TEXT NOT NULL,
                    `at_ms` INTEGER NOT NULL,
                    `latency_ms` INTEGER,
                    `failure` TEXT,
                    `pid` INTEGER,
                    `version` TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_server_health_ping_profile_id_at_ms` " +
                    "ON `server_health_ping` (`profile_id`, `at_ms`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `server_health_event` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `profile_id` TEXT NOT NULL,
                    `at_ms` INTEGER NOT NULL,
                    `kind` TEXT NOT NULL,
                    `value` TEXT NOT NULL,
                    `detail` TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_server_health_event_profile_id_at_ms` " +
                    "ON `server_health_event` (`profile_id`, `at_ms`)",
            )
        }
    }
