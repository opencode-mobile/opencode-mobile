package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.agent.AgentDomainAdapter
import dev.opencode.mobile.domain.agent.AgentRepository
import dev.opencode.mobile.domain.agent.DefaultAgentRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentModule {
    @Binds
    @Singleton
    abstract fun bindAgentRepository(impl: DefaultAgentRepository): AgentRepository

    companion object {
        @Provides
        @Singleton
        fun provideAgentDomainAdapter(): AgentDomainAdapter = AgentDomainAdapter()
    }
}
