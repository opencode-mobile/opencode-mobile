package dev.opencode.mobile.domain.live

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a gated refresh did with its REST response. */
internal enum class LiveStoreRefreshOutcome {
    /** The response was applied to the store. */
    COMMITTED,

    /** The response arrived after another source had already moved the state. */
    DISCARDED,

    /** The REST call itself failed; nothing was applied. */
    FAILED,
}

/**
 * Single-flight plus revision gate for one live-store observation.
 *
 * Every live store runs three concurrent refresh triggers over the same state
 * (world signals, invalidation, the SSE stream). Without this gate two things go
 * wrong, and both are sticky — no later event corrects them:
 *
 * 1. **A REST response resurrects a state an SSE patch already superseded.** The
 *    status store awaits `getAllSessionStatuses`; while it is in flight a
 *    `session.idle` patch drops the finished session; the REST coroutine then
 *    resumes and writes back its pre-idle snapshot, leaving a finished session
 *    spinning until pull-to-refresh.
 * 2. **Two REST responses land out of order.** `permission.replied` starts a
 *    refresh that returns `[]`, `permission.asked` starts a second that returns
 *    `[P2]`, and on a slow radio the empty one lands last — the prompt never
 *    appears and the agent stays blocked server-side.
 *
 * The counter is the same shape as `SessionMessageEngine.replaceAllIfRevision`:
 * capture the revision before awaiting, apply only if it has not moved. Callers
 * bump it from every state mutation that a REST commit could otherwise overwrite.
 *
 * The mutex fixes (2) and is also what makes the gate safe: capture, load and
 * commit all happen under it, so a refresh can never discard itself against the
 * revision its own predecessor bumped — a queued refresh captures the *post*
 * commit revision. Discarding therefore only ever means "another source moved
 * the state while this response was in flight".
 *
 * The gate is per-observation, not per-store: two directories must not serialize
 * against each other.
 *
 * This gate does not touch invalidation acks. Acks are completed by the
 * collectors' `finally` blocks around the whole refresh, so a discarded response
 * still releases the pull-to-refresh spinner.
 */
internal class LiveStoreRefreshGate {
    private val mutex = Mutex()
    private val revisionLock = Any()
    private var revision = 0L

    /**
     * Records a state mutation that did not come from a gated refresh — an SSE
     * patch, or a local mutation that a REST snapshot would undo. An in-flight
     * refresh that captured an earlier revision will discard its response.
     */
    fun bump() {
        synchronized(revisionLock) { revision += 1 }
    }

    /** Test seam: the current revision. */
    fun revision(): Long = synchronized(revisionLock) { revision }

    /**
     * Runs [load] as the only refresh in flight for this observation, then applies
     * it through [commit] unless another source bumped the revision meanwhile.
     *
     * [load] returns `null` for a failed fetch, which is reported as
     * [LiveStoreRefreshOutcome.FAILED] and leaves the state untouched.
     */
    suspend fun <T> refresh(
        load: suspend () -> T?,
        commit: suspend (T) -> Unit,
    ): LiveStoreRefreshOutcome =
        mutex.withLock {
            val expected = revision()
            val loaded = load() ?: return@withLock LiveStoreRefreshOutcome.FAILED
            if (revision() != expected) {
                return@withLock LiveStoreRefreshOutcome.DISCARDED
            }
            commit(loaded)
            // The commit is itself a mutation. Nothing can observe it mid-flight
            // (the mutex is still held), but keeping the counter monotonic across
            // every mutation is what makes "the revision moved" mean "state changed".
            bump()
            LiveStoreRefreshOutcome.COMMITTED
        }
}
