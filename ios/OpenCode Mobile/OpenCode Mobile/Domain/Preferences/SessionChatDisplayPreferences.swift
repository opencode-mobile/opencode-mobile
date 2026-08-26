import Foundation
import Observation

/// Session Chat display preferences (OPE-144).
/// Device-local, default on, cleared by Clear All Data.
@Observable
nonisolated final class SessionChatDisplayPreferences {
    static let shared = SessionChatDisplayPreferences()
    static let defaultCollapseConsecutiveToolCalls = true
    static let defaultSmoothStreamingUpdates = true
    static let defaultFollowUpDelivery = FollowUpDelivery.steer

    private enum StorageKeys {
        static let collapseConsecutiveToolCalls = "collapseConsecutiveToolCalls"
        static let smoothStreamingUpdates = "smoothStreamingUpdates"
        static let followUpDelivery = "followUpDelivery"
    }

    private let defaults: UserDefaults

    /// When true, a run of two or more consecutive visible tool parts in one
    /// assistant message collapses into one summary tool group.
    private(set) var collapseConsecutiveToolCalls: Bool

    /// When true, live assistant tokens coalesce into 80ms / 160ms display frames.
    /// Off paints every streaming snapshot immediately.
    private(set) var smoothStreamingUpdates: Bool
    private(set) var followUpDelivery: FollowUpDelivery

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
        followUpDelivery = FollowUpDelivery.fromWire(defaults.string(forKey: StorageKeys.followUpDelivery))
            ?? Self.defaultFollowUpDelivery
    }

    func setCollapseConsecutiveToolCalls(_ value: Bool) {
        collapseConsecutiveToolCalls = value
        defaults.set(value, forKey: StorageKeys.collapseConsecutiveToolCalls)
    }

    func setSmoothStreamingUpdates(_ value: Bool) {
        smoothStreamingUpdates = value
        defaults.set(value, forKey: StorageKeys.smoothStreamingUpdates)
    }

    func setFollowUpDelivery(_ value: FollowUpDelivery) {
        followUpDelivery = value
        defaults.set(value.wireValue, forKey: StorageKeys.followUpDelivery)
    }

    func clear() {
        defaults.removeObject(forKey: StorageKeys.collapseConsecutiveToolCalls)
        defaults.removeObject(forKey: StorageKeys.smoothStreamingUpdates)
        defaults.removeObject(forKey: StorageKeys.followUpDelivery)
        collapseConsecutiveToolCalls = Self.defaultCollapseConsecutiveToolCalls
        smoothStreamingUpdates = Self.defaultSmoothStreamingUpdates
        followUpDelivery = Self.defaultFollowUpDelivery
    }
}
