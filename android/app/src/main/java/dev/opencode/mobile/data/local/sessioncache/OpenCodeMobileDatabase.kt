package dev.opencode.mobile.data.local.sessioncache

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.opencode.mobile.data.local.serverhealth.ServerHealthDao
import dev.opencode.mobile.data.local.serverhealth.ServerHealthEventEntity
import dev.opencode.mobile.data.local.serverhealth.ServerHealthPingEntity

@Database(
    entities = [
        SessionCacheSnapshotEntity::class,
        ServerHealthPingEntity::class,
        ServerHealthEventEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class OpenCodeMobileDatabase : RoomDatabase() {
    abstract fun sessionCacheDao(): SessionCacheDao

    abstract fun serverHealthDao(): ServerHealthDao
}
