package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.command.CommandDomainAdapter
import dev.opencode.mobile.domain.command.CommandRepository
import dev.opencode.mobile.domain.command.DefaultCommandRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CommandModule {
    @Binds
    @Singleton
    abstract fun bindCommandRepository(impl: DefaultCommandRepository): CommandRepository

    companion object {
        @Provides
        @Singleton
        fun provideCommandDomainAdapter(): CommandDomainAdapter = CommandDomainAdapter()
    }
}
