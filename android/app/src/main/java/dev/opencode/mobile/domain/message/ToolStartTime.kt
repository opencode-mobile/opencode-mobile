package dev.opencode.mobile.domain.message

object ToolStartTime {
    /** Accepts seconds, milliseconds, or microseconds. Drops relative/seq values. */
    fun normalizeEpochMs(raw: Long?): Long? {
        if (raw == null || raw <= 0L) return null
        return when {
            raw > 100_000_000_000_000L -> raw / 1_000L
            raw > 100_000_000_000L -> raw
            raw > 1_000_000_000L -> raw * 1_000L
            else -> null
        }
    }

    fun resolve(
        incoming: Long?,
        existing: Long?,
        status: ToolStatus,
        nowEpochMs: Long,
    ): Long? {
        usableEpochMs(existing, nowEpochMs)?.let { return it }
        usableEpochMs(incoming, nowEpochMs)?.let { return it }
        if (status == ToolStatus.Pending || status == ToolStatus.Running) return nowEpochMs
        return null
    }

    /** Drops a start that is more than two seconds in the future (device clock behind). */
    private fun usableEpochMs(
        raw: Long?,
        nowEpochMs: Long,
    ): Long? {
        val normalized = normalizeEpochMs(raw) ?: return null
        if (normalized > nowEpochMs + 2_000L) return null
        return normalized
    }
}
