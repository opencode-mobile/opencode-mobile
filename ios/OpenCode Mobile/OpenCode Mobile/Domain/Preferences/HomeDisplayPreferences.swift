import Foundation
import Observation

@Observable
final class HomeDisplayPreferences {
    static let shared = HomeDisplayPreferences()

    private enum StorageKeys {
        static let showSessionListOnHome = "showSessionListOnHome"
        static let hideUserDirectoryPrefix = "hideUserDirectoryPrefix"
    }

    private let defaults: UserDefaults

    private(set) var showSessionListOnHome: Bool
    /// When true, replace remote `/Users/…` or `/home/…` with `~` in path labels.
    private(set) var hideUserDirectoryPrefix: Bool

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if defaults.object(forKey: StorageKeys.showSessionListOnHome) == nil {
            showSessionListOnHome = true
        } else {
            showSessionListOnHome = defaults.bool(forKey: StorageKeys.showSessionListOnHome)
        }
        if defaults.object(forKey: StorageKeys.hideUserDirectoryPrefix) == nil {
            hideUserDirectoryPrefix = true
        } else {
            hideUserDirectoryPrefix = defaults.bool(forKey: StorageKeys.hideUserDirectoryPrefix)
        }
    }

    func setShowSessionListOnHome(_ value: Bool) {
        showSessionListOnHome = value
        defaults.set(value, forKey: StorageKeys.showSessionListOnHome)
    }

    func setHideUserDirectoryPrefix(_ value: Bool) {
        hideUserDirectoryPrefix = value
        defaults.set(value, forKey: StorageKeys.hideUserDirectoryPrefix)
    }

    func clear() {
        defaults.removeObject(forKey: StorageKeys.showSessionListOnHome)
        defaults.removeObject(forKey: StorageKeys.hideUserDirectoryPrefix)
        showSessionListOnHome = true
        hideUserDirectoryPrefix = true
    }
}
