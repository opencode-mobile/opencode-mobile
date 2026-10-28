import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct HomeDisplayPreferencesWorktreesTests {
    private func freshDefaults() -> UserDefaults {
        let name = "HomeDisplayPreferencesWorktreesTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    @Test func worktreesAreOnByDefault() {
        #expect(HomeDisplayPreferences(defaults: freshDefaults()).showsWorktrees)
    }

    @Test func turningWorktreesOffPersistsUnderItsKey() {
        let defaults = freshDefaults()
        let preferences = HomeDisplayPreferences(defaults: defaults)
        preferences.setShowsWorktrees(false)
        #expect(!preferences.showsWorktrees)
        #expect(defaults.object(forKey: "showsWorktrees") as? Bool == false)
        #expect(!HomeDisplayPreferences(defaults: defaults).showsWorktrees)
    }

    @Test func clearRestoresWorktrees() {
        let defaults = freshDefaults()
        let preferences = HomeDisplayPreferences(defaults: defaults)
        preferences.setShowsWorktrees(false)
        preferences.clear()
        #expect(preferences.showsWorktrees)
        #expect(defaults.object(forKey: "showsWorktrees") == nil)
    }
}
