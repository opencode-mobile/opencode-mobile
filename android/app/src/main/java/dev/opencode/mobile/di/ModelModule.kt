package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.local.DataStoreSessionModelSelectionStore
import dev.opencode.mobile.domain.model.DefaultModelRepository
import dev.opencode.mobile.domain.model.ModelDomainAdapter
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ModelModule {
    @Binds
    @Singleton
    abstract fun bindModelRepository(impl: DefaultModelRepository): ModelRepository

    @Binds
    @Singleton
    abstract fun bindSessionModelSelectionStore(impl: DataStoreSessionModelSelectionStore): SessionModelSelectionStore

    companion object {
        @Provides
        @Singleton
        fun provideModelDomainAdapter(): ModelDomainAdapter = ModelDomainAdapter()
    }
}
