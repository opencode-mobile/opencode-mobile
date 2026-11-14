package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.goal.DefaultSessionGoalRepository
import dev.opencode.mobile.domain.goal.SessionGoalRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class GoalModule {
    @Binds
    @Singleton
    abstract fun bindSessionGoalRepository(impl: DefaultSessionGoalRepository): SessionGoalRepository
}
