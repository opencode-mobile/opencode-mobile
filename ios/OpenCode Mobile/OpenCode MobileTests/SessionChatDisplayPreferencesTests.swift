import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct SessionChatDisplayPreferencesTests {
    @Test func missingKey_defaultsOn() {
        let suite = "SessionChatDisplayPreferencesTests.default"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)

        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        #expect(preferences.collapseConsecutiveToolCalls == true)
    }

    @Test func storedOff_isPreserved() {
        let suite = "SessionChatDisplayPreferencesTests.stored"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defaults.set(false, forKey: "collapseConsecutiveToolCalls")

        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        #expect(preferences.collapseConsecutiveToolCalls == false)
    }

    @Test func clear_restoresDefaultOn() {
        let suite = "SessionChatDisplayPreferencesTests.clear"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = SessionChatDisplayPreferences(defaults: defaults)
        preferences.setCollapseConsecutiveToolCalls(false)

        preferences.clear()

        #expect(preferences.collapseConsecutiveToolCalls == true)
        #expect(defaults.object(forKey: "collapseConsecutiveToolCalls") == nil)
    }
}
