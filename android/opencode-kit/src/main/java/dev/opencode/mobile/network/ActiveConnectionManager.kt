package dev.opencode.mobile.network

import dev.opencode.mobile.domain.connection.ConnectionProbing
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ProbeStatus
import dev.opencode.mobile.domain.connection.ProtocolRedetection
import dev.opencode.mobile.domain.connection.ServerAddressProbe
import dev.opencode.mobile.domain.connection.ServerCapabilities
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.connection.V2ServerFeatures
import dev.opencode.mobile.domain.connection.toProbeStatus
import dev.opencode.mobile.domain.project.ServerProbeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

/** A running probe of [connection]; its waiters share [result]. */
private class ProbeFlight(
    val connection: ServerConnection,
    /** Ran every health endpoint, with no saved-protocol shortcut. */
    val fullDetection: Boolean,
) {
    val result = CompletableDeferred<Result<ServerProtocol>>()
}

/** The probe no longer speaks for the bound connection; not a server failure. */
private object ProbeSuperseded : Exception() {
    private fun readResolve(): Any = ProbeSuperseded
}

/** The connection a pinned operation started on is no longer the bound one. */
class ConnectionChangedException : IOException("The active server changed")

/**
 * One connection captured for an operation that must not move to another server. Run the work
 * with [ActiveConnectionManager.pinContext]; its requests use this connection or fail.
 */
class ConnectionPin internal constructor(
    internal val connection: ServerConnection,
    internal val binding: ConnectionBinding,
    internal val protocol: ServerProtocol,
    internal val features: V2ServerFeatures,
) {
    val profileId: String get() = binding.profileId
}

/** The server a piece of work started against. */
data class ConnectionBinding(
    val profileId: String,
    val baseUrl: String,
    val username: String,
)

@Singleton
class ActiveConnectionManager(
    private val connectionProfileStore: ConnectionProfileStore,
    private val connectionProbing: ConnectionProbing? = null,
) {
    private val mutex = Mutex()
    private var connection: ServerConnection? = null
    private var profile: ConnectionProfile? = null
    private val inFlightProbe = AtomicReference<ProbeFlight?>(null)
    private var probeFailed = false

    /** The pin of the running coroutine; it follows the coroutine across dispatchers. */
    private val pinOfCoroutine = ThreadLocal<ConnectionPin?>()

    /** Health `version` from the last probe of the active connection; null until one succeeds. */
    @Volatile
    private var serverVersion: String? = null

    /** Release-dependent v2 features from the server's API contract; all off until a probe reads it. */
    @Volatile
    private var v2Features = V2ServerFeatures()

    /** Server version [v2Features] was read for, so a re-probe of the same release skips the fetch. */
    private var v2FeaturesVersion: String? = null

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
            bindLocked(connectionProfileStore.activeProfile())
        }
    }

    /**
     * Binds the stored address immediately. Address probing is not part of this call:
     * a server switch paints that address while [resolveReachableAddress] looks for a
     * better one in the background.
     */
    suspend fun reloadFromStore() {
        val loaded = connectionProfileStore.activeProfile()
        mutex.withLock {
            bindLocked(loaded)
        }
    }

    /**
     * Probes the profile's saved addresses and rebinds when a different host wins.
     * Returns false when nothing changed, or when [profile] is no longer the active one.
     */
    suspend fun resolveReachableAddress(profile: ConnectionProfile): Boolean {
        val resolved = selectReachableAddress(profile)
        val before = ConnectionProfileIdentity.normalizeBaseUrl(profile.baseUrl)
        val after = ConnectionProfileIdentity.normalizeBaseUrl(resolved.baseUrl)
        if (before == after) return false
        return mutex.withLock {
            val active = this.profile
            if (active == null || active.id != profile.id) return@withLock false
            if (ConnectionProfileIdentity.normalizeBaseUrl(active.baseUrl) != before) return@withLock false
            if (!persistAddressChange(profile, resolved)) return@withLock false
            bindLocked(connectionProfileStore.activeProfile())
            true
        }
    }

    fun current(): ServerConnection? = connection

    /**
     * Who a response belongs to: profile, address and username. A protocol or password update
     * keeps it, so a load that started before one still commits.
     */
    fun connectionIdentity(): ConnectionBinding? {
        val active = connection ?: return null
        return ConnectionBinding(profile?.id.orEmpty(), active.baseUrl, active.username)
    }

    fun currentProtocol(): ServerProtocol? = pinOfCoroutine.get()?.protocol ?: connection?.serverProtocol

    /**
     * Captures the bound connection with a known protocol. An unknown protocol is probed first,
     * and if the connection changes meanwhile this throws [ConnectionChangedException].
     */
    suspend fun pinConnection(): ConnectionPin {
        val started = connectionIdentity() ?: throw ConnectionChangedException()
        val probed = sharedProbe(force = false).getOrDefault(ServerProtocol.V1)
        return mutex.withLock {
            val active = connection
            if (active == null || connectionIdentity() != started) throw ConnectionChangedException()
            ConnectionPin(active, started, active.serverProtocol ?: probed, v2Features)
        }
    }

    fun pinContext(pin: ConnectionPin): CoroutineContext = pinOfCoroutine.asContextElement(pin)

    /** The connection the running coroutine is pinned to, if any. */
    fun pinnedConnection(): ServerConnection? = pinOfCoroutine.get()?.connection

    fun isCurrent(pin: ConnectionPin): Boolean = connectionIdentity() == pin.binding

    fun currentServerVersion(): String? = serverVersion?.takeIf { connection != null }

    fun protocolHint(): ServerProtocol = currentProtocol() ?: ServerProtocol.V1

    fun currentCapabilities(): ServerCapabilities {
        pinOfCoroutine.get()?.let { pin ->
            return ServerCapabilities.forProtocol(pin.protocol, v2Features = pin.features)
        }
        val active = connection ?: return ServerCapabilities.forProtocol(ServerProtocol.V1)
        return ServerCapabilities.forProtocol(
            active.serverProtocol ?: ServerProtocol.V1,
            v2Features = v2Features,
        )
    }

    /** `paths.tmp` for streamed uploads, or null while the server has not declared the route. */
    fun attachmentUploadTmpDirectory(): String? {
        pinOfCoroutine.get()?.let { return it.features.attachmentUploadTmp }
        return if (connection != null) v2Features.attachmentUploadTmp else null
    }

    /**
     * A live upload showed the route is not what the contract promised. Streamed uploads stay
     * off until the connection changes, so every file goes the data-URL way.
     */
    fun disableAttachmentUpload() {
        v2Features = v2Features.copy(attachmentUploadTmp = null)
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
            forgetV2Features()
            supersedeFlight()
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
    suspend fun currentProtocolOrProbe(): ServerProtocol =
        pinOfCoroutine.get()?.protocol ?: sharedProbe(force = false).getOrDefault(ServerProtocol.V1)

    /** Home / Test Connection: join the in-flight detect and throw on failure. */
    suspend fun probeActive(): ServerProtocol = sharedProbe(force = true).getOrThrow()

    /**
     * Re-runs detection on the active connection, so a server replaced at the same address by
     * the other protocol moves the profile to it. A rejected credential is not an answer.
     */
    suspend fun redetectProtocol(): ProtocolRedetection {
        if (connection == null || connectionProbing == null) return ProtocolRedetection.Skipped
        return sharedProbe(force = true, fullDetection = true).fold(
            onSuccess = { ProtocolRedetection.Detected(it) },
            onFailure = { error ->
                when {
                    error is CancellationException -> ProtocolRedetection.Skipped
                    (error as? ServerProbeError)?.toProbeStatus() == ProbeStatus.AuthFailed -> ProtocolRedetection.Skipped
                    else -> ProtocolRedetection.Unreachable
                }
            },
        )
    }

    /**
     * One probe per bound connection. A flight replaced by a bind, or whose leader was cancelled,
     * answers [ProbeSuperseded], and its waiters ask again against whatever is bound now.
     */
    private suspend fun sharedProbe(
        force: Boolean,
        fullDetection: Boolean = false,
    ): Result<ServerProtocol> {
        while (true) {
            currentCoroutineContext().ensureActive()
            var joined: ProbeFlight? = null
            var leading: ProbeFlight? = null
            var outlasting: ProbeFlight? = null
            mutex.withLock {
                if (!force) {
                    connection?.serverProtocol?.let { return Result.success(it) }
                    if (probeFailed) return Result.success(ServerProtocol.V1)
                }
                val active = connection
                if (active == null || connectionProbing == null) {
                    return Result.success(ServerProtocol.V1)
                }
                val inFlight = inFlightProbe.get()
                if (inFlight != null && fullDetection && !inFlight.fullDetection) {
                    // A shortcut answer cannot settle a redetection: wait it out, then run in full.
                    outlasting = inFlight
                } else if (inFlight != null) {
                    joined = inFlight
                } else {
                    if (force) probeFailed = false
                    leading =
                        ProbeFlight(active, fullDetection = fullDetection || active.serverProtocol == null)
                            .also(inFlightProbe::set)
                }
            }
            outlasting?.let { running ->
                running.result.await()
                continue
            }
            val result = joined?.result?.await() ?: leadProbe(checkNotNull(leading), checkNotNull(connectionProbing))
            if (result.exceptionOrNull() !is ProbeSuperseded) return result
        }
    }

    /** Runs the flight, and stops waiting on a dead server the moment a bind supersedes it. */
    private suspend fun leadProbe(
        flight: ProbeFlight,
        probing: ConnectionProbing,
    ): Result<ServerProtocol> =
        coroutineScope {
            val work = launch { runProbe(flight, probing) }
            flight.result.await().also { work.cancel() }
        }

    private suspend fun runProbe(
        flight: ProbeFlight,
        probing: ConnectionProbing,
    ): Result<ServerProtocol> {
        val result =
            try {
                val target = if (flight.fullDetection) flight.connection.copy(serverProtocol = null) else flight.connection
                val server = probing.probeServer(target)
                val detected = server.protocol
                val features = if (detected == ServerProtocol.V2) probeV2Features(probing, flight.connection, server.version) else null
                mutex.withLock {
                    val active = connection
                    if (active == null || inFlightProbe.get() !== flight) {
                        Result.failure(ProbeSuperseded)
                    } else {
                        serverVersion = server.version
                        v2Features = features?.getOrNull() ?: V2ServerFeatures()
                        v2FeaturesVersion = features?.getOrNull()?.takeUnless { it.tmpDirectoryUnread }?.let { server.version }
                        connection = active.withProtocol(detected)
                        persistLocked()
                        Result.success(detected)
                    }
                }
            } catch (cancelled: CancellationException) {
                finishFlight(flight, Result.failure(ProbeSuperseded))
                throw cancelled
            } catch (error: Throwable) {
                mutex.withLock {
                    if (inFlightProbe.get() === flight) {
                        probeFailed = true
                        Result.failure(error)
                    } else {
                        Result.failure(ProbeSuperseded)
                    }
                }
            }
        finishFlight(flight, result)
        return result
    }

    /**
     * Reads v2 features once per server release. A failed read is not remembered,
     * so the next probe tries again; until then archive and Last turn stay hidden.
     * Neither is a read whose `/api/info` failed, so streamed uploads can turn on later.
     */
    private suspend fun probeV2Features(
        probing: ConnectionProbing,
        connection: ServerConnection,
        version: String?,
    ): Result<V2ServerFeatures> {
        val cached = mutex.withLock { v2FeaturesVersion?.takeIf { it == version }?.let { v2Features } }
        if (cached != null) return Result.success(cached)
        return try {
            Result.success(probing.probeV2Features(connection))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun forgetV2Features() {
        v2Features = V2ServerFeatures()
        v2FeaturesVersion = null
    }

    private fun finishFlight(
        flight: ProbeFlight,
        result: Result<ServerProtocol>,
    ) {
        inFlightProbe.compareAndSet(flight, null)
        flight.result.complete(result)
    }

    /** The connection changed: whoever waits on the running probe asks the new one instead. */
    private fun supersedeFlight() {
        inFlightProbe.getAndSet(null)?.result?.complete(Result.failure(ProbeSuperseded))
    }

    /** Stores a newly detected protocol. An unchanged one writes nothing. */
    private suspend fun persistLocked() {
        val stored = profile ?: return
        val detected = connection?.serverProtocol ?: return
        if (stored.serverProtocol == detected) return
        if (connectionProfileStore.updateStoredProfile(stored.id) { it.copy(serverProtocol = detected) }) {
            profile = stored.copy(serverProtocol = detected)
        }
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
     * Resolve the saved address list and keep the winner on the same profile id.
     * Empty [ConnectionProfile.serverUrls] skips this and uses the stored baseUrl.
     * Candidates are swept without credentials; the password goes to the one chosen host,
     * and only a host that accepted it wins. A rejected password or no answer at all leaves
     * the stored baseUrl alone, and the later single-URL probe fails as usual.
     */
    private suspend fun selectReachableAddress(profile: ConnectionProfile): ConnectionProfile {
        val probing = connectionProbing ?: return profile
        if (profile.serverUrls.isEmpty()) return profile
        val urls = ConnectionProfileIdentity.normalizeServerUrls(profile.serverUrls)
        if (urls.isEmpty()) return profile
        val resolution =
            ServerAddressProbe.resolve(
                urls = urls,
                username = profile.username,
                password = profile.password,
                currentBaseUrl = profile.baseUrl,
                probing = probing,
            )
        val winner = resolution.chosen ?: return profile
        val detected = resolution.reachableProtocol ?: return profile
        val normalizedWinner = ConnectionProfileIdentity.normalizeBaseUrl(winner)
        if (normalizedWinner == ConnectionProfileIdentity.normalizeBaseUrl(profile.baseUrl)) return profile
        return profile.copy(baseUrl = normalizedWinner, serverProtocol = detected)
    }

    private suspend fun persistAddressChange(
        loaded: ConnectionProfile?,
        resolved: ConnectionProfile?,
    ): Boolean {
        if (loaded == null || resolved == null) return false
        if (
            ConnectionProfileIdentity.normalizeBaseUrl(loaded.baseUrl) ==
            ConnectionProfileIdentity.normalizeBaseUrl(resolved.baseUrl)
        ) {
            return false
        }
        return connectionProfileStore.updateStoredProfile(resolved.id) {
            it.copy(baseUrl = resolved.baseUrl, serverProtocol = resolved.serverProtocol)
        }
    }

    private fun bindLocked(loaded: ConnectionProfile?) {
        if (loaded == null || profile?.hasSameIdentity(loaded) != true) {
            serverVersion = null
            forgetV2Features()
        }
        profile = loaded
        connection = loaded?.let(ServerConnection::fromProfile)
        probeFailed = false
        supersedeFlight()
    }
}
