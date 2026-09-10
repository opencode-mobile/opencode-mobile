package dev.opencode.mobile.domain.event

/**
 * Per-projection reconciliation authority keyed by [LiveConnection] gap
 * revision plus local invalidation. Reconcile iff the current obligation is
 * newer than the last successfully acknowledged revision.
 */
class GapAuthority {
    private val lock = Any()
    private var lastAcknowledged = 0L
    private var localObligation = 0L
    private var inFlight: Long? = null

    fun obligation(snapshot: LiveConnectionSnapshot): Long =
        synchronized(lock) {
            maxOf(snapshot.gap?.generation ?: 0L, localObligation)
        }

    fun needsReconcile(snapshot: LiveConnectionSnapshot): Boolean =
        synchronized(lock) {
            val target = maxOf(snapshot.gap?.generation ?: 0L, localObligation)
            target > lastAcknowledged && inFlight == null
        }

    fun tryBegin(snapshot: LiveConnectionSnapshot): Long? =
        synchronized(lock) {
            val target = maxOf(snapshot.gap?.generation ?: 0L, localObligation)
            if (target <= lastAcknowledged) return null
            if (inFlight != null) return null
            inFlight = target
            target
        }

    /** Marks the current obligation in-flight so a force REST can ack only that generation. */
    fun captureBegin(snapshot: LiveConnectionSnapshot): Long =
        synchronized(lock) {
            val target = maxOf(snapshot.gap?.generation ?: 0L, localObligation)
            inFlight = target
            target
        }

    fun isInFlight(): Boolean = synchronized(lock) { inFlight != null }

    fun acknowledgedGeneration(): Long = synchronized(lock) { lastAcknowledged }

    fun inFlightGeneration(): Long? = synchronized(lock) { inFlight }

    fun acknowledge(generation: Long) {
        synchronized(lock) {
            if (generation >= lastAcknowledged) {
                lastAcknowledged = generation
            }
            if (inFlight == generation) {
                inFlight = null
            }
        }
    }

    fun acknowledgeCurrent(snapshot: LiveConnectionSnapshot) {
        acknowledge(obligation(snapshot))
    }

    fun fail(generation: Long) {
        synchronized(lock) {
            if (inFlight == generation) {
                inFlight = null
            }
        }
    }

    fun invalidate(snapshot: LiveConnectionSnapshot) {
        synchronized(lock) {
            val current =
                maxOf(
                    snapshot.gap?.generation ?: 0L,
                    localObligation,
                    lastAcknowledged,
                )
            localObligation = current + 1
        }
    }
}
