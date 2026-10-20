import Foundation

/// Platform-free rules for the privacy cover and the optional app lock (#755).
///
/// The cover hides transcripts and terminal output whenever the app is not in the
/// foreground (app switcher) and while the lock is engaged. The lock engages when the app
/// comes back after having been fully in the background, and at a cold start, but not for
/// a brief interruption such as a system alert or Control Center.
enum AppLockPolicy {
    static func coverVisible(isForeground: Bool, locked: Bool) -> Bool {
        !isForeground || locked
    }

    static func shouldLock(enabled: Bool, wasInBackground: Bool) -> Bool {
        enabled && wasInBackground
    }
}
