package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.local.DataStoreConnectionProfileStore
import dev.opencode.mobile.data.local.EncryptedSecureCredentialStore
import dev.opencode.mobile.domain.connection.ConnectionProbeService
import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.SecureCredentialStore
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.network.ActiveConnectionManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectionModule {
    @Binds
    @Singleton
    abstract fun bindConnectionProfileStore(impl: DataStoreConnectionProfileStore): ConnectionProfileStore

    @Binds
    @Singleton
    abstract fun bindSecureCredentialStore(impl: EncryptedSecureCredentialStore): SecureCredentialStore

    companion object {
        @Provides
        @Singleton
        fun provideConnectionProbing(
            json: kotlinx.serialization.json.Json,
            adapter: ProjectDomainAdapter,
        ): ConnectionProbing = ConnectionProbeService(json, adapter)

        @Provides
        @Singleton
        fun provideActiveConnectionManager(
            connectionProfileStore: ConnectionProfileStore,
            connectionProbing: ConnectionProbing,
        ): ActiveConnectionManager =
            ActiveConnectionManager(
                connectionProfileStore = connectionProfileStore,
                connectionProbing = connectionProbing,
            )
    }
}
