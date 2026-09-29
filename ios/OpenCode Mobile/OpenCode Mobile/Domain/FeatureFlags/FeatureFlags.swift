import Foundation
import Observation

/// Feature flag store (OPE-269). Holds the remote snapshot for this session,
/// device-local developer overrides, and resolves each `FeatureFlag` through
/// `FeatureFlagResolutionPolicy`. Overrides are cleared by Clear All Data.
///
/// The remote snapshot is applied once per launch by the release-observability
/// seam after Remote Config activation, so a flag never flips mid-session.
@Observable
nonisolated final class FeatureFlags {
    static let shared = FeatureFlags()

    private static let overrideKeyPrefix = "featureFlagOverride_"

    private let defaults: UserDefaults
    private(set) var remote: [FeatureFlag: Bool] = [:]
    private(set) var overrides: [FeatureFlag: Bool] = [:]

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        for flag in FeatureFlag.allCases {
            let key = Self.overrideKey(flag)
            if defaults.object(forKey: key) != nil {
                overrides[flag] = defaults.bool(forKey: key)
            }
        }
    }

    /// Replaces the remote snapshot for this session. Unknown keys are ignored.
    func applyRemote(_ values: [String: Bool]) {
        var snapshot: [FeatureFlag: Bool] = [:]
        for (key, value) in values {
            if let flag = FeatureFlag(rawValue: key) {
                snapshot[flag] = value
            }
        }
        remote = snapshot
    }

    func isEnabled(_ flag: FeatureFlag) -> Bool {
        resolution(flag).value
    }

    func resolution(_ flag: FeatureFlag) -> FeatureFlagResolution {
        FeatureFlagResolutionPolicy.resolve(
            override: overrides[flag],
            remote: remote[flag],
            default: flag.defaultValue
        )
    }

    func override(_ flag: FeatureFlag) -> Bool? {
        overrides[flag]
    }

    func setOverride(_ flag: FeatureFlag, _ value: Bool?) {
        let key = Self.overrideKey(flag)
        if let value {
            overrides[flag] = value
            defaults.set(value, forKey: key)
        } else {
            overrides[flag] = nil
            defaults.removeObject(forKey: key)
        }
    }

    func clearOverrides() {
        for flag in FeatureFlag.allCases {
            defaults.removeObject(forKey: Self.overrideKey(flag))
        }
        overrides = [:]
    }

    private static func overrideKey(_ flag: FeatureFlag) -> String {
        overrideKeyPrefix + flag.key
    }
}
