package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.local.DataStoreConnectionProfileStore
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectionModule {
  @Binds
  @Singleton
  abstract fun bindConnectionProfileStore(impl: DataStoreConnectionProfileStore): ConnectionProfileStore
}
