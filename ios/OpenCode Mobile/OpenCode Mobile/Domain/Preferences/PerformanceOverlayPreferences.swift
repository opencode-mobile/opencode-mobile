import Foundation
import Observation

/// Developer Tools toggle for the live frame-time HUD. Off until turned on.
@Observable
final class PerformanceOverlayPreferences {
    static let shared = PerformanceOverlayPreferences()

    private enum StorageKeys {
        static let enabled = "performanceOverlayEnabled"
    }

    private let defaults: UserDefaults
    private(set) var enabled: Bool

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        enabled = defaults.bool(forKey: StorageKeys.enabled)
    }

    func setEnabled(_ value: Bool) {
        guard enabled != value else { return }
        enabled = value
        defaults.set(value, forKey: StorageKeys.enabled)
    }

    func clear() {
        defaults.removeObject(forKey: StorageKeys.enabled)
        enabled = false
    }
}
