package dev.opencode.mobile.network

import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActiveConnectionManager
    @Inject
    constructor(
        private val connectionProfileStore: ConnectionProfileStore,
    ) {
        private val mutex = Mutex()
        private var connection: ServerConnection? = null

        suspend fun ensureLoaded() {
            mutex.withLock {
                if (connection == null) {
                    connection = connectionProfileStore.activeProfile()?.let(ServerConnection::fromProfile)
                }
            }
        }

        fun current(): ServerConnection? = connection

        fun currentProfile(): ConnectionProfile? =
            current()?.let { connection ->
                ConnectionProfile(
                    baseUrl = connection.baseUrl,
                    username = connection.username,
                    password = connection.password.orEmpty(),
                )
            }

        fun disconnect() {
            connection = null
        }

        fun requireConnection(): ServerConnection = connection ?: error("No active server connection")

        suspend fun applyProfile(profile: ConnectionProfile) {
            mutex.withLock {
                connectionProfileStore.saveProfile(profile)
                connection = ServerConnection.fromProfile(profile)
            }
        }
    }
