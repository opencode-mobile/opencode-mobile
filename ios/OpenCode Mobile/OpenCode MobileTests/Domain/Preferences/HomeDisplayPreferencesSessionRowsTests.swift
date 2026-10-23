import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct HomeDisplayPreferencesSessionRowsTests {
    private func freshDefaults() -> UserDefaults {
        let name = "HomeDisplayPreferencesSessionRowsTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    @Test func compactIsTheDefault() {
        #expect(HomeDisplayPreferences(defaults: freshDefaults()).sessionRowStyle == .compact)
    }

    @Test func classicPersistsUnderItsKeyAndSurvivesRelaunch() {
        let defaults = freshDefaults()
        let preferences = HomeDisplayPreferences(defaults: defaults)
        preferences.setSessionRowStyle(.classic)
        #expect(preferences.sessionRowStyle == .classic)
        #expect(defaults.string(forKey: "sessionRowStyle") == "classic")
        #expect(HomeDisplayPreferences(defaults: defaults).sessionRowStyle == .classic)

        preferences.setSessionRowStyle(.compact)
        #expect(HomeDisplayPreferences(defaults: defaults).sessionRowStyle == .compact)
    }

    @Test func clearRestoresCompact() {
        let defaults = freshDefaults()
        let preferences = HomeDisplayPreferences(defaults: defaults)
        preferences.setSessionRowStyle(.classic)
        preferences.clear()
        #expect(preferences.sessionRowStyle == .compact)
        #expect(defaults.object(forKey: "sessionRowStyle") == nil)
        #expect(HomeDisplayPreferences(defaults: defaults).sessionRowStyle == .compact)
    }

    @Test func unknownStoredValueFallsBackToCompact() {
        let defaults = freshDefaults()
        defaults.set("dense", forKey: "sessionRowStyle")
        #expect(HomeDisplayPreferences(defaults: defaults).sessionRowStyle == .compact)
    }

    @Test func labelsMatchSettings() {
        #expect(HomeSessionRowStyle.allCases.map(\.title) == ["Compact", "Classic"])
        #expect(HomeSessionRowStyle.allCases.map(\.wireValue) == ["compact", "classic"])
    }
}
