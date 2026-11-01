package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.remote.ShellApi
import dev.opencode.mobile.domain.shell.AgentShellDomainAdapter
import dev.opencode.mobile.domain.shell.AgentShellRepository
import dev.opencode.mobile.domain.shell.DefaultAgentShellRepository
import dev.opencode.mobile.network.OpencodeHttpClient
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ShellModule {
    @Binds
    @Singleton
    abstract fun bindAgentShellRepository(impl: DefaultAgentShellRepository): AgentShellRepository

    companion object {
        @Provides
        @Singleton
        fun provideShellApi(
            httpClient: OpencodeHttpClient,
            json: Json,
        ): ShellApi = ShellApi(httpClient, json)

        @Provides
        @Singleton
        fun provideAgentShellDomainAdapter(json: Json): AgentShellDomainAdapter = AgentShellDomainAdapter(json)
    }
}
