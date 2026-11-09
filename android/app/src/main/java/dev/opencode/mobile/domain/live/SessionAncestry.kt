package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parent links of the sessions Home has seen (#981), so a busy sub-agent rolls up to its root
 * row without listing every child. Seeded from Home's listed windows (roots map to themselves,
 * and children an older v1 server still returns); a busy id with an unknown parent is fetched
 * one session at a time up to a known root. Per connection profile, in memory: the session
 * live store clears it with its cache.
 */
@Singleton
class SessionAncestry(
    private val sessionRepository: SessionRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    @Inject
    constructor(
        sessionRepository: SessionRepository,
        liveStoreScope: LiveStoreScope,
    ) : this(sessionRepository, liveStoreScope.coroutineScope)

    /** Parent id per session; [ROOT] for a session without one. */
    private val parents = ConcurrentHashMap<String, String>()

    /** Archived sessions do not roll up to their root row. */
    private val archived = ConcurrentHashMap.newKeySet<String>()

    /** Latest copy of every root seen, listed or fetched. */
    private val roots = ConcurrentHashMap<String, Session>()
    private val failedAtMs = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, Deferred<Session?>>()

    @Volatile private var generation = 0
    private val _changes = MutableStateFlow(0L)

    /** Ticks whenever a lookup learns a link or root that was not known before. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** Records [sessions]' parent links; listed roots also refresh their stored copy. */
    fun record(sessions: List<Session>) {
        var learned = false
        for (session in sessions) {
            val parent = session.parentId?.takeIf { it.isNotEmpty() } ?: ROOT
            if (parents.put(session.id, parent) != parent) learned = true
            if (session.isArchived) archived += session.id else archived -= session.id
            if (parent == ROOT) roots[session.id] = session
        }
        if (learned) _changes.update { it + 1 }
    }

    /**
     * Root id of [sessionId] from known links; null while a link on the way up is unknown, and
     * for an archived session.
     */
    fun rootOf(sessionId: String): String? {
        if (sessionId in archived) return null
        var current = sessionId
        repeat(MAX_DEPTH + 1) {
            val parent = parents[current] ?: return null
            if (parent == ROOT) return current
            current = parent
        }
        return null
    }

    /** The stored copy of root [sessionId], listed or fetched. */
    fun rootSession(sessionId: String): Session? = roots[sessionId]

    /** Resolves [sessionIds] with unknown roots in the background; [changes] ticks with what it learns. */
    fun requestRoots(
        sessionIds: Collection<String>,
        directory: String?,
    ) {
        val unresolved = sessionIds.filter { it !in archived && rootOf(it) == null }
        if (unresolved.isEmpty()) return
        scope.launch { resolveRoots(unresolved, directory) }
    }

    /**
     * Root id per id in [sessionIds] that resolves, fetching unknown parents (`GET` one session)
     * at most [MAX_DEPTH] levels up. A failed fetch is not retried for [FAILURE_RETRY_MS].
     */
    suspend fun resolveRoots(
        sessionIds: Collection<String>,
        directory: String?,
    ): Map<String, String> =
        buildMap {
            for (id in sessionIds) {
                resolveRoot(id, directory)?.let { put(id, it) }
            }
        }

    fun clear() {
        generation++
        parents.clear()
        archived.clear()
        roots.clear()
        failedAtMs.clear()
        inFlight.clear()
        _changes.update { it + 1 }
    }

    private suspend fun resolveRoot(
        sessionId: String,
        directory: String?,
    ): String? {
        var current = sessionId
        var steps = 0
        while (steps <= MAX_DEPTH) {
            when (val parent = parents[current]) {
                ROOT -> return current
                null -> {
                    fetch(current, directory) ?: return null
                    if (parents[current] == null) return null
                }
                else -> {
                    current = parent
                    steps++
                }
            }
        }
        return null
    }

    /** Fetches [sessionId] once at a time and records its link; null when it failed or recently did. */
    private suspend fun fetch(
        sessionId: String,
        directory: String?,
    ): Session? {
        failedAtMs[sessionId]?.let { failedAt ->
            if (nowMs() - failedAt < FAILURE_RETRY_MS) return null
        }
        val fetchGeneration = generation
        val pending =
            inFlight.computeIfAbsent(sessionId) {
                scope.async(ioDispatcher) {
                    try {
                        sessionRepository.getSession(sessionId, directory)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        DiagnosticLog.warning(DiagnosticLogArea.Session, "session ancestry lookup failed session=$sessionId", error)
                        null
                    }
                }
            }
        val session =
            try {
                pending.await()
            } finally {
                inFlight.remove(sessionId, pending)
            }
        if (fetchGeneration != generation) return null
        if (session == null) {
            failedAtMs[sessionId] = nowMs()
            return null
        }
        failedAtMs.remove(sessionId)
        record(listOf(session))
        return session
    }

    companion object {
        /** Parent levels walked before a busy id counts as unresolved. */
        const val MAX_DEPTH = 8
        const val FAILURE_RETRY_MS = 60_000L
        private const val ROOT = ""
    }
}
