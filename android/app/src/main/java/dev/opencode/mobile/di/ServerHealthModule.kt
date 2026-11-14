package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.local.serverhealth.RoomServerHealthStore
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.event.LiveConnection
import dev.opencode.mobile.domain.event.WorldSignals
import dev.opencode.mobile.domain.serverhealth.KtorServerHealthPinger
import dev.opencode.mobile.domain.serverhealth.ServerHealthClock
import dev.opencode.mobile.domain.serverhealth.ServerHealthMonitor
import dev.opencode.mobile.domain.serverhealth.ServerHealthPingTarget
import dev.opencode.mobile.domain.serverhealth.ServerHealthPinger
import dev.opencode.mobile.domain.serverhealth.ServerHealthStore
import dev.opencode.mobile.domain.serverhealth.ServerHealthStreamFailureRelay
import dev.opencode.mobile.domain.serverhealth.ServerHealthStreamObserver
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ServerHealthModule {
    @Binds
    @Singleton
    abstract fun bindServerHealthStore(impl: RoomServerHealthStore): ServerHealthStore

    companion object {
        @Provides
        @Singleton
        fun provideServerHealthStreamFailureRelay(): ServerHealthStreamFailureRelay = ServerHealthStreamFailureRelay()

        @Provides
        @Singleton
        fun provideServerHealthStreamObserver(relay: ServerHealthStreamFailureRelay): ServerHealthStreamObserver = relay

        @Provides
        fun provideServerHealthClock(): ServerHealthClock = ServerHealthClock.System

        @Provides
        @Singleton
        fun provideServerHealthPinger(json: Json): ServerHealthPinger = KtorServerHealthPinger(json)

        @Provides
        @Singleton
        fun provideServerHealthMonitor(
            liveConnection: LiveConnection,
            worldSignals: WorldSignals,
            relay: ServerHealthStreamFailureRelay,
            activeConnectionManager: ActiveConnectionManager,
            pinger: ServerHealthPinger,
            store: ServerHealthStore,
            clock: ServerHealthClock,
        ): ServerHealthMonitor =
            ServerHealthMonitor(
                snapshots = liveConnection.snapshot,
                occupancy = worldSignals.occupancy,
                streamFailures = relay.failures,
                activeTarget = {
                    val profileId = activeConnectionManager.currentProfile()?.id?.takeIf { it.isNotBlank() }
                    val connection = activeConnectionManager.current()
                    if (profileId == null || connection == null) {
                        null
                    } else {
                        ServerHealthPingTarget(profileId, connection, connection.serverProtocol ?: ServerProtocol.V1)
                    }
                },
                pinger = pinger,
                store = store,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                clock = clock::nowMs,
            )
    }
}
