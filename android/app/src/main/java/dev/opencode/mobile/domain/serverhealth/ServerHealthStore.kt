package dev.opencode.mobile.domain.serverhealth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ServerHealthProcessIdentity(
    val pid: Int?,
    val version: String?,
)

/** Device-local history of health checks and connection changes, per Connection Profile. */
interface ServerHealthStore {
    suspend fun appendPing(ping: ServerHealthPing)

    suspend fun appendEvent(event: ServerHealthEvent)

    /** Checks at or after [sinceMs], oldest first. */
    suspend fun pings(
        profileId: String,
        sinceMs: Long,
    ): List<ServerHealthPing>

    /**
     * Events at or after [sinceMs], oldest first, led by the latest State event before [sinceMs]
     * (when there is one) so the caller knows the state at the start of the range.
     */
    suspend fun events(
        profileId: String,
        sinceMs: Long,
    ): List<ServerHealthEvent>

    /** Pid and version of the newest check that reported either. */
    suspend fun lastPid(profileId: String): ServerHealthProcessIdentity?

    suspend fun prune(olderThanMs: Long)

    suspend fun purge(profileId: String)

    suspend fun purgeAll()

    companion object {
        const val RETENTION_MS: Long = 7L * 24L * 60L * 60_000L
    }
}

/** Process-memory store, for tests and previews. */
class InMemoryServerHealthStore : ServerHealthStore {
    private val mutex = Mutex()
    private val pingRows = mutableListOf<ServerHealthPing>()
    private val eventRows = mutableListOf<ServerHealthEvent>()

    val allPings: List<ServerHealthPing>
        get() = pingRows.toList()

    val allEvents: List<ServerHealthEvent>
        get() = eventRows.toList()

    override suspend fun appendPing(ping: ServerHealthPing) {
        mutex.withLock { pingRows += ping }
    }

    override suspend fun appendEvent(event: ServerHealthEvent) {
        mutex.withLock { eventRows += event }
    }

    override suspend fun pings(
        profileId: String,
        sinceMs: Long,
    ): List<ServerHealthPing> =
        mutex.withLock {
            pingRows.filter { it.profileId == profileId && it.atMs >= sinceMs }.sortedBy { it.atMs }
        }

    override suspend fun events(
        profileId: String,
        sinceMs: Long,
    ): List<ServerHealthEvent> =
        mutex.withLock {
            val mine = eventRows.filter { it.profileId == profileId }
            val lead =
                mine
                    .filter { it.kind == ServerHealthEventKind.State && it.atMs < sinceMs }
                    .maxByOrNull { it.atMs }
            listOfNotNull(lead) + mine.filter { it.atMs >= sinceMs }.sortedBy { it.atMs }
        }

    override suspend fun lastPid(profileId: String): ServerHealthProcessIdentity? =
        mutex.withLock {
            pingRows
                .filter { it.profileId == profileId && (it.pid != null || it.version != null) }
                .maxByOrNull { it.atMs }
                ?.let { ServerHealthProcessIdentity(it.pid, it.version) }
        }

    override suspend fun prune(olderThanMs: Long) {
        mutex.withLock {
            pingRows.removeAll { it.atMs < olderThanMs }
            eventRows.removeAll { it.atMs < olderThanMs }
        }
    }

    override suspend fun purge(profileId: String) {
        mutex.withLock {
            pingRows.removeAll { it.profileId == profileId }
            eventRows.removeAll { it.profileId == profileId }
        }
    }

    override suspend fun purgeAll() {
        mutex.withLock {
            pingRows.clear()
            eventRows.clear()
        }
    }
}
