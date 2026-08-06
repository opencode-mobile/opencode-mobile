package dev.opencode.mobile.data.local.sessioncache

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SessionCacheSnapshotEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class OpenCodeMobileDatabase : RoomDatabase() {
    abstract fun sessionCacheDao(): SessionCacheDao
}
