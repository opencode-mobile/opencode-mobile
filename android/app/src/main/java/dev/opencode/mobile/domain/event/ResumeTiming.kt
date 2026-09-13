package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Resume-path diagnostic timestamps measured from Foreground after BackgroundStop.
 * Not analytics.
 */
object ResumeTiming {
    private val originMs = AtomicLong(UNSET)
    private val cachePaintedMs = AtomicLong(UNSET)
    private val connectedMs = AtomicLong(UNSET)
    private val logged = AtomicBoolean(false)

    fun begin(nowMs: Long) {
        originMs.set(nowMs)
        cachePaintedMs.set(UNSET)
        connectedMs.set(UNSET)
        logged.set(false)
    }

    fun markCachePainted(nowMs: Long) {
        val origin = originMs.get()
        if (origin == UNSET) return
        cachePaintedMs.compareAndSet(UNSET, nowMs - origin)
    }

    fun markConnected(nowMs: Long) {
        val origin = originMs.get()
        if (origin == UNSET) return
        connectedMs.compareAndSet(UNSET, nowMs - origin)
    }

    fun markBootstrapDone(nowMs: Long) {
        val origin = originMs.get()
        if (origin == UNSET || !logged.compareAndSet(false, true)) return
        val cacheMs = cachePaintedMs.get().takeIf { it != UNSET } ?: 0L
        val connectedAt = connectedMs.get().takeIf { it != UNSET } ?: 0L
        val bootstrapMs = nowMs - origin
        DiagnosticLog.info(
            DiagnosticLogArea.Sse,
            "resume: cache painted +${cacheMs}ms, connected +${connectedAt}ms, bootstrap done +${bootstrapMs}ms",
        )
        originMs.set(UNSET)
    }

    fun reset() {
        originMs.set(UNSET)
        cachePaintedMs.set(UNSET)
        connectedMs.set(UNSET)
        logged.set(false)
    }

    private const val UNSET = Long.MIN_VALUE
}
