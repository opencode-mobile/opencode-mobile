package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.remote.GoalApi
import dev.opencode.mobile.domain.goal.DefaultSessionGoalRepository
import dev.opencode.mobile.domain.goal.SessionGoalRepository
import dev.opencode.mobile.network.OpencodeHttpClient
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class GoalModule {
    @Binds
    @Singleton
    abstract fun bindSessionGoalRepository(impl: DefaultSessionGoalRepository): SessionGoalRepository

    companion object {
        @Provides
        @Singleton
        fun provideGoalApi(
            httpClient: OpencodeHttpClient,
            json: Json,
        ): GoalApi = GoalApi(httpClient, json)
    }
}
