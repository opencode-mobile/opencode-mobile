package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.sessioncontext.DefaultSessionContextRepository
import dev.opencode.mobile.domain.sessioncontext.SessionContextCalculator
import dev.opencode.mobile.domain.sessioncontext.SessionContextRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionContextModule {
    @Binds
    @Singleton
    abstract fun bindSessionContextRepository(impl: DefaultSessionContextRepository): SessionContextRepository

    companion object {
        @Provides
        @Singleton
        fun provideSessionContextCalculator(): SessionContextCalculator = SessionContextCalculator()
    }
}
