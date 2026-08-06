import Foundation
import Observation

/// Local flag for one-time App onboarding (distinct from home Connection onboarding).
/// Clear Data resets completion so the pager can return on a fresh wipe.
@Observable
final class AppOnboardingPreferences {
    static let shared = AppOnboardingPreferences()

    private enum StorageKeys {
        static let completed = "appOnboardingCompleted"
    }

    private let defaults: UserDefaults
    private(set) var isCompleted: Bool

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        isCompleted = defaults.bool(forKey: StorageKeys.completed)
    }

    func markCompleted() {
        isCompleted = true
        defaults.set(true, forKey: StorageKeys.completed)
    }

    func clear() {
        defaults.removeObject(forKey: StorageKeys.completed)
        isCompleted = false
    }
}
