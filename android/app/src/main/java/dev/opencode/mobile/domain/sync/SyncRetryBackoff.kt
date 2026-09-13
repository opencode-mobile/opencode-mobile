package dev.opencode.mobile.domain.sync

import kotlin.math.min

internal object SyncRetryBackoff {
    const val INITIAL_MS = 500L
    const val CAP_MS = 5_000L

    fun delayMs(attempt: Int): Long {
        val shift = attempt.coerceAtLeast(0)
        val raw = INITIAL_MS shl shift.coerceAtMost(16)
        return min(CAP_MS, raw)
    }
}
