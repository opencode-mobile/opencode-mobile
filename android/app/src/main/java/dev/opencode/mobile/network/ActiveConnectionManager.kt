package dev.opencode.mobile.network

import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.PairingQRPayload
import dev.opencode.mobile.domain.connection.ProbeStatus
import dev.opencode.mobile.domain.connection.ServerCapabilities
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.connection.toProbeStatus
import dev.opencode.mobile.domain.project.ServerProbeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        val loaded =
            mutex.withLock {
                if (connection != null) return
                connectionProfileStore.activeProfile()
            }
        val resolved = loaded?.let { selectReachableAddress(it) }
        mutex.withLock {
            if (connection != null) return
            persistAddressChange(loaded, resolved)
            bindLocked(connectionProfileStore.activeProfile() ?: resolved)
        }
    }

    suspend fun reloadFromStore() {
        val loaded = mutex.withLock { connectionProfileStore.activeProfile() }
        val resolved = loaded?.let { selectReachableAddress(it) }
        mutex.withLock {
            persistAddressChange(loaded, resolved)
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
        val probedProfile = profile
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
                    if (active != null && sameProfileDuringProbe(probedProfile, profile, active, current)) {
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
        val stamped = profile.copy(serverProtocol = serverProtocol ?: profile.serverProtocol)
        val resolved = selectReachableAddress(stamped)
        mutex.withLock {
            connectionProfileStore.saveProfile(resolved)
            bindLocked(connectionProfileStore.activeProfile() ?: resolved)
        }
    }

    /**
     * Probe every saved address and keep the winner on the same profile id.
     * Empty [ConnectionProfile.serverUrls] skips this and uses the stored baseUrl.
     * If none answer, the stored baseUrl stays and the later single-URL probe fails as usual.
     */
    private suspend fun selectReachableAddress(profile: ConnectionProfile): ConnectionProfile {
        if (profile.serverUrls.isEmpty() || connectionProbing == null) return profile
        val urls = ConnectionProfileIdentity.normalizeServerUrls(profile.serverUrls)
        if (urls.isEmpty()) return profile
        val answered =
            coroutineScope {
                urls
                    .map { url ->
                        async {
                            url to addressAnswered(profile, url)
                        }
                    }.awaitAll()
                    .mapNotNull { (url, ok) -> url.takeIf { ok } }
            }
        val winner = PairingQRPayload.pickReachableUrl(answered) ?: return profile
        val normalizedWinner = ConnectionProfileIdentity.normalizeBaseUrl(winner)
        if (normalizedWinner == ConnectionProfileIdentity.normalizeBaseUrl(profile.baseUrl)) return profile
        return profile.copy(baseUrl = normalizedWinner)
    }

    private suspend fun addressAnswered(
        profile: ConnectionProfile,
        url: String,
    ): Boolean {
        val probing = connectionProbing ?: return false
        val connection = ServerConnection.fromProfile(profile.copy(baseUrl = url))
        return try {
            probing.probe(connection)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ServerProbeError) {
            error.toProbeStatus() == ProbeStatus.AuthFailed
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun persistAddressChange(
        loaded: ConnectionProfile?,
        resolved: ConnectionProfile?,
    ) {
        if (loaded == null || resolved == null) return
        if (
            ConnectionProfileIdentity.normalizeBaseUrl(loaded.baseUrl) ==
            ConnectionProfileIdentity.normalizeBaseUrl(resolved.baseUrl)
        ) {
            return
        }
        connectionProfileStore.saveProfile(resolved)
    }

    private fun sameProfileDuringProbe(
        probed: ConnectionProfile?,
        activeProfile: ConnectionProfile?,
        active: ServerConnection,
        probedConnection: ServerConnection,
    ): Boolean {
        if (probed != null && activeProfile != null) {
            return probed.hasSameIdentity(activeProfile)
        }
        return active.hasSameIdentity(probedConnection)
    }

    private fun bindLocked(loaded: ConnectionProfile?) {
        profile = loaded
        connection = loaded?.let(ServerConnection::fromProfile)
        probeFailed = false
        completeInFlight(Result.success(loaded?.serverProtocol ?: ServerProtocol.V1))
    }
}
