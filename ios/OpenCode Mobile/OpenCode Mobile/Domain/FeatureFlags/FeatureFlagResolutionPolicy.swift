import Foundation

/// Where a resolved flag value came from.
enum FeatureFlagSource: Sendable {
    case `default`
    case remote
    case override
}

struct FeatureFlagResolution: Equatable, Sendable {
    let value: Bool
    let source: FeatureFlagSource
}

/// Pure resolution order for one flag (OPE-269): developer override on device,
/// then the activated Remote Config value, then the bundled default.
enum FeatureFlagResolutionPolicy {
    static func resolve(override: Bool?, remote: Bool?, default defaultValue: Bool) -> FeatureFlagResolution {
        if let override {
            return FeatureFlagResolution(value: override, source: .override)
        }
        if let remote {
            return FeatureFlagResolution(value: remote, source: .remote)
        }
        return FeatureFlagResolution(value: defaultValue, source: .default)
    }
}
