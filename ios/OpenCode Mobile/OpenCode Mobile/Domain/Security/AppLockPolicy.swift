import Foundation
import Observation

/// Platform-free rules for the privacy cover and the optional app lock (#755).
///
/// The cover hides transcripts and terminal output only when App Lock is enabled:
/// while the app is not in the foreground (app switcher) or while the lock is
/// engaged. When App Lock is off the content stays visible in the task manager.
/// The lock engages when the app comes back after having been fully in the background,
/// and at a cold start, but not for a brief interruption such as a system alert
/// or Control Center.
enum AppLockPolicy {
    static func coverVisible(isForeground: Bool, locked: Bool, enabled: Bool) -> Bool {
        locked || (enabled && !isForeground)
    }

    static func shouldLock(enabled: Bool, wasInBackground: Bool) -> Bool {
        enabled && wasInBackground
    }
}

/// Whether the app lock is engaged right now. Keyboard shortcuts stay off while it is, since
/// they act on the content under the cover.
@Observable
final class AppLockState {
    static let shared = AppLockState()

    var isLocked = false
}
