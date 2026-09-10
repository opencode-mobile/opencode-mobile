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
            target > lastAcknowledged && inFlight != target
        }

    fun tryBegin(snapshot: LiveConnectionSnapshot): Long? =
        synchronized(lock) {
            val target = maxOf(snapshot.gap?.generation ?: 0L, localObligation)
            if (target <= lastAcknowledged) return null
            if (inFlight == target) return null
            inFlight = target
            target
        }

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
