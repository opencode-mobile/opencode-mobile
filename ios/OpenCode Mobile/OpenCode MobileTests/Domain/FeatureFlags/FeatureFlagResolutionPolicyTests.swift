import Testing
@testable import OpenCode_Mobile

@Suite struct FeatureFlagResolutionPolicyTests {
    @Test func defaultWinsWhenNothingElseIsSet() {
        #expect(
            FeatureFlagResolutionPolicy.resolve(override: nil, remote: nil, default: true)
                == FeatureFlagResolution(value: true, source: .default)
        )
    }

    @Test func remoteWinsOverDefault() {
        #expect(
            FeatureFlagResolutionPolicy.resolve(override: nil, remote: false, default: true)
                == FeatureFlagResolution(value: false, source: .remote)
        )
    }

    @Test func overrideWinsOverRemoteAndDefault() {
        #expect(
            FeatureFlagResolutionPolicy.resolve(override: true, remote: false, default: false)
                == FeatureFlagResolution(value: true, source: .override)
        )
    }
}
