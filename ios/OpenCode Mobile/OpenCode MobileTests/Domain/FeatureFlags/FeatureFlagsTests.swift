import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct FeatureFlagsTests {
    private func makeDefaults(_ name: String) -> UserDefaults {
        let suite = "FeatureFlagsTests.\(name)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }

    @Test func keysAreUniqueSnakeCaseSharedWithAndroid() {
        let keys = FeatureFlag.allCases.map(\.key)
        #expect(Set(keys).count == keys.count)
        let pattern = try! NSRegularExpression(pattern: "^[a-z][a-z0-9]*(_[a-z0-9]+)*$")
        for key in keys {
            let range = NSRange(key.startIndex..., in: key)
            #expect(pattern.firstMatch(in: key, range: range) != nil, "\(key) must be snake_case")
        }
    }

    @Test func dockedComposerDefaultsOff() {
        let flags = FeatureFlags(defaults: makeDefaults("default"))
        #expect(flags.resolution(.sessionComposerDocked) == FeatureFlagResolution(value: false, source: .default))
    }

    @Test func removedChipTimelineKeyIsUnknown() {
        #expect(FeatureFlag(rawValue: "session_chat_chip_timeline") == nil)
    }

    @Test func remoteSnapshotAppliesKnownKeysOnly() {
        let flags = FeatureFlags(defaults: makeDefaults("remote"))
        flags.applyRemote(["session_composer_docked": true, "session_chat_chip_timeline": true, "unknown_flag": true])
        #expect(flags.resolution(.sessionComposerDocked) == FeatureFlagResolution(value: true, source: .remote))
        #expect(flags.remote.count == 1)
    }

    @Test func overridePersistsAndReloads() {
        let defaults = makeDefaults("override")
        let flags = FeatureFlags(defaults: defaults)
        flags.applyRemote(["session_composer_docked": false])
        flags.setOverride(.sessionComposerDocked, true)
        #expect(flags.resolution(.sessionComposerDocked) == FeatureFlagResolution(value: true, source: .override))

        let reloaded = FeatureFlags(defaults: defaults)
        #expect(reloaded.override(.sessionComposerDocked) == true)
    }

    @Test func clearOverridesRestoresRemoteOrDefault() {
        let defaults = makeDefaults("clear")
        let flags = FeatureFlags(defaults: defaults)
        flags.setOverride(.sessionComposerDocked, true)
        // An override left behind by the removed Chip timeline flag (#1120).
        defaults.set(true, forKey: "featureFlagOverride_session_chat_chip_timeline")

        flags.clearOverrides()

        #expect(flags.isEnabled(.sessionComposerDocked) == false)
        #expect(defaults.object(forKey: "featureFlagOverride_session_composer_docked") == nil)
        #expect(defaults.object(forKey: "featureFlagOverride_session_chat_chip_timeline") == nil)
    }
}
