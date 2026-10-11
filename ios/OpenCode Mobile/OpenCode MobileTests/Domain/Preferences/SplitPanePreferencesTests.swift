import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct SplitPanePreferencesTests {
    private func makeDefaults(_ name: String) -> UserDefaults {
        let suite = "SplitPanePreferencesTests.\(name)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }

    @Test func missingKeys_defaultToOpenAtDefaultWidth() {
        let preferences = SplitPanePreferences(defaults: makeDefaults("default"))

        #expect(preferences.state(for: .home) == .initial)
        #expect(preferences.state(for: .settings) == .initial)
        #expect(OpenCodeSplitPaneState.initial.leadingWidth == 360)
        #expect(OpenCodeSplitPaneState.initial.isCollapsed == false)
    }

    @Test func setState_persistsPerScreen() {
        let defaults = makeDefaults("perScreen")
        let preferences = SplitPanePreferences(defaults: defaults)

        preferences.setState(OpenCodeSplitPaneState(leadingWidth: 300, isCollapsed: true), for: .home)

        #expect(preferences.state(for: .home) == OpenCodeSplitPaneState(leadingWidth: 300, isCollapsed: true))
        #expect(preferences.state(for: .settings) == .initial)
        #expect(defaults.double(forKey: "splitPane.home.leadingWidth") == 300)
        #expect(defaults.bool(forKey: "splitPane.home.isCollapsed") == true)

        let reloaded = SplitPanePreferences(defaults: defaults)
        #expect(reloaded.state(for: .home) == OpenCodeSplitPaneState(leadingWidth: 300, isCollapsed: true))
        #expect(reloaded.state(for: .settings) == .initial)
    }

    @Test func setState_clampsWidth() {
        let defaults = makeDefaults("clamp")
        let preferences = SplitPanePreferences(defaults: defaults)

        preferences.setState(OpenCodeSplitPaneState(leadingWidth: 900, isCollapsed: false), for: .settings)

        #expect(preferences.state(for: .settings).leadingWidth == 480)
    }

    @Test func setState_clampsChangesAndTerminalsToSidebarRange() {
        let preferences = SplitPanePreferences(defaults: makeDefaults("contentWidth"))

        preferences.setState(OpenCodeSplitPaneState(leadingWidth: 640, isCollapsed: false), for: .changes)

        #expect(preferences.state(for: .changes).leadingWidth == 480)
        #expect(preferences.state(for: .terminals) == .initial)
    }

    @Test func storedOutOfRangeWidth_isClampedOnLoad() {
        let defaults = makeDefaults("storedClamp")
        defaults.set(100.0, forKey: "splitPane.settings.leadingWidth")

        let preferences = SplitPanePreferences(defaults: defaults)

        #expect(preferences.state(for: .settings).leadingWidth == 280)
    }

    @Test func clear_restoresDefaults() {
        let defaults = makeDefaults("clear")
        let preferences = SplitPanePreferences(defaults: defaults)
        preferences.setState(OpenCodeSplitPaneState(leadingWidth: 400, isCollapsed: true), for: .home)
        preferences.setState(OpenCodeSplitPaneState(leadingWidth: 300, isCollapsed: true), for: .settings)

        preferences.clear()

        #expect(preferences.state(for: .home) == .initial)
        #expect(preferences.state(for: .settings) == .initial)
        #expect(defaults.object(forKey: "splitPane.home.leadingWidth") == nil)
        #expect(defaults.object(forKey: "splitPane.home.isCollapsed") == nil)
        #expect(defaults.object(forKey: "splitPane.settings.leadingWidth") == nil)
        #expect(defaults.object(forKey: "splitPane.settings.isCollapsed") == nil)
    }
}
