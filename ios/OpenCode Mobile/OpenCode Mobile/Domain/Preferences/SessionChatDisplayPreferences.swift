import Foundation
import Observation

/// Session Chat display preferences (OPE-144).
/// Device-local, default on, cleared by Clear All Data.
@Observable
nonisolated final class SessionChatDisplayPreferences {
    static let shared = SessionChatDisplayPreferences()
    static let defaultCollapseConsecutiveToolCalls = true
    static let defaultSmoothStreamingUpdates = true

    private enum StorageKeys {
        static let collapseConsecutiveToolCalls = "collapseConsecutiveToolCalls"
        static let smoothStreamingUpdates = "smoothStreamingUpdates"
    }

    private let defaults: UserDefaults

    /// When true, a run of two or more consecutive visible tool parts in one
    /// assistant message collapses into one summary tool group.
    private(set) var collapseConsecutiveToolCalls: Bool

    /// When true, live assistant tokens coalesce into 80ms / 160ms display frames.
    /// Off paints every streaming snapshot immediately.
    private(set) var smoothStreamingUpdates: Bool

    nonisolated static func currentSmoothStreamingUpdates(defaults: UserDefaults = .standard) -> Bool {
        if defaults.object(forKey: StorageKeys.smoothStreamingUpdates) == nil {
            return defaultSmoothStreamingUpdates
        }
        return defaults.bool(forKey: StorageKeys.smoothStreamingUpdates)
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if defaults.object(forKey: StorageKeys.collapseConsecutiveToolCalls) == nil {
            collapseConsecutiveToolCalls = Self.defaultCollapseConsecutiveToolCalls
        } else {
            collapseConsecutiveToolCalls = defaults.bool(forKey: StorageKeys.collapseConsecutiveToolCalls)
        }
        if defaults.object(forKey: StorageKeys.smoothStreamingUpdates) == nil {
            smoothStreamingUpdates = Self.defaultSmoothStreamingUpdates
        } else {
            smoothStreamingUpdates = defaults.bool(forKey: StorageKeys.smoothStreamingUpdates)
        }
    }

    func setCollapseConsecutiveToolCalls(_ value: Bool) {
        collapseConsecutiveToolCalls = value
        defaults.set(value, forKey: StorageKeys.collapseConsecutiveToolCalls)
    }

    func setSmoothStreamingUpdates(_ value: Bool) {
        smoothStreamingUpdates = value
        defaults.set(value, forKey: StorageKeys.smoothStreamingUpdates)
    }

    func clear() {
        defaults.removeObject(forKey: StorageKeys.collapseConsecutiveToolCalls)
        defaults.removeObject(forKey: StorageKeys.smoothStreamingUpdates)
        collapseConsecutiveToolCalls = Self.defaultCollapseConsecutiveToolCalls
        smoothStreamingUpdates = Self.defaultSmoothStreamingUpdates
    }
}
