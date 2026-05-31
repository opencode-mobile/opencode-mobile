package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.model.DefaultModelRepository
import dev.opencode.mobile.domain.model.ModelDomainAdapter
import dev.opencode.mobile.domain.model.ModelRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ModelModule {
    @Binds
    @Singleton
    abstract fun bindModelRepository(impl: DefaultModelRepository): ModelRepository

    companion object {
        @Provides
        @Singleton
        fun provideModelDomainAdapter(): ModelDomainAdapter = ModelDomainAdapter()
    }
}
