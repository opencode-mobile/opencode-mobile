package dev.opencode.hexcode.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Where the developer settings unlock is saved on the phone. */
interface DeveloperSettingsStore {
    val developerSettings: Flow<Boolean>

    suspend fun setDeveloperSettings(on: Boolean)
}

/**
 * Settings › Developer's lock (app-plan.md, Developer settings): tapping the Version row [REQUIRED_TAPS] times, each
 * tap within [TAP_WINDOW_MS] of the last, unlocks it, and the unlock is saved; Hide developer settings locks it again.
 */
class DeveloperSettings(
    private val store: DeveloperSettingsStore,
) {
    private var taps = 0
    private var lastTapAt = 0L

    val unlocked: Flow<Boolean> get() = store.developerSettings

    /**
     * Counts a tap on the Version row at [nowMs] (a monotonic clock, such as `SystemClock.elapsedRealtime`). Returns
     * true on the tap that unlocks, once it is saved; false for every other tap, and while already unlocked.
     */
    suspend fun tapVersion(nowMs: Long): Boolean {
        // Counted before anything suspends, so quick taps never race each other.
        taps = if (taps > 0 && nowMs - lastTapAt <= TAP_WINDOW_MS) taps + 1 else 1
        lastTapAt = nowMs
        if (taps < REQUIRED_TAPS) return false
        taps = 0
        if (store.developerSettings.first()) return false
        store.setDeveloperSettings(true)
        return true
    }

    /** Hide developer settings: locks them again, and the next unlock needs all its taps. */
    suspend fun hide() {
        taps = 0
        store.setDeveloperSettings(false)
    }

    companion object {
        const val REQUIRED_TAPS = 10
        const val TAP_WINDOW_MS = 2_000L
    }
}
