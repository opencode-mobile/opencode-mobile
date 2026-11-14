package dev.opencode.mobile.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.local.serverhealth.MIGRATION_1_2
import dev.opencode.mobile.data.local.serverhealth.ServerHealthDao
import dev.opencode.mobile.data.local.sessioncache.OpenCodeMobileDatabase
import dev.opencode.mobile.data.local.sessioncache.RoomSessionCacheStore
import dev.opencode.mobile.data.local.sessioncache.SessionCacheDao
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.domain.message.ToolImageThumbnails
import dev.opencode.mobile.domain.sessioncache.CacheUrlNormalizer
import dev.opencode.mobile.domain.sessioncache.DefaultSessionCache
import dev.opencode.mobile.domain.sessioncache.SessionCache
import dev.opencode.mobile.domain.sessioncache.SessionCacheStore
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionCacheModule {
    @Binds
    @Singleton
    abstract fun bindSessionCacheStore(impl: RoomSessionCacheStore): SessionCacheStore

    companion object {
        @Provides
        @Singleton
        fun provideOpenCodeMobileDatabase(
            @ApplicationContext context: Context,
        ): OpenCodeMobileDatabase =
            Room
                .databaseBuilder(
                    context,
                    OpenCodeMobileDatabase::class.java,
                    "opencode_mobile.db",
                ).addMigrations(MIGRATION_1_2)
                .build()

        @Provides
        fun provideSessionCacheDao(database: OpenCodeMobileDatabase): SessionCacheDao = database.sessionCacheDao()

        @Provides
        fun provideServerHealthDao(database: OpenCodeMobileDatabase): ServerHealthDao = database.serverHealthDao()

        @Provides
        @Singleton
        fun provideToolImageThumbnails(
            @ApplicationContext context: Context,
        ): ToolImageThumbnails = ToolImageThumbnails(root = File(context.cacheDir, ToolImageThumbnails.DIRECTORY_NAME))

        @Provides
        @Singleton
        fun provideSessionCache(
            store: SessionCacheStore,
            liveStoreScope: LiveStoreScope,
            json: Json,
            activeConnectionManager: ActiveConnectionManager,
            thumbnails: ToolImageThumbnails,
        ): SessionCache =
            DefaultSessionCache(
                store = store,
                connectionIdProvider = {
                    val profileId = activeConnectionManager.currentProfile()?.id?.takeIf { it.isNotBlank() }
                    if (profileId != null) {
                        profileId
                    } else {
                        val connection = activeConnectionManager.current() ?: return@DefaultSessionCache null
                        CacheUrlNormalizer.connectionId(connection.baseUrl, connection.username)
                    }
                },
                scope = liveStoreScope.coroutineScope,
                json = json,
                thumbnails = thumbnails,
            ).also { cache ->
                // The cache is built once, early in the app's life: the launch-time sweep and
                // eviction of tool thumbnails run from here, off the main thread.
                cache.startThumbnailMaintenance()
            }
    }
}
