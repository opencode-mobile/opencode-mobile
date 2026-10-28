package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.local.DataStoreComposerDraftStore
import dev.opencode.mobile.data.local.DataStoreSessionModelSelectionStore
import dev.opencode.mobile.domain.composer.ComposerDraftStore
import dev.opencode.mobile.domain.engine.EngineModelRepository
import dev.opencode.mobile.domain.model.ModelDomainAdapter
import dev.opencode.mobile.domain.model.ModelRepository
import dev.opencode.mobile.domain.model.SessionModelSelectionStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ModelModule {
    @Binds
    @Singleton
    abstract fun bindModelRepository(impl: EngineModelRepository): ModelRepository

    @Binds
    @Singleton
    abstract fun bindSessionModelSelectionStore(impl: DataStoreSessionModelSelectionStore): SessionModelSelectionStore

    @Binds
    @Singleton
    abstract fun bindComposerDraftStore(impl: DataStoreComposerDraftStore): ComposerDraftStore

    companion object {
        @Provides
        @Singleton
        fun provideModelDomainAdapter(): ModelDomainAdapter = ModelDomainAdapter()
    }
}
