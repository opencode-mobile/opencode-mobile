package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.local.SharedPreferencesSessionReadyPersistence
import dev.opencode.mobile.domain.live.DefaultPermissionLiveStore
import dev.opencode.mobile.domain.live.DefaultProjectionCaches
import dev.opencode.mobile.domain.live.DefaultQuestionLiveStore
import dev.opencode.mobile.domain.live.DefaultSessionLiveStore
import dev.opencode.mobile.domain.live.DefaultSessionReadyStore
import dev.opencode.mobile.domain.live.DefaultSessionStatusLiveStore
import dev.opencode.mobile.domain.live.DefaultSessionTodoLiveStore
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.domain.live.PermissionLiveStore
import dev.opencode.mobile.domain.live.ProjectionCaches
import dev.opencode.mobile.domain.live.QuestionLiveStore
import dev.opencode.mobile.domain.live.SessionLiveStore
import dev.opencode.mobile.domain.live.SessionReadyClock
import dev.opencode.mobile.domain.live.SessionReadyPersistence
import dev.opencode.mobile.domain.live.SessionReadyStore
import dev.opencode.mobile.domain.live.SessionStatusLiveStore
import dev.opencode.mobile.domain.live.SessionTodoLiveStore
import dev.opencode.mobile.domain.live.defaultLiveStoreScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LiveStoreModule {
    @Binds
    @Singleton
    abstract fun bindPermissionLiveStore(impl: DefaultPermissionLiveStore): PermissionLiveStore

    @Binds
    @Singleton
    abstract fun bindQuestionLiveStore(impl: DefaultQuestionLiveStore): QuestionLiveStore

    @Binds
    @Singleton
    abstract fun bindSessionStatusLiveStore(impl: DefaultSessionStatusLiveStore): SessionStatusLiveStore

    @Binds
    @Singleton
    abstract fun bindSessionLiveStore(impl: DefaultSessionLiveStore): SessionLiveStore

    @Binds
    @Singleton
    abstract fun bindSessionTodoLiveStore(impl: DefaultSessionTodoLiveStore): SessionTodoLiveStore

    @Binds
    @Singleton
    abstract fun bindSessionReadyStore(impl: DefaultSessionReadyStore): SessionReadyStore

    @Binds
    @Singleton
    abstract fun bindSessionReadyPersistence(
        impl: SharedPreferencesSessionReadyPersistence,
    ): SessionReadyPersistence

    @Binds
    @Singleton
    abstract fun bindProjectionCaches(impl: DefaultProjectionCaches): ProjectionCaches

    companion object {
        @Provides
        @Singleton
        fun provideLiveStoreScope(): LiveStoreScope = defaultLiveStoreScope()

        @Provides
        @Singleton
        fun provideSessionReadyClock(): SessionReadyClock = SessionReadyClock { System.currentTimeMillis() }
    }
}
