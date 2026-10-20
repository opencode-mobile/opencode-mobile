package dev.opencode.mobile.domain.security

/**
 * Platform-free rules for the privacy cover and the optional app lock (#755).
 *
 * The cover hides transcripts and terminal output whenever the app is not in the
 * foreground (app switcher, recents) and while the lock is engaged. The lock engages
 * when the app comes back after having been fully in the background, and at a cold
 * start, but not for a brief interruption such as a system dialog.
 */
object AppLockPolicy {
    fun coverVisible(
        isForeground: Boolean,
        locked: Boolean,
    ): Boolean = !isForeground || locked

    fun shouldLock(
        enabled: Boolean,
        wasInBackground: Boolean,
    ): Boolean = enabled && wasInBackground
}
