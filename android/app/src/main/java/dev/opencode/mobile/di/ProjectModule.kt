package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.remote.EventsApi
import dev.opencode.mobile.data.remote.FilesystemApi
import dev.opencode.mobile.data.remote.HealthApi
import dev.opencode.mobile.data.remote.ProjectsApi
import dev.opencode.mobile.domain.connection.ConnectivityDeviceNetworkPath
import dev.opencode.mobile.domain.connection.DeviceNetworkPath
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogger
import dev.opencode.mobile.domain.event.DefaultForegroundEventStream
import dev.opencode.mobile.domain.event.DefaultLiveUpdates
import dev.opencode.mobile.domain.event.EventDomainAdapter
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.event.LiveConnection
import dev.opencode.mobile.domain.event.LiveUpdates
import dev.opencode.mobile.domain.event.ProcessWorldSignals
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.filesystem.DefaultFilesystemRepository
import dev.opencode.mobile.domain.filesystem.FilesystemDomainAdapter
import dev.opencode.mobile.domain.filesystem.FilesystemRepository
import dev.opencode.mobile.domain.project.DefaultProjectRepository
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ProjectModule {
    @Binds
    @Singleton
    abstract fun bindProjectRepository(impl: DefaultProjectRepository): ProjectRepository

    @Binds
    @Singleton
    abstract fun bindFilesystemRepository(impl: DefaultFilesystemRepository): FilesystemRepository

    companion object {
        @Provides
        @Singleton
        fun provideHealthApi(httpClient: OpencodeHttpClient): HealthApi = HealthApi(httpClient)

        @Provides
        @Singleton
        fun provideProjectsApi(httpClient: OpencodeHttpClient): ProjectsApi = ProjectsApi(httpClient)

        @Provides
        @Singleton
        fun provideFilesystemApi(httpClient: OpencodeHttpClient): FilesystemApi = FilesystemApi(httpClient)

        @Provides
        @Singleton
        fun provideProjectDomainAdapter(): ProjectDomainAdapter = ProjectDomainAdapter()

        @Provides
        @Singleton
        fun provideFilesystemDomainAdapter(): FilesystemDomainAdapter = FilesystemDomainAdapter()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EventStreamModule {
    @Binds
    @Singleton
    abstract fun bindForegroundEventStream(impl: DefaultForegroundEventStream): ForegroundEventStream

    @Binds
    @Singleton
    abstract fun bindLiveConnection(impl: DefaultForegroundEventStream): LiveConnection

    @Binds
    @Singleton
    abstract fun bindSyncCoordinator(
        impl: dev.opencode.mobile.domain.sync.DefaultSyncCoordinator,
    ): dev.opencode.mobile.domain.sync.SyncCoordinator

    @Binds
    @Singleton
    abstract fun bindDeviceNetworkPath(impl: ConnectivityDeviceNetworkPath): DeviceNetworkPath

    companion object {
        @Provides
        @Singleton
        fun provideEventsApi(sseHttpClient: OpencodeSseHttpClient): EventsApi = EventsApi(sseHttpClient)

        @Provides
        @Singleton
        fun provideEventDomainAdapter(json: Json): EventDomainAdapter = EventDomainAdapter(json)

        @Provides
        @Singleton
        fun provideWorldSignals(
            stream: ForegroundEventStream,
            deviceNetworkPath: DeviceNetworkPath,
            diagnosticLogger: DiagnosticLogger,
            activeConnectionManager: ActiveConnectionManager,
        ): WorldSignals =
            ProcessWorldSignals(
                stream = stream,
                deviceNetworkPath = deviceNetworkPath,
                diagnosticLogger = diagnosticLogger,
                hasActiveConnection = { activeConnectionManager.current() != null },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                activeConnectionManager = activeConnectionManager,
            )

        @Provides
        @Singleton
        fun provideLiveUpdates(
            worldSignals: WorldSignals,
            stream: ForegroundEventStream,
            deviceNetworkPath: DeviceNetworkPath,
            activeConnectionManager: ActiveConnectionManager,
        ): LiveUpdates =
            DefaultLiveUpdates(
                worldSignals = worldSignals,
                stream = stream,
                path = deviceNetworkPath,
                hasActiveConnection = { activeConnectionManager.current() != null },
            )
    }
}
