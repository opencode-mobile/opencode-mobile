package dev.opencode.mobile.network

import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ServerCapabilities
import dev.opencode.mobile.domain.connection.ServerProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    private var profile: ConnectionProfile? = null
    private var inFlightProbe: CompletableDeferred<Result<ServerProtocol>>? = null
    private var probeFailed = false

    suspend fun ensureLoaded() {
        mutex.withLock {
            if (connection == null) {
                bindLocked(connectionProfileStore.activeProfile())
            }
        }
    }

    suspend fun reloadFromStore() {
        mutex.withLock {
            bindLocked(connectionProfileStore.activeProfile())
        }
    }

    fun current(): ServerConnection? = connection

    fun currentProtocol(): ServerProtocol? = connection?.serverProtocol

    fun protocolHint(): ServerProtocol = currentProtocol() ?: ServerProtocol.V1

    fun currentCapabilities(): ServerCapabilities = connection?.capabilities ?: ServerCapabilities.forProtocol(ServerProtocol.V1)

    fun currentProfile(): ConnectionProfile? =
        profile?.copy(
            password = connection?.password.orEmpty(),
            serverProtocol = connection?.serverProtocol ?: profile?.serverProtocol,
        )

    suspend fun disconnect() {
        mutex.withLock {
            connection = null
            profile = null
            probeFailed = false
            completeInFlight(Result.success(ServerProtocol.V1))
        }
    }

    fun resetProbeFailure() {
        probeFailed = false
    }

    fun requireConnection(): ServerConnection = connection ?: error("No active server connection")

    suspend fun updateProtocol(protocol: ServerProtocol) {
        mutex.withLock {
            connection = connection?.withProtocol(protocol)
            persistLocked()
        }
    }

    /**
     * Implicit protocol resolution for repository remotes.
     *
     * Cache hit returns immediately. Cache miss probes the active connection,
     * caches the result, and returns it. Missing connection, missing probe, or
     * a thrown probe returns [ServerProtocol.V1] without throwing.
     * A failed probe is remembered until the connection changes or [probeActive].
     */
    suspend fun currentProtocolOrProbe(): ServerProtocol = sharedProbe(force = false).getOrDefault(ServerProtocol.V1)

    /** Home / Test Connection: join the in-flight detect and throw on failure. */
    suspend fun probeActive(): ServerProtocol = sharedProbe(force = true).getOrThrow()

    private suspend fun sharedProbe(force: Boolean): Result<ServerProtocol> {
        var existing: CompletableDeferred<Result<ServerProtocol>>? = null
        mutex.withLock {
            if (!force) {
                connection?.serverProtocol?.let { return Result.success(it) }
                if (probeFailed) return Result.success(ServerProtocol.V1)
            }
            if (connection == null || connectionProbing == null) {
                return Result.success(ServerProtocol.V1)
            }
            val inFlight = inFlightProbe
            if (inFlight != null) {
                existing = inFlight
            } else {
                if (force) probeFailed = false
                val deferred = CompletableDeferred<Result<ServerProtocol>>()
                inFlightProbe = deferred
            }
        }
        existing?.let { return it.await() }

        val current = connection
        val probing = connectionProbing
        if (current == null || probing == null) {
            val fallback = Result.success(ServerProtocol.V1)
            completeInFlight(fallback)
            return fallback
        }
        val result =
            try {
                val detected = probing.probe(current)
                mutex.withLock {
                    val active = connection
                    if (active != null && active.hasSameIdentity(current)) {
                        connection = active.withProtocol(detected)
                        persistLocked()
                        Result.success(detected)
                    } else {
                        Result.success(connection?.serverProtocol ?: ServerProtocol.V1)
                    }
                }
            } catch (cancelled: CancellationException) {
                completeInFlight(Result.failure(cancelled))
                throw cancelled
            } catch (error: Throwable) {
                mutex.withLock { probeFailed = true }
                Result.failure(error)
            }
        completeInFlight(result)
        return result
    }

    private fun completeInFlight(result: Result<ServerProtocol>) {
        val deferred = inFlightProbe
        inFlightProbe = null
        deferred?.complete(result)
    }

    private suspend fun persistLocked() {
        val profile = currentProfile() ?: return
        connectionProfileStore.saveProfile(profile)
    }

    suspend fun applyProfile(
        profile: ConnectionProfile,
        serverProtocol: ServerProtocol? = null,
    ) {
        mutex.withLock {
            val stamped = profile.copy(serverProtocol = serverProtocol ?: profile.serverProtocol)
            connectionProfileStore.saveProfile(stamped)
            bindLocked(connectionProfileStore.activeProfile() ?: stamped)
        }
    }

    private fun bindLocked(loaded: ConnectionProfile?) {
        profile = loaded
        connection = loaded?.let(ServerConnection::fromProfile)
        probeFailed = false
        completeInFlight(Result.success(loaded?.serverProtocol ?: ServerProtocol.V1))
    }
}
