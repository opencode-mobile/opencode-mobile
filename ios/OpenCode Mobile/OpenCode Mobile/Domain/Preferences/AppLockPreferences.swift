import Foundation
import Observation

/// Optional Face ID / Touch ID / passcode lock when the app returns from the background (#755).
@Observable
final class AppLockPreferences {
    static let shared = AppLockPreferences()

    private enum StorageKeys {
        static let enabled = "appLockEnabled"
    }

    private let defaults: UserDefaults

    private(set) var enabled: Bool

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        enabled = defaults.bool(forKey: StorageKeys.enabled)
    }

    func setEnabled(_ value: Bool) {
        enabled = value
        defaults.set(value, forKey: StorageKeys.enabled)
    }

    func clear() {
        defaults.removeObject(forKey: StorageKeys.enabled)
        enabled = false
    }
}
