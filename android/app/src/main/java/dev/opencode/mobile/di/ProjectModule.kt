package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.remote.EventsApi
import dev.opencode.mobile.data.remote.HealthApi
import dev.opencode.mobile.data.remote.ProjectsApi
import dev.opencode.mobile.domain.event.DefaultForegroundEventStream
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.project.DefaultProjectRepository
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ProjectModule {
    @Binds
    @Singleton
    abstract fun bindProjectRepository(impl: DefaultProjectRepository): ProjectRepository

    companion object {
        @Provides
        @Singleton
        fun provideHealthApi(httpClient: OpencodeHttpClient): HealthApi = HealthApi(httpClient)

        @Provides
        @Singleton
        fun provideProjectsApi(httpClient: OpencodeHttpClient): ProjectsApi = ProjectsApi(httpClient)

        @Provides
        @Singleton
        fun provideProjectDomainAdapter(): ProjectDomainAdapter = ProjectDomainAdapter()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EventStreamModule {
    @Binds
    @Singleton
    abstract fun bindForegroundEventStream(impl: DefaultForegroundEventStream): ForegroundEventStream

    companion object {
        @Provides
        @Singleton
        fun provideEventsApi(sseHttpClient: OpencodeSseHttpClient): EventsApi = EventsApi(sseHttpClient)

        @Provides
        @Singleton
        fun provideEventDomainAdapter(json: Json): EventDomainAdapter = EventDomainAdapter(json)
    }
}
