package dev.opencode.mobile.domain.security

/**
 * Platform-free rules for the privacy cover and the optional app lock (#755).
 *
 * The cover hides transcripts and terminal output only when App Lock is enabled:
 * while the app is not in the foreground (app switcher, recents) or while the lock
 * is engaged. When App Lock is off the content stays visible in the task manager.
 * The lock engages when the app comes back after having been fully in the background,
 * and at a cold start, but not for a brief interruption such as a system dialog.
 */
object AppLockPolicy {
    fun coverVisible(
        isForeground: Boolean,
        locked: Boolean,
        enabled: Boolean,
    ): Boolean = locked || (enabled && !isForeground)

    fun shouldLock(
        enabled: Boolean,
        wasInBackground: Boolean,
    ): Boolean = enabled && wasInBackground
}
