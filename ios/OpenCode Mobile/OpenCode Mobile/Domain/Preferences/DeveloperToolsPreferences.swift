import Foundation
import Observation

@Observable
final class DeveloperToolsPreferences {
    static let shared = DeveloperToolsPreferences()

    private enum StorageKeys {
        static let unlocked = "developerToolsUnlocked"
    }

    private let defaults: UserDefaults
    private(set) var unlocked: Bool

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        unlocked = defaults.bool(forKey: StorageKeys.unlocked)
    }

    func unlock() {
        unlocked = true
        defaults.set(true, forKey: StorageKeys.unlocked)
    }

    func clear() {
        defaults.removeObject(forKey: StorageKeys.unlocked)
        unlocked = false
    }
}
