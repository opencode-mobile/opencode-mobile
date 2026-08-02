package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.live.DefaultSessionLiveStore
import dev.opencode.mobile.domain.live.DefaultSessionStatusLiveStore
import dev.opencode.mobile.domain.live.DefaultPermissionLiveStore
import dev.opencode.mobile.domain.live.DefaultQuestionLiveStore
import dev.opencode.mobile.domain.live.DefaultSessionTodoLiveStore
import dev.opencode.mobile.domain.live.PermissionLiveStore
import dev.opencode.mobile.domain.live.QuestionLiveStore
import dev.opencode.mobile.domain.live.LiveStoreScope
import dev.opencode.mobile.domain.live.SessionLiveStore
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

    companion object {
        @Provides
        @Singleton
        fun provideLiveStoreScope(): LiveStoreScope = defaultLiveStoreScope()
    }
}
