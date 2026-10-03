import Foundation
import Observation

@Observable
final class HomeDisplayPreferences {
    static let shared = HomeDisplayPreferences()

    private enum StorageKeys {
        static let showSessionListOnHome = "showSessionListOnHome"
        static let hideUserDirectoryPrefix = "hideUserDirectoryPrefix"
        static let recentWindowHours = "recentWindowHours"
        static let lastHomeHiddenAtEpochMs = "lastHomeHiddenAtEpochMs"
        static let sessionListProjectPickerEnabled = "sessionListProjectPickerEnabled"
    }

    private let defaults: UserDefaults

    /// When true, replace remote `/Users/…` or `/home/…` with `~` in path labels.
    private(set) var hideUserDirectoryPrefix: Bool
    private(set) var recentWindowHours: Int

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if defaults.object(forKey: StorageKeys.hideUserDirectoryPrefix) == nil {
            hideUserDirectoryPrefix = true
        } else {
            hideUserDirectoryPrefix = defaults.bool(forKey: StorageKeys.hideUserDirectoryPrefix)
        }
        if defaults.object(forKey: StorageKeys.recentWindowHours) == nil {
            recentWindowHours = HomeSessionLadder.defaultWindowHours
        } else {
            recentWindowHours = HomeSessionLadder.sanitizeWindowHours(
                defaults.integer(forKey: StorageKeys.recentWindowHours)
            )
        }
    }

    func setHideUserDirectoryPrefix(_ value: Bool) {
        hideUserDirectoryPrefix = value
        defaults.set(value, forKey: StorageKeys.hideUserDirectoryPrefix)
    }

    func lastHomeHiddenAtEpochMs() -> Int64? {
        guard defaults.object(forKey: StorageKeys.lastHomeHiddenAtEpochMs) != nil else {
            return nil
        }
        return defaults.object(forKey: StorageKeys.lastHomeHiddenAtEpochMs) as? Int64
            ?? Int64(defaults.integer(forKey: StorageKeys.lastHomeHiddenAtEpochMs))
    }

    func setLastHomeHiddenAtEpochMs(_ value: Int64?) {
        if let value {
            defaults.set(value, forKey: StorageKeys.lastHomeHiddenAtEpochMs)
        } else {
            defaults.removeObject(forKey: StorageKeys.lastHomeHiddenAtEpochMs)
        }
    }

    func setRecentWindowHours(_ value: Int) {
        let sanitized = HomeSessionLadder.sanitizeWindowHours(value)
        recentWindowHours = sanitized
        defaults.set(sanitized, forKey: StorageKeys.recentWindowHours)
    }

    func clear() {
        defaults.removeObject(forKey: StorageKeys.showSessionListOnHome)
        defaults.removeObject(forKey: StorageKeys.hideUserDirectoryPrefix)
        defaults.removeObject(forKey: StorageKeys.recentWindowHours)
        defaults.removeObject(forKey: StorageKeys.lastHomeHiddenAtEpochMs)
        defaults.removeObject(forKey: StorageKeys.sessionListProjectPickerEnabled)
        hideUserDirectoryPrefix = true
        recentWindowHours = HomeSessionLadder.defaultWindowHours
    }
}
