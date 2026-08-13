package dev.opencode.mobile.network

import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerCapabilities
import dev.opencode.mobile.domain.connection.ServerProtocol
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Singleton

@Singleton
class ActiveConnectionManager(
    private val connectionProfileStore: ConnectionProfileStore,
    private val connectionProbing: ConnectionProbing? = null,
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

    fun currentProtocol(): ServerProtocol? = connection?.serverProtocol

    fun currentCapabilities(): ServerCapabilities =
        connection?.capabilities ?: ServerCapabilities.forProtocol(ServerProtocol.V1)

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

    suspend fun updateProtocol(protocol: ServerProtocol) {
        mutex.withLock {
            connection = connection?.withProtocol(protocol)
        }
    }

    /**
     * Implicit protocol resolution for repository remotes.
     *
     * Cache hit returns immediately. Cache miss probes the active connection,
     * caches the result, and returns it. Missing connection, missing probe, or
     * a thrown probe returns [ServerProtocol.V1] without throwing.
     */
    suspend fun currentProtocolOrProbe(): ServerProtocol =
        mutex.withLock {
            connection?.serverProtocol?.let { return it }
            val current = connection ?: return ServerProtocol.V1
            val probing = connectionProbing ?: return ServerProtocol.V1
            val detected =
                runCatching { probing.probe(current) }.getOrElse {
                    return ServerProtocol.V1
                }
            val active = connection
            if (active != null && active.hasSameIdentity(current)) {
                connection = active.withProtocol(detected)
                detected
            } else {
                connection?.serverProtocol ?: ServerProtocol.V1
            }
        }

    suspend fun applyProfile(
        profile: ConnectionProfile,
        serverProtocol: ServerProtocol? = null,
    ) {
        mutex.withLock {
            connectionProfileStore.saveProfile(profile)
            connection = ServerConnection.fromProfile(profile, serverProtocol)
        }
    }
}
