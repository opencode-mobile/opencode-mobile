import Foundation
import Observation

/// How Home draws a session row (#803). Wire values are stored.
nonisolated enum HomeSessionRowStyle: String, CaseIterable, Sendable {
    /// Two lines: title and time, then project / worktree and the status.
    case compact
    /// Project and worktree on their own lines under the title (#798).
    case classic

    static let defaultStyle: HomeSessionRowStyle = .compact

    var wireValue: String { rawValue }

    static func fromWire(_ value: String?) -> HomeSessionRowStyle? {
        value.flatMap(HomeSessionRowStyle.init(rawValue:))
    }

    var title: String {
        switch self {
        case .compact: "Compact"
        case .classic: "Classic"
        }
    }

    var subtitle: String {
        switch self {
        case .compact: "Two lines: title and time, then project / worktree"
        case .classic: "Project and worktree on their own lines"
        }
    }
}

@Observable
final class HomeDisplayPreferences {
    static let shared = HomeDisplayPreferences()

    private enum StorageKeys {
        static let showSessionListOnHome = "showSessionListOnHome"
        static let hideUserDirectoryPrefix = "hideUserDirectoryPrefix"
        static let recentWindowHours = "recentWindowHours"
        static let lastHomeHiddenAtEpochMs = "lastHomeHiddenAtEpochMs"
        static let sessionListProjectPickerEnabled = "sessionListProjectPickerEnabled"
        static let showsWorktrees = "showsWorktrees"
        static let sessionRowStyle = "sessionRowStyle"
    }

    private let defaults: UserDefaults

    /// When true, replace remote `/Users/…` or `/home/…` with `~` in path labels.
    private(set) var hideUserDirectoryPrefix: Bool
    private(set) var recentWindowHours: Int
    /// Home shows each project's worktrees and branches, and holding New session can pick one.
    /// Off changes presentation only: sessions of every worktree stay under their project.
    private(set) var showsWorktrees: Bool
    private(set) var sessionRowStyle: HomeSessionRowStyle

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if defaults.object(forKey: StorageKeys.hideUserDirectoryPrefix) == nil {
            hideUserDirectoryPrefix = true
        } else {
            hideUserDirectoryPrefix = defaults.bool(forKey: StorageKeys.hideUserDirectoryPrefix)
        }
        if defaults.object(forKey: StorageKeys.showsWorktrees) == nil {
            showsWorktrees = true
        } else {
            showsWorktrees = defaults.bool(forKey: StorageKeys.showsWorktrees)
        }
        sessionRowStyle = HomeSessionRowStyle.fromWire(defaults.string(forKey: StorageKeys.sessionRowStyle))
            ?? HomeSessionRowStyle.defaultStyle
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

    func setShowsWorktrees(_ value: Bool) {
        showsWorktrees = value
        defaults.set(value, forKey: StorageKeys.showsWorktrees)
    }

    func setSessionRowStyle(_ value: HomeSessionRowStyle) {
        sessionRowStyle = value
        defaults.set(value.wireValue, forKey: StorageKeys.sessionRowStyle)
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
        defaults.removeObject(forKey: StorageKeys.showsWorktrees)
        defaults.removeObject(forKey: StorageKeys.sessionRowStyle)
        hideUserDirectoryPrefix = true
        showsWorktrees = true
        sessionRowStyle = HomeSessionRowStyle.defaultStyle
        recentWindowHours = HomeSessionLadder.defaultWindowHours
    }
}
