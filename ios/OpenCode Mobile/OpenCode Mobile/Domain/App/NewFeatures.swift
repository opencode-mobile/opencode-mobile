import Foundation
import Observation

/// Features that carry a "New" badge until the next app update or first use.
///
/// Adding a badge takes one case here (its key and `removeAfter`) plus
/// `NewFeatureStore.isNew(_:)` and `markUsed(_:)` at the call site. The raw
/// value is the stable storage key, shared with Android.
enum NewFeature: String, CaseIterable, Sendable {
    case selectMessageText = "select_message_text"

    var key: String { rawValue }

    /// Calendar date after which the entry and its badge call sites should be
    /// deleted. A unit test fails once this date has passed.
    var removeAfter: DateComponents {
        switch self {
        case .selectMessageText: DateComponents(year: 2027, month: 2, day: 10)
        }
    }
}

/// What the app remembers about "New" badges.
struct NewFeatureState: Equatable, Sendable {
    struct Entry: Equatable, Sendable {
        /// Marketing version that first showed the feature, or
        /// `NewFeaturePolicy.baselineVersion` when it came with the install.
        var firstSeenVersion: String?
        var used = false
    }

    /// The store has synced at least once on this install.
    var initialized = false
    var entries: [String: Entry] = [:]
}

/// Rules for "New" badges, free of I/O. Shared with Android.
///
/// A feature is new in the app version that first showed it to this install,
/// until it is used. A fresh install starts with every feature already known,
/// so nothing is new to someone who never had the app without it.
enum NewFeaturePolicy {
    /// `firstSeenVersion` of a feature that was present when the app was installed.
    static let baselineVersion = "baseline"

    /// Records where each feature in `keys` was first seen. A feature with no
    /// record gets the baseline on the first sync of a fresh install, and
    /// `currentVersion` otherwise, so a feature that appears later is new
    /// wherever the person lands, including after skipping releases.
    static func sync(
        _ state: NewFeatureState,
        keys: [String],
        currentVersion: String,
        isFreshInstall: Bool
    ) -> NewFeatureState {
        var next = state
        let firstSeen = !state.initialized && isFreshInstall ? baselineVersion : currentVersion
        for key in keys where next.entries[key]?.firstSeenVersion == nil {
            next.entries[key, default: NewFeatureState.Entry()].firstSeenVersion = firstSeen
        }
        next.initialized = true
        return next
    }

    /// New only in the version that first showed the feature, and only until it is used.
    static func isNew(_ state: NewFeatureState, key: String, currentVersion: String) -> Bool {
        guard let entry = state.entries[key], !entry.used else { return false }
        return entry.firstSeenVersion == currentVersion
    }

    static func markUsed(_ state: NewFeatureState, key: String) -> NewFeatureState {
        var next = state
        next.entries[key, default: NewFeatureState.Entry()].used = true
        return next
    }
}

/// App-wide "New" badge state, kept in `UserDefaults` under `newFeatures.*`.
///
/// Views read `NewFeatureStore.shared.isNew(.feature)` in `body` (the store is
/// observable, so the badge goes away right after `markUsed`). The app root
/// calls `sync(isFreshInstall:)` once per launch before Home appears.
@Observable
final class NewFeatureStore {
    static let shared = NewFeatureStore()

    /// DEBUG only: `-debugShowNewFeatureBadges YES` shows the badge of every
    /// unused feature, so a fresh simulator install can capture it.
    static let debugShowBadgesKey = "debugShowNewFeatureBadges"

    private enum StorageKeys {
        static let prefix = "newFeatures."
        static let initialized = prefix + "initialized"

        static func firstSeenVersion(_ key: String) -> String { prefix + key + ".firstSeenVersion" }
        static func used(_ key: String) -> String { prefix + key + ".used" }
    }

    private let defaults: UserDefaults
    private let currentVersion: String
    private let features: [NewFeature]
    private(set) var state: NewFeatureState

    /// - Parameter currentVersion: marketing version only; the build number
    ///   changes without an update the person would notice.
    init(
        defaults: UserDefaults = .standard,
        currentVersion: String = AppVersion.current().version,
        features: [NewFeature] = NewFeature.allCases
    ) {
        self.defaults = defaults
        self.currentVersion = currentVersion
        self.features = features
        state = Self.load(from: defaults, features: features)
    }

    /// Call once per launch, before UI reads `isNew`. `isFreshInstall` only
    /// matters the first time this runs on an install.
    func sync(isFreshInstall: Bool) {
        let next = NewFeaturePolicy.sync(
            state,
            keys: features.map(\.key),
            currentVersion: currentVersion,
            isFreshInstall: isFreshInstall
        )
        apply(next)
    }

    func isNew(_ feature: NewFeature) -> Bool {
        #if DEBUG
        if defaults.bool(forKey: Self.debugShowBadgesKey) {
            return state.entries[feature.key]?.used != true
        }
        #endif
        return NewFeaturePolicy.isNew(state, key: feature.key, currentVersion: currentVersion)
    }

    /// The person used the feature: its badge is gone for good.
    func markUsed(_ feature: NewFeature) {
        apply(NewFeaturePolicy.markUsed(state, key: feature.key))
    }

    private func apply(_ next: NewFeatureState) {
        guard next != state else { return }
        state = next
        defaults.set(next.initialized, forKey: StorageKeys.initialized)
        for (key, entry) in next.entries {
            if let version = entry.firstSeenVersion {
                defaults.set(version, forKey: StorageKeys.firstSeenVersion(key))
            }
            if entry.used {
                defaults.set(true, forKey: StorageKeys.used(key))
            }
        }
    }

    private static func load(from defaults: UserDefaults, features: [NewFeature]) -> NewFeatureState {
        var state = NewFeatureState(initialized: defaults.bool(forKey: StorageKeys.initialized))
        for feature in features {
            let version = defaults.string(forKey: StorageKeys.firstSeenVersion(feature.key))
            let used = defaults.bool(forKey: StorageKeys.used(feature.key))
            if version != nil || used {
                state.entries[feature.key] = NewFeatureState.Entry(firstSeenVersion: version, used: used)
            }
        }
        return state
    }
}
