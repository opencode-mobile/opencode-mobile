package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.mcp.DefaultMcpRepository
import dev.opencode.mobile.domain.mcp.McpDomainAdapter
import dev.opencode.mobile.domain.mcp.McpRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class McpModule {
    @Binds
    @Singleton
    abstract fun bindMcpRepository(impl: DefaultMcpRepository): McpRepository

    companion object {
        @Provides
        @Singleton
        fun provideMcpDomainAdapter(): McpDomainAdapter = McpDomainAdapter()
    }
}
