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

    @Test func chipTimelineDefaultsOn() {
        let flags = FeatureFlags(defaults: makeDefaults("default"))
        #expect(flags.resolution(.sessionChatChipTimeline) == FeatureFlagResolution(value: true, source: .default))
    }

    @Test func remoteSnapshotAppliesKnownKeysOnly() {
        let flags = FeatureFlags(defaults: makeDefaults("remote"))
        flags.applyRemote(["session_chat_chip_timeline": false, "unknown_flag": true])
        #expect(flags.resolution(.sessionChatChipTimeline) == FeatureFlagResolution(value: false, source: .remote))
        #expect(flags.remote.count == 1)
    }

    @Test func overridePersistsAndReloads() {
        let defaults = makeDefaults("override")
        let flags = FeatureFlags(defaults: defaults)
        flags.applyRemote(["session_chat_chip_timeline": false])
        flags.setOverride(.sessionChatChipTimeline, true)
        #expect(flags.resolution(.sessionChatChipTimeline) == FeatureFlagResolution(value: true, source: .override))

        let reloaded = FeatureFlags(defaults: defaults)
        #expect(reloaded.override(.sessionChatChipTimeline) == true)
    }

    @Test func clearOverridesRestoresRemoteOrDefault() {
        let defaults = makeDefaults("clear")
        let flags = FeatureFlags(defaults: defaults)
        flags.setOverride(.sessionChatChipTimeline, false)

        flags.clearOverrides()

        #expect(flags.isEnabled(.sessionChatChipTimeline) == true)
        #expect(defaults.object(forKey: "featureFlagOverride_session_chat_chip_timeline") == nil)
    }
}
