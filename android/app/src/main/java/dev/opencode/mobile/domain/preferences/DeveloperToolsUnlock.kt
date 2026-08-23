package dev.opencode.mobile.domain.preferences

object DeveloperToolsUnlock {
    const val REQUIRED_TAPS = 10
    const val TAP_WINDOW_MS = 2_000L

    fun nextCount(
        previousCount: Int,
        lastTapAtMs: Long,
        nowMs: Long,
    ): Int {
        if (nowMs - lastTapAtMs > TAP_WINDOW_MS) return 1
        return previousCount + 1
    }
}
