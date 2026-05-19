package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.session.DefaultSessionRepository
import dev.opencode.mobile.domain.session.SessionDomainAdapter
import dev.opencode.mobile.domain.session.SessionRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionModule {
  @Binds
  @Singleton
  abstract fun bindSessionRepository(impl: DefaultSessionRepository): SessionRepository

  companion object {
    @Provides
    @Singleton
    fun provideSessionDomainAdapter(): SessionDomainAdapter = SessionDomainAdapter()
  }
}
