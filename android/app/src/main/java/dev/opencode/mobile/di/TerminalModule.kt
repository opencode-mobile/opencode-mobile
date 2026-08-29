package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.terminal.DefaultTerminalRepository
import dev.opencode.mobile.domain.terminal.TerminalDomainAdapter
import dev.opencode.mobile.domain.terminal.TerminalRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TerminalModule {
    @Binds
    @Singleton
    abstract fun bindTerminalRepository(impl: DefaultTerminalRepository): TerminalRepository

    companion object {
        @Provides
        @Singleton
        fun provideTerminalDomainAdapter(): TerminalDomainAdapter = TerminalDomainAdapter()
    }
}
