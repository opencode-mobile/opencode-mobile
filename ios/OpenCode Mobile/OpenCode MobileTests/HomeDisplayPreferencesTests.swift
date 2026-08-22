import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct HomeDisplayPreferencesTests {
    @Test func missingProjectPickerKey_defaultsOn() {
        let suite = "HomeDisplayPreferencesTests.default"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)

        let preferences = HomeDisplayPreferences(defaults: defaults)

        #expect(preferences.sessionListProjectPickerEnabled == true)
    }

    @Test func storedProjectPickerOff_isPreserved() {
        let suite = "HomeDisplayPreferencesTests.stored"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defaults.set(false, forKey: "sessionListProjectPickerEnabled")

        let preferences = HomeDisplayPreferences(defaults: defaults)

        #expect(preferences.sessionListProjectPickerEnabled == false)
    }

    @Test func clear_restoresProjectPickerDefaultOn() {
        let suite = "HomeDisplayPreferencesTests.clear"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = HomeDisplayPreferences(defaults: defaults)
        preferences.setSessionListProjectPickerEnabled(false)

        preferences.clear()

        #expect(preferences.sessionListProjectPickerEnabled == true)
        #expect(defaults.object(forKey: "sessionListProjectPickerEnabled") == nil)
    }
}
