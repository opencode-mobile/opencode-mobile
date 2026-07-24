import Foundation
import Observation

@Observable
final class HomeDisplayPreferences {
    static let shared = HomeDisplayPreferences()

    private enum StorageKeys {
        static let showSessionListOnHome = "showSessionListOnHome"
    }

    private let defaults: UserDefaults

    private(set) var showSessionListOnHome: Bool

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if defaults.object(forKey: StorageKeys.showSessionListOnHome) == nil {
            showSessionListOnHome = true
        } else {
            showSessionListOnHome = defaults.bool(forKey: StorageKeys.showSessionListOnHome)
        }
    }

    func setShowSessionListOnHome(_ value: Bool) {
        showSessionListOnHome = value
        defaults.set(value, forKey: StorageKeys.showSessionListOnHome)
    }

    func clear() {
        defaults.removeObject(forKey: StorageKeys.showSessionListOnHome)
        showSessionListOnHome = true
    }
}
