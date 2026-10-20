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

    /** Health `version` from the last probe of the active connection; null until one succeeds. */
    @Volatile
    private var serverVersion: String? = null

    /** v2 session archive support from the server's API contract; false until a probe reads it. */
    @Volatile
    private var v2SessionArchive = false

    /** Server version [v2SessionArchive] was read for, so a re-probe of the same release skips the fetch. */
    private var v2SessionArchiveVersion: String? = null

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

    fun currentServerVersion(): String? = serverVersion?.takeIf { connection != null }

    fun protocolHint(): ServerProtocol = currentProtocol() ?: ServerProtocol.V1

    fun currentCapabilities(): ServerCapabilities {
        val active = connection ?: return ServerCapabilities.forProtocol(ServerProtocol.V1)
        return ServerCapabilities.forProtocol(
            active.serverProtocol ?: ServerProtocol.V1,
            v2SessionArchive = v2SessionArchive,
        )
    }

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
            serverVersion = null
            forgetV2SessionArchive()
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
                val server = probing.probeServer(current)
                val detected = server.protocol
                val archive = if (detected == ServerProtocol.V2) probeV2SessionArchive(probing, current, server.version) else null
                mutex.withLock {
                    val active = connection
                    if (active != null && sameProfileDuringProbe(probedProfile, profile, active, current)) {
                        serverVersion = server.version
                        v2SessionArchive = archive?.getOrNull() ?: false
                        v2SessionArchiveVersion = archive?.getOrNull()?.let { server.version }
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

    /**
     * Reads v2 archive support once per server release. A failed read is not remembered,
     * so the next probe tries again; until then archive stays hidden.
     */
    private suspend fun probeV2SessionArchive(
        probing: ConnectionProbing,
        connection: ServerConnection,
        version: String?,
    ): Result<Boolean> {
        val cached = mutex.withLock { v2SessionArchiveVersion?.takeIf { it == version }?.let { v2SessionArchive } }
        if (cached != null) return Result.success(cached)
        return try {
            Result.success(probing.probeV2SessionArchive(connection))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun forgetV2SessionArchive() {
        v2SessionArchive = false
        v2SessionArchiveVersion = null
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
        if (loaded == null || profile?.hasSameIdentity(loaded) != true) {
            serverVersion = null
            forgetV2SessionArchive()
        }
        profile = loaded
        connection = loaded?.let(ServerConnection::fromProfile)
        probeFailed = false
        completeInFlight(Result.success(loaded?.serverProtocol ?: ServerProtocol.V1))
    }
}
