package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.local.DataStoreConnectionProfileStore
import dev.opencode.mobile.data.local.EncryptedSecureCredentialStore
import dev.opencode.mobile.domain.connection.ConnectionProbeService
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
        fun provideConnectionProbeService(
            json: kotlinx.serialization.json.Json,
            adapter: ProjectDomainAdapter,
        ): ConnectionProbeService = ConnectionProbeService(json, adapter)

        @Provides
        @Singleton
        fun provideActiveConnectionManager(
            connectionProfileStore: ConnectionProfileStore,
            connectionProbeService: ConnectionProbeService,
        ): ActiveConnectionManager =
            ActiveConnectionManager(
                connectionProfileStore = connectionProfileStore,
                connectionProbing = connectionProbeService,
            )
    }
}
